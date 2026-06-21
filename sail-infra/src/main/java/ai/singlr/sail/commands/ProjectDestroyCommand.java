/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
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
          "Also remove the project from the org catalog. This propagates the deletion to every box"
              + " on the next sync — by default destroy only tears down this box's container.")
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
    var purged = purge && purgeFromCatalog();

    emitResult(containerPresent, dirPresent, purged);
  }

  static String confirmPrompt(String name, boolean purge) {
    if (purge) {
      return "Destroy project "
          + name
          + " and remove it from the org catalog? It disappears from every box on the next sync,"
          + " and this cannot be undone.";
    }
    return "Destroy project " + name + "? This cannot be undone.";
  }

  /** Tombstones the project in the catalog so the removal replicates. Idempotent if absent. */
  private boolean purgeFromCatalog() {
    if (dryRun) {
      System.out.println(
          "[dry-run] remove '" + name + "' from the catalog (propagates to other boxes on sync)");
      return false;
    }
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      new SchemaManager(db).migrate();
      return new ProjectStore(db).delete(name);
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

  private void emitResult(boolean containerPresent, boolean dirPresent, boolean purged) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("destroyed", name);
      if (!containerPresent) {
        map.put("status", dirPresent ? "state_cleaned" : "catalog_only");
      }
      if (purge) {
        map.put("purged", purged);
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
    if (purged) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Removed '"
                  + name
                  + "' from the org catalog — it disappears from other boxes on the next sync."));
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
