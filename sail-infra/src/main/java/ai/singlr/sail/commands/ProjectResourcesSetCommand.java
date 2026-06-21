/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SailYamlUpdater;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerManager.ResourceLimits;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectDefinitions;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.gen.SailYamlGenerator;
import java.io.PrintStream;
import java.nio.file.Path;
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

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

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

  @Spec private CommandSpec spec;

  private final ShellExec shellOverride;
  private final BooleanSupplier rootChecker;
  private final PrintStream out;
  private final PrintStream err;

  public ProjectResourcesSetCommand() {
    this(null, ConsoleHelper::isRoot, System.out, System.err);
  }

  ProjectResourcesSetCommand(
      ShellExec shellOverride, BooleanSupplier rootChecker, PrintStream out, PrintStream err) {
    this.shellOverride = shellOverride;
    this.rootChecker = rootChecker;
    this.out = out;
    this.err = err;
  }

  @Override
  public void run() {
    CliCommand.run(spec, err, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    requireRequestedChange();

    if (!dryRun && !rootChecker.getAsBoolean()) {
      throw new IllegalStateException(
          "Root privileges required. Run with: sudo sail project resources set " + name);
    }

    var explicit = ProjectDefinitions.explicitFile(file);
    var descriptorPath = explicit != null ? explicit : ProjectDefinitions.canonicalPath(name);
    var config =
        SailYaml.fromMap(YamlUtil.parseMap(ProjectMutations.currentDefinition(name, explicit)));
    if (config.resources() == null) {
      throw new IllegalStateException(
          "sail.yaml must have a resources section with cpu, memory, and disk.");
    }
    if (!name.equals(config.name())) {
      throw new IllegalStateException(
          "Project name mismatch: expected '" + name + "', found '" + config.name() + "'.");
    }

    var desiredResources = SailYamlUpdater.mergeResources(config.resources(), cpu, memory, disk);
    var descriptorChanged = !config.resources().equals(desiredResources);

    var queryManager = new ContainerManager(queryShell());
    var info = queryManager.queryInfo(name);
    requireExistingProject(info.state());

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
      printAlreadyCurrent(descriptorPath, desiredResources);
      return;
    }

    if (descriptorChanged) {
      var updatedText =
          SailYamlGenerator.generate(SailYamlUpdater.updateResources(config, cpu, memory, disk));
      ProjectMutations.persist(
          name,
          explicit,
          updatedText,
          dryRun,
          out,
          "record resources for '" + name + "' in the catalog");
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
        descriptorPath,
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

  private void requireExistingProject(ContainerState state) {
    switch (state) {
      case ContainerState.Running ignored -> {}
      case ContainerState.Stopped ignored -> {}
      case ContainerState.NotCreated ignored ->
          throw new IllegalStateException(
              "Project '"
                  + name
                  + "' does not exist. Run 'sail project create "
                  + name
                  + "' first.");
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }
  }

  private ShellExec queryShell() {
    return shellOverride != null ? shellOverride : new ShellExecutor(false);
  }

  private ShellExec applyShell() {
    return shellOverride != null ? shellOverride : new ShellExecutor(dryRun);
  }

  private static boolean limitsDiffer(ResourceLimits limits, SailYaml.Resources desired) {
    if (limits == null) {
      return true;
    }
    return !Objects.equals(String.valueOf(desired.cpu()), limits.cpu())
        || !normalizedSize(desired.memory()).equals(normalizedSize(limits.memory()));
  }

  private static String normalizedSize(String value) {
    return value == null ? "" : value.strip().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
  }

  private void printAlreadyCurrent(Path singYamlPath, SailYaml.Resources desiredResources) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("status", "unchanged");
      map.put("descriptor", singYamlPath.toAbsolutePath().toString());
      map.put("resources", resourceMap(desiredResources));
      out.println(YamlUtil.dumpJson(map));
      return;
    }

    out.println(Ansi.AUTO.string("  @|faint Resources already match the requested allocation.|@"));
  }

  private void printResult(
      Path singYamlPath,
      SailYaml.Resources previous,
      SailYaml.Resources desired,
      boolean descriptorChanged,
      boolean liveChanged,
      boolean restarted) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("descriptor", singYamlPath.toAbsolutePath().toString());
      map.put("dry_run", dryRun);
      map.put("updated_descriptor", descriptorChanged);
      map.put("applied_live", liveChanged);
      map.put("restarted", restarted);
      map.put("previous_resources", resourceMap(previous));
      map.put("resources", resourceMap(desired));
      out.println(YamlUtil.dumpJson(map));
      return;
    }

    if (descriptorChanged) {
      out.println(
          Ansi.AUTO.string(
              "  @|green \u2713|@ Updated sail.yaml @|faint "
                  + singYamlPath.toAbsolutePath()
                  + "|@"));
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

  private static LinkedHashMap<String, Object> resourceMap(SailYaml.Resources resources) {
    var map = new LinkedHashMap<String, Object>();
    map.put("cpu", resources.cpu());
    map.put("memory", resources.memory());
    map.put("disk", resources.disk());
    return map;
  }
}
