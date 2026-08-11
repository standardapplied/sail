/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wake launch lane: {@code startRoomRun} mints a {@code room}-role run through the same
 * reservation transaction as dispatch, seeds the delivery ledger with exactly the rendered room
 * messages, resumes a recorded conversation when one exists, and {@code guardRoomRun} turns a moved
 * HEAD into a loud guardrail event instead of a review.
 */
class RoomWakeLaunchTest {

  private static final String HANDLE = "uday";

  private static final String RUNNING_JSON =
      """
      [{"name": "acme", "status": "Running", "state": {}}]
      """;

  private static final String YAML =
      """
      name: acme
      ssh:
        user: dev
      repos:
        - url: https://github.com/acme/app.git
          path: app
      agent:
        type: claude-code
      """;

  @TempDir Path tempDir;

  private SpecStore specStore;
  private RunStore runStore;
  private MessageStore messageStore;
  private Sqlite db;
  private final List<Event> events = new ArrayList<>();
  private final AtomicReference<List<String>> launched = new AtomicReference<>();

  @AfterEach
  void tearDown() {
    if (db != null) {
      db.close();
    }
  }

  private DispatchOperations operations(ShellExec shell) throws IOException {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(yaml, YAML);
    db = Sqlite.open(tempDir.resolve("wake-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    runStore = new RunStore(db);
    messageStore = new MessageStore(db);
    new FdeStore(db).add(HANDLE, null, null, "admin");
    return new DispatchOperations(
            shell,
            yaml.toString(),
            specStore,
            new ReviewStore(db),
            runStore,
            new FdeStore(db),
            events::add,
            new WatcherSpawner(shell, (command, logPath) -> 4242L),
            (project, config) -> "",
            command -> {
              launched.set(command);
              return 0;
            },
            DispatchOperations.Listener.NONE)
        .useMessages(messageStore);
  }

  private static StubShell liveAgentShell() {
    return new StubShell()
        .on("incus list ^acme$", RUNNING_JSON)
        .on("mkdir -p /home/dev/.sail", "")
        .on("rev-parse HEAD", "aaa111\n")
        .on("diff --binary HEAD", "")
        .on("printf '%s'", "")
        .on("agent.pid", "123")
        .on("kill -0 123", "")
        .on("agent-session.json", "{\"task\": \"work\"}");
  }

  private void seedSpec(String id) {
    seedSpec(id, null);
  }

  private void seedSpec(String id, String agent) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "acme",
            "OAuth flow",
            SpecStatus.DONE,
            HANDLE,
            agent,
            null,
            null,
            null,
            0,
            HANDLE,
            "",
            "",
            null,
            List.of(),
            List.of("app")));
    specStore.setContent(id, "Build the OAuth flow.", "");
  }

  @Test
  void aWakeMintsARoomRunAndSeedsTheLedgerWithTheRenderedMessages() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");
    var question = messageStore.append("auth", "uday", "what did you ship?", null);
    var verdict = messageStore.append("auth", "sail", "Review passed.", null);

    var runId = ops.startRoomRun("acme", "auth", HANDLE);

    var run = runStore.findById(runId).orElseThrow();
    assertEquals("room", run.role());
    assertEquals("auth", run.specId());
    assertEquals(HANDLE, run.node());
    assertEquals(HANDLE, run.owner());
    assertEquals("claude/room-" + runId, run.principal());
    assertEquals(List.of(), run.repos());
    assertEquals("running", run.status());
    assertEquals("sail-agent-" + runId, run.unit());
    assertEquals(123, run.pid());
    assertEquals(
        java.util.Set.of(question.id(), verdict.id()),
        runStore.deliveredMessageIds(runId),
        "the prompt is the run's first delivery, seeded by identity");
    assertTrue(run.task().contains("Room Duty"));
    assertTrue(run.task().contains("what did you ship?"));
    assertTrue(run.task().contains("Build the OAuth flow."), "a fresh session is primed with body");
    assertNotNull(launched.get());
    var joined = String.join(" ", launched.get());
    assertFalse(joined.contains("--resume"), "no recorded session: a fresh conversation");
    assertEquals("room", launched.get().getLast(), "SAIL_RUN_ROLE rides the launch");
    assertFalse(
        joined.contains("--dangerously-skip-permissions"),
        "the chat lane never launches full-permission");
    assertTrue(joined.contains("--tools \"Bash,Read,Grep,Glob\""), joined);
    assertTrue(
        runStore.consumeRoomGuardBaseline(runId).orElseThrow().contains("aaa111"),
        "the guard baseline is recorded host-side before the chat launches");
    var started =
        events.stream()
            .filter(e -> Event.WellKnownTypes.AGENT_SESSION_STARTED.equals(e.type()))
            .findFirst()
            .orElseThrow();
    assertEquals("auth", started.spec());
    assertEquals(runId, started.data().get(Event.WellKnownData.RUN_ID));
  }

  @Test
  void aLiveRunOfTheSameSpecRefusesTheWake() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");
    var live = DateTimeUtils.newId().toString();
    runStore.create(
        live,
        "acme",
        "auth",
        HANDLE,
        HANDLE,
        "build",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + live);

    var refusal = assertThrows(ApiException.class, () -> ops.startRoomRun("acme", "auth", HANDLE));

    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, refusal.failure().errorCode());
  }

  @Test
  void aDisjointSpecsLiveBuildNeverBlocksTheWake() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");
    var live = DateTimeUtils.newId().toString();
    db.execute(
        "INSERT INTO runs (id, project, spec_id, node, role, agent, status, started_at, repos)"
            + " VALUES (?, 'acme', 'other', ?, 'build', 'claude-code', 'running', 't0',"
            + " '[\"app\"]')",
        live,
        HANDLE);

    var runId = ops.startRoomRun("acme", "auth", HANDLE);

    assertEquals("room", runStore.findById(runId).orElseThrow().role());
  }

  @Test
  void aRecordedSessionOfTheSameAgentIsResumed() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");
    var prior = DateTimeUtils.newId().toString();
    runStore.create(
        prior,
        "acme",
        "auth",
        HANDLE,
        HANDLE,
        "build",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + prior);
    runStore.recordSession(prior, "sess-42", "startup", "/tmp/transcript.jsonl");
    runStore.complete(prior, "completed", 0);

    var runId = ops.startRoomRun("acme", "auth", HANDLE);

    var joined = String.join(" ", launched.get());
    assertTrue(joined.contains("--resume sess-42"), joined);
    assertFalse(
        runStore.findById(runId).orElseThrow().task().contains("Build the OAuth flow."),
        "a resumed conversation already carries the spec context");
  }

  @Test
  void aMalformedOrForeignAgentSessionIdFallsBackToAFreshConversation() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");
    var codex = DateTimeUtils.newId().toString();
    runStore.create(
        codex,
        "acme",
        "auth",
        HANDLE,
        HANDLE,
        "build",
        "codex",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + codex);
    runStore.recordSession(codex, "codex-sess", "startup", null);
    runStore.complete(codex, "completed", 0);
    var malformed = DateTimeUtils.newId().toString();
    runStore.create(
        malformed,
        "acme",
        "auth",
        HANDLE,
        HANDLE,
        "build",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + malformed);
    runStore.recordSession(malformed, "$(rm -rf ~)", "startup", null);
    runStore.complete(malformed, "completed", 0);

    ops.startRoomRun("acme", "auth", HANDLE);

    assertFalse(
        String.join(" ", launched.get()).contains("--resume"),
        "another CLI's session and a malformed id are both unusable");
  }

  @Test
  void aSessionRecordedOnAnotherNodeIsNeverResumed() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");
    var remote = DateTimeUtils.newId().toString();
    runStore.create(
        remote,
        "acme",
        "auth",
        "raj",
        "raj",
        "build",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + remote);
    runStore.recordSession(remote, "sess-remote", "startup", null);
    runStore.complete(remote, "completed", 0);

    var runId = ops.startRoomRun("acme", "auth", HANDLE);

    assertFalse(
        String.join(" ", launched.get()).contains("--resume"),
        "conversation state lives on the box that ran it; a synced session id is not resumable");
    assertTrue(
        runStore.findById(runId).orElseThrow().task().contains("Build the OAuth flow."),
        "a fresh conversation is primed with the spec body");
  }

  @Test
  void aCodexSpecDeclinesTheWakeBecauseNothingEnforcesItsReadOnlyLane() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth", "codex");

    var declined = assertThrows(ApiException.class, () -> ops.startRoomRun("acme", "auth", HANDLE));

    assertEquals(ErrorCode.AGENT_NOT_CONFIGURED, declined.failure().errorCode());
    assertTrue(
        declined.failure().errorMessage().contains("claude-code"),
        declined.failure().errorMessage());
    assertTrue(runStore.listForSpec("auth").isEmpty(), "a declined wake reserves nothing");
  }

  @Test
  void theGuardPublishesALoudGuardrailWhenAHeadMovedUnattributed() throws Exception {
    var runId = DateTimeUtils.newId().toString();
    var shell =
        new StubShell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("rev-parse HEAD", "bbb222\n")
            .on("diff --name-only", "src/Main.java\nsrc/Flag.java\n");
    var ops = operations(shell);
    seedSpec("auth");
    runStore.create(
        runId,
        "acme",
        "auth",
        HANDLE,
        HANDLE,
        "room",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + runId);
    runStore.complete(runId, "completed", 0);
    runStore.saveRoomGuardBaseline(runId, "{\"app\": {\"head\": \"aaa111\"}}");

    ops.guardRoomRun("acme", runId);

    var guardrail =
        events.stream()
            .filter(e -> Event.WellKnownTypes.GUARDRAIL_TRIGGERED.equals(e.type()))
            .findFirst()
            .orElseThrow();
    assertEquals("auth", guardrail.spec());
    var reason = String.valueOf(guardrail.data().get("reason"));
    assertTrue(reason.contains("app"), reason);
    assertTrue(reason.contains("src/Main.java, src/Flag.java"), reason);
    assertTrue(reason.contains(runId), reason);
  }

  @Test
  void theGuardIsAsLoudForAnUncommittedEditAsForACommit() throws Exception {
    var runId = DateTimeUtils.newId().toString();
    var shell =
        new StubShell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("rev-parse HEAD", "aaa111\n")
            .on("diff --binary HEAD", "diff --git a/src/Main.java b/src/Main.java\n-old\n+new\n")
            .on("status --porcelain", " M src/Main.java\n?? notes.txt\n");
    var ops = operations(shell);
    seedSpec("auth");
    runStore.create(
        runId,
        "acme",
        "auth",
        HANDLE,
        HANDLE,
        "room",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + runId);
    runStore.complete(runId, "completed", 0);
    runStore.saveRoomGuardBaseline(
        runId,
        "{\"app\": {\"head\": \"aaa111\", \"state\":"
            + " \"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}}");

    ops.guardRoomRun("acme", runId);

    var guardrail =
        events.stream()
            .filter(e -> Event.WellKnownTypes.GUARDRAIL_TRIGGERED.equals(e.type()))
            .findFirst()
            .orElseThrow();
    var reason = String.valueOf(guardrail.data().get("reason"));
    assertTrue(reason.contains("worktree changed"), reason);
    assertTrue(reason.contains("src/Main.java, notes.txt"), reason);
  }

  @Test
  void anEditToAnAlreadyDirtyFileIsStillDetected() throws Exception {
    var runId = DateTimeUtils.newId().toString();
    var shell =
        new StubShell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("rev-parse HEAD", "aaa111\n")
            .on("diff --binary HEAD", "diff --git a/src/Main.java b/src/Main.java\n-old\n+worse\n")
            .on("status --porcelain", " M src/Main.java\n");
    var ops = operations(shell);
    seedSpec("auth");
    runStore.create(
        runId,
        "acme",
        "auth",
        HANDLE,
        HANDLE,
        "room",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + runId);
    runStore.complete(runId, "completed", 0);
    var baselineDiff = "diff --git a/src/Main.java b/src/Main.java\n-old\n+new\n";
    runStore.saveRoomGuardBaseline(
        runId, "{\"app\": {\"head\": \"aaa111\", \"state\": \"" + sha256(baselineDiff) + "\"}}");

    ops.guardRoomRun("acme", runId);

    var guardrail =
        events.stream()
            .filter(e -> Event.WellKnownTypes.GUARDRAIL_TRIGGERED.equals(e.type()))
            .findFirst()
            .orElseThrow();
    var reason = String.valueOf(guardrail.data().get("reason"));
    assertTrue(
        reason.contains("worktree changed"),
        "the same porcelain listing with different content must still trip the guard: " + reason);
  }

  @Test
  void theGuardAttributesNothingWhileAnotherLiveRunHoldsTheRepo() throws Exception {
    var runId = DateTimeUtils.newId().toString();
    var shell =
        new StubShell().on("incus list ^acme$", RUNNING_JSON).on("rev-parse HEAD", "bbb222\n");
    var ops = operations(shell);
    seedSpec("auth");
    runStore.create(
        runId,
        "acme",
        "auth",
        HANDLE,
        HANDLE,
        "room",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + runId);
    runStore.complete(runId, "completed", 0);
    runStore.saveRoomGuardBaseline(runId, "{\"app\": {\"head\": \"aaa111\"}}");
    var live = DateTimeUtils.newId().toString();
    db.execute(
        "INSERT INTO runs (id, project, spec_id, node, role, agent, status, started_at, repos)"
            + " VALUES (?, 'acme', 'other', ?, 'build', 'claude-code', 'running', 't0',"
            + " '[\"app\"]')",
        live,
        HANDLE);

    ops.guardRoomRun("acme", runId);

    assertTrue(
        events.stream().noneMatch(e -> Event.WellKnownTypes.GUARDRAIL_TRIGGERED.equals(e.type())),
        "a concurrent build's commits must never be pinned on the chat");
  }

  @Test
  void aForeignNodeBuildDoesNotSuppressTheLocalRoomGuard() throws Exception {
    var runId = DateTimeUtils.newId().toString();
    var shell =
        new StubShell().on("incus list ^acme$", RUNNING_JSON).on("rev-parse HEAD", "bbb222\n");
    var ops = operations(shell);
    seedSpec("auth");
    runStore.create(
        runId,
        "acme",
        "auth",
        HANDLE,
        HANDLE,
        "room",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + runId);
    runStore.complete(runId, "completed", 0);
    runStore.saveRoomGuardBaseline(runId, "{\"app\": {\"head\": \"aaa111\"}}");
    var foreignBuild = DateTimeUtils.newId().toString();
    db.execute(
        "INSERT INTO runs (id, project, spec_id, node, role, agent, status, started_at,"
            + " completed_at, repos) VALUES (?, 'acme', 'other', 'other-node', 'build',"
            + " 'claude-code', 'completed', ?, ?, '[\"app\"]')",
        foreignBuild,
        DateTimeUtils.now().minusSeconds(600).toString(),
        DateTimeUtils.now().plusSeconds(60).toString());

    ops.guardRoomRun("acme", runId);

    assertTrue(
        events.stream().anyMatch(e -> Event.WellKnownTypes.GUARDRAIL_TRIGGERED.equals(e.type())),
        "a build in another node's container cannot touch this workspace, so it must never"
            + " shield the local guard — HEAD moved aaa111→bbb222 and the room run is blamed");
  }

  @Test
  void aBuildThatFinishedMidChatStillShieldsItsRepos() throws Exception {
    var runId = DateTimeUtils.newId().toString();
    var shell =
        new StubShell().on("incus list ^acme$", RUNNING_JSON).on("rev-parse HEAD", "bbb222\n");
    var ops = operations(shell);
    seedSpec("auth");
    runStore.create(
        runId,
        "acme",
        "auth",
        HANDLE,
        HANDLE,
        "room",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + runId);
    runStore.complete(runId, "completed", 0);
    runStore.saveRoomGuardBaseline(runId, "{\"app\": {\"head\": \"aaa111\"}}");
    var build = DateTimeUtils.newId().toString();
    db.execute(
        "INSERT INTO runs (id, project, spec_id, node, role, agent, status, started_at,"
            + " completed_at, repos) VALUES (?, 'acme', 'other', ?, 'build', 'claude-code',"
            + " 'completed', ?, ?, '[\"app\"]')",
        build,
        HANDLE,
        DateTimeUtils.now().minusSeconds(600).toString(),
        DateTimeUtils.now().plusSeconds(60).toString());

    ops.guardRoomRun("acme", runId);

    assertTrue(
        events.stream().noneMatch(e -> Event.WellKnownTypes.GUARDRAIL_TRIGGERED.equals(e.type())),
        "a build that committed mid-chat and finished before the guard must still shield its repo");
  }

  @Test
  void aBuildThatFinishedBeforeTheChatBeganNeverShields() throws Exception {
    var runId = DateTimeUtils.newId().toString();
    var shell =
        new StubShell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("rev-parse HEAD", "bbb222\n")
            .on("diff --name-only", "src/Main.java\n");
    var ops = operations(shell);
    seedSpec("auth");
    runStore.create(
        runId,
        "acme",
        "auth",
        HANDLE,
        HANDLE,
        "room",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + runId);
    runStore.complete(runId, "completed", 0);
    runStore.saveRoomGuardBaseline(runId, "{\"app\": {\"head\": \"aaa111\"}}");
    var build = DateTimeUtils.newId().toString();
    db.execute(
        "INSERT INTO runs (id, project, spec_id, node, role, agent, status, started_at,"
            + " completed_at, repos) VALUES (?, 'acme', 'other', ?, 'build', 'claude-code',"
            + " 'completed', ?, ?, '[\"app\"]')",
        build,
        HANDLE,
        DateTimeUtils.now().minusSeconds(7200).toString(),
        DateTimeUtils.now().minusSeconds(3600).toString());

    ops.guardRoomRun("acme", runId);

    assertTrue(
        events.stream().anyMatch(e -> Event.WellKnownTypes.GUARDRAIL_TRIGGERED.equals(e.type())),
        "a build that finished before the baseline was captured cannot be the author");
  }

  @Test
  void aConcurrentRoomRunNeverSuppressesTheGuard() throws Exception {
    var runId = DateTimeUtils.newId().toString();
    var shell =
        new StubShell()
            .on("incus list ^acme$", RUNNING_JSON)
            .on("rev-parse HEAD", "bbb222\n")
            .on("diff --name-only", "src/Main.java\n");
    var ops = operations(shell);
    seedSpec("auth");
    runStore.create(
        runId,
        "acme",
        "auth",
        HANDLE,
        HANDLE,
        "room",
        "claude-code",
        null,
        "t",
        null,
        null,
        null,
        "sail-agent-" + runId);
    runStore.complete(runId, "completed", 0);
    runStore.saveRoomGuardBaseline(runId, "{\"app\": {\"head\": \"aaa111\"}}");
    var chat = DateTimeUtils.newId().toString();
    db.execute(
        "INSERT INTO runs (id, project, spec_id, node, role, agent, status, started_at, repos)"
            + " VALUES (?, 'acme', 'other', ?, 'room', 'claude-code', 'running', ?, '[]')",
        chat,
        HANDLE,
        DateTimeUtils.now().toString());

    ops.guardRoomRun("acme", runId);

    assertTrue(
        events.stream().anyMatch(e -> Event.WellKnownTypes.GUARDRAIL_TRIGGERED.equals(e.type())),
        "another spec's chat reserves nothing and must not read as a whole-container claim");
  }

  @Test
  void theGuardIsQuietWithoutARecordedBaseline() throws Exception {
    var runId = DateTimeUtils.newId().toString();
    var ops = operations(new StubShell().on("incus list ^acme$", RUNNING_JSON));

    ops.guardRoomRun("acme", runId);

    assertTrue(events.isEmpty());
  }

  @Test
  void anUnknownSpecOrMissingAgentBlockRefusesTheWake() throws Exception {
    var ops = operations(liveAgentShell());

    var missing = assertThrows(ApiException.class, () -> ops.startRoomRun("acme", "ghost", HANDLE));
    assertEquals(ErrorCode.SPEC_NOT_FOUND, missing.failure().errorCode());
  }

  @Test
  void theServerLaneDelegatesWakeAndGuardThroughSailOperations() throws Exception {
    var shell = liveAgentShell();
    operations(shell);
    seedSpec("auth");
    var yaml = tempDir.resolve("sail-server.yaml");
    Files.writeString(yaml, YAML);
    try (var bus = new EventBus()) {
      var sailOps =
          new SailOperations(
                  shell,
                  yaml.toString(),
                  (command, logPath) -> 4242L,
                  bus,
                  null,
                  specStore,
                  new ReviewStore(db),
                  runStore)
              .useMessages(messageStore);

      var runId = sailOps.startRoomRun("acme", "auth", HANDLE);
      assertEquals("room", runStore.findById(runId).orElseThrow().role());

      sailOps.guardRoomRun("acme", runId);
      assertTrue(
          runStore.consumeRoomGuardBaseline(runId).isEmpty(),
          "the guard consumed its baseline; an unmoved tree stays quiet");
    }
  }

  private static String sha256(String value) throws Exception {
    var hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);
  }

  private static final class StubShell implements ShellExec {
    private final Map<String, ShellExec.Result> scripts = new LinkedHashMap<>();

    StubShell() {
      on("incus config device add", "");
      on("cat " + ContainerSailSetup.STAMP_PATH, ContainerSailSetup.fingerprint());
    }

    StubShell on(String pattern, String stdout) {
      scripts.put(pattern, new ShellExec.Result(0, stdout, ""));
      return this;
    }

    @Override
    public ShellExec.Result exec(List<String> command) {
      var joined = String.join(" ", command);
      for (var entry : scripts.entrySet()) {
        if (joined.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      return new ShellExec.Result(1, "", "no script for " + joined);
    }

    @Override
    public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
