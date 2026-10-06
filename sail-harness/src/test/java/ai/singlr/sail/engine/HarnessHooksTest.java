/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HarnessHooksTest {

  private static final Harness CLAUDE_CODE = Harnesses.of("claude-code");
  private static final Harness CODEX = Harnesses.of("codex");

  @SuppressWarnings("unchecked")
  private static Map<String, Object> hooksOf(Harness harness) {
    return (Map<String, Object>) YamlUtil.parseMap(HarnessHooks.render(harness)).get("hooks");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> groups(Harness harness, String event) {
    return (List<Map<String, Object>>) hooksOf(harness).get(event);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> hooks(Map<String, Object> group) {
    return (List<Map<String, Object>>) group.get("hooks");
  }

  private static List<Object> commands(Map<String, Object> group) {
    return hooks(group).stream().map(hook -> hook.get("command")).toList();
  }

  @Test
  void claudeCodeRenderIncludesAllThreeHookKinds() {
    var json = HarnessHooks.render(CLAUDE_CODE);
    assertTrue(json.contains("SessionStart"));
    assertTrue(json.contains("Stop"));
    assertTrue(json.contains("SessionEnd"));
  }

  @Test
  void codexRenderIncludesLifecycleAndToolHooksButNoSessionEnd() {
    var json = HarnessHooks.render(CODEX);

    assertTrue(json.contains("SessionStart"));
    assertTrue(json.contains("PreToolUse"));
    assertTrue(json.contains("PostToolUse"));
    assertTrue(json.contains("Stop"));
    assertFalse(json.contains("SessionEnd"), "Codex has no SessionEnd analogue");
  }

  @Test
  void renderWiresToolHooksSoTheStallWatcherSeesProgress() {
    for (var harness : Harnesses.all()) {
      var json = HarnessHooks.render(harness);
      assertTrue(
          hooksOf(harness).containsKey("PreToolUse"),
          "PreToolUse must fire a progress heartbeat, or the stall guardrail counts a busy agent"
              + " as idle and kills it at max_idle");
      assertTrue(
          hooksOf(harness).containsKey("PostToolUse"),
          "PostToolUse must fire a progress heartbeat");
      assertTrue(
          json.contains(SailEventHelper.SCRIPT_PATH + " agent_tool_started"),
          "PreToolUse must emit agent_tool_started (the event AgentWatchCommand resets the stall"
              + " on)");
      assertTrue(
          json.contains(SailEventHelper.SCRIPT_PATH + " agent_tool_finished"),
          "PostToolUse must emit agent_tool_finished");
    }
  }

  @Test
  void aToolCallThatFailedIsToldFinishedAsOneThatSucceededIs() {
    var finished = SailEventHelper.SCRIPT_PATH + " agent_tool_finished";

    for (var ending : List.of("PostToolUse", "PostToolUseFailure")) {
      var groups = groups(CLAUDE_CODE, ending);
      assertNotNull(
          groups,
          ending
              + " must be wired: Claude Code fires PostToolUse only for a call that succeeded, so a"
              + " failed call with no hook of its own is counted in flight for the rest of the run");
      assertEquals(1, groups.size(), ending);
      assertNull(groups.getFirst().get("matcher"), ending + " must match every tool");
      assertTrue(commands(groups.getFirst()).contains(finished), ending);
    }
    assertEquals(
        List.of(SailEventHelper.SCRIPT_PATH + " agent_tool_started"),
        commands(groups(CLAUDE_CODE, "PreToolUse").getFirst()));
  }

  @Test
  void aBatchThatResolvedIsToldThroughItsOwnHook() {
    var groups = groups(CLAUDE_CODE, "PostToolBatch");

    assertNotNull(
        groups,
        "a call Claude Code denies has no finish of its own: only its batch resolving says it"
            + " is over, and without that the call is counted in flight for the rest of the run");
    assertEquals(1, groups.size());
    assertNull(groups.getFirst().get("matcher"), "every batch, whatever tools were in it");
    assertEquals(
        List.of(SailEventHelper.SCRIPT_PATH + " " + SailEventHelper.BATCH_RESOLVED),
        commands(groups.getFirst()));
  }

  @Test
  void renderWiresTheStopGateAsTheOnlyStopHook() {
    for (var harness : Harnesses.all()) {
      var stopGroups = groups(harness, "Stop");
      assertEquals(1, stopGroups.size());
      var stopHooks = hooks(stopGroups.getFirst());
      assertEquals(
          1,
          stopHooks.size(),
          "gating and publishing must live in ONE combined script: hooks in a matcher group run in"
              + " parallel, so a bare publisher beside the gate would announce a cancelled stop");
      assertEquals(SailStopGate.SCRIPT_PATH, stopHooks.getFirst().get("command"));
      assertEquals(SailStopGate.HOOK_TIMEOUT_SECONDS, stopHooks.getFirst().get("timeout"));
      assertFalse(
          HarnessHooks.render(harness)
              .contains(SailEventHelper.SCRIPT_PATH + " agent_session_stopped"),
          "the bare Stop publisher is replaced by the gate, which publishes the event itself");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void claudeCodeDeniesReadsOfTheContainerCredentialsAndKeys() {
    var permissions =
        (Map<String, Object>)
            YamlUtil.parseMap(HarnessHooks.render(CLAUDE_CODE)).get("permissions");
    var deny = (List<String>) permissions.get("deny");
    assertEquals(
        List.of(
            "Read(//var/lib/sail/run/box.credential)",
            "Read(/home/dev/.ssh/**)",
            "Read(/home/dev/.git-credentials)"),
        deny,
        "the room lane's reads are cwd-scoped (Claude auto-approves cat/head/tail/grep only inside"
            + " the workspace, refusing out-of-tree paths), so the container's secrets are unreadable"
            + " by default; these Read-denies belt-and-suspenders the top credentials — the box FDE"
            + " credential, the box SSH identity, the git credential — explicitly on top. Deny"
            + " outranks allow and --setting-sources is pinned, so no ambient file can out-vote"
            + " these; a full (YOLO) agent skips them by design. Hermetic isolation stays with the"
            + " sidecar follow-up.");
  }

  @Test
  void claudeCodeDisablesCommitCoAuthorAttribution() {
    assertTrue(
        HarnessHooks.render(CLAUDE_CODE).contains("\"includeCoAuthoredBy\": false"),
        "dispatched agents must not sign commits as co-author");
  }

  @Test
  void renderEmbedsNoSpecId() {
    for (var harness : Harnesses.all()) {
      var json = HarnessHooks.render(harness);
      var firstCmd = SailEventHelper.SCRIPT_PATH + " agent_session_started";
      assertTrue(
          json.contains(firstCmd),
          "command should be '<script> <event-type>' with no spec id baked in");
      assertFalse(
          json.contains(firstCmd + " "),
          "no trailing arg should follow the event type — spec id flows via SAIL_SPEC_ID env var");
    }
  }

  @Test
  void claudeCodeUsesStartupMatcherForSessionStart() {
    assertTrue(HarnessHooks.render(CLAUDE_CODE).contains("\"matcher\": \"startup\""));
  }

  @Test
  void codexOmitsMatchersSoToolHooksMatchEveryTool() {
    assertFalse(
        HarnessHooks.render(CODEX).contains("\"matcher\""),
        "no matcher means match-all: the heartbeats must fire for every tool, and SessionStart /"
            + " Stop take no matcher at all");
  }

  @Test
  void renderProducesValidJsonWithAHooksMap() {
    for (var harness : Harnesses.all()) {
      assertDoesNotThrow(() -> YamlUtil.parseMap(HarnessHooks.render(harness)));
      var hooks = hooksOf(harness);
      assertNotNull(hooks);
      assertTrue(hooks.containsKey("SessionStart"));
      assertTrue(hooks.containsKey("Stop"));
    }
  }

  @Test
  void constructorRejectsNullShell() {
    assertThrows(NullPointerException.class, () -> new HarnessHooks(null));
  }

  @Test
  void installWritesClaudeCodeToTheSailOwnedSettingsPath() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    new HarnessHooks(shell).install("light-grid", CLAUDE_CODE);

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
  void installWritesCodexToItsConfigDir() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    new HarnessHooks(shell).install("light-grid", CODEX);

    var cmds = shell.invocations();
    assertEquals(2, cmds.size());
    assertTrue(cmds.get(0).contains("mkdir -p /home/dev/.codex"));
    assertTrue(cmds.get(1).contains("/home/dev/.codex/hooks.json"));
  }

  @Test
  void installPropagatesMkdirFailure() {
    var shell = new ScriptedShellExecutor().onFail("mkdir", "denied");

    assertThrows(IOException.class, () -> new HarnessHooks(shell).install("light-grid", CODEX));
  }

  @Test
  void installPropagatesWriteFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("mkdir -p /home/dev/.sail")
            .onFail("printf '%s'", "disk full");

    var ex =
        assertThrows(
            IOException.class, () -> new HarnessHooks(shell).install("light-grid", CLAUDE_CODE));
    assertTrue(ex.getMessage().contains("disk full"));
  }

  @Test
  void installRejectsInvalidContainerName() {
    var writer = new HarnessHooks(new ScriptedShellExecutor());
    assertThrows(Exception.class, () -> writer.install("../bad", CLAUDE_CODE));
  }

  @Test
  void claudeCodeWiresTheSessionReportOnEverySessionStartSource() {
    var startGroups = groups(CLAUDE_CODE, "SessionStart");
    assertEquals(2, startGroups.size(), "the startup-matched event group plus the report group");
    assertEquals("startup", startGroups.get(0).get("matcher"));
    var reportGroup = startGroups.get(1);
    assertFalse(
        reportGroup.containsKey("matcher"),
        "the session report must fire on every start source — a resume, clear, or compact restart"
            + " mints a new conversation that must overwrite the row (last write wins)");
    var reportHooks = hooks(reportGroup);
    assertEquals(1, reportHooks.size());
    assertEquals(
        SailSessionReport.SCRIPT_PATH + " claude-code",
        reportHooks.getFirst().get("command"),
        "the report is told which CLI it speaks for — an interactive session has no SAIL_AGENT");
    assertEquals(SailSessionReport.HOOK_TIMEOUT_SECONDS, reportHooks.getFirst().get("timeout"));
  }

  @Test
  void codexWiresTheSessionReportBesideTheSessionStartEvent() {
    var startGroups = groups(CODEX, "SessionStart");
    assertEquals(1, startGroups.size());
    var startHooks = hooks(startGroups.getFirst());
    assertEquals(2, startHooks.size(), "the event and the session report run in parallel");
    assertEquals(
        SailEventHelper.SCRIPT_PATH + " agent_session_started", startHooks.get(0).get("command"));
    assertEquals(
        SailSessionReport.SCRIPT_PATH + " codex",
        startHooks.get(1).get("command"),
        "codex's matcher-less group fires on every start source, so one group serves both hooks");
    assertEquals(SailSessionReport.HOOK_TIMEOUT_SECONDS, startHooks.get(1).get("timeout"));
  }

  @Test
  void renderWiresTheRoomRelayBesideThePostToolUseHeartbeat() {
    for (var harness : Harnesses.all()) {
      var postGroups = groups(harness, "PostToolUse");
      assertEquals(1, postGroups.size(), "one matcher group: heartbeat and relay run in parallel");
      var postHooks = hooks(postGroups.getFirst());
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
}
