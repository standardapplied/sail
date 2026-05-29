/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ShellExecutor;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "stop",
    description = "Stop a running agent session.",
    mixinStandardHelpOptions = true)
public final class AgentStopCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
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
    ContainerStateGuard.requireRunning(state, name);

    var agentSession = new AgentSession(shell);
    var info = agentSession.queryStatus(name);

    if (info == null || !info.running()) {
      if (json) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", name);
        map.put("stopped", false);
        map.put("reason", "no agent running");
        System.out.println(YamlUtil.dumpJson(map));
        return;
      }
      System.out.println(
          Ansi.AUTO.string("  @|faint No running agent session for " + name + ".|@"));
      return;
    }

    agentSession.killAgent(name);

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("stopped", true);
      map.put("pid", info.pid());
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    System.out.println(
        Ansi.AUTO.string("  @|green \u2713|@ Agent stopped (PID " + info.pid() + ") in " + name));
  }
}
