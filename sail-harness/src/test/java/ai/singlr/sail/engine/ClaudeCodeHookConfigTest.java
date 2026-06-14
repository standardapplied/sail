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
    assertEquals(2, cmds.size());
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
}
