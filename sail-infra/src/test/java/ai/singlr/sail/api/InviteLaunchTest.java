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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The invite launch lane: {@code startInvite} mints an {@code invite} (read only) or {@code
 * invite-full} run through the same reservation transaction as dispatch. Read only is the room
 * lane's wiring verbatim — harness-restricted command, no repos, guard baseline, ledger seeding —
 * and runs alongside anything, its own spec's live build included. Full reserves like a build,
 * takes a mandatory pre-launch snapshot labeled {@code invite-<runId>} (published into the room),
 * and launches full-permission; a held reservation refuses with the dispatch vocabulary and a
 * failed snapshot aborts the launch.
 */
class InviteLaunchTest {

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
  private final List<String> order = new ArrayList<>();
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
    db = Sqlite.open(tempDir.resolve("invite-" + System.nanoTime() + ".db"));
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
              order.add("launch");
              return 0;
            },
            DispatchOperations.Listener.NONE)
        .useMessages(messageStore);
  }

  private StubShell liveAgentShell() {
    return new StubShell(order)
        .on("incus list ^acme$", RUNNING_JSON)
        .on("command -v", "/usr/local/bin/claude\n")
        .on("incus snapshot create", "")
        .on("mkdir -p /home/dev/.sail", "")
        .on("rev-parse HEAD", "aaa111\n")
        .on("diff --binary HEAD", "")
        .on("printf '%s'", "")
        .on("agent.pid", "123")
        .on("kill -0 123", "")
        .on("agent-session.json", "{\"task\": \"work\"}");
  }

  private void seedSpec(String id) {
    seedSpec(id, HANDLE, null);
  }

  private void seedSpec(String id, String assignee, String branch) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "acme",
            "OAuth flow",
            SpecStatus.DRAFT,
            assignee,
            null,
            null,
            null,
            branch,
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
  void aReadOnlyInviteMintsAnInviteRunOnTheRoomContract() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");
    var question = messageStore.append("auth", "uday", "poke holes in this design", null);

    var launch =
        ops.startInvite("auth", "claude-code", false, null, Actor.cliOperator(HANDLE), HANDLE);

    var run = runStore.findById(launch.runId()).orElseThrow();
    assertEquals("invite", run.role());
    assertEquals("auth", run.specId());
    assertEquals(HANDLE, run.owner());
    assertEquals("claude/invite-" + launch.runId(), run.principal());
    assertEquals(launch.principal(), run.principal());
    assertEquals(List.of(), run.repos(), "read only reserves nothing");
    assertEquals("", launch.snapshot(), "read only pays no snapshot");
    assertEquals(
        Set.of(question.id()),
        runStore.deliveredMessageIds(launch.runId()),
        "the prompt is the run's first delivery, seeded by identity");
    assertTrue(run.task().contains("Invite Duty (read only)"));
    assertTrue(run.task().contains("Build the OAuth flow."));
    var joined = String.join(" ", launched.get());
    assertFalse(joined.contains("--dangerously-skip-permissions"), joined);
    assertTrue(joined.contains("--tools \"Bash,Read,Grep,Glob\""), joined);
    assertFalse(joined.contains("--resume"), "an invite is always a fresh participant");
    assertEquals("invite", launched.get().getLast(), "SAIL_RUN_ROLE rides the launch");
    assertTrue(
        runStore.consumeRoomGuardBaseline(launch.runId()).orElseThrow().contains("aaa111"),
        "the worktree-digest guard baseline is recorded exactly like a wake");
    assertTrue(
        events.stream().noneMatch(e -> Event.WellKnownTypes.SNAPSHOT_CREATED.equals(e.type())));
    var started =
        events.stream()
            .filter(e -> Event.WellKnownTypes.AGENT_SESSION_STARTED.equals(e.type()))
            .findFirst()
            .orElseThrow();
    assertEquals("auth", started.spec());
  }

  @Test
  void aReadOnlyInviteRunsAlongsideItsOwnSpecsLiveBuild() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");
    var live = DateTimeUtils.newId().toString();
    db.execute(
        "INSERT INTO runs (id, project, spec_id, node, role, agent, status, started_at, repos)"
            + " VALUES (?, 'acme', 'auth', ?, 'build', 'claude-code', 'running', 't0',"
            + " '[\"app\"]')",
        live,
        HANDLE);

    var launch =
        ops.startInvite("auth", "claude-code", false, null, Actor.cliOperator(HANDLE), HANDLE);

    assertEquals("invite", runStore.findById(launch.runId()).orElseThrow().role());
  }

  @Test
  void aFullInviteReservesSnapshotsAndLaunchesFullPermission() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth", HANDLE, "agent/auth");

    var launch =
        ops.startInvite("auth", "claude-code", true, "opus-x", Actor.cliOperator(HANDLE), HANDLE);

    var run = runStore.findById(launch.runId()).orElseThrow();
    assertEquals("invite-full", run.role());
    assertEquals(List.of("app"), run.repos(), "full reserves like a build");
    assertEquals("agent/auth", run.branch());
    assertEquals("claude/invite-" + launch.runId(), run.principal());
    assertEquals("invite-" + launch.runId(), launch.snapshot());
    assertEquals(List.of("snapshot", "launch"), order, "the snapshot precedes the launch");
    assertTrue(run.task().contains("Invite Duty (full access)"));
    var joined = String.join(" ", launched.get());
    assertTrue(joined.contains("--dangerously-skip-permissions"), joined);
    assertTrue(joined.contains("--model opus-x"), joined);
    assertEquals("invite-full", launched.get().getLast(), "SAIL_RUN_ROLE rides the launch");
    var snapshot =
        events.stream()
            .filter(e -> Event.WellKnownTypes.SNAPSHOT_CREATED.equals(e.type()))
            .findFirst()
            .orElseThrow();
    assertEquals("auth", snapshot.spec(), "the snapshot event renders in the room");
    assertEquals("invite-" + launch.runId(), snapshot.data().get("label"));
    assertEquals(launch.runId(), snapshot.data().get(Event.WellKnownData.RUN_ID));
  }

  @Test
  void aFullInviteIsRefusedWhileABuildHoldsTheRepos() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");
    var live = DateTimeUtils.newId().toString();
    db.execute(
        "INSERT INTO runs (id, project, spec_id, node, role, agent, status, started_at, repos)"
            + " VALUES (?, 'acme', 'other', ?, 'build', 'claude-code', 'running', 't0',"
            + " '[\"app\"]')",
        live,
        HANDLE);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                ops.startInvite(
                    "auth", "claude-code", true, null, Actor.cliOperator(HANDLE), HANDLE));

    assertEquals(ErrorCode.AGENT_ALREADY_RUNNING, refusal.failure().errorCode());
    assertTrue(
        refusal.failure().errorMessage().contains(live), "the refusal names the reservation");
    assertEquals(List.of(), order, "a refused invite takes no snapshot and launches nothing");
  }

  @Test
  void aSnapshotFailureAbortsTheFullInviteBeforeLaunch() throws Exception {
    var shell =
        new StubShell(order)
            .on("incus list ^acme$", RUNNING_JSON)
            .on("command -v", "/usr/local/bin/claude\n")
            .on("mkdir -p /home/dev/.sail", "");
    var ops = operations(shell);
    seedSpec("auth");

    var failure =
        assertThrows(
            ApiException.class,
            () ->
                ops.startInvite(
                    "auth", "claude-code", true, null, Actor.cliOperator(HANDLE), HANDLE));

    assertEquals(ErrorCode.SNAPSHOT_FAILED, failure.failure().errorCode());
    assertEquals(null, launched.get(), "the invite does not launch without its rollback point");
    var run = runStore.listForSpec("auth").getFirst();
    assertEquals("failed", run.status(), "the reservation is released through the failed run");
    assertTrue(
        events.stream().noneMatch(e -> Event.WellKnownTypes.SNAPSHOT_CREATED.equals(e.type())));
  }

  @Test
  void aCodexReadOnlyInviteIsRefusedNamingWhatItSupports() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.startInvite("auth", "codex", false, null, Actor.cliOperator(HANDLE), HANDLE));

    assertEquals(ErrorCode.BAD_REQUEST, refusal.failure().errorCode());
    assertTrue(
        refusal.failure().errorMessage().contains("full access"), refusal.failure().errorMessage());
    assertTrue(runStore.listForSpec("auth").isEmpty(), "a refused mode reserves nothing");
  }

  @Test
  void aCodexFullInviteLaunchesWithTheBypassFlags() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");

    var launch = ops.startInvite("auth", "codex", true, null, Actor.cliOperator(HANDLE), HANDLE);

    assertEquals("invite-full", runStore.findById(launch.runId()).orElseThrow().role());
    assertEquals("codex/invite-" + launch.runId(), launch.principal());
    var joined = String.join(" ", launched.get());
    assertTrue(joined.contains("--dangerously-bypass-approvals-and-sandbox"), joined);
  }

  @Test
  void aShellUnsafeModelIsRefusedBeforeAnyReservation() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                ops.startInvite(
                    "auth",
                    "claude-code",
                    false,
                    "x; touch /home/dev/workspace/PWNED #",
                    Actor.cliOperator(HANDLE),
                    HANDLE));

    assertEquals(ErrorCode.INVALID_REQUEST, refusal.failure().errorCode());
    assertTrue(refusal.failure().errorMessage().contains("shell"), "names the shell hazard");
    assertTrue(runStore.listForSpec("auth").isEmpty(), "a refused model reserves nothing");
    assertEquals(List.of(), order, "a refused model takes no snapshot and launches nothing");
  }

  @Test
  void anAgentAbsentFromTheContainerIsRefusedBeforeReserveOrSnapshot() throws Exception {
    var shell = new StubShell(order).on("incus list ^acme$", RUNNING_JSON);
    var ops = operations(shell);
    seedSpec("auth");

    var refusal =
        assertThrows(
            ApiException.class,
            () -> ops.startInvite("auth", "codex", true, null, Actor.cliOperator(HANDLE), HANDLE));

    assertEquals(ErrorCode.AGENT_NOT_CONFIGURED, refusal.failure().errorCode());
    assertTrue(refusal.failure().errorMessage().contains("codex"), "names the absent agent");
    assertTrue(runStore.listForSpec("auth").isEmpty(), "an absent agent reserves nothing");
    assertEquals(List.of(), order, "an absent agent takes no snapshot and launches nothing");
  }

  @Test
  void anUnknownAgentIsRefused() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                ops.startInvite("auth", "gemini", false, null, Actor.cliOperator(HANDLE), HANDLE));

    assertEquals(ErrorCode.BAD_REQUEST, refusal.failure().errorCode());
    assertTrue(refusal.failure().errorMessage().contains("claude-code"), "names the known agents");
  }

  @Test
  void anAgentLanePrincipalCannotInvite() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                ops.startInvite(
                    "auth",
                    "claude-code",
                    false,
                    null,
                    Actor.agentPrincipal("claude/x", HANDLE),
                    HANDLE));

    assertEquals(ErrorCode.AGENT_LANE_FORBIDDEN, refusal.failure().errorCode());
  }

  @Test
  void aSpecAssignedToAnotherNodeIsRefused() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth", "raj", null);

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                ops.startInvite(
                    "auth", "claude-code", false, null, Actor.cliOperator(HANDLE), HANDLE));

    assertEquals(ErrorCode.RUNS_ON_OTHER_NODE, refusal.failure().errorCode());
  }

  @Test
  void aMissingSpecIsRefused() throws Exception {
    var ops = operations(liveAgentShell());

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                ops.startInvite(
                    "ghost", "claude-code", false, null, Actor.cliOperator(HANDLE), HANDLE));

    assertEquals(ErrorCode.SPEC_NOT_FOUND, refusal.failure().errorCode());
  }

  @Test
  void aFullInviteStopNeverTriggersTheReviewPipeline() throws Exception {
    var ops = operations(liveAgentShell());
    seedSpec("auth");

    var launch =
        ops.startInvite("auth", "claude-code", true, null, Actor.cliOperator(HANDLE), HANDLE);

    assertNotNull(launch.runId());
    assertTrue(Event.WellKnownData.nonTriggeringLane("invite"));
    assertTrue(Event.WellKnownData.nonTriggeringLane("invite-full"));
    assertTrue(runStore.findById(launch.runId()).orElseThrow().inviteRole());
  }

  @Test
  void theServerLaneDelegatesInviteThroughSailOperationsAndReportsAgentModes() throws Exception {
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

      var launched =
          sailOps.inviteToSpec(
              "auth",
              new InviteRequest("claude-code", null, false),
              Actor.cliOperator(HANDLE),
              HANDLE);
      assertTrue(launched instanceof Result.Success<InviteResponse>);
      var response = ((Result.Success<InviteResponse>) launched).value();
      assertEquals("read_only", response.mode());
      assertEquals("invite", runStore.findById(response.runId()).orElseThrow().role());
      assertEquals("", response.snapshot());

      var refused =
          sailOps.inviteToSpec(
              "auth", new InviteRequest("codex", null, false), Actor.cliOperator(HANDLE), HANDLE);
      assertTrue(refused instanceof Result.Failure<InviteResponse>);
      assertEquals(ErrorCode.BAD_REQUEST, ((Result.Failure<InviteResponse>) refused).errorCode());

      var agents = sailOps.agents();
      assertTrue(agents instanceof Result.Success<AgentsResponse>);
      var roster = ((Result.Success<AgentsResponse>) agents).value().agents();
      assertEquals(List.of("claude-code", "codex"), roster.stream().map(AgentView::name).toList());
      var codexModes = roster.getLast().modes();
      assertEquals("read_only", codexModes.getFirst().mode());
      assertFalse(codexModes.getFirst().supported());
      assertNotNull(codexModes.getFirst().reason());
      assertTrue(codexModes.getLast().supported(), "every agent supports the full lane");
    }
  }

  private static final class StubShell implements ShellExec {
    private final Map<String, ShellExec.Result> scripts = new LinkedHashMap<>();
    private final List<String> order;

    StubShell(List<String> order) {
      this.order = order;
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
      if (joined.contains("incus snapshot create")) {
        order.add("snapshot");
      }
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
