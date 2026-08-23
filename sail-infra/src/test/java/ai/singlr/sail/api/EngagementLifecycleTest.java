/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.Roster;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engagement lifecycle: engage records the standing agent on the spec row (full mode only after
 * its snapshot payment succeeds, off the request thread), disengage clears it, and every transition
 * speaks on the bus so the room renders joins and leaves.
 */
class EngagementLifecycleTest {

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
  private RoomStore roomStore;
  private RunStore runStore;
  private Sqlite db;
  private final List<Event> events = new ArrayList<>();
  private final List<String> order = new ArrayList<>();

  @AfterEach
  void tearDown() {
    if (db != null) {
      db.close();
    }
  }

  private DispatchOperations operations(ShellExec shell) throws IOException {
    var yaml = tempDir.resolve("sail-" + System.nanoTime() + ".yaml");
    Files.writeString(yaml, YAML);
    db = Sqlite.open(tempDir.resolve("engage-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    roomStore = new RoomStore(db);
    runStore = new RunStore(db);
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
            command -> 0,
            DispatchOperations.Listener.NONE)
        .useMessages(new MessageStore(db))
        .useRooms(roomStore);
  }

  private StubShell shell() {
    return new StubShell(order)
        .on("incus list ^acme$", RUNNING_JSON)
        .on("command -v", "/usr/local/bin/claude\n")
        .on("incus snapshot create", "");
  }

  private void seedSpec(String id) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "acme",
            "OAuth flow",
            SpecStatus.DRAFT,
            HANDLE,
            null,
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
  }

  private Engagement stored(String specId) {
    return Engagement.fromJson(specStore.findById(specId).orElseThrow().engagement());
  }

  private Engagement roomMember(String specId) {
    return roomStore
        .findById(specId)
        .map(room -> Roster.fromJson(room.roster()).standing())
        .orElse(null);
  }

  private List<Event> ofType(String type) {
    return events.stream().filter(e -> type.equals(e.type())).toList();
  }

  private static final class StubShell implements ShellExec {
    private final java.util.Map<String, ShellExec.Result> scripts = new java.util.LinkedHashMap<>();
    private final List<String> order;

    StubShell(List<String> order) {
      this.order = order;
      on("incus config device add", "");
      on(
          "cat " + ai.singlr.sail.engine.ContainerSailSetup.STAMP_PATH,
          ai.singlr.sail.engine.ContainerSailSetup.fingerprint());
    }

    StubShell on(String pattern, String stdout) {
      scripts.put(pattern, new ShellExec.Result(0, stdout, ""));
      return this;
    }

    StubShell failing(String pattern, String stderr) {
      scripts.put(pattern, new ShellExec.Result(1, "", stderr));
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
    public ShellExec.Result exec(List<String> command, Path workDir, java.time.Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  @Test
  void theDefaultFullEngageTakesEffectImmediatelyWithNoSnapshot() throws Exception {
    var ops = operations(shell());
    seedSpec("auth");

    var launch =
        ops.engage("auth", "claude-code", null, null, false, Actor.cliOperator(HANDLE), HANDLE);

    assertEquals("full", launch.mode(), "full is the default mode");
    assertEquals("", launch.snapshot(), "no snapshot unless asked — dir backends are slow");
    assertNull(launch.completion(), "nothing is deferred");
    assertTrue(stored("auth").full());
    assertTrue(ofType(Event.WellKnownTypes.SNAPSHOT_CREATED).isEmpty());
    assertEquals(1, ofType(Event.WellKnownTypes.SPEC_ENGAGED).size());
  }

  @Test
  void aFullEngageTakesEffectOnlyAfterItsRequestedSnapshotAndInOrder() throws Exception {
    var ops = operations(shell());
    seedSpec("auth");

    var launch =
        ops.engage("auth", "claude-code", null, "opus-x", true, Actor.cliOperator(HANDLE), HANDLE);

    assertEquals("full", launch.mode(), "full is the default mode");
    assertTrue(launch.snapshot().startsWith("engage-"));
    assertNotNull(launch.completion());
    assertNull(stored("auth"), "the payment precedes the access");

    launch.completion().run();

    var engagement = stored("auth");
    assertEquals("claude-code", engagement.agent());
    assertEquals("opus-x", engagement.model());
    assertTrue(engagement.full());
    assertEquals(1, ofType(Event.WellKnownTypes.SNAPSHOT_CREATED).size());
    var engaged = ofType(Event.WellKnownTypes.SPEC_ENGAGED);
    assertEquals(1, engaged.size());
    assertEquals("auth", engaged.getFirst().spec());
    assertEquals("full", engaged.getFirst().data().get("mode"));
    assertEquals(launch.snapshot(), engaged.getFirst().data().get("label"));
  }

  @Test
  void aFailedSnapshotPublishesEngageFailedAndLeavesTheRoomEmpty() throws Exception {
    var failing =
        new StubShell(order)
            .on("incus list ^acme$", RUNNING_JSON)
            .on("command -v", "/usr/local/bin/claude\n")
            .failing("incus snapshot create", "no space left on storage pool");
    var ops = operations(failing);
    seedSpec("auth");

    var launch =
        ops.engage("auth", "claude-code", "full", null, true, Actor.cliOperator(HANDLE), HANDLE);
    launch.completion().run();

    assertNull(stored("auth"), "a failed payment engages nobody");
    assertTrue(ofType(Event.WellKnownTypes.SPEC_ENGAGED).isEmpty());
    var failedEvents = ofType(Event.WellKnownTypes.SPEC_ENGAGE_FAILED);
    assertEquals(1, failedEvents.size());
    assertEquals("claude-code", failedEvents.getFirst().data().get("agent"));
    assertNotNull(failedEvents.getFirst().data().get("error"));
  }

  @Test
  void engagingAMissingSpecIsRefusedAsNotFound() throws Exception {
    var ops = operations(shell());

    var ex =
        assertThrows(
            ApiException.class,
            () ->
                ops.engage(
                    "ghost", "claude-code", null, null, false, Actor.cliOperator(HANDLE), HANDLE));
    assertEquals(ErrorCode.SPEC_NOT_FOUND, ex.failure().errorCode());
  }

  @Test
  void disengagingAMissingSpecIsRefusedAsNotFound() throws Exception {
    var ops = operations(shell());

    var ex =
        assertThrows(
            ApiException.class, () -> ops.disengage("ghost", Actor.cliOperator(HANDLE), HANDLE));
    assertEquals(ErrorCode.SPEC_NOT_FOUND, ex.failure().errorCode());
  }

  @Test
  void aSpecDeletedDuringItsSnapshotPaymentPublishesEngageFailed() throws Exception {
    var ops = operations(shell());
    seedSpec("auth");

    var launch =
        ops.engage("auth", "claude-code", "full", null, true, Actor.cliOperator(HANDLE), HANDLE);
    specStore.delete("auth");
    launch.completion().run();

    assertNull(specStore.findById("auth").orElse(null), "the spec is gone");
    assertTrue(
        ofType(Event.WellKnownTypes.SPEC_ENGAGED).isEmpty(),
        "a spec that vanished mid-payment engages nobody");
    assertEquals(1, ofType(Event.WellKnownTypes.SPEC_ENGAGE_FAILED).size());
  }

  @Test
  void aReadOnlyEngagePersistsImmediatelyWithNoSnapshot() throws Exception {
    var ops = operations(shell());
    seedSpec("auth");

    var launch =
        ops.engage(
            "auth", "claude-code", "read-only", null, true, Actor.cliOperator(HANDLE), HANDLE);

    assertNull(launch.completion(), "nothing is deferred — no payment to make");
    assertEquals("", launch.snapshot());
    var engagement = stored("auth");
    assertEquals("read_only", engagement.mode());
    assertTrue(ofType(Event.WellKnownTypes.SNAPSHOT_CREATED).isEmpty());
    assertEquals(1, ofType(Event.WellKnownTypes.SPEC_ENGAGED).size());
  }

  @Test
  void aCodexReadOnlyEngageIsRefusedWithTheHarnessReason() throws Exception {
    var ops = operations(shell());
    seedSpec("auth");

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                ops.engage(
                    "auth", "codex", "read-only", null, false, Actor.cliOperator(HANDLE), HANDLE));

    assertEquals(ErrorCode.BAD_REQUEST, refusal.failure().errorCode());
    assertNull(stored("auth"));
  }

  @Test
  void aBogusModeOrUnknownAgentIsRefusedBeforeAnythingHappens() throws Exception {
    var ops = operations(shell());
    seedSpec("auth");

    assertThrows(
        ApiException.class,
        () ->
            ops.engage(
                "auth", "claude-code", "yolo", null, false, Actor.cliOperator(HANDLE), HANDLE));
    assertThrows(
        ApiException.class,
        () -> ops.engage("auth", "hal9000", null, null, false, Actor.cliOperator(HANDLE), HANDLE));
    assertNull(stored("auth"));
    assertTrue(events.isEmpty());
  }

  @Test
  void theServerLaneDelegatesEngageAndDisengageThroughSailOperations() throws Exception {
    var shell = shell();
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
              .useMessages(new MessageStore(db))
              .useRooms(roomStore)
              .useInviteExecutor(Runnable::run);

      var engaged =
          sailOps.engageToSpec(
              "auth",
              new EngageRequest("claude-code", null, null, true),
              Actor.cliOperator(HANDLE),
              HANDLE);
      assertTrue(engaged instanceof Result.Success<EngageResponse>);
      var response = ((Result.Success<EngageResponse>) engaged).value();
      assertEquals("full", response.mode());
      assertTrue(response.snapshot().startsWith("engage-"));
      assertNotNull(stored("auth"), "the deferred snapshot ran inline and engaged the room");

      var refused =
          sailOps.engageToSpec(
              "auth",
              new EngageRequest("codex", "read-only", null, false),
              Actor.cliOperator(HANDLE),
              HANDLE);
      assertTrue(refused instanceof Result.Failure<EngageResponse>);

      var membersAdd =
          sailOps.addRoomMember(
              "auth",
              new EngageRequest("claude-code", "read_only", null, false),
              Actor.cliOperator(HANDLE),
              HANDLE);
      assertTrue(membersAdd instanceof Result.Success<EngageResponse>);
      assertEquals("read_only", ((Result.Success<EngageResponse>) membersAdd).value().mode());
      var listed = sailOps.roomMembers("auth");
      assertTrue(listed instanceof Result.Success<RoomMembersResponse>);
      assertEquals(1, ((Result.Success<RoomMembersResponse>) listed).value().members().size());
      var removed = sailOps.removeRoomMember("auth", Actor.cliOperator(HANDLE), HANDLE);
      assertTrue(removed instanceof Result.Success<DisengageResponse>);
      assertEquals("claude-code", ((Result.Success<DisengageResponse>) removed).value().agent());
      sailOps.engageToSpec(
          "auth",
          new EngageRequest("claude-code", null, null, true),
          Actor.cliOperator(HANDLE),
          HANDLE);

      var dismissed = sailOps.disengageSpec("auth", Actor.cliOperator(HANDLE), HANDLE);
      assertTrue(dismissed instanceof Result.Success<DisengageResponse>);
      assertEquals("claude-code", ((Result.Success<DisengageResponse>) dismissed).value().agent());
      assertNull(stored("auth"));

      var empty = sailOps.disengageSpec("auth", Actor.cliOperator(HANDLE), HANDLE);
      assertTrue(empty instanceof Result.Success<DisengageResponse>);
      assertNull(((Result.Success<DisengageResponse>) empty).value().agent());
    }
  }

  @Test
  void disengageClearsTheRoomSpeaksOnTheBusAndIsIdempotent() throws Exception {
    var ops = operations(shell());
    seedSpec("auth");
    ops.engage("auth", "claude-code", "read-only", null, false, Actor.cliOperator(HANDLE), HANDLE);

    var dismissed = ops.disengage("auth", Actor.cliOperator(HANDLE), HANDLE);

    assertEquals("claude-code", dismissed);
    assertNull(stored("auth"));
    var left = ofType(Event.WellKnownTypes.SPEC_DISENGAGED);
    assertEquals(1, left.size());
    assertEquals("claude-code", left.getFirst().data().get("agent"));

    assertNull(
        ops.disengage("auth", Actor.cliOperator(HANDLE), HANDLE),
        "dismissing an empty room is a no-op, not an error");
    assertEquals(1, ofType(Event.WellKnownTypes.SPEC_DISENGAGED).size());
  }

  @Test
  void aBoxWithNoRoomAggregateRefusesMembershipLoudly() throws Exception {
    var yaml = tempDir.resolve("sail-norooms.yaml");
    Files.writeString(yaml, YAML);
    db = Sqlite.open(tempDir.resolve("norooms-" + System.nanoTime() + ".db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    runStore = new RunStore(db);
    new FdeStore(db).add(HANDLE, null, null, "admin");
    var unwired =
        new DispatchOperations(
                shell(),
                yaml.toString(),
                specStore,
                new ReviewStore(db),
                runStore,
                new FdeStore(db),
                events::add,
                new WatcherSpawner(shell(), (command, logPath) -> 4242L),
                (project, config) -> "",
                command -> 0,
                DispatchOperations.Listener.NONE)
            .useMessages(new MessageStore(db));
    seedSpec("auth");

    var refusal =
        assertThrows(
            ApiException.class,
            () ->
                unwired.engage(
                    "auth", "claude-code", null, null, false, Actor.cliOperator(HANDLE), HANDLE));

    assertEquals(ErrorCode.COMMAND_FAILED, refusal.failure().errorCode());
    assertNull(stored("auth"), "nothing was seated");
  }

  @Test
  void anEngageSeatsTheMemberOnTheRoomRowAsTheAuthoritativeHome() throws Exception {
    var ops = operations(shell());
    seedSpec("auth");

    ops.engage("auth", "claude-code", null, null, false, Actor.cliOperator(HANDLE), HANDLE);

    var member = roomMember("auth");
    assertNotNull(member, "membership lives on the room row");
    assertEquals("claude-code", member.agent());
    assertTrue(member.full());
    assertEquals(
        member.toJson(),
        stored("auth").toJson(),
        "the spec's legacy engagement column is dual-written identically");
    var room = roomStore.findById("auth").orElseThrow();
    assertEquals("acme", room.project(), "the identity room was minted on demand");
    assertEquals("OAuth flow", room.title());
  }

  @Test
  void aDisengageClearsTheRoomRosterAndTheLegacyColumnTogether() throws Exception {
    var ops = operations(shell());
    seedSpec("auth");
    ops.engage("auth", "claude-code", "read-only", null, false, Actor.cliOperator(HANDLE), HANDLE);
    assertNotNull(roomMember("auth"));

    ops.disengage("auth", Actor.cliOperator(HANDLE), HANDLE);

    assertNull(roomMember("auth"), "the roster empties");
    assertNull(stored("auth"), "the legacy column clears with it");
    assertNull(
        roomStore.findById("auth").orElseThrow().roster(),
        "an empty roster stores as null, matching the engagement convention");
  }

  @Test
  void membersReadsTheRosterRoomFirstThroughTheLifecycle() throws Exception {
    var ops = operations(shell());
    seedSpec("auth");
    assertTrue(ops.roomMembers("auth").isEmpty(), "a fresh room seats nobody");

    ops.engage("auth", "claude-code", "read-only", null, false, Actor.cliOperator(HANDLE), HANDLE);
    var members = ops.roomMembers("auth");
    assertEquals(1, members.size());
    assertEquals("claude-code", members.getFirst().agent());
    assertEquals("read_only", members.getFirst().mode());

    ops.disengage("auth", Actor.cliOperator(HANDLE), HANDLE);
    assertTrue(ops.roomMembers("auth").isEmpty(), "dismissal empties the roster");

    var missing = assertThrows(ApiException.class, () -> ops.roomMembers("ghost"));
    assertEquals(ErrorCode.SPEC_NOT_FOUND, missing.failure().errorCode());
  }

  @Test
  void aDismissalOnTheRoomWinsEvenIfTheLegacyColumnIsStale() throws Exception {
    var ops = operations(shell());
    seedSpec("auth");
    ops.engage("auth", "claude-code", "read-only", null, false, Actor.cliOperator(HANDLE), HANDLE);
    var spec = specStore.findById("auth").orElseThrow();
    roomStore.update(
        new RoomStore.RoomRow(
            "auth", "acme", "OAuth flow", HANDLE, null, null, HANDLE, null, null, HANDLE));

    assertNotNull(Engagement.fromJson(spec.engagement()), "the legacy column is now stale");
    assertNull(
        ops.disengage("auth", Actor.cliOperator(HANDLE), HANDLE),
        "the room row is authoritative: an empty roster means nobody is seated");
  }
}
