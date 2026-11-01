/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.config.SpecCatalog;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerManager.ContainerInfo;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ShellExecutor;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
    name = "show",
    description = "Show a project's definition and its container's live status.",
    mixinStandardHelpOptions = true)
public final class ProjectConfigShowCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    NameValidator.requireValidProjectName(name);

    var config = ProjectMutations.current(name, operator);

    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);
    var info = mgr.queryInfo(name);
    var agentSession = loadAgentSession(shell, name, info.state(), operator);
    var specSummary = loadSpecSummary(shell, config, info.state(), operator);

    if (json) {
      printJson(config, info, agentSession, specSummary);
      return;
    }

    Banner.printBranding(System.out, Ansi.AUTO);
    Banner.printProjectConfig(config, info.state(), System.out, Ansi.AUTO);
  }

  private static void printJson(
      ProjectDefinition config,
      ContainerInfo info,
      AgentSession.SessionInfo agentSession,
      SpecSnapshot specSummary) {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", config.name());
    map.put("definition", YamlUtil.parseMap(ProjectDefinitionJson.write(config)));
    var statusStr =
        switch (info.state()) {
          case ContainerState.Running ignored -> "running";
          case ContainerState.Stopped ignored -> "stopped";
          case ContainerState.NotCreated ignored -> "not_created";
          case ContainerState.Error ignored -> "error";
        };
    map.put("container_status", statusStr);
    if (info.state() instanceof ContainerState.Running r && r.ipv4() != null) {
      map.put("container_ip", r.ipv4());
    }
    if (info.limits() != null) {
      var limits = new LinkedHashMap<String, Object>();
      if (info.limits().cpu() != null) {
        limits.put("cpu", info.limits().cpu());
      }
      if (info.limits().memory() != null) {
        limits.put("memory", info.limits().memory());
      }
      if (!limits.isEmpty()) {
        map.put("container_limits", limits);
      }
    }
    map.put("agent_session", toAgentSessionMap(agentSession, info.state()));
    map.put("specs", specSummary.toMap());

    System.out.println(YamlUtil.dumpJson(map));
  }

  private static AgentSession.SessionInfo loadAgentSession(
      ShellExecutor shell, String containerName, ContainerState state, OperatorCommand operator)
      throws Exception {
    if (!(state instanceof ContainerState.Running)) {
      return null;
    }
    try (var operations = OperationsFactory.open()) {
      return operations.dispatching().projectSession(containerName, NodeIdentity.handle());
    }
  }

  private SpecSnapshot loadSpecSummary(
      ShellExecutor shell, ProjectDefinition config, ContainerState state, OperatorCommand operator)
      throws Exception {
    if (!(state instanceof ContainerState.Running)) {
      return SpecSnapshot.unavailable("project_stopped");
    }
    if (config.agent() == null) {
      return SpecSnapshot.unavailable("specs_not_configured");
    }
    try (var operations = OperationsFactory.open()) {
      return SpecSnapshot.available(SpecCatalog.summarize(operations.catalog().projectSpecs(name)));
    } catch (Exception e) {
      return SpecSnapshot.unavailable("specs_unavailable");
    }
  }

  private static Map<String, Object> toAgentSessionMap(
      AgentSession.SessionInfo session, ContainerState state) {
    var map = new LinkedHashMap<String, Object>();
    var available = state instanceof ContainerState.Running;
    map.put("available", available);
    map.put("running", session != null && session.running());
    if (!available) {
      map.put("reason", "project_stopped");
      return map;
    }
    if (session == null) {
      return map;
    }
    map.put("pid", session.pid());
    map.put("task", session.task());
    map.put("started_at", session.startedAt());
    map.put("branch", session.branch());
    map.put("log_path", session.logPath());
    return map;
  }

  private record SpecSnapshot(boolean available, String reason, SpecCatalog.Summary summary) {

    private static SpecSnapshot available(SpecCatalog.Summary summary) {
      return new SpecSnapshot(true, null, summary);
    }

    private static SpecSnapshot unavailable(String reason) {
      return new SpecSnapshot(false, reason, null);
    }

    private Map<String, Object> toMap() {
      var map = new LinkedHashMap<String, Object>();
      map.put("available", available);
      if (reason != null) {
        map.put("reason", reason);
      }
      if (summary != null) {
        map.putAll(summary.toMap());
      }
      return map;
    }
  }
}
