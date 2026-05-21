/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.*;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClaudeCodeHookConfigTest {

  @Test
  void renderRejectsBlankSpecId() {
    assertThrows(IllegalArgumentException.class, () -> ClaudeCodeHookConfig.render(""));
    assertThrows(IllegalArgumentException.class, () -> ClaudeCodeHookConfig.render(null));
  }

  @Test
  void renderIncludesAllThreeHookKinds() {
    var json = ClaudeCodeHookConfig.render("oauth-flow");
    assertTrue(json.contains("SessionStart"));
    assertTrue(json.contains("Stop"));
    assertTrue(json.contains("SessionEnd"));
  }

  @Test
  void renderEmbedsSpecIdInEachCommand() {
    var json = ClaudeCodeHookConfig.render("oauth-flow");
    var occurrences = countOccurrences(json, "oauth-flow");
    assertEquals(3, occurrences, "spec id should appear in each of three hook commands");
  }

  @Test
  void renderEmbedsHelperScriptPath() {
    var json = ClaudeCodeHookConfig.render("oauth-flow");
    assertTrue(json.contains(SailEventHelper.SCRIPT_PATH));
  }

  @Test
  void renderUsesStartupMatcherForSessionStart() {
    var json = ClaudeCodeHookConfig.render("oauth-flow");
    assertTrue(json.contains("\"matcher\": \"startup\""));
  }

  @Test
  void renderProducesValidJson() {
    var json = ClaudeCodeHookConfig.render("oauth-flow");
    assertDoesNotThrow(() -> YamlUtil.parseMap(json));
  }

  @Test
  @SuppressWarnings("unchecked")
  void renderShapeMatchesClaudeCodeHooksSchema() {
    var json = ClaudeCodeHookConfig.render("oauth-flow");
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
  void installInvokesIncusExec() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var writer = new ClaudeCodeHookConfig(shell);

    writer.install("light-grid", "/home/dev/workspace/sing", "oauth-flow");

    var cmds = shell.invocations();
    assertEquals(2, cmds.size());
    assertTrue(cmds.get(0).contains("mkdir -p /home/dev/workspace/sing/.claude"));
    assertTrue(cmds.get(1).contains("/home/dev/workspace/sing/.claude/settings.local.json"));
  }

  @Test
  void installPropagatesMkdirFailure() {
    var shell = new ScriptedShellExecutor().onFail("mkdir", "denied");
    var writer = new ClaudeCodeHookConfig(shell);

    assertThrows(
        IOException.class, () -> writer.install("light-grid", "/home/dev/workspace", "spec-a"));
  }

  @Test
  void installRejectsBlankWorkDir() {
    var writer = new ClaudeCodeHookConfig(new ScriptedShellExecutor());
    assertThrows(IllegalArgumentException.class, () -> writer.install("p", "", "spec-a"));
  }

  @Test
  void installRejectsNullWorkDir() {
    var writer = new ClaudeCodeHookConfig(new ScriptedShellExecutor());
    assertThrows(NullPointerException.class, () -> writer.install("p", null, "spec-a"));
  }

  @Test
  void installRejectsInvalidContainerName() {
    var writer = new ClaudeCodeHookConfig(new ScriptedShellExecutor());
    assertThrows(Exception.class, () -> writer.install("../bad", "/home/dev", "spec-a"));
  }

  private static int countOccurrences(String haystack, String needle) {
    var n = 0;
    var idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) >= 0) {
      n++;
      idx += needle.length();
    }
    return n;
  }
}
