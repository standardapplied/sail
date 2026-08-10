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
 * <p>Hooks wired:
 *
 * <ul>
 *   <li>{@code SessionStart} ({@code matcher: startup}) → {@code agent_session_started}
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
    hooks.put("SessionStart", List.of(matcherGroup("startup", sessionStart)));
    hooks.put("PreToolUse", List.of(matcherGroup(null, toolStarted)));
    hooks.put("PostToolUse", List.of(matcherGroup(null, toolFinished, roomRelayCommand())));
    hooks.put("Stop", List.of(matcherGroup(null, stop)));
    hooks.put("SessionEnd", List.of(matcherGroup(null, sessionEnd)));

    var root = new LinkedHashMap<String, Object>();
    root.put("includeCoAuthoredBy", false);
    root.put("hooks", hooks);
    return YamlUtil.dumpJson(root);
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
}
