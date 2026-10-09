/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.engine.ContainerExec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/** Codex CLI: {@code codex exec}, whose hooks sail reaches through Codex's hook trust model. */
final class Codex implements Harness {

  private static final String BINARY = "codex";

  /**
   * Codex does not expose a {@code --settings <path>} flag the way Claude Code does: its discovery
   * is fixed to {@code ~/.codex/hooks.json} (plus {@code config.toml} and project-scoped variants),
   * so session scoping runs through Codex's hook trust model instead. Codex records trust per hook
   * against a content hash ({@code [hooks.state]} in the engineer's {@code config.toml}) and
   * <em>silently skips</em> new or changed hooks until they are trusted, including in headless
   * {@code codex exec} (verified against codex-cli 0.144.0). Sail cannot pre-seed those hashes,
   * since the format is internal and {@code config.toml} belongs to the engineer, so
   * sail-dispatched sessions pass {@code --dangerously-bypass-hook-trust} instead (see {@link
   * #headless}). Engineer-run interactive {@code codex} sessions never get that flag, so this layer
   * stays inert for them unless they trust it via {@code /hooks}, and even then sail's scripts
   * self-gate on the run's environment, so nothing leaks into the spec event bus and no interactive
   * stop is gated.
   */
  private static final String HOOKS_PATH = ContainerExec.DEV_HOME + "/.codex/hooks.json";

  @Override
  public String yamlName() {
    return "codex";
  }

  @Override
  public String binaryName() {
    return BINARY;
  }

  @Override
  public String displayName() {
    return "Codex CLI";
  }

  @Override
  public String installCommand() {
    return "curl -fsSL https://chatgpt.com/codex/install.sh | CODEX_NON_INTERACTIVE=1 sh";
  }

  @Override
  public String skillsDir() {
    return ".agents/skills/";
  }

  /**
   * {@code codex exec}, or {@code codex exec resume <id>} for a recorded conversation. Codex
   * already streams a readable transcript, so a streaming launch is the same command. A
   * full-permission session additionally passes {@code --dangerously-bypass-hook-trust}: Codex
   * silently skips untrusted hooks even in headless {@code exec}, and sail cannot pre-seed trust
   * hashes for the hooks file it owns and rewrites, so without the flag the sail hooks layer would
   * never fire. It is tied to full permissions because hooks run outside the Codex sandbox: in a
   * sandboxed session auto-trusting hooks would grant an escape hatch, while an unsandboxed session
   * gains nothing it did not already have. Every sail dispatch path runs with full permissions, and
   * interactive engineer sessions never get the flag.
   */
  @Override
  public String headless(Launch launch) {
    var resume = launch.resume();
    var perm =
        launch.fullPermissions()
            ? " --dangerously-bypass-approvals-and-sandbox --dangerously-bypass-hook-trust"
            : "";
    return BINARY
        + " exec"
        + resume.map(id -> " resume").orElse("")
        + perm
        + modelOptions(launch.model(), launch.reasoningEffort())
        + resume.map(id -> " " + id).orElse("")
        + " "
        + launch.task();
  }

  /**
   * Codex cannot run the room lane's read-only session at all: its only enforcement layer is the
   * bubblewrap sandbox, which needs user namespaces, blocked inside incus containers ({@code bwrap:
   * setting up uid map: Permission denied}), so its sole executing mode is the full bypass flag the
   * room forbids.
   */
  @Override
  public Optional<String> readOnlyRefusal() {
    return Optional.of(
        displayName()
            + " has no harness-enforced read-only session inside a sail container: its bubblewrap"
            + " sandbox needs user namespaces, which incus containers block, so its only working mode"
            + " bypasses all restrictions. Add it with full access instead — the per-turn repo"
            + " reservation guards that lane.");
  }

  @Override
  public String interactive(boolean fullPermissions) {
    return fullPermissions ? BINARY + " --dangerously-bypass-approvals-and-sandbox" : BINARY;
  }

  @Override
  public String attach(String sessionId) {
    return sessionId != null
        ? BINARY + " resume " + Harness.requireSafeSessionId(sessionId)
        : BINARY;
  }

  @Override
  public boolean honoursReasoningEffort() {
    return true;
  }

  @Override
  public OptionalInt loginTunnelPort() {
    return OptionalInt.empty();
  }

  @Override
  public Optional<String> interactiveTip() {
    return Optional.empty();
  }

  /**
   * Codex's {@code SessionStart} payload carries {@code session_id} and {@code transcript_path}
   * under the same names as Claude Code's, so the one report script records the resumable
   * conversation beside the session-started event. Its {@code PostToolUse} honors {@code
   * hookSpecificOutput.additionalContext} exactly as Claude Code does, so the one relay script
   * gives both the same mid-run room delivery; the relay must not move to {@code PreToolUse}, where
   * Codex parses but rejects {@code additionalContext}. Codex fires {@code PostToolUse} for a call
   * that failed as for one that succeeded, so it needs no counterpart of Claude Code's {@code
   * PostToolUseFailure}, and it has no batch hook. {@code Stop} carries the same {@code
   * stop_hook_active} loop guard and honors the same block decision as Claude Code, so the one gate
   * script serves both unmodified, and it must be the only {@code Stop} hook. Codex has no analogue
   * of {@code SessionEnd}, so no session-completed event is emitted for it; the spec lifecycle
   * already moves on the stop.
   */
  @Override
  public HookFile hooks() {
    return new HookFile(
        HOOKS_PATH,
        new LinkedHashMap<>(),
        List.of(
            new HookFile.Group(
                "SessionStart", null, List.of(SailHook.SESSION_STARTED, SailHook.SESSION_REPORT)),
            new HookFile.Group("PreToolUse", null, List.of(SailHook.TOOL_STARTED)),
            new HookFile.Group(
                "PostToolUse", null, List.of(SailHook.TOOL_FINISHED, SailHook.ROOM_RELAY)),
            new HookFile.Group("Stop", null, List.of(SailHook.STOP_GATE))));
  }

  private static String modelOptions(String model, String reasoningEffort) {
    var options = new StringBuilder();
    if (model != null) {
      options.append(" --model ").append(model);
    }
    if (reasoningEffort != null) {
      options.append(" --config model_reasoning_effort='\"").append(reasoningEffort).append("\"'");
    }
    return options.toString();
  }
}
