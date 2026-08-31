/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyRooms;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class AgentAttachCommandTest {

  @Test
  void anUnreadableRunStateRefusesTheAttachInsteadOfForkingFresh() {
    var thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                AgentAttachCommand.orRefuse(
                    "acme",
                    () -> {
                      throw new IllegalStateException("database disk image is malformed");
                    }));
    assertTrue(
        thrown.getMessage().contains("refusing to attach"),
        "unknown is never absent: an unreadable database must refuse, because treating it as"
            + " 'no run' bypasses the live-run refusal and forks a second agent");
    assertTrue(thrown.getCause().getMessage().contains("malformed"), "the cause travels");
  }

  @Test
  void aSuccessfulEmptyQueryAllowsTheFreshFallback() {
    assertTrue(
        AgentAttachCommand.orRefuse("acme", java.util.Optional::empty).isEmpty(),
        "only a successful empty query means no run — the fresh-attach lane stays open");
  }

  @Test
  void aRecordedSessionResumesExactlyByIdForClaudeCode() {
    var cmd = AgentAttachCommand.buildResumeCommand(AgentCli.CLAUDE_CODE, "abc-123");
    assertEquals(List.of("bash", "-lc", "cd ~/workspace && claude --resume abc-123"), cmd);
  }

  @Test
  void aRecordedSessionResumesExactlyByIdForCodex() {
    var cmd = AgentAttachCommand.buildResumeCommand(AgentCli.CODEX, "abc-123");
    assertEquals(
        List.of("bash", "-lc", "cd ~/workspace && codex resume abc-123"),
        cmd,
        "codex resumes by id via 'codex resume <SESSION_ID>' — verified against current docs");
  }

  @Test
  void aNullSessionAttachesFreshNeverAnInteractivePicker() {
    assertEquals(
        List.of("bash", "-lc", "cd ~/workspace && claude"),
        AgentAttachCommand.buildResumeCommand(AgentCli.CLAUDE_CODE, null),
        "no recorded session means a fresh conversation, not '--resume' picker roulette");
    assertEquals(
        List.of("bash", "-lc", "cd ~/workspace && codex"),
        AgentAttachCommand.buildResumeCommand(AgentCli.CODEX, null));
  }

  @Test
  void ordinarySessionIdShapesAreSafe() {
    assertTrue(AgentAttachCommand.isSafeSessionId("0198f00d-1234-7000-8000-abcdefabcdef"));
    assertTrue(AgentAttachCommand.isSafeSessionId("abc-123"));
    assertTrue(AgentAttachCommand.isSafeSessionId("a"));
    assertTrue(AgentAttachCommand.isSafeSessionId("9session.name_x"));
  }

  @Test
  void sessionIdStartingWithDashIsRejectedAsOptionInjection() {
    assertFalse(
        AgentAttachCommand.isSafeSessionId("--dangerously-bypass-approvals-and-sandbox"),
        "a leading '-' would be parsed by the agent CLI as an option, not a session id");
    assertFalse(AgentAttachCommand.isSafeSessionId("-r"));
    assertFalse(AgentAttachCommand.isSafeSessionId(".hidden"));
    assertFalse(AgentAttachCommand.isSafeSessionId("_x"));
  }

  @Test
  void sessionIdWithShellMetacharactersOrOversizeIsRejected() {
    assertFalse(AgentAttachCommand.isSafeSessionId("abc; rm -rf /"));
    assertFalse(AgentAttachCommand.isSafeSessionId("abc$(id)"));
    assertFalse(AgentAttachCommand.isSafeSessionId(""));
    assertFalse(AgentAttachCommand.isSafeSessionId("a".repeat(129)));
    assertTrue(AgentAttachCommand.isSafeSessionId("a".repeat(128)));
  }

  @Test
  void buildIncusExecWithTtyIncludesTtyFlag() {
    var cmd =
        AgentAttachCommand.buildIncusExecWithTty(
            "myproject", List.of("bash", "-lc", "claude --resume abc"));
    assertTrue(cmd.contains("-t"));
    assertTrue(cmd.contains("myproject"));
    assertTrue(cmd.contains("--user"));
    assertTrue(cmd.contains("1000"));
    assertEquals("claude --resume abc", cmd.getLast());
  }

  @Test
  void buildIncusExecSetsHomeEnv() {
    var cmd = AgentAttachCommand.buildIncusExecWithTty("proj", List.of("echo", "test"));
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("HOME=/home/dev"));
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void openOrJoinOpensOnceThenJoinsTheLiveSessionInsteadOfForking(@TempDir Path dir)
      throws Exception {
    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            token -> new PtyIdentity("uday", true),
            PtyRooms.NONE,
            PtyEvents.NONE)) {
      var plan =
          new AgentAttachCommand.ResumePlan("resume-1", List.of("sh", "-c", "read a"), "", "");
      try (var client = SessionClient.connect(dir.resolve("h.sock"), "")) {
        assertTrue(AgentAttachCommand.openOrJoin(client, plan, 80, 24), "the first attach opens");
        assertFalse(
            AgentAttachCommand.openOrJoin(client, plan, 80, 24),
            "a second attach joins the live session, never a second agent");
        assertEquals(1, host.sessionCount());

        var unbound =
            new AgentAttachCommand.ResumePlan("resume-2", List.of("sh"), "", "no-such-room");
        assertThrows(
            IOException.class,
            () -> AgentAttachCommand.openOrJoin(client, unbound, 80, 24),
            "a refusal with nothing live behind it is the error it was");
      }
    }
  }

  @Test
  void theDryRunPlanIsTheSessionVerbsTheAttachIsMadeOf() {
    var plan =
        new AgentAttachCommand.ResumePlan(
            "resume-1",
            AgentAttachCommand.buildResumeCommand(AgentCli.CLAUDE_CODE, "abc"),
            "acme",
            "spec-x");
    assertEquals(
        "sail session new resume-1 --project acme --room spec-x --command bash -lc"
            + " 'cd ~/workspace && claude --resume abc'\nsail session attach resume-1",
        plan.asSessionCommands());
    var unbound = new AgentAttachCommand.ResumePlan("resume-1", List.of("codex"), "acme", "");
    assertFalse(unbound.asSessionCommands().contains("--room"));
  }

  @Test
  void knownRoomIsTheRunsConversationOnlyWhenThatRoomExistsHere(@TempDir Path dir) {
    try (var db = Sqlite.open(dir.resolve("t.db"))) {
      new SchemaManager(db).migrate();
      var rooms = new RoomStore(db);
      rooms.create(
          new RoomStore.RoomRow("spec-x", "acme", "X", "it", "on", null, "it", null, null, "it"));
      var runs = new RunStore(db);
      runs.create(
          "r1", "acme", "spec-x", "it", "it", "build", "codex", null, "t", null, null, "l", "u");
      runs.create(
          "r2", "acme", "legacy", "it", "it", "build", "codex", null, "t", null, null, "l", "u");
      runs.create(
          "r3", "acme", null, "it", "it", "adhoc", "codex", null, "t", null, null, "l", "u");
      assertEquals(
          "spec-x", AgentAttachCommand.knownRoom(rooms, runs.findById("r1").orElseThrow()));
      assertEquals(
          "",
          AgentAttachCommand.knownRoom(rooms, runs.findById("r2").orElseThrow()),
          "a spec without a room here opens unbound rather than unreachable");
      assertEquals("", AgentAttachCommand.knownRoom(rooms, runs.findById("r3").orElseThrow()));
    }
  }
}
