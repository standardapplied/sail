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

class CodexHookConfigTest {

  @Test
  void settingsPathConstantsMatch() {
    assertEquals("/home/dev/.codex", CodexHookConfig.SETTINGS_DIR);
    assertEquals("hooks.json", CodexHookConfig.SETTINGS_FILE);
    assertEquals("/home/dev/.codex/hooks.json", CodexHookConfig.SETTINGS_PATH);
  }

  @Test
  void renderIncludesLifecycleAndToolHooksButNoSessionEnd() {
    var json = CodexHookConfig.render();

    assertTrue(json.contains("SessionStart"));
    assertTrue(json.contains("PreToolUse"));
    assertTrue(json.contains("PostToolUse"));
    assertTrue(json.contains("Stop"));
    assertFalse(json.contains("SessionEnd"), "Codex has no SessionEnd analogue");
  }

  @Test
  void renderWiresToolHooksSoTheStallWatcherSeesProgress() {
    var json = CodexHookConfig.render();
    assertTrue(
        json.contains(SailEventHelper.SCRIPT_PATH + " agent_tool_started"),
        "PreToolUse must emit agent_tool_started (the event AgentWatchCommand resets the stall"
            + " on), or the watcher counts a busy Codex agent as idle and kills it at max_idle");
    assertTrue(
        json.contains(SailEventHelper.SCRIPT_PATH + " agent_tool_finished"),
        "PostToolUse must emit agent_tool_finished");
  }

  @Test
  @SuppressWarnings("unchecked")
  void renderWiresTheStopGateAsTheOnlyStopHook() {
    var json = CodexHookConfig.render();
    var hooks = (Map<String, Object>) YamlUtil.parseMap(json).get("hooks");
    var stopGroups = (List<Map<String, Object>>) hooks.get("Stop");
    assertEquals(1, stopGroups.size());
    var stopHooks = (List<Map<String, Object>>) stopGroups.get(0).get("hooks");
    assertEquals(
        1,
        stopHooks.size(),
        "gating and publishing must live in ONE combined script: matching hooks run concurrently,"
            + " so a bare publisher beside the gate would announce a cancelled stop");
    assertEquals(SailStopGate.SCRIPT_PATH, stopHooks.get(0).get("command"));
    assertEquals(SailStopGate.HOOK_TIMEOUT_SECONDS, stopHooks.get(0).get("timeout"));
    assertFalse(
        json.contains(SailEventHelper.SCRIPT_PATH + " agent_session_stopped"),
        "the bare Stop publisher is replaced by the gate, which publishes the event itself");
  }

  @Test
  void renderEmbedsNoSpecId() {
    var json = CodexHookConfig.render();
    var startCmd = SailEventHelper.SCRIPT_PATH + " agent_session_started";
    assertTrue(
        json.contains(startCmd),
        "command should be '<script> <event-type>' with no spec id baked in");
    assertFalse(
        json.contains(startCmd + " "),
        "no trailing arg should follow event type — spec id flows via SAIL_SPEC_ID env var");
  }

  @Test
  void renderEmbedsHelperScriptPath() {
    var json = CodexHookConfig.render();
    assertTrue(json.contains(SailEventHelper.SCRIPT_PATH));
  }

  @Test
  void renderProducesValidJson() {
    var json = CodexHookConfig.render();
    assertDoesNotThrow(() -> YamlUtil.parseMap(json));
  }

  @Test
  @SuppressWarnings("unchecked")
  void renderShapeMatchesCodexHooksSchema() {
    var json = CodexHookConfig.render();
    var root = YamlUtil.parseMap(json);
    var hooks = (Map<String, Object>) root.get("hooks");
    assertNotNull(hooks);
    assertTrue(hooks.containsKey("SessionStart"));
    assertTrue(hooks.containsKey("Stop"));
  }

  @Test
  void renderOmitsMatchersSoToolHooksMatchEveryTool() {
    assertFalse(
        CodexHookConfig.render().contains("\"matcher\""),
        "no matcher means match-all: the heartbeats must fire for every tool, and SessionStart /"
            + " Stop take no matcher at all");
  }

  @Test
  void constructorRejectsNullShell() {
    assertThrows(NullPointerException.class, () -> new CodexHookConfig(null));
  }

  @Test
  void installWritesToCodexConfigDir() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var writer = new CodexHookConfig(shell);

    writer.install("light-grid");

    var cmds = shell.invocations();
    assertEquals(2, cmds.size());
    assertTrue(cmds.get(0).contains("mkdir -p /home/dev/.codex"));
    assertTrue(cmds.get(1).contains("/home/dev/.codex/hooks.json"));
  }

  @Test
  void installPropagatesMkdirFailure() {
    var shell = new ScriptedShellExecutor().onFail("mkdir", "denied");
    var writer = new CodexHookConfig(shell);

    assertThrows(IOException.class, () -> writer.install("light-grid"));
  }

  @Test
  void installPropagatesWriteFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("mkdir -p /home/dev/.codex")
            .onFail("printf '%s'", "disk full");
    var writer = new CodexHookConfig(shell);

    var ex = assertThrows(IOException.class, () -> writer.install("light-grid"));
    assertTrue(ex.getMessage().contains("disk full"));
  }

  @Test
  void installRejectsInvalidContainerName() {
    var writer = new CodexHookConfig(new ScriptedShellExecutor());
    assertThrows(Exception.class, () -> writer.install("../bad"));
  }
}
