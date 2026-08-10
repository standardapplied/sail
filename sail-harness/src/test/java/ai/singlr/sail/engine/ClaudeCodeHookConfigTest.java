/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClaudeCodeHookConfigTest {

  @Test
  void settingsPathConstantsMatch() {
    assertEquals("/home/dev/.sail", ClaudeCodeHookConfig.SETTINGS_DIR);
    assertEquals("claude-settings.json", ClaudeCodeHookConfig.SETTINGS_FILE);
    assertEquals("/home/dev/.sail/claude-settings.json", ClaudeCodeHookConfig.SETTINGS_PATH);
  }

  @Test
  void renderIncludesAllThreeHookKinds() {
    var json = ClaudeCodeHookConfig.render();
    assertTrue(json.contains("SessionStart"));
    assertTrue(json.contains("Stop"));
    assertTrue(json.contains("SessionEnd"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void renderWiresToolHooksSoTheStallWatcherSeesProgress() {
    var json = ClaudeCodeHookConfig.render();
    var hooks = (Map<String, Object>) YamlUtil.parseMap(json).get("hooks");
    assertTrue(
        hooks.containsKey("PreToolUse"),
        "PreToolUse must fire a progress heartbeat, or the stall guardrail counts a busy agent"
            + " as idle and kills it at max_idle");
    assertTrue(hooks.containsKey("PostToolUse"), "PostToolUse must fire a progress heartbeat");
    assertTrue(
        json.contains(SailEventHelper.SCRIPT_PATH + " agent_tool_started"),
        "PreToolUse must emit agent_tool_started (the event AgentWatchCommand resets the stall on)");
    assertTrue(
        json.contains(SailEventHelper.SCRIPT_PATH + " agent_tool_finished"),
        "PostToolUse must emit agent_tool_finished");
  }

  @Test
  @SuppressWarnings("unchecked")
  void renderWiresTheStopGateAsTheOnlyStopHook() {
    var json = ClaudeCodeHookConfig.render();
    var hooks = (Map<String, Object>) YamlUtil.parseMap(json).get("hooks");
    var stopGroups = (List<Map<String, Object>>) hooks.get("Stop");
    assertEquals(1, stopGroups.size());
    var stopHooks = (List<Map<String, Object>>) stopGroups.get(0).get("hooks");
    assertEquals(
        1,
        stopHooks.size(),
        "gating and publishing must live in ONE combined script: hooks in a matcher group run in"
            + " parallel, so a bare publisher beside the gate would announce a cancelled stop");
    assertEquals(SailStopGate.SCRIPT_PATH, stopHooks.get(0).get("command"));
    assertEquals(SailStopGate.HOOK_TIMEOUT_SECONDS, stopHooks.get(0).get("timeout"));
    assertFalse(
        json.contains(SailEventHelper.SCRIPT_PATH + " agent_session_stopped"),
        "the bare Stop publisher is replaced by the gate, which publishes the event itself");
  }

  @Test
  void renderDisablesCommitCoAuthorAttribution() {
    var json = ClaudeCodeHookConfig.render();
    assertTrue(
        json.contains("\"includeCoAuthoredBy\": false"),
        "dispatched agents must not sign commits as co-author");
  }

  @Test
  void renderEmbedsNoSpecId() {
    var json = ClaudeCodeHookConfig.render();
    var firstCmd = SailEventHelper.SCRIPT_PATH + " agent_session_started";
    assertTrue(
        json.contains(firstCmd),
        "command should be '<script> <event-type>' with no spec id baked in");
    assertFalse(
        json.contains(firstCmd + " "),
        "no trailing arg should follow the event type — spec id flows via SAIL_SPEC_ID env var");
  }

  @Test
  void renderEmbedsHelperScriptPath() {
    var json = ClaudeCodeHookConfig.render();
    assertTrue(json.contains(SailEventHelper.SCRIPT_PATH));
  }

  @Test
  void renderUsesStartupMatcherForSessionStart() {
    var json = ClaudeCodeHookConfig.render();
    assertTrue(json.contains("\"matcher\": \"startup\""));
  }

  @Test
  void renderProducesValidJson() {
    var json = ClaudeCodeHookConfig.render();
    assertDoesNotThrow(() -> YamlUtil.parseMap(json));
  }

  @Test
  @SuppressWarnings("unchecked")
  void renderShapeMatchesClaudeCodeHooksSchema() {
    var json = ClaudeCodeHookConfig.render();
    var root = YamlUtil.parseMap(json);
    var hooks = (Map<String, Object>) root.get("hooks");
    assertNotNull(hooks);
    assertTrue(hooks.containsKey("SessionStart"));
    assertTrue(hooks.containsKey("Stop"));
    assertTrue(hooks.containsKey("SessionEnd"));
  }

  @Test
  void constructorRejectsNullShell() {
    assertThrows(NullPointerException.class, () -> new ClaudeCodeHookConfig(null));
  }

  @Test
  void installWritesToSailOwnedSettingsPath() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var writer = new ClaudeCodeHookConfig(shell);

    writer.install("light-grid");

    var cmds = shell.invocations();
    assertEquals(
        2,
        cmds.size(),
        "one hooks layer only: lanes are expressed by SAIL_SPEC_ID/SAIL_RUN_ID at launch, never"
            + " by a second settings file");
    assertTrue(cmds.get(0).contains("mkdir -p /home/dev/.sail"));
    assertTrue(cmds.get(1).contains("/home/dev/.sail/claude-settings.json"));
    assertFalse(
        cmds.get(1).contains("settings.local.json"),
        "must not write to the project-scoped settings.local.json anymore");
  }

  @Test
  void installPropagatesMkdirFailure() {
    var shell = new ScriptedShellExecutor().onFail("mkdir", "denied");
    var writer = new ClaudeCodeHookConfig(shell);

    assertThrows(IOException.class, () -> writer.install("light-grid"));
  }

  @Test
  void installPropagatesWriteFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("mkdir -p /home/dev/.sail")
            .onFail("printf '%s'", "disk full");
    var writer = new ClaudeCodeHookConfig(shell);

    var ex = assertThrows(IOException.class, () -> writer.install("light-grid"));
    assertTrue(ex.getMessage().contains("disk full"));
  }

  @Test
  void installRejectsInvalidContainerName() {
    var writer = new ClaudeCodeHookConfig(new ScriptedShellExecutor());
    assertThrows(Exception.class, () -> writer.install("../bad"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void renderWiresTheRoomRelayBesideThePostToolUseHeartbeat() {
    var json = ClaudeCodeHookConfig.render();
    var hooks = (Map<String, Object>) YamlUtil.parseMap(json).get("hooks");
    var postGroups = (List<Map<String, Object>>) hooks.get("PostToolUse");
    assertEquals(1, postGroups.size(), "one matcher group: heartbeat and relay run in parallel");
    var postHooks = (List<Map<String, Object>>) postGroups.get(0).get("hooks");
    assertEquals(2, postHooks.size());
    assertEquals(
        SailEventHelper.SCRIPT_PATH + " agent_tool_finished", postHooks.get(0).get("command"));
    assertEquals(
        SailRoomRelay.SCRIPT_PATH,
        postHooks.get(1).get("command"),
        "the relay rides beside the heartbeat, which prints nothing — stdout stays the relay's");
    assertEquals(SailRoomRelay.HOOK_TIMEOUT_SECONDS, postHooks.get(1).get("timeout"));
  }
}
