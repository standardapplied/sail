/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ShellExecutor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "logs",
    description = "Tail logs for a Podman service inside a project.",
    mixinStandardHelpOptions = true)
public final class LogsCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Parameters(index = "1", arity = "0..1", description = "Service name (omit to list services).")
  private String service;

  @Option(
      names = {"-f", "--follow"},
      description = "Follow log output.")
  private boolean follow;

  @Option(names = "--tail", description = "Number of lines to show.", defaultValue = "100")
  private int tail;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState(name);
    ContainerStateGuard.requireRunning(state, name);

    if (service == null) {
      listServices(shell);
      return;
    }

    NameValidator.requireValidServiceName(service);

    var podmanCmd = new ArrayList<>(List.of("podman", "logs", "--tail", String.valueOf(tail)));
    if (follow) {
      podmanCmd.add("--follow");
    }
    podmanCmd.add(service);

    if (follow && !json) {
      var fullCmd = ContainerExec.asDevUser(name, podmanCmd);
      var pb = new ProcessBuilder(fullCmd);
      pb.inheritIO();
      var process = pb.start();
      var exitCode = process.waitFor();
      if (exitCode != 0) {
        System.err.println(Banner.errorLine("Logs exited with code " + exitCode, Ansi.AUTO));
      }
    } else {
      var cmd = ContainerExec.asDevUser(name, podmanCmd);
      var result = shell.exec(cmd);
      if (!result.ok()) {
        throw new IOException("Failed to get logs for '" + service + "': " + result.stderr());
      }
      if (json) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", name);
        map.put("service", service);
        var lines =
            result.stdout().isBlank()
                ? List.of()
                : Arrays.asList(result.stdout().strip().split("\n"));
        map.put("lines", lines);
        System.out.println(YamlUtil.dumpJson(map));
      } else {
        System.out.print(result.stdout());
      }
    }
  }

  private void listServices(ShellExecutor shell) throws Exception {
    var cmd = ContainerExec.asDevUser(name, List.of("podman", "ps", "--format", "{{.Names}}"));
    var result = shell.exec(cmd);

    var services = new ArrayList<String>();
    if (result.ok() && !result.stdout().isBlank()) {
      for (var line : result.stdout().strip().split("\n")) {
        if (!line.isBlank()) {
          services.add(line.strip());
        }
      }
    }

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("services", services);
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    Banner.printBranding(System.out, Ansi.AUTO);
    System.out.println();

    if (services.isEmpty()) {
      System.out.println(Ansi.AUTO.string("  @|faint No Podman containers running.|@"));
      return;
    }

    System.out.println(Ansi.AUTO.string("  @|bold Available services:|@"));
    for (var svc : services) {
      System.out.println("    " + svc);
    }
    System.out.println();
    System.out.println(
        Ansi.AUTO.string("  Usage: @|bold sail project logs " + name + " <service>|@"));
  }
}
