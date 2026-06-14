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

class CodexHookConfigTest {

  @Test
  void settingsPathConstantsMatch() {
    assertEquals("/home/dev/.codex", CodexHookConfig.SETTINGS_DIR);
    assertEquals("hooks.json", CodexHookConfig.SETTINGS_FILE);
    assertEquals("/home/dev/.codex/hooks.json", CodexHookConfig.SETTINGS_PATH);
  }

  @Test
  void renderIncludesSessionStartAndStopOnly() {
    var json = CodexHookConfig.render();

    assertTrue(json.contains("SessionStart"));
    assertTrue(json.contains("Stop"));
    assertFalse(json.contains("SessionEnd"), "Codex has no SessionEnd analogue");
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
  void renderOmitsMatcherSinceCodexSessionEventsHaveNoTools() {
    assertFalse(
        CodexHookConfig.render().contains("\"matcher\""),
        "SessionStart / Stop in Codex don't take a tool matcher");
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
