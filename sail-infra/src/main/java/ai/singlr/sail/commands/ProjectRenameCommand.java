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
import ai.singlr.sail.engine.ProjectRenamer;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Renames a project on this box. Re-keys the catalog, specs, shared files, on-disk state, the Incus
 * container, and the guest hostname (see {@link ProjectRenamer}). The rename is local: it does not
 * propagate through {@code sail sync}, which the command states on success.
 */
@Command(
    name = "rename",
    description = "Rename a project on this box (local only — does not sync to other boxes).",
    mixinStandardHelpOptions = true)
public final class ProjectRenameCommand implements Runnable {

  @Parameters(index = "0", description = "Current project name.")
  private String name;

  @Parameters(index = "1", description = "New project name.")
  private String newName;

  @Option(names = "--dry-run", description = "Print what would happen instead of doing it.")
  private boolean dryRun;

  @Option(names = "--yes", description = "Skip the confirmation prompt.")
  private boolean yes;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    NameValidator.requireValidProjectName(newName);
    if (name.equals(newName)) {
      throw new IllegalArgumentException("'" + newName + "' is already the project's name.");
    }
    if (!json && !dryRun) {
      Banner.printBranding(System.out, Ansi.AUTO);
    }
    if (!dryRun && !ConsoleHelper.isRoot()) {
      throw new IllegalStateException(
          "Root privileges required. Run with: sudo sail project rename " + name + " " + newName);
    }

    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      new SchemaManager(db).migrate();
      var projects = new ProjectStore(db);
      if (projects.findByName(name).isEmpty()) {
        throw new IllegalStateException(
            "No project '" + name + "' in the catalog. Run 'sail project list' to see projects.");
      }
      if (projects.findByName(newName).isPresent()) {
        throw new IllegalStateException("A project named '" + newName + "' already exists.");
      }

      if (dryRun) {
        printDryRun();
        return;
      }
      if (!json && !confirm()) {
        System.out.println(Ansi.AUTO.string("  @|faint Cancelled.|@"));
        return;
      }

      var renamer = new ProjectRenamer(db, new ShellExecutor(false), SailPaths.projectsDir());
      var result = renamer.rename(name, newName);
      emit(result);
    }
  }

  private boolean confirm() {
    return ConsoleHelper.confirm(
        "Rename '" + name + "' to '" + newName + "'? This stops and restarts the container.");
  }

  private void printDryRun() throws Exception {
    var hasContainer =
        !(new ContainerManager(new ShellExecutor(false)).queryState(name)
            instanceof ContainerState.NotCreated);
    System.out.println("[dry-run] rename project '" + name + "' to '" + newName + "':");
    if (hasContainer) {
      System.out.println("[dry-run]   stop, 'incus rename " + name + " " + newName + "', restart");
      System.out.println("[dry-run]   set the container hostname to " + newName);
    }
    System.out.println("[dry-run]   re-key catalog, specs, and shared files to " + newName);
    System.out.println(
        "[dry-run]   move " + SailPaths.projectDir(name) + " -> " + SailPaths.projectDir(newName));
    System.out.println("[dry-run]   local only: not propagated to other boxes via sail sync");
  }

  private void emit(ProjectRenamer.Result result) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("action", "rename");
      map.put("from", result.from());
      map.put("to", result.to());
      map.put("container_renamed", result.containerRenamed());
      map.put("local_only", true);
      if (!result.warnings().isEmpty()) {
        map.put("warnings", result.warnings());
      }
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    System.out.println();
    System.out.println(
        Ansi.AUTO.string(
            "  @|bold,green ✓|@ Renamed @|bold "
                + result.from()
                + "|@ → @|bold "
                + result.to()
                + "|@"
                + (result.containerRenamed() ? " (container and all state)" : " (catalog only)")));
    for (var warning : result.warnings()) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|yellow ⚠|@ "
                  + warning
                  + " — run 'sail project reconfigure "
                  + result.to()
                  + "'"));
    }
    System.out.println();
    System.out.println(
        Ansi.AUTO.string(
            "  @|yellow ⚠ This rename is local to this box.|@ Other boxes won't see it through"
                + " @|bold sail sync|@ — rename it there too (or re-sync) once your FDEs are on"
                + " board."));
  }
}
