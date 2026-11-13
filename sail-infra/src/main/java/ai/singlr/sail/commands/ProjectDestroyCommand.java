/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostCatalog;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ProjectTeardown;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "destroy",
    description = "Destroy a project: delete its container and all state.",
    mixinStandardHelpOptions = true)
public final class ProjectDestroyCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Parameters(index = "0", description = "Project name to destroy.")
  private String name;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--yes", description = "Skip confirmation prompts.")
  private boolean yes;

  @Option(
      names = "--purge",
      description =
          "Also erase the project everywhere: its catalog entry, specs, rooms, runs, files and"
              + " their history, on every box. Irreversible. On a node, main erases it and this"
              + " box follows on its next sync — by default destroy only tears down this box's"
              + " container.")
  private boolean purge;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Override
  public void run() {
    CliCommand.run(CliCommand.DRY_RUN_HINT, this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    NameValidator.requireValidProjectName(name);

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
    }

    if (!dryRun && !ConsoleHelper.isRoot()) {
      throw RootRequired.refusal(spec);
    }

    var shell = new ShellExecutor(dryRun);
    var instances = new BoxInstances(shell).all();
    var own = instances.stream().filter(instance -> instance.name().equals(name)).findFirst();
    BoxInstances.requireNotTakenFor(name, own);
    var state = own.map(BoxInstances.Instance::state).orElse(new ContainerState.NotCreated());
    var others = others(name, instances);
    var projectDir = SailPaths.projectDir(name);
    var containerPresent = own.isPresent();
    var dirPresent = Files.exists(projectDir);

    if (!containerPresent && others.isEmpty() && !dirPresent && !purge) {
      emitNothingToDo();
      return;
    }

    if (!json && containerPresent) {
      System.out.println();
      Banner.printContainerStatus(name, state, System.out, Ansi.AUTO);
    }
    var erases = purge ? rehearsePurge(operator) : null;
    if (erases != null && !json) {
      System.out.println(Ansi.AUTO.string("  @|bold Purging erases everywhere:|@ " + erases));
    }

    if (containerPresent || !others.isEmpty() || purge) {
      confirm(yes, dryRun, json, name, purge, others.stream().map(Other::said).toList());
    }

    var deleted = new ArrayList<String>();
    for (var other : others) {
      deleted.add(other.instance());
    }
    if (containerPresent) {
      deleted.add(name);
    }
    if (!json) {
      for (var instance : deleted) {
        System.out.println();
        System.out.println(Ansi.AUTO.string("  @|bold Deleting container|@ " + instance + "..."));
      }
    }
    new ProjectTeardown(shell, SailPaths.projectsDir()).tearDown(name, deleted);
    var destroyed = purge ? purgeFromCatalog(erases, operator) : null;

    emitResult(containerPresent, dirPresent, destroyed);
  }

  /**
   * Returns when destroy may go on: asked, defaulting to no and refused unless answered yes, where
   * nobody can answer ({@link ConsoleHelper#confirmDestructive}) and under {@code --json}; {@code
   * --yes} or a dry run asks nothing.
   */
  static void confirm(
      boolean yes, boolean dryRun, boolean json, String name, boolean purge, List<String> also) {
    if (yes || dryRun) {
      return;
    }
    if (json) {
      throw new CliCommand.Refused("--json requires --yes (nobody answers a prompt through JSON).");
    }
    ConsoleHelper.confirmDestructive(confirmPrompt(name, purge, also), "Not destroyed", "--yes");
  }

  /**
   * What destroy asks: the project, then each of its workspaces and its template ({@code also}),
   * every one of which goes with it (W11).
   */
  static String confirmPrompt(String name, boolean purge, List<String> also) {
    var with =
        also.isEmpty() ? "" : " with " + String.join(", ", also) + " and their uncommitted work";
    if (purge) {
      return "Destroy project "
          + name
          + with
          + " and erase it everywhere — its specs, rooms, runs, files and history, on every box?"
          + " This cannot be undone.";
    }
    return "Destroy project " + name + with + "? This cannot be undone.";
  }

  /** One of the project's instances besides its own container, and how the prompt names it. */
  record Other(String instance, String said) {}

  /** Project {@code name}'s workspaces, then its template, as the box's instances tell them. */
  static List<Other> others(String name, List<BoxInstances.Instance> instances) {
    var others = new ArrayList<Other>();
    for (var instance : instances) {
      if (instance.kind() instanceof BoxInstances.Workspace workspace
          && workspace.project().equals(name)) {
        others.add(new Other(instance.name(), "workspace " + workspace.workspace()));
      }
    }
    for (var instance : instances) {
      if (instance.kind() instanceof BoxInstances.Template template
          && template.project().equals(name)) {
        others.add(new Other(instance.name(), "template " + instance.name()));
      }
    }
    return others;
  }

  /** What the purge will erase, rehearsed through the one prune method before anything goes. */
  private String rehearsePurge(OperatorCommand operator) {
    try (var operations = OperationsFactory.open()) {
      return operations.catalog().purgeSummary(name);
    }
  }

  /** Prunes the project everywhere, through the one prune method. Idempotent once erased. */
  private HostCatalog.Destroyed purgeFromCatalog(String erases, OperatorCommand operator) {
    if (dryRun) {
      System.out.println("[dry-run] erase '" + name + "' everywhere: " + erases);
      return new HostCatalog.Destroyed(name, false, false);
    }
    try (var operations = OperationsFactory.open()) {
      return operations.catalog().destroy(name, true);
    }
  }

  private void emitNothingToDo() {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("destroyed", name);
      map.put("status", "already_absent");
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    System.out.println(
        Ansi.AUTO.string("  @|faint Project '" + name + "' does not exist. Nothing to do.|@"));
  }

  private void emitResult(
      boolean containerPresent, boolean dirPresent, HostCatalog.Destroyed destroyed) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("destroyed", name);
      if (!containerPresent) {
        map.put("status", dirPresent ? "state_cleaned" : "catalog_only");
      }
      if (destroyed != null) {
        map.put("purged", destroyed.purged());
        map.put("requested", destroyed.requested());
      }
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    System.out.println();
    if (containerPresent) {
      Banner.printProjectDestroyed(name, System.out, Ansi.AUTO);
    } else if (dirPresent) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint Container '" + name + "' already absent — cleaned up stale state.|@"));
    }
    if (destroyed != null && destroyed.requested()) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Asked main to erase '"
                  + name
                  + "' with its specs, rooms, runs, files and history — it goes on this box's"
                  + " next sync."));
    } else if (destroyed != null && destroyed.purged()) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Erased '"
                  + name
                  + "' with its specs, rooms, runs, files and history — other boxes follow on"
                  + " their next sync."));
    }
  }
}
