/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerSetupSweep;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ResourceChecker;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import java.util.LinkedHashMap;
import java.util.function.Function;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "start",
    description = "Start a project container.",
    mixinStandardHelpOptions = true)
public final class UpCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  private final Function<Boolean, ShellExec> shells;

  public UpCommand() {
    this(ShellExecutor::new);
  }

  /**
   * @param shells the host shell, by whether this is a dry run
   */
  UpCommand(Function<Boolean, ShellExec> shells) {
    this.shells = shells;
  }

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    NameValidator.requireValidProjectName(name);
    var shell = shells.apply(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);

    switch (state) {
      case ContainerState.Running r -> {
        ContainerSetupSweep.reconcile(shell, name);
        if (json) {
          printJson("running", r.ipv4());
          return;
        }
        Banner.printBranding(System.out, Ansi.AUTO);
        System.out.println();
        Banner.printContainerStatus(name, r, System.out, Ansi.AUTO);
        Banner.printConnect(name, operator, System.out, Ansi.AUTO);
        printTunnelHints(shell);
        printOvercommitHints(shell, operator);
      }
      case ContainerState.Stopped _, ContainerState.Frozen _ -> {
        if (!json) {
          System.out.println(Ansi.AUTO.string("  @|bold Starting|@ " + name + "..."));
        }
        mgr.start(name);
        ContainerSetupSweep.reconcile(shell, name);
        var newState = mgr.queryState(name);
        var ip = newState instanceof ContainerState.Running r ? r.ipv4() : null;
        if (json) {
          printJson("started", ip);
          return;
        }
        Banner.printBranding(System.out, Ansi.AUTO);
        System.out.println();
        Banner.printContainerStatus(name, new ContainerState.Running(ip), System.out, Ansi.AUTO);
        Banner.printConnect(name, operator, System.out, Ansi.AUTO);
        printTunnelHints(shell);
        printOvercommitHints(shell, operator);
      }
      case ContainerState.NotCreated ignored -> throw ContainerStateGuard.notCreated(name);
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    }
  }

  private void printJson(String status, String ip) {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", name);
    map.put("status", status);
    map.put("ip", ip);
    System.out.println(YamlUtil.dumpJson(map));
  }

  private void printTunnelHints(ShellExec shell) {
    try {
      var ports = ContainerExec.queryServicePorts(shell, name);
      Banner.printSshTunnels(name, null, ports, System.out, Ansi.AUTO);
    } catch (Exception ignored) {
    }
  }

  private void printOvercommitHints(ShellExec shell, OperatorCommand operator) {
    try {
      var containers =
          new BoxInstances(shell).all().stream().map(BoxInstances.Instance::info).toList();
      var running =
          containers.stream().filter(c -> c.state() instanceof ContainerState.Running).toList();
      var others = running.stream().filter(c -> !c.name().equals(name)).toList();
      Banner.printAlsoRunning(others, System.out, Ansi.AUTO);

      var hostInfo = new HostDetector().detect();
      var capacity = new ResourceChecker.HostCapacity(hostInfo.threads(), hostInfo.memoryMb());
      var overcommit = ResourceChecker.check(running, capacity);
      Banner.printOvercommitWarning(overcommit, operator, System.out, Ansi.AUTO);
    } catch (Exception ignored) {
    }
  }
}
