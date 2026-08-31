/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.commands.AgentAttachCommand;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.SailSessionReport;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole session-capture loop against the production local API: the real report script posts a
 * SessionStart payload over the real Unix socket, the run row records the conversation identity, a
 * compact restart overwrites it (last write wins), and a finished run's revoked credential can no
 * longer write — all in the fix lane's environment, which exports the run identity but never {@code
 * SAIL_SPEC_ID}. The recorded identity then renders the exact resume argv attach uses.
 */
class SessionReportDeliveryIT {

  @TempDir Path root;
  private Sqlite db;
  private EventBus bus;
  private RunStore runStore;
  private LocalApiSocket listener;
  private Path home;
  private String runId;
  private String credential;

  @BeforeEach
  void bootFakeRunAgainstTheProductionApi() throws Exception {
    home = root.resolve("home");
    Files.createDirectories(home);
    db = Sqlite.open(root.resolve("session.db"));
    new SchemaManager(db).migrate();
    db.execute(
        """
        INSERT INTO specs
            (id, title, project, assignee, created_by, created_at, updated_at)
        VALUES ('room', 'Room', 'acme', 'ada', 'ada', 'now', 'now')""");
    bus = new EventBus();
    runStore = new RunStore(db);
    var operations =
        new SailOperations(
                new ShellExecutor(false),
                "sail.yaml",
                bus,
                null,
                new SpecStore(db),
                new ReviewStore(db),
                runStore,
                new ProjectStore(db),
                SyncScheduler.disabled(),
                null,
                SessionYield.NONE)
            .useMessages(new MessageStore(db));
    runId = DateTimeUtils.newId().toString();
    var reservation =
        (RunStore.Reservation.Reserved)
            runStore.reserveDispatch(
                runId,
                "acme",
                "room",
                "node-a",
                "ada",
                "build",
                List.of(),
                "claude-code",
                "b",
                "t",
                "l",
                "u");
    credential = reservation.credential();
    listener = new LocalApiSocket(bus, operations, root.resolve("api.sock"));
    listener.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (listener != null) {
      listener.close();
    }
    bus.close();
    db.close();
  }

  @Test
  void aDispatchedRunRecordsItsSessionAndAttachRendersTheExactResumeArgv() throws Exception {
    var report =
        runScript(
            """
            {"session_id": "0199aaaa-1111-7000-8000-000000000001", "source": "startup",
             "transcript_path": "/home/dev/.claude/projects/w/s.jsonl",
             "hook_event_name": "SessionStart"}""");
    assertEquals(0, report.exitCode());
    assertEquals("", report.stdout(), "SessionStart stdout joins the agent context: stay mute");

    var run = runStore.findById(runId).orElseThrow();
    assertEquals("0199aaaa-1111-7000-8000-000000000001", run.sessionId());
    assertEquals("startup", run.sessionSource());
    assertEquals("/home/dev/.claude/projects/w/s.jsonl", run.transcriptPath());

    assertEquals(
        List.of(
            "bash",
            "-lc",
            "cd ~/workspace && claude --resume 0199aaaa-1111-7000-8000-000000000001"),
        AgentAttachCommand.buildResumeCommand(AgentCli.fromYamlName(run.agent()), run.sessionId()));
  }

  @Test
  void aCompactRestartOverwritesTheRecordedConversation() throws Exception {
    runScript("{\"session_id\": \"first-111\", \"source\": \"startup\"}");
    var second = runScript("{\"session_id\": \"second-222\", \"source\": \"compact\"}");
    assertEquals(0, second.exitCode());

    var run = runStore.findById(runId).orElseThrow();
    assertEquals("second-222", run.sessionId(), "last write wins: the newest conversation");
    assertEquals("compact", run.sessionSource());
  }

  @Test
  void aFinishedRunsRevokedCredentialCannotRewriteItsSession() throws Exception {
    runScript("{\"session_id\": \"live-111\", \"source\": \"startup\"}");
    runStore.complete(runId, "completed", 0);

    var late = runScript("{\"session_id\": \"stale-999\", \"source\": \"resume\"}");

    assertEquals(0, late.exitCode(), "the report swallows the 401 — never blocks an agent");
    assertEquals(
        "live-111",
        runStore.findById(runId).orElseThrow().sessionId(),
        "credential revocation at completion is the write gate: the recorded session survives");
  }

  @Test
  void aDeadSocketLeavesTheReportSilent() throws Exception {
    listener.close();
    Files.deleteIfExists(root.resolve("api.sock"));

    var report = runScript("{\"session_id\": \"abc\", \"source\": \"startup\"}");

    assertEquals(0, report.exitCode(), "a down API must never block a session start");
    assertEquals("", report.stdout());
    assertNull(runStore.findById(runId).orElseThrow().sessionId());
  }

  private record ScriptResult(int exitCode, String stdout, String stderr) {}

  private ScriptResult runScript(String stdin) throws Exception {
    var script = writeScript();
    var pb = new ProcessBuilder("/bin/sh", script.toString());
    var env = pb.environment();
    env.put("HOME", home.toString());
    env.remove("SAIL_SPEC_ID");
    env.put("SAIL_RUN_ID", runId);
    env.put("SAIL_RUN_CREDENTIAL", credential);
    var process = pb.start();
    try (var in = process.getOutputStream()) {
      in.write(stdin.getBytes(StandardCharsets.UTF_8));
    }
    var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the report must finish inside its timeout");
    return new ScriptResult(process.exitValue(), stdout, stderr);
  }

  private Path writeScript() throws IOException {
    var path = home.resolve("sail-session-report");
    Files.writeString(
        path,
        SailSessionReport.scriptContent()
            .replace(
                SailPaths.apiSocketContainerPath().toString(),
                root.resolve("api.sock").toString()));
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
    return path;
  }
}
