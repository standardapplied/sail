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
import ai.singlr.sail.engine.ShellExecutor;
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
    name = "stop",
    description = "Stop a project container, or all containers if no name given.",
    mixinStandardHelpOptions = true)
public final class DownCommand implements Runnable {

  @Parameters(index = "0", arity = "0..1", description = "Project name (omit to stop all).")
  private String name;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);

    if (name != null) {
      NameValidator.requireValidProjectName(name);
      stopOne(mgr, name);
    } else {
      stopAll(mgr);
    }
  }

  private void stopOne(ContainerManager mgr, String projectName) throws Exception {
    var state = mgr.queryState(projectName);

    switch (state) {
      case ContainerState.Running ignored -> {
        if (!json) {
          Banner.printBranding(System.out, Ansi.AUTO);
          System.out.println();
          System.out.println(Ansi.AUTO.string("  @|bold Stopping|@ " + projectName + "..."));
        }
        mgr.stop(projectName);
        if (json) {
          printJson(List.of(projectName));
          return;
        }
        Banner.printContainerStatus(
            projectName, new ContainerState.Stopped(), System.out, Ansi.AUTO);
      }
      case ContainerState.Stopped ignored -> {
        if (json) {
          printJson(List.of());
          return;
        }
        Banner.printBranding(System.out, Ansi.AUTO);
        System.out.println();
        Banner.printContainerStatus(projectName, state, System.out, Ansi.AUTO);
      }
      case ContainerState.NotCreated ignored ->
          throw new IllegalStateException("Project '" + projectName + "' does not exist.");
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }
  }

  private void stopAll(ContainerManager mgr) throws Exception {
    var containers = mgr.listAll();
    var running =
        containers.stream().filter(c -> c.state() instanceof ContainerState.Running).toList();

    if (running.isEmpty()) {
      if (json) {
        printJson(List.of());
        return;
      }
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
      System.out.println(Ansi.AUTO.string("  @|faint No running containers.|@"));
      return;
    }

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
    }

    var stopped = new ArrayList<String>();
    for (var c : running) {
      if (!json) {
        System.out.println(Ansi.AUTO.string("  @|bold Stopping|@ " + c.name() + "..."));
      }
      mgr.stop(c.name());
      stopped.add(c.name());
      if (!json) {
        Banner.printContainerStatus(c.name(), new ContainerState.Stopped(), System.out, Ansi.AUTO);
      }
    }

    if (json) {
      printJson(stopped);
      return;
    }

    System.out.println();
    System.out.println(
        Ansi.AUTO.string(
            "  @|bold,green \u2713|@ "
                + running.size()
                + " container"
                + (running.size() > 1 ? "s" : "")
                + " stopped."));
  }

  private static void printJson(List<String> stopped) {
    var map = new LinkedHashMap<String, Object>();
    map.put("stopped", stopped);
    System.out.println(YamlUtil.dumpJson(map));
  }
}
