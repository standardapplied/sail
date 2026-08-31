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
 * Writes a sail-owned Codex CLI hooks file at {@link #SETTINGS_PATH} inside the container. Codex
 * does not expose a {@code --settings <path>} flag the way Claude Code does — its discovery is
 * fixed to {@code ~/.codex/hooks.json} (plus {@code config.toml} and project-scoped variants) — so
 * session scoping runs through Codex's hook trust model instead:
 *
 * <p>Codex records trust per hook against a content hash ({@code [hooks.state]} in the engineer's
 * {@code config.toml}) and <em>silently skips</em> new or changed hooks until they are trusted,
 * including in headless {@code codex exec} (verified against codex-cli 0.144.0). Sail cannot
 * pre-seed those hashes — the format is internal and {@code config.toml} belongs to the engineer —
 * so sail-dispatched sessions pass {@code --dangerously-bypass-hook-trust} instead (see {@link
 * AgentCli#headlessCommand}). Engineer-run interactive {@code codex} sessions never get that flag,
 * so this layer stays inert for them unless they trust it via {@code /hooks} — and even then the
 * {@link SailEventHelper} script self-gates on {@code SAIL_SPEC_ID} and {@link SailStopGate} on
 * {@code SAIL_RUN_ID}, so nothing leaks into the spec event bus and no interactive stop is gated.
 *
 * <p>Hooks wired:
 *
 * <ul>
 *   <li>{@code SessionStart} → {@code agent_session_started}, plus {@link SailSessionReport} beside
 *       it in the same matcher-less group: Codex's SessionStart payload carries {@code session_id}
 *       and {@code transcript_path} under the same names as Claude Code's (sources
 *       startup/resume/clear), so the one report script records the resumable conversation for both
 *       CLIs
 *   <li>{@code PreToolUse} → {@code agent_tool_started}
 *   <li>{@code PostToolUse} → {@code agent_tool_finished}, plus {@link SailRoomRelay} beside it:
 *       Codex's {@code PostToolUse} honors {@code hookSpecificOutput.additionalContext} exactly as
 *       Claude Code does (injected as a developer message before the next model call), so the one
 *       relay script gives both CLIs the same mid-run room delivery. Do not move the relay to
 *       {@code PreToolUse}: Codex parses but rejects {@code additionalContext} there.
 *   <li>{@code Stop} → {@link SailStopGate}, which publishes {@code agent_session_stopped} when it
 *       allows the stop or {@code agent_stop_nudged} when it blocks a premature one. It must be the
 *       only {@code Stop} hook: matching hooks run concurrently, so a bare publisher beside the
 *       gate would announce a stop that the gate then cancels. Codex's {@code Stop} payload carries
 *       the same {@code stop_hook_active} loop guard and honors the same {@code {"decision":
 *       "block", "reason": ...}} contract as Claude Code, so the one gate script serves both CLIs
 *       unmodified.
 * </ul>
 *
 * <p>The tool hooks are the dispatch watcher's liveness signal, exactly as in {@link
 * ClaudeCodeHookConfig}: {@code AgentWatchCommand} resets its stall timer on the heartbeats, so a
 * working Codex agent pushes the {@code max_idle} deadline out on every tool call.
 *
 * <p>Codex has no analogue of Claude Code's {@code SessionEnd}, so we do not emit {@code
 * agent_session_completed} for Codex agents. {@link ai.singlr.sail.api.SpecLifecycleReactor}
 * already transitions the spec to {@code review} on {@code stopped}, so the back half of the
 * lifecycle still works.
 */
public final class CodexHookConfig {

  /** Container-side directory holding the hooks file. */
  public static final String SETTINGS_DIR = "/home/dev/.codex";

  /** Hooks filename. */
  public static final String SETTINGS_FILE = "hooks.json";

  /** Container-side absolute path to the hooks file. */
  public static final String SETTINGS_PATH = SETTINGS_DIR + "/" + SETTINGS_FILE;

  private final ShellExec shell;

  public CodexHookConfig(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * Returns the JSON content that {@link #install} writes. Pure function — no I/O. Public for
   * tests.
   */
  public static String render() {
    var sessionStart = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_session_started");
    var toolStarted = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_tool_started");
    var toolFinished = hookCommand(SailEventHelper.SCRIPT_PATH, "agent_tool_finished");
    var stop = stopGateCommand();

    var hooks = new LinkedHashMap<String, Object>();
    hooks.put("SessionStart", List.of(matcherGroup(sessionStart, sessionReportCommand())));
    hooks.put("PreToolUse", List.of(matcherGroup(toolStarted)));
    hooks.put("PostToolUse", List.of(matcherGroup(toolFinished, roomRelayCommand())));
    hooks.put("Stop", List.of(matcherGroup(stop)));

    var root = new LinkedHashMap<String, Object>();
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
  private static Map<String, Object> matcherGroup(Map<String, Object>... hooks) {
    var group = new LinkedHashMap<String, Object>();
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
    hook.put("command", SailSessionReport.SCRIPT_PATH + " " + AgentCli.CODEX.yamlName());
    hook.put("timeout", SailSessionReport.HOOK_TIMEOUT_SECONDS);
    return hook;
  }
}
