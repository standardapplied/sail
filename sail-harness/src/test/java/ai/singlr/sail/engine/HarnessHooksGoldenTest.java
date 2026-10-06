/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.harness.Harnesses;
import org.junit.jupiter.api.Test;

/**
 * Pins both hook files sail installs, whole, as text. The expected strings here never change; only
 * the expressions that produce them do.
 */
class HarnessHooksGoldenTest {

  static final String CLAUDE_CODE_HOOKS =
      """
      {"includeCoAuthoredBy": false, \
      "permissions": {"deny": ["Read(//var/lib/sail/run/box.credential)", \
      "Read(/home/dev/.ssh/**)", "Read(/home/dev/.git-credentials)"]}, \
      "hooks": {\
      "SessionStart": [{"matcher": "startup", "hooks": [\
      {"type": "command", "command": "/home/dev/.sail/bin/sail-event.sh agent_session_started", "timeout": 10}]}, \
      {"hooks": [\
      {"type": "command", "command": "/home/dev/.sail/bin/sail-session-report claude-code", "timeout": 10}]}], \
      "PreToolUse": [{"hooks": [\
      {"type": "command", "command": "/home/dev/.sail/bin/sail-event.sh agent_tool_started", "timeout": 10}]}], \
      "PostToolUse": [{"hooks": [\
      {"type": "command", "command": "/home/dev/.sail/bin/sail-event.sh agent_tool_finished", "timeout": 10}, \
      {"type": "command", "command": "/home/dev/.sail/bin/sail-room-relay", "timeout": 15}]}], \
      "PostToolUseFailure": [{"hooks": [\
      {"type": "command", "command": "/home/dev/.sail/bin/sail-event.sh agent_tool_finished", "timeout": 10}]}], \
      "PostToolBatch": [{"hooks": [\
      {"type": "command", "command": "/home/dev/.sail/bin/sail-event.sh batch-resolved", "timeout": 10}]}], \
      "Stop": [{"hooks": [\
      {"type": "command", "command": "/home/dev/.sail/bin/sail-stop-gate", "timeout": 15}]}], \
      "SessionEnd": [{"hooks": [\
      {"type": "command", "command": "/home/dev/.sail/bin/sail-event.sh agent_session_completed", "timeout": 10}]}]}}""";

  static final String CODEX_HOOKS =
      """
      {"hooks": {\
      "SessionStart": [{"hooks": [\
      {"type": "command", "command": "/home/dev/.sail/bin/sail-event.sh agent_session_started", "timeout": 10}, \
      {"type": "command", "command": "/home/dev/.sail/bin/sail-session-report codex", "timeout": 10}]}], \
      "PreToolUse": [{"hooks": [\
      {"type": "command", "command": "/home/dev/.sail/bin/sail-event.sh agent_tool_started", "timeout": 10}]}], \
      "PostToolUse": [{"hooks": [\
      {"type": "command", "command": "/home/dev/.sail/bin/sail-event.sh agent_tool_finished", "timeout": 10}, \
      {"type": "command", "command": "/home/dev/.sail/bin/sail-room-relay", "timeout": 15}]}], \
      "Stop": [{"hooks": [\
      {"type": "command", "command": "/home/dev/.sail/bin/sail-stop-gate", "timeout": 15}]}]}}""";

  @Test
  void claudeCodeHookFile() {
    var claudeCode = Harnesses.of("claude-code");

    assertEquals("/home/dev/.sail/claude-settings.json", claudeCode.hooks().path());
    assertEquals(CLAUDE_CODE_HOOKS, HarnessHooks.render(claudeCode));
  }

  @Test
  void codexHookFile() {
    var codex = Harnesses.of("codex");

    assertEquals("/home/dev/.codex/hooks.json", codex.hooks().path());
    assertEquals(CODEX_HOOKS, HarnessHooks.render(codex));
  }
}
