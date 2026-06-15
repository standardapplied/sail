/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ResourceChecker;
import ai.singlr.sail.engine.ShellExecutor;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "switch",
    aliases = "restart",
    description =
        "Switch to a project (start it, make it current), warning if resources overcommit.",
    mixinStandardHelpOptions = true)
public final class SwitchCommand implements Runnable {

  @Parameters(index = "0", description = "Project name to switch to.")
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
    NameValidator.requireValidProjectName(name);
    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);

    var state = mgr.queryState(name);
    if (state instanceof ContainerState.NotCreated) {
      throw new IllegalStateException(
          "Project '" + name + "' does not exist. Run 'sail project create' first.");
    }
    if (state instanceof ContainerState.Error e) {
      throw new IllegalStateException("Container error: " + e.message());
    }

    if (!dryRun) {
      CurrentProject.set(name);
    }

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
    }

    String ip = null;
    if (state instanceof ContainerState.Running r) {
      ip = r.ipv4();
      if (!json) {
        Banner.printContainerStatus(name, r, System.out, Ansi.AUTO);
      }
    } else {
      if (!json) {
        System.out.println(Ansi.AUTO.string("  @|bold Starting|@ " + name + "..."));
      }
      mgr.start(name);
      var newState = mgr.queryState(name);
      ip = newState instanceof ContainerState.Running r ? r.ipv4() : null;
      if (!json) {
        Banner.printContainerStatus(name, new ContainerState.Running(ip), System.out, Ansi.AUTO);
      }
    }

    var containers = mgr.listAll();
    var running =
        containers.stream().filter(c -> c.state() instanceof ContainerState.Running).toList();
    var others = running.stream().filter(c -> !c.name().equals(name)).toList();

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("status", "running");
      map.put("ip", ip);
      map.put("also_running", others.stream().map(ContainerManager.ContainerInfo::name).toList());
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    Banner.printZedConnect(name, null, System.out, Ansi.AUTO);
    try {
      var ports = ContainerExec.queryServicePorts(shell, name);
      Banner.printSshTunnels(name, null, ports, System.out, Ansi.AUTO);
    } catch (Exception ignored) {
    }

    Banner.printAlsoRunning(others, System.out, Ansi.AUTO);

    try {
      var hostInfo = new HostDetector().detect();
      var capacity = new ResourceChecker.HostCapacity(hostInfo.threads(), hostInfo.memoryMb());
      var overcommit = ResourceChecker.check(running, capacity);
      Banner.printOvercommitWarning(overcommit, System.out, Ansi.AUTO);
    } catch (Exception ignored) {
    }
  }
}
