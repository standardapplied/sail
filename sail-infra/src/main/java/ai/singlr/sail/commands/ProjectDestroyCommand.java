/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostCatalog;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(
        spec,
        "  If this is unexpected, re-run with --dry-run to see what would execute.",
        this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
    }

    if (!dryRun && !ConsoleHelper.isRoot()) {
      throw new IllegalStateException(
          "Root privileges required. Run with: sudo sail project destroy " + name);
    }

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);
    var projectDir = SailPaths.projectDir(name);
    var containerPresent = !(state instanceof ContainerState.NotCreated);
    var dirPresent = Files.exists(projectDir);

    if (!containerPresent && !dirPresent && !purge) {
      emitNothingToDo();
      return;
    }

    if (!json && containerPresent) {
      System.out.println();
      Banner.printContainerStatus(name, state, System.out, Ansi.AUTO);
    }
    var erases = purge ? rehearsePurge() : null;
    if (erases != null && !json) {
      System.out.println(Ansi.AUTO.string("  @|bold Purging erases everywhere:|@ " + erases));
    }

    if ((containerPresent || purge)
        && !yes
        && !dryRun
        && !ConsoleHelper.confirm(confirmPrompt(name, purge))) {
      System.out.println("  Aborted.");
      return;
    }

    if (containerPresent) {
      if (!json) {
        System.out.println();
        System.out.println(Ansi.AUTO.string("  @|bold Deleting container|@ " + name + "..."));
      }
      mgr.forceDelete(name);
    }
    if (dirPresent) {
      if (dryRun) {
        System.out.println("[dry-run] rm -rf " + projectDir);
      } else {
        deleteDirectory(projectDir);
      }
    }
    var purged = purge ? purgeFromCatalog(erases) : null;

    emitResult(containerPresent, dirPresent, purged);
  }

  static String confirmPrompt(String name, boolean purge) {
    if (purge) {
      return "Destroy project "
          + name
          + " and erase it everywhere — its specs, rooms, runs, files and history, on every box?"
          + " This cannot be undone.";
    }
    return "Destroy project " + name + "? This cannot be undone.";
  }

  /** What the purge will erase, rehearsed through the one prune method before anything goes. */
  private String rehearsePurge() {
    try (var operations = OperationsFactory.open()) {
      return operations.catalog().purgeSummary(name);
    }
  }

  /** Prunes the project everywhere, through the one prune method. Idempotent once erased. */
  private HostCatalog.Destroyed purgeFromCatalog(String erases) {
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
      boolean containerPresent, boolean dirPresent, HostCatalog.Destroyed purged) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("destroyed", name);
      if (!containerPresent) {
        map.put("status", dirPresent ? "state_cleaned" : "catalog_only");
      }
      if (purged != null) {
        map.put("purged", purged.purged());
        map.put("requested", purged.requested());
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
    if (purged != null && purged.requested()) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Asked main to erase '"
                  + name
                  + "' with its specs, rooms, runs, files and history — it goes on this box's"
                  + " next sync."));
    } else if (purged != null && purged.purged()) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Erased '"
                  + name
                  + "' with its specs, rooms, runs, files and history — other boxes follow on"
                  + " their next sync."));
    }
  }

  private static void deleteDirectory(Path dir) throws IOException {
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    }
  }
}
