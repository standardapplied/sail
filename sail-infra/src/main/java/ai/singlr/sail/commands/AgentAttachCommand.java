/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.Stty;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 *       --resume <id>} / {@code codex resume <id>}) inside a host-owned session named for the run
 *       ({@link SessionYield#resumeSession}), pinned to the run's room, and attaches this terminal
 *       to it. The conversation outlives the terminal: {@code Ctrl-]} detaches, running attach
 *       again joins the live session rather than forking a second agent, and a dispatch that
 *       reserves the run's repos ends it with a reason in the stream.
 *   <li><b>Completed run without a session:</b> attaches a fresh conversation over a raw tty,
 *       loudly.
 *   <li><b>Live run:</b> refuses — attaching would fork a second concurrent agent over the same
 *       conversation and worktree. The refusal names the real lanes: reply in the spec room to
 *       steer it mid-run, or stop it first.
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

  @Option(names = "--socket", hidden = true, description = "Host socket override.")
  private Path socket;

  @Spec private CommandSpec commandSpec;

  /** What the resume lane opens: the host session, its child, and the room it is pinned to. */
  record ResumePlan(String session, List<String> command, String project, String room) {

    /** The {@code sail session} verbs this attach is made of — what {@code --dry-run} prints. */
    String asSessionCommands() {
      var open = new ArrayList<>(List.of("sail", "session", "new", session, "--project", project));
      if (!room.isBlank()) {
        open.addAll(List.of("--room", room));
      }
      open.add("--command");
      open.addAll(command);
      return open.stream().map(AgentAttachCommand::shellWord).collect(Collectors.joining(" "))
          + "\nsail session attach "
          + session;
    }
  }

  /** The latest run and, when its room exists on this box, that room. */
  record Latest(RunStore.RunRow run, String room) {
    static final Latest NONE = new Latest(null, "");
  }

  @Override
  public void run() {
    CliCommand.run(commandSpec, this::execute);
  }

  private void execute() throws Exception {
    name = CurrentProject.require(name);
    NameValidator.requireValidProjectName(name);

    var latest = latest();
    var run = latest.run();
    if (run != null && LIVE_STATUSES.contains(run.status())) {
      throw new IllegalStateException(refusal(run));
    }
    var agentType = run != null ? AgentCli.fromYamlName(run.agent()) : resolveAgentType();
    var sessionId = validatedSessionId(run);
    var command = buildResumeCommand(agentType, sessionId);
    if (sessionId == null) {
      attachFresh(run, agentType, buildIncusExecWithTty(name, command));
      return;
    }
    var plan = new ResumePlan(SessionYield.resumeSession(run.id()), command, name, latest.room());
    if (json) {
      System.out.println(YamlUtil.dumpJson(plan(run, agentType, sessionId, plan)));
      return;
    }
    if (dryRun) {
      System.out.println(plan.asSessionCommands());
      return;
    }
    if (Stty.saved().isEmpty()) {
      throw new IllegalStateException(
          "sail agent attach needs an interactive terminal to open session '"
              + plan.session()
              + "'.");
    }
    announceResume(run, agentType, sessionId, plan);
    requireRunning();
    var size = Stty.size(new int[] {24, 80});
    try (var client = SessionClient.connect(SessionCommand.socketOrDefault(socket))) {
      var opened = openOrJoin(client, plan, size[1], size[0]);
      System.out.println(
          Ansi.AUTO.string(
              opened
                  ? "  @|faint Opened session " + plan.session() + " (Ctrl-] detaches).|@"
                  : "  @|faint Joined the live session "
                      + plan.session()
                      + " (Ctrl-] detaches).|@"));
      SessionCommand.attachTerminal(client, plan.session(), true);
    }
  }

  /**
   * Opens the resume session, or joins it when it is already live: a create the host refuses
   * because the session runs — a reattach after detach, or the losing side of two simultaneous
   * attaches — is the join case, never a second agent. Any other refusal is the error it was.
   * Returns whether this call opened the session.
   */
  static boolean openOrJoin(SessionClient client, ResumePlan plan, int cols, int rows)
      throws IOException {
    try {
      client.create(
          plan.session(),
          plan.command(),
          System.getProperty("user.home", "/home/dev"),
          plan.project(),
          plan.room(),
          cols,
          rows);
      return true;
    } catch (IOException refused) {
      if (client.list().stream()
          .anyMatch(info -> info.live() && info.name().equals(plan.session()))) {
        return false;
      }
      throw refused;
    }
  }

  private void attachFresh(RunStore.RunRow run, AgentCli agentType, List<String> command)
      throws Exception {
    if (json) {
      System.out.println(YamlUtil.dumpJson(plan(run, agentType, null, command)));
      return;
    }
    if (dryRun) {
      System.out.println(String.join(" ", command));
      return;
    }
    announceFresh(run);
    requireRunning();
    var process = new ProcessBuilder(command).inheritIO().start();
    var exitCode = process.waitFor();
    if (exitCode != 0) {
      System.err.println(
          Ansi.AUTO.string("  @|yellow ⚠|@ Agent session exited with code " + exitCode));
    }
  }

  private void requireRunning() throws Exception {
    var state = new ContainerManager(new ShellExecutor(false)).queryState(name);
    ContainerStateGuard.requireRunning(state, name);
  }

  /**
   * The latest local agent session (build or ad-hoc) of the project with its room, read directly
   * from the control-plane database like the status and log lanes. A run whose room is not on this
   * box resolves to no room — the session then opens unbound, announced — so a legacy conversation
   * is never unreachable for want of a room row. An unreadable database fails closed through {@link
   * #orRefuse}.
   */
  private Latest latest() {
    return orRefuse(
        name,
        () -> {
          try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
            var run =
                new RunStore(db).latestForProjectOnNode(name, NodeIdentity.handle()).orElse(null);
            return run == null ? Latest.NONE : new Latest(run, knownRoom(new RoomStore(db), run));
          }
        });
  }

  static String knownRoom(RoomStore rooms, RunStore.RunRow run) {
    var conversation = run.conversationId();
    return conversation != null && rooms.findById(conversation).isPresent() ? conversation : "";
  }

  /**
   * The fail-closed policy, pure: unknown is never absent. A successful query may find no run —
   * only then may attach fall back to a fresh conversation. A query failure of any kind refuses the
   * attach, because treating an unreadable database as "no run" would bypass the live-run refusal
   * and fork a second agent over the same worktree.
   */
  static <T> T orRefuse(String project, Supplier<T> query) {
    try {
      return query.get();
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "Could not read run state for project '" + project + "'; refusing to attach.", e);
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
        + ".";
  }

  private void announceFresh(RunStore.RunRow run) {
    if (run == null) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|yellow ⚠|@ No recorded run for '" + name + "'; attaching a fresh session."));
      return;
    }
    System.out.println(
        Ansi.AUTO.string(
            "  @|yellow ⚠|@ Run " + run.id() + " recorded no session; attaching fresh."));
  }

  private void announceResume(
      RunStore.RunRow run, AgentCli agentType, String sessionId, ResumePlan plan) {
    if (run.conversationId() != null && plan.room().isBlank()) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|yellow ⚠|@ Room "
                  + run.conversationId()
                  + " is not on this box; the session opens without a room."));
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
      RunStore.RunRow run, AgentCli agentType, String sessionId, ResumePlan resume) {
    var map = plan(run, agentType, sessionId, resume.command());
    map.put("session", resume.session());
    if (!resume.room().isBlank()) {
      map.put("room", resume.room());
    }
    return map;
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

  private static String shellWord(String word) {
    return word.matches("[A-Za-z0-9._/=:-]+") ? word : "'" + word.replace("'", "'\\''") + "'";
  }
}
