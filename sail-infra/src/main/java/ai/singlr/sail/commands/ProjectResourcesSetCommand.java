/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import java.nio.file.Path;
import java.util.function.Supplier;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.config.DefinitionEdits;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerManager.ResourceLimits;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "set",
    description = "Update CPU, memory, or disk allocation for an existing project.",
    mixinStandardHelpOptions = true)
public final class ProjectResourcesSetCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(names = "--cpu", description = "CPU core limit.")
  private Integer cpu;

  @Option(names = "--memory", description = "Memory limit (for example 16GB).")
  private String memory;

  @Option(names = "--disk", description = "Disk quota (for example 100GB).")
  private String disk;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  private final ShellExec shellOverride;
  private final BooleanSupplier rootChecker;
  private final Catalog catalog;
  private final PrintStream out;
  private final PrintStream err;

  /** The catalog this command writes, and the operator a write is made as. */
  record Catalog(Path database, Supplier<Actor> operator) {
    static Catalog here() {
      return new Catalog(SailPaths.controlPlaneDb(), CliOperator::current);
    }
  }

  public ProjectResourcesSetCommand() {
    this(null, ConsoleHelper::isRoot, System.out, System.err, Catalog.here());
  }

  ProjectResourcesSetCommand(
      ShellExec shellOverride,
      BooleanSupplier rootChecker,
      PrintStream out,
      PrintStream err,
      Catalog catalog) {
    this.shellOverride = shellOverride;
    this.rootChecker = rootChecker;
    this.out = out;
    this.err = err;
    this.catalog = catalog;
  }

  @Override
  public void run() {
    CliCommand.run(err, this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    NameValidator.requireValidProjectName(name);
    requireRequestedChange();

    if (!dryRun && !rootChecker.getAsBoolean()) {
      throw RootRequired.refusal(spec);
    }

    var actor = dryRun ? null : catalog.operator().get();
    if (actor != null) {
      ProjectCatalog.requireRecordable(catalog.database(), name, actor);
    }
    var config = ProjectMutations.current(catalog.database(), name, operator);
    if (config.resources() == null) {
      throw new IllegalStateException(
          "Project '" + name + "' sets no resources: set cpu, memory and disk in its definition.");
    }

    var desiredResources = DefinitionEdits.mergeResources(config.resources(), cpu, memory, disk);
    var descriptorChanged = !config.resources().equals(desiredResources);

    var queryManager = new ContainerManager(queryShell());
    var info = queryManager.queryInfo(name);
    ContainerStateGuard.requireCreated(info.state(), name);

    var cpuOrMemoryRequested = cpu != null || memory != null;
    var liveLimitsChanged = cpuOrMemoryRequested && limitsDiffer(info.limits(), desiredResources);
    var diskChanged =
        disk != null && !Objects.equals(config.resources().disk(), desiredResources.disk());
    var restarted = false;

    if (!json) {
      Banner.printBranding(out, Ansi.AUTO);
      out.println();
      out.println(Ansi.AUTO.string("  @|bold Updating resources for|@ " + name + "..."));
      out.println();
    }

    if (!descriptorChanged && !liveLimitsChanged && !diskChanged) {
      printAlreadyCurrent(desiredResources);
      return;
    }

    if (descriptorChanged) {
      ProjectMutations.write(
          catalog.database(),
          Section.RESOURCES,
          config,
          ProjectMutations.checked(DefinitionEdits.updateResources(config, cpu, memory, disk)),
          dryRun,
          out,
          "record resources for '" + name + "' in the catalog",
          actor);
    }

    var applyManager = new ContainerManager(applyShell());
    if (liveLimitsChanged) {
      applyManager.setResourceLimits(
          name,
          new ResourceLimits(String.valueOf(desiredResources.cpu()), desiredResources.memory()));
    }
    if (diskChanged) {
      applyManager.setDiskQuota(name, desiredResources.disk());
    }
    if (info.state() instanceof ContainerState.Running && (liveLimitsChanged || diskChanged)) {
      applyManager.restart(name);
      restarted = true;
    }

    printResult(
        config.resources(),
        desiredResources,
        descriptorChanged,
        liveLimitsChanged || diskChanged,
        restarted);
  }

  private void requireRequestedChange() {
    if (cpu == null && memory == null && disk == null) {
      throw new IllegalArgumentException("Specify at least one of --cpu, --memory, or --disk.");
    }
  }

  private ShellExec queryShell() {
    return shellOverride != null ? shellOverride : new ShellExecutor(false);
  }

  private ShellExec applyShell() {
    return shellOverride != null ? shellOverride : new ShellExecutor(dryRun);
  }

  private static boolean limitsDiffer(ResourceLimits limits, ProjectDefinition.Resources desired) {
    if (limits == null) {
      return true;
    }
    return !Objects.equals(String.valueOf(desired.cpu()), limits.cpu())
        || !normalizedSize(desired.memory()).equals(normalizedSize(limits.memory()));
  }

  private static String normalizedSize(String value) {
    return value == null ? "" : value.strip().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
  }

  private void printAlreadyCurrent(ProjectDefinition.Resources desiredResources) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("status", "unchanged");
      map.put("resources", resourceMap(desiredResources));
      out.println(YamlUtil.dumpJson(map));
      return;
    }

    out.println(Ansi.AUTO.string("  @|faint Resources already match the requested allocation.|@"));
  }

  private void printResult(
      ProjectDefinition.Resources previous,
      ProjectDefinition.Resources desired,
      boolean descriptorChanged,
      boolean liveChanged,
      boolean restarted) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("dry_run", dryRun);
      map.put("updated_definition", descriptorChanged);
      map.put("applied_live", liveChanged);
      map.put("restarted", restarted);
      map.put("previous_resources", resourceMap(previous));
      map.put("resources", resourceMap(desired));
      out.println(YamlUtil.dumpJson(map));
      return;
    }

    if (descriptorChanged) {
      out.println(Ansi.AUTO.string("  @|green \u2713|@ Updated the project's definition"));
    }
    if (liveChanged) {
      out.println(
          Ansi.AUTO.string(
              "  @|green \u2713|@ Applied live resource changes (@|bold "
                  + desired.cpu()
                  + " CPU|@, @|bold "
                  + desired.memory()
                  + " RAM|@, @|bold "
                  + desired.disk()
                  + " disk|@)"));
    }
    if (restarted) {
      out.println(Ansi.AUTO.string("  @|green \u2713|@ Restarted running container"));
    } else if (liveChanged) {
      out.println(Ansi.AUTO.string("  @|faint Project was stopped; restart not required.|@"));
    }
    out.println();
    out.println(Ansi.AUTO.string("  @|bold,green \u2713 Resource update complete.|@"));
  }

  private static LinkedHashMap<String, Object> resourceMap(ProjectDefinition.Resources resources) {
    var map = new LinkedHashMap<String, Object>();
    map.put("cpu", resources.cpu());
    map.put("memory", resources.memory());
    map.put("disk", resources.disk());
    return map;
  }
}
