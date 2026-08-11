/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Opens the project's latest agent conversation. {@code --resume} is conversation resurrection, not
 * process attachment: it spawns a fresh agent process loading the saved conversation, which is
 * exactly right for a completed run and exactly wrong for a live one.
 *
 * <ul>
 *   <li><b>Completed run with a recorded session:</b> resumes that session exactly ({@code claude
 *       --resume <id>} / {@code codex resume <id>}) in the container under the caller's ambient
 *       identity — never an interactive picker.
 *   <li><b>Completed run without a session:</b> attaches a fresh conversation, loudly.
 *   <li><b>Live run:</b> refuses — attaching would fork a second concurrent agent over the same
 *       conversation and worktree. The refusal names the real lanes: reply in the spec room to
 *       steer it mid-run, or stop it first; live observe/attach arrives with the PTY session host.
 * </ul>
 */
@Command(
    name = "attach",
    description = "Resume the latest run's recorded agent conversation.",
    mixinStandardHelpOptions = true)
public final class AgentAttachCommand implements Runnable {

  private static final Pattern SAFE_SESSION_ID =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  private static final Set<String> LIVE_STATUSES = Set.of("running", "stopping");

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (default: the current project).")
  private String name;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml.",
      defaultValue = "sail.yaml")
  private String file;

  @Option(names = "--dry-run", description = "Print the exact command instead of attaching.")
  private boolean dryRun;

  @Option(names = "--json", description = "Print the resolved attach plan as JSON; do not attach.")
  private boolean json;

  @Spec private CommandSpec commandSpec;

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    name = CurrentProject.require(name);
    NameValidator.requireValidProjectName(name);

    var run = latestRun().orElse(null);
    if (run != null && LIVE_STATUSES.contains(run.status())) {
      throw new IllegalStateException(refusal(run));
    }
    var agentType = run != null ? AgentCli.fromYamlName(run.agent()) : resolveAgentType();
    var sessionId = validatedSessionId(run);
    var command = buildIncusExecWithTty(name, buildResumeCommand(agentType, sessionId));

    if (json) {
      System.out.println(YamlUtil.dumpJson(plan(run, agentType, sessionId, command)));
      return;
    }
    if (dryRun) {
      System.out.println(String.join(" ", command));
      return;
    }

    announce(run, agentType, sessionId);
    var state = new ContainerManager(new ShellExecutor(false)).queryState(name);
    ContainerStateGuard.requireRunning(state, name);
    var process = new ProcessBuilder(command).inheritIO().start();
    var exitCode = process.waitFor();
    if (exitCode != 0) {
      System.err.println(
          Ansi.AUTO.string("  @|yellow ⚠|@ Agent session exited with code " + exitCode));
    }
  }

  /**
   * The latest local agent session (build or ad-hoc) of the project, read directly from the
   * control-plane database like the status and log lanes. Empty only when a successful query finds
   * no row — attach then falls back to a fresh conversation, loudly. An unreadable database
   * (locked, corrupt, unmigrated) fails closed instead: treating it as "no run" would bypass the
   * live-run refusal and fork a second agent over the same worktree.
   */
  private Optional<RunStore.RunRow> latestRun() {
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      return new RunStore(db).latestForProjectOnNode(name, NodeIdentity.handle());
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "Could not read run state for project '" + name + "'; refusing to attach.", e);
    }
  }

  /**
   * The recorded session id, validated before it is spliced into a shell command: the value arrives
   * from an agent-reported hook payload and replicates between boxes, so only a plain token shape
   * ever reaches the argv. The first character must be alphanumeric — an id starting with {@code -}
   * would be parsed by the agent CLI as an option, not a session id. A malformed id fails loud
   * rather than resuming something else.
   */
  private static String validatedSessionId(RunStore.RunRow run) {
    if (run == null || run.sessionId() == null) {
      return null;
    }
    if (!isSafeSessionId(run.sessionId())) {
      throw new IllegalStateException(
          "Run "
              + run.id()
              + " recorded a malformed session id; refusing to build a resume command from it."
              + " Attach fresh with: sail agent attach --json to inspect, or start a new session"
              + " inside the container.");
    }
    return run.sessionId();
  }

  static boolean isSafeSessionId(String sessionId) {
    return SAFE_SESSION_ID.matcher(sessionId).matches();
  }

  private String refusal(RunStore.RunRow run) {
    var room =
        run.specId() == null
            ? "its spec room"
            : "its spec room (spec comment " + run.specId() + ")";
    return "Run "
        + run.id()
        + " is live — attaching would fork a second concurrent agent over the same conversation"
        + " and worktree. Reply in "
        + room
        + " to steer it (delivered mid-run), or stop it first with: sail agent stop "
        + name
        + ". Live observe/attach arrives with the PTY session host.";
  }

  private void announce(RunStore.RunRow run, AgentCli agentType, String sessionId) {
    if (run == null) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|yellow ⚠|@ No recorded run for '" + name + "'; attaching a fresh session."));
      return;
    }
    if (sessionId == null) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|yellow ⚠|@ Run " + run.id() + " recorded no session; attaching fresh."));
      return;
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|faint Resuming "
                + agentType.yamlName()
                + " session "
                + sessionId
                + " of run "
                + run.id()
                + " in "
                + name
                + "...|@"));
  }

  private LinkedHashMap<String, Object> plan(
      RunStore.RunRow run, AgentCli agentType, String sessionId, List<String> command) {
    var map = new LinkedHashMap<String, Object>();
    map.put("project", name);
    map.put("agent", agentType.yamlName());
    map.put("mode", sessionId != null ? "resume" : "fresh");
    if (run != null) {
      map.put("run_id", run.id());
      map.put("run_status", run.status());
      if (sessionId != null) {
        map.put("session_id", sessionId);
      }
      if (run.sessionSource() != null) {
        map.put("session_source", run.sessionSource());
      }
    }
    map.put("command", command);
    return map;
  }

  private AgentCli resolveAgentType() throws IOException {
    var sailYamlPath = SailPaths.resolveSailYaml(name, file);
    if (Files.exists(sailYamlPath)) {
      var config = SailYaml.fromMap(YamlUtil.parseFile(sailYamlPath));
      if (config.agent() != null && config.agent().type() != null) {
        return AgentCli.fromYamlName(config.agent().type());
      }
    }
    return AgentCli.CLAUDE_CODE;
  }

  /**
   * The in-container command: the recorded session resumed exactly by id, or a fresh conversation
   * when there is nothing to resume — never an interactive picker. Runs from the workspace root,
   * where sail-launched sessions run, so the CLI's per-directory session lookup finds the recorded
   * conversation.
   */
  public static List<String> buildResumeCommand(AgentCli agentType, String sessionId) {
    var launch =
        switch (agentType) {
          case CLAUDE_CODE -> sessionId != null ? "claude --resume " + sessionId : "claude";
          case CODEX -> sessionId != null ? "codex resume " + sessionId : "codex";
        };
    return List.of("bash", "-lc", "cd ~/workspace && " + launch);
  }

  static List<String> buildIncusExecWithTty(String container, List<String> args) {
    var cmd = new ArrayList<String>();
    cmd.add("incus");
    cmd.add("exec");
    cmd.add(container);
    cmd.add("--user");
    cmd.add("1000");
    cmd.add("--group");
    cmd.add("1000");
    cmd.add("--env");
    cmd.add("HOME=/home/dev");
    cmd.add("-t");
    cmd.add("--");
    cmd.addAll(args);
    return List.copyOf(cmd);
  }
}
