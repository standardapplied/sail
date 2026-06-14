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

    if (state instanceof ContainerState.NotCreated) {
      var projectDir = SailPaths.projectDir(name);
      if (Files.exists(projectDir)) {
        if (!dryRun) {
          deleteDirectory(projectDir);
        }
        if (json) {
          var map = new LinkedHashMap<String, Object>();
          map.put("destroyed", name);
          map.put("status", "state_cleaned");
          System.out.println(YamlUtil.dumpJson(map));
          return;
        }
        var msg = "  @|faint Container '" + name + "' already absent — cleaned up stale state.|@";
        System.out.println(Ansi.AUTO.string(msg));
        return;
      }
      if (json) {
        var map = new LinkedHashMap<String, Object>();
        map.put("destroyed", name);
        map.put("status", "already_absent");
        System.out.println(YamlUtil.dumpJson(map));
        return;
      }
      System.out.println(
          Ansi.AUTO.string("  @|faint Project '" + name + "' does not exist. Nothing to do.|@"));
      return;
    }

    if (!json) {
      System.out.println();
      Banner.printContainerStatus(name, state, System.out, Ansi.AUTO);
    }

    if (!yes && !dryRun) {
      System.out.println();
      if (!ConsoleHelper.confirm("Destroy project " + name + "? This cannot be undone.")) {
        System.out.println("  Aborted.");
        return;
      }
    }

    if (!json) {
      System.out.println();
      System.out.println(Ansi.AUTO.string("  @|bold Deleting container|@ " + name + "..."));
    }
    mgr.forceDelete(name);

    var projectDir = SailPaths.projectDir(name);
    if (Files.exists(projectDir)) {
      if (dryRun) {
        System.out.println("[dry-run] rm -rf " + projectDir);
      } else {
        deleteDirectory(projectDir);
      }
    }

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("destroyed", name);
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println();
    Banner.printProjectDestroyed(name, System.out, Ansi.AUTO);
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
