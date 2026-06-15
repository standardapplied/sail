/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SpecDirectory;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerManager.ContainerInfo;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "config",
    description = "Show project configuration and live container status.",
    mixinStandardHelpOptions = true)
public final class ProjectConfigCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
  private String name;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    NameValidator.requireValidProjectName(name);

    var singYamlPath = SailPaths.resolveSailYaml(name, file);
    if (!Files.exists(singYamlPath)) {
      throw new IllegalStateException(
          "Project descriptor not found: "
              + singYamlPath.toAbsolutePath()
              + "\n  Create a sail.yaml in the current directory, or specify one with --file.");
    }
    var config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));

    var shell = new ShellExecutor(false);
    var mgr = new ContainerManager(shell);
    var info = mgr.queryInfo(name);
    var agentSession = loadAgentSession(shell, name, info.state());
    var specSummary = loadSpecSummary(shell, config, info.state());

    if (json) {
      printJson(config, info, agentSession, specSummary);
      return;
    }

    Banner.printBranding(System.out, Ansi.AUTO);
    Banner.printProjectConfig(config, info.state(), System.out, Ansi.AUTO);
  }

  private static void printJson(
      SailYaml config,
      ContainerInfo info,
      AgentSession.SessionInfo agentSession,
      SpecSnapshot specSummary) {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", config.name());
    map.put("description", config.description());
    map.put("image", config.image());

    if (config.resources() != null) {
      var res = new LinkedHashMap<String, Object>();
      res.put("cpu", config.resources().cpu());
      res.put("memory", config.resources().memory());
      res.put("disk", config.resources().disk());
      map.put("resources", res);
    }

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

    if (config.runtimes() != null) {
      var rt = new LinkedHashMap<String, Object>();
      if (config.runtimes().jdk() > 0) rt.put("jdk", config.runtimes().jdk());
      if (config.runtimes().node() != null) rt.put("node", config.runtimes().node());
      if (config.runtimes().maven() != null) rt.put("maven", config.runtimes().maven());
      if (!rt.isEmpty()) map.put("runtimes", rt);
    }

    if (config.services() != null && !config.services().isEmpty()) {
      var svcs = new LinkedHashMap<String, Object>();
      for (var entry : config.services().entrySet()) {
        var svc = new LinkedHashMap<String, Object>();
        svc.put("image", entry.getValue().image());
        svc.put("ports", entry.getValue().ports());
        svcs.put(entry.getKey(), svc);
      }
      map.put("services", svcs);
    }

    if (config.agent() != null) {
      var agent = new LinkedHashMap<String, Object>();
      agent.put("type", config.agent().type());
      agent.put("auto_snapshot", config.agent().autoSnapshot());
      agent.put("auto_branch", config.agent().autoBranch());
      if (config.agent().specsDir() != null) agent.put("specs_dir", config.agent().specsDir());
      if (config.agent().install() != null) agent.put("install", config.agent().install());
      map.put("agent", agent);
    }
    map.put("agent_session", toAgentSessionMap(agentSession, info.state()));
    map.put("specs", specSummary.toMap());

    if (config.ssh() != null) {
      map.put("ssh_user", config.ssh().user());
    }

    System.out.println(YamlUtil.dumpJson(map));
  }

  private static AgentSession.SessionInfo loadAgentSession(
      ShellExecutor shell, String containerName, ContainerState state) throws Exception {
    if (!(state instanceof ContainerState.Running)) {
      return null;
    }
    return new AgentSession(shell).queryStatus(containerName);
  }

  private SpecSnapshot loadSpecSummary(ShellExecutor shell, SailYaml config, ContainerState state)
      throws Exception {
    if (!(state instanceof ContainerState.Running)) {
      return SpecSnapshot.unavailable("project_stopped");
    }
    if (config.agent() == null || config.agent().specsDir() == null) {
      return SpecSnapshot.unavailable("specs_not_configured");
    }
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      return SpecSnapshot.available(SpecDirectory.summarize(new SpecStore(db).projectSpecs(name)));
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

  private record SpecSnapshot(boolean available, String reason, SpecDirectory.Summary summary) {

    private static SpecSnapshot available(SpecDirectory.Summary summary) {
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
