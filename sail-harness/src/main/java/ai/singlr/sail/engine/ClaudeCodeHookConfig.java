/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Writes a sail-owned Claude Code settings file at {@link #SETTINGS_PATH} inside the container.
 * Passed to {@code claude} via {@code --settings} only when sail launches the agent — engineer SSH
 * sessions that run bare {@code claude} never see these hooks, so their {@code Stop} events do not
 * leak into the spec event bus.
 *
 * <p>Besides hooks, the file carries the {@link #roomReadDenyRules} permission rules — a
 * belt-and-suspenders Read-deny on the container's top secrets (box credential, SSH identity, git
 * credential) for the room / read-only-invite lane, whose primary read boundary is Claude's
 * cwd-scoped approval (see {@link #roomReadDenyRules}).
 *
 * <p>Hooks wired:
 *
 * <ul>
 *   <li>{@code SessionStart} ({@code matcher: startup}) → {@code agent_session_started}, plus
 *       {@link SailSessionReport} in its own matcher-less group beside it: the event announces a
 *       session's beginning exactly once, but the session report must fire on every start source —
 *       a resume, clear, or compact restart mints a new conversation whose identity must overwrite
 *       the row (last write wins) — so it cannot share the startup-matched group
 *   <li>{@code PreToolUse} → {@code agent_tool_started}
 *   <li>{@code PostToolUse} → {@code agent_tool_finished}, plus {@link SailRoomRelay} beside it in
 *       the same matcher group: the heartbeat prints nothing, so stdout stays the relay's for
 *       mid-run room delivery via {@code hookSpecificOutput.additionalContext}
 *   <li>{@code Stop} → {@link SailStopGate}, which publishes {@code agent_session_stopped} when it
 *       allows the stop or {@code agent_stop_nudged} when it blocks a premature one. It must be the
 *       only {@code Stop} hook: hooks in a matcher group run in parallel, so a bare publisher
 *       beside the gate would announce a stop that the gate then cancels.
 *   <li>{@code SessionEnd} → {@code agent_session_completed}
 * </ul>
 *
 * <p>The tool hooks are the dispatch watcher's liveness signal: {@code AgentWatchCommand} resets
 * its stall timer on {@code agent_tool_started}/{@code agent_tool_finished}, so a working agent
 * pushes the {@code max_idle} deadline out on every tool call. Without them the stall timer counts
 * from launch and kills even a busy agent at {@code max_idle}.
 *
 * <p>This is the one hooks layer for every sail-launched Claude session; the lane is expressed
 * entirely by the environment, never by a second settings file. Dispatch exports {@code
 * SAIL_SPEC_ID} and {@code SAIL_RUN_ID} (events + stop gate); the review pipeline's fix lane
 * exports only {@code SAIL_RUN_ID} (gate armed, events silent); the reviewer exports neither, so
 * every hook is inert. The scripts self-gate on those variables, which keeps the file install-once
 * at provision/sync time rather than rewritten per dispatch — and keeps Claude Code and Codex
 * symmetric, since Codex's fixed hooks discovery admits no per-session file either.
 */
public final class ClaudeCodeHookConfig {

  /** Container-side directory holding the settings file. */
  public static final String SETTINGS_DIR = "/home/dev/.sail";

  /** Settings filename. */
  public static final String SETTINGS_FILE = "claude-settings.json";

  /** Container-side absolute path to the settings file. Used with {@code claude --settings}. */
  public static final String SETTINGS_PATH = SETTINGS_DIR + "/" + SETTINGS_FILE;

  private final ShellExec shell;

  public ClaudeCodeHookConfig(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * Returns the JSON content that {@link #install} writes. Pure function — no I/O. Public for tests
   * and {@code sail spec dispatch --show-hooks}.
   */
  public static String render() {
    var sessionStart = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_session_started");
    var toolStarted = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_tool_started");
    var toolFinished = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_tool_finished");
    var stop = stopGateCommand();
    var sessionEnd = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_session_completed");

    var hooks = new LinkedHashMap<String, Object>();
    hooks.put(
        "SessionStart",
        List.of(matcherGroup("startup", sessionStart), matcherGroup(null, sessionReportCommand())));
    hooks.put("PreToolUse", List.of(matcherGroup(null, toolStarted)));
    hooks.put("PostToolUse", List.of(matcherGroup(null, toolFinished, roomRelayCommand())));
    hooks.put("Stop", List.of(matcherGroup(null, stop)));
    hooks.put("SessionEnd", List.of(matcherGroup(null, sessionEnd)));

    var root = new LinkedHashMap<String, Object>();
    root.put("includeCoAuthoredBy", false);
    root.put("permissions", Map.of("deny", roomReadDenyRules()));
    root.put("hooks", hooks);
    return YamlUtil.dumpJson(root);
  }

  /**
   * The Read-deny rules that belt-and-suspenders the room / read-only-invite lane's highest-value
   * credentials. The primary boundary is elsewhere: Claude Code auto-approves read commands ({@code
   * cat}/{@code head}/{@code tail}/{@code grep}) only inside the working directory (the workspace),
   * and refuses a read of any path outside it (verified empirically) — so the container's secrets,
   * all of which live outside {@code ~/workspace}, are unreadable by default even without a rule.
   * These denies harden the box FDE {@code box.credential}, the box SSH identity ({@code ~/.ssh} —
   * the Sail CLI identity), and the {@code ~/.git-credentials} token explicitly on top of that, so
   * the protection does not rest solely on the cwd heuristic. Claude Code applies a {@code
   * Read(path)} deny to a Bash command that reads that path (verified), deny outranks every allow
   * rule, and the room invocation pins {@code --setting-sources ""} so no ambient settings file can
   * shadow these. A full (YOLO) agent skips permission rules by design — it is the trusted member
   * lane. Spec-CLI auth is untouched: the helper reads the credential at the OS level, not through
   * a tool. Residual (a secret committed inside the workspace, a kernel escape, a
   * harness-enforcement bug) is owned by the room-lane hardening follow-up — a read-only-disk
   * sidecar — not this denylist.
   */
  public static List<String> roomReadDenyRules() {
    return List.of(
        boxCredentialReadDeny(), "Read(" + DEV_HOME + "/.ssh/**)", gitCredentialReadDeny());
  }

  private static final String DEV_HOME = "/home/dev";

  /** Deny rule for the ambient box FDE credential; see {@link #roomReadDenyRules}. */
  public static String boxCredentialReadDeny() {
    var credential = SailPaths.apiSocketContainerDir().resolve(BoxCredentialFile.FILE_NAME);
    return "Read(/" + credential + ")";
  }

  private static String gitCredentialReadDeny() {
    return "Read(" + DEV_HOME + "/.git-credentials)";
  }

  /**
   * Idempotently writes {@link #SETTINGS_PATH} inside the container. Install-once at provision or
   * {@code sail project apply}; not rewritten per dispatch.
   */
  public void install(String container) throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(container);

    var mkdir =
        shell.exec(ContainerExec.asDevUser(container, List.of("mkdir", "-p", SETTINGS_DIR)));
    if (!mkdir.ok()) {
      throw new IOException(
          "Failed to create " + SETTINGS_DIR + " in " + container + ": " + mkdir.stderr());
    }

    var write =
        shell.exec(
            ContainerExec.asDevUser(
                container,
                List.of(
                    "bash", "-c", "printf '%s' \"$1\" > \"$2\"", "bash", render(), SETTINGS_PATH)));
    if (!write.ok()) {
      throw new IOException(
          "Failed to write " + SETTINGS_PATH + " in " + container + ": " + write.stderr());
    }
  }

  @SafeVarargs
  private static Map<String, Object> matcherGroup(String matcher, Map<String, Object>... hooks) {
    var group = new LinkedHashMap<String, Object>();
    if (matcher != null) {
      group.put("matcher", matcher);
    }
    group.put("hooks", List.of(hooks));
    return group;
  }

  private static Map<String, Object> hookCommand(String script, String eventType) {
    var hook = new LinkedHashMap<String, Object>();
    hook.put("type", "command");
    hook.put("command", script + " " + eventType);
    hook.put("timeout", 10);
    return hook;
  }

  private static Map<String, Object> stopGateCommand() {
    var hook = new LinkedHashMap<String, Object>();
    hook.put("type", "command");
    hook.put("command", SailStopGate.SCRIPT_PATH);
    hook.put("timeout", SailStopGate.HOOK_TIMEOUT_SECONDS);
    return hook;
  }

  private static Map<String, Object> roomRelayCommand() {
    var hook = new LinkedHashMap<String, Object>();
    hook.put("type", "command");
    hook.put("command", SailRoomRelay.SCRIPT_PATH);
    hook.put("timeout", SailRoomRelay.HOOK_TIMEOUT_SECONDS);
    return hook;
  }

  private static Map<String, Object> sessionReportCommand() {
    var hook = new LinkedHashMap<String, Object>();
    hook.put("type", "command");
    hook.put("command", SailSessionReport.SCRIPT_PATH);
    hook.put("timeout", SailSessionReport.HOOK_TIMEOUT_SECONDS);
    return hook;
  }
}
