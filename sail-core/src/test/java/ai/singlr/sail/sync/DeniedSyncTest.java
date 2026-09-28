/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A change main refuses on authority settles over a real session: main answers {@link
 * SyncWire.Denied} with its version, the node adopts it and keeps its own in history, the round
 * completes, and the next round has nothing left to offer.
 */
@ActingAs
class DeniedSyncTest {

  private static final Actor ADA = Actor.sync("ada", Role.MEMBER);
  private static final Actor ADA_VIEWING = Actor.sync("ada", Role.VIEWER);

  private SyncBox main;
  private SyncBox node;

  @BeforeEach
  void setUp() {
    main = new SyncBox("main");
    node = new SyncBox("node");
  }

  @AfterEach
  void tearDown() {
    node.close();
    main.close();
  }

  private SyncSession.TypeReport round(Actor as, String type, LocalReplica local)
      throws IOException {
    try (var link = SyncBox.connect(main.server(as), node)) {
      return link.reconcile(type, local);
    }
  }

  private SyncSession.TypeReport round(Actor as, String type) throws IOException {
    return round(as, type, replica(type));
  }

  private LocalReplica replica(String type) {
    return SyncedEntities.replicas(node.db, "node", "ada").get(type);
  }

  private void assertNextRoundIsClean(Actor as, String type, LocalReplica local)
      throws IOException {
    try (var link = SyncBox.connect(main.server(as), node)) {
      var next = link.reconcile(type, local);
      assertNull(next.failure());
      assertEquals(0, next.report().total(), next.toString());
      assertEquals(List.of(), next.denials());
      assertEquals(0, link.count("push"), "nothing is offered again");
    }
  }

  private void sharedRoom() {
    for (var box : List.of(main, node)) {
      box.db.execute(
          """
          INSERT INTO rooms (id, title, project, assignee, created_at, updated_at)
          VALUES ('room', 'Room', 'acme', 'ada', 'now', 'now')""");
    }
  }

  private static String startRun(RunStore runs, String node) {
    var id = DateTimeUtils.newId().toString();
    runs.create(
        id,
        "acme",
        "room",
        node,
        "ada",
        "build",
        "claude-code",
        "feat/auth",
        "do it",
        1,
        null,
        "/log",
        "unit");
    return id;
  }

  private long credentials(String runId) {
    return node.db
        .queryOne(
            "SELECT count(*) FROM run_credentials WHERE run_id = ?", row -> row.integer(0), runId)
        .orElseThrow();
  }

  private List<String> history(String type, String id) {
    return new ChangeLog(node.db)
        .history(type, id).stream().map(ChangeLog.Entry::snapshot).toList();
  }

  @Test
  void aViewersLocalSpecEditIsDeniedAndTheNodeHoldsMainsVersion() throws IOException {
    main.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    round(ADA, "spec");
    node.specs.update(SyncBox.spec("auth", "Viewer edit", "pending"));

    SyncSession.TypeReport report;
    try (var link = SyncBox.connect(main.server(ADA_VIEWING), node)) {
      report = link.reconcile("spec", replica("spec"));
      assertEquals(
          List.of(
              "spec auth: main denied this change — "
                  + StoreReplica.READ_ONLY
                  + ". Yours is in its history: sail spec history auth."),
          link.notices(),
          "each denial is announced as it settles");
    }

    assertNull(report.failure(), "a denial settles; it never fails the round");
    assertEquals(1, report.denials().size());
    var denial = report.denials().getFirst();
    assertEquals("spec", denial.type());
    assertEquals("auth", denial.id());
    assertTrue(denial.reason().contains("read-only"), denial.reason());
    assertEquals("Auth", node.specs.findById("auth").orElseThrow().title());
    assertEquals(main.specs.latestRev("auth"), node.specs.latestRev("auth"));
    assertTrue(
        history("spec", "auth").stream().anyMatch(snapshot -> snapshot.contains("Viewer edit")),
        "the denied edit stays in the node's history");
    assertTrue(node.conflicts.pendingIds("spec").isEmpty(), "no conflict is parked");
    assertEquals("Auth", main.specs.findById("auth").orElseThrow().title());
    assertNextRoundIsClean(ADA_VIEWING, "spec", replica("spec"));
  }

  @Test
  void aDeniedDeleteWhoseVersionOutgrowsItsAnswerIsFetchedAndRestored() throws IOException {
    var title = "Large ".repeat(400);
    main.specs.create(SyncBox.spec("big", title, "pending"));
    round(ADA, "spec");
    node.specs.delete("big");

    SyncSession.TypeReport report;
    try (var link = SyncBox.connect(main.server(ADA_VIEWING), node)) {
      report = link.reconcile("spec", replica("spec"));
      assertEquals(1, link.count("push"));
      assertEquals(2, link.count("need"), "main withholds a version larger than its answer's room");
    }

    assertNull(report.failure());
    assertEquals(List.of("big"), report.denials().stream().map(SyncSession.Denial::id).toList());
    assertEquals(1, report.report().pulled(), "adopting main's version is a pull");
    assertEquals(title, node.specs.findById("big").orElseThrow().title());
    assertEquals(main.specs.latestRev("big"), node.specs.latestRev("big"));
    assertNextRoundIsClean(ADA_VIEWING, "spec", replica("spec"));
  }

  @Test
  void deniedEditsAreAnsweredWithinTheFrameTheirPushFit() throws IOException {
    var ids = IntStream.range(10, 22).mapToObj(i -> "spec-" + i).toList();
    ids.forEach(id -> main.specs.create(SyncBox.spec(id, "Shared " + id, "pending")));
    round(ADA, "spec");
    ids.forEach(id -> node.specs.update(SyncBox.spec(id, "Mine", "pending")));
    var mains = SyncedEntities.replicas(main.db, "main", "main").get("spec");
    var entry =
        ids.stream()
            .map(mains::state)
            .mapToInt(
                state ->
                    SyncWire.encodedLength(
                        new SyncWire.Entry(
                            99,
                            "spec-99",
                            state.rev(),
                            false,
                            state.snapshot(),
                            ChangeLog.Kind.REVISION,
                            null)))
            .max()
            .orElseThrow();
    var frame = 256 + 4 * (entry + 2) + 40;
    var longest = new AtomicInteger();

    SyncSession.TypeReport report;
    try (var link =
        SyncBox.connect(main.server(ADA_VIEWING), node, frame, out -> measured(out, longest))) {
      report =
          SyncBox.reconcile(
              ((PagedSyncSession) link.session()).frame(frame), "spec", replica("spec"));
    }

    assertNull(report.failure());
    assertEquals(ids, report.denials().stream().map(SyncSession.Denial::id).toList());
    assertTrue(longest.get() <= frame, "main's longest reply took " + longest.get() + " bytes");
    ids.forEach(id -> assertEquals("Shared " + id, node.specs.findById(id).orElseThrow().title()));
    assertNextRoundIsClean(ADA_VIEWING, "spec", replica("spec"));
  }

  private static OutputStream measured(OutputStream out, AtomicInteger longest) {
    return new FilterOutputStream(out) {
      private int line;

      @Override
      public void write(int value) throws IOException {
        out.write(value);
        line = value == '\n' ? 0 : line + 1;
        longest.accumulateAndGet(line, Math::max);
      }
    };
  }

  @Test
  void aNodeBornSpecMainDeniesLeavesTheNodeAndItsHistoryKeepsIt() throws IOException {
    node.specs.create(SyncBox.spec("born", "Offline idea", "pending"));

    var report = round(ADA_VIEWING, "spec");

    assertNull(report.failure());
    assertEquals(List.of("born"), report.denials().stream().map(SyncSession.Denial::id).toList());
    assertTrue(node.specs.findById("born").isEmpty(), "main holds none, so the node holds none");
    assertTrue(main.specs.findById("born").isEmpty());
    assertTrue(
        history("spec", "born").stream().anyMatch(snapshot -> snapshot.contains("Offline idea")));
    assertTrue(node.conflicts.pendingIds("spec").isEmpty());
    assertNextRoundIsClean(ADA_VIEWING, "spec", replica("spec"));
  }

  @Test
  void aMessagePostedAsAnotherFdeIsDeniedAndLeavesTheRoomWhileItsNeighboursLand()
      throws IOException {
    sharedRoom();
    new FdeStore(main.db).add("grace", null, null, "member");
    var messages = new MessageStore(node.db);
    var own = messages.append("room", "ada", "mine", null);
    var forged = messages.append("room", "grace", "posted as grace", null);
    var reply = messages.append("room", "ada", "replying to it", forged.id());
    var after = messages.append("room", "ada", "also mine", null);

    var report = round(ADA, "message");

    assertNull(report.failure(), "a forged author is a decision, never a failed push");
    assertEquals(2, report.report().pushed());
    assertEquals(
        List.of(forged.id(), reply.id()),
        report.denials().stream().map(SyncSession.Denial::id).toList());
    assertTrue(report.denials().getFirst().reason().contains("may not post as 'grace'"));
    var mains = new MessageStore(main.db);
    assertTrue(mains.findById(own.id()).isPresent());
    assertTrue(mains.findById(after.id()).isPresent());
    assertTrue(mains.findById(forged.id()).isEmpty());
    assertTrue(messages.findById(forged.id()).isEmpty(), "the denied message leaves the room");
    assertTrue(messages.findById(reply.id()).isEmpty(), "and takes this box's reply with it");
    assertFalse(history("message", forged.id()).isEmpty(), "its revision stays in the change log");
    assertNextRoundIsClean(ADA, "message", replica("message"));
  }

  @Test
  void aRunsMessageWaitsForItsRunAndLandsAfterIt() throws IOException {
    sharedRoom();
    var runs = new RunStore(node.db);
    var id = startRun(runs, "ada");
    var messages = new MessageStore(node.db);
    var posted =
        messages.append("room", runs.findById(id).orElseThrow().principal(), "from the run", null);
    var reply = messages.append("room", "ada", "answering the run", posted.id());

    var waiting = round(ADA, "message");

    assertNull(waiting.failure());
    assertEquals(0, waiting.report().pushed(), "the conversation waits for the run it rests on");
    assertTrue(messages.findById(posted.id()).isPresent());
    round(ADA, "run");
    var landed = round(ADA, "message");
    assertEquals(2, landed.report().pushed());
    assertEquals(List.of(), landed.denials());
    assertTrue(new MessageStore(main.db).findById(reply.id()).isPresent());
    assertNextRoundIsClean(ADA, "message", replica("message"));
  }

  @Test
  void thePipelinesMessageWaitsForTheRunItNarrates() throws IOException {
    sharedRoom();
    var runs = new RunStore(node.db);
    startRun(runs, "ada");
    var posted = new MessageStore(node.db).append("room", "sail", "review passed", null);

    assertEquals(0, round(ADA, "message").report().pushed());
    round(ADA, "run");

    assertEquals(1, round(ADA, "message").report().pushed());
    assertTrue(new MessageStore(main.db).findById(posted.id()).isPresent());
  }

  @Test
  void aSpecBornInARoomPostsThereThroughItsRuns() throws IOException {
    for (var box : List.of(main, node)) {
      box.db.execute(
          """
          INSERT INTO rooms (id, title, project, assignee, created_at, updated_at)
          VALUES ('home', 'Home', 'acme', 'ada', 'now', 'now')""");
    }
    main.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    round(ADA, "spec");
    for (var box : List.of(main, node)) {
      box.db.execute("UPDATE specs SET room_id = 'home' WHERE id = 'auth'");
    }
    var runs = new RunStore(node.db);
    var id = DateTimeUtils.newId().toString();
    runs.create(
        id,
        "acme",
        "auth",
        "ada",
        "ada",
        "build",
        "claude-code",
        "feat/auth",
        "do it",
        1,
        null,
        "/log",
        "unit");
    var messages = new MessageStore(node.db);
    var agent =
        messages.append("home", runs.findById(id).orElseThrow().principal(), "working", null);
    var narrator = messages.append("home", "sail", "review passed", null);
    var reply = messages.append("home", "ada", "thanks", agent.id());

    assertEquals(0, round(ADA, "message").report().pushed(), "held until the spec's run lands");
    round(ADA, "run");
    var landed = round(ADA, "message");

    assertEquals(3, landed.report().pushed());
    assertEquals(List.of(), landed.denials());
    var mains = new MessageStore(main.db);
    for (var posted : List.of(agent, narrator, reply)) {
      assertTrue(mains.findById(posted.id()).isPresent(), posted.body());
    }
    assertNextRoundIsClean(ADA, "message", replica("message"));
  }

  @Test
  void anAgentsPostWaitsForItsSpecAsWellAsItsRun() throws IOException {
    for (var box : List.of(main, node)) {
      box.db.execute(
          """
          INSERT INTO rooms (id, title, project, assignee, created_at, updated_at)
          VALUES ('home', 'Home', 'acme', 'ada', 'now', 'now')""");
    }
    node.specs.create(SyncBox.spec("auth", "Auth", "pending").withRoomId("home"));
    var runs = new RunStore(node.db);
    var id = DateTimeUtils.newId().toString();
    runs.create(
        id,
        "acme",
        "auth",
        "ada",
        "ada",
        "build",
        "claude-code",
        "feat/auth",
        "do it",
        1,
        null,
        "/log",
        "unit");
    var posted =
        new MessageStore(node.db)
            .append("home", runs.findById(id).orElseThrow().principal(), "working", null);
    round(ADA, "run");

    assertEquals(0, round(ADA, "message").report().pushed(), "main places the run by its spec");
    round(ADA, "spec");

    assertEquals(1, round(ADA, "message").report().pushed());
    assertTrue(new MessageStore(main.db).findById(posted.id()).isPresent());
  }

  @Test
  void thePipelinesPostGoesOnceMainHoldsARunOfItsOwnerThere() throws IOException {
    sharedRoom();
    var runs = new RunStore(node.db);
    startRun(runs, "ada");
    round(ADA, "run");
    startRun(runs, null);
    round(ADA, "run");
    var posted = new MessageStore(node.db).append("room", "sail", "review passed", null);

    assertEquals(
        1, round(ADA, "message").report().pushed(), "main already holds a run to decide by");
    assertTrue(new MessageStore(main.db).findById(posted.id()).isPresent());
  }

  @Test
  void aRunMainKeepsDenyingHoldsOnlyItsOwnPosts() throws IOException {
    sharedRoom();
    var runs = new RunStore(node.db);
    var id = startRun(runs, null);
    var messages = new MessageStore(node.db);
    var stuck = messages.append("room", runs.findById(id).orElseThrow().principal(), "stuck", null);
    var human = messages.append("room", "ada", "carry on", null);

    assertEquals(
        List.of(id), round(ADA, "run").denials().stream().map(SyncSession.Denial::id).toList());
    var round = round(ADA, "message");

    assertEquals(1, round.report().pushed(), "a human's post never waits on a run");
    assertTrue(new MessageStore(main.db).findById(human.id()).isPresent());
    assertTrue(messages.findById(stuck.id()).isPresent(), "the run's own post waits with it");
  }

  @Test
  void aPostByARotatedPrincipalWaitsForTheRunsNewRevision() throws IOException {
    sharedRoom();
    var runs = new RunStore(node.db);
    var id = startRun(runs, "ada");
    round(ADA, "run");
    runs.rotateCredential(id, "codex", "fix");
    var posted =
        new MessageStore(node.db)
            .append("room", runs.findById(id).orElseThrow().principal(), "fixing", null);

    assertEquals(0, round(ADA, "message").report().pushed());
    round(ADA, "run");

    assertEquals(1, round(ADA, "message").report().pushed());
    assertTrue(new MessageStore(main.db).findById(posted.id()).isPresent());
  }

  @Test
  void theMessagesOfARunMainDeniesAreDeniedAfterItNeverRefused() throws IOException {
    sharedRoom();
    var runs = new RunStore(node.db);
    var id = startRun(runs, null);
    var messages = new MessageStore(node.db);
    var posted =
        messages.append("room", runs.findById(id).orElseThrow().principal(), "unstamped", null);
    runs.complete(id, "completed", 0);

    var run = round(ADA, "run");

    assertEquals(List.of(id), run.denials().stream().map(SyncSession.Denial::id).toList());
    assertTrue(runs.findById(id).isEmpty(), "a run with no node stamp is never main's to take");
    var message = round(ADA, "message");
    assertNull(message.failure());
    assertEquals(
        List.of(posted.id()), message.denials().stream().map(SyncSession.Denial::id).toList());
    assertTrue(messages.findById(posted.id()).isEmpty());
    assertNextRoundIsClean(ADA, "message", replica("message"));
  }

  @Test
  void aMessageInARoomMainDoesNotHoldYetIsRefusedAndLandsAfterTheRoom() throws IOException {
    new RoomStore(node.db)
        .create(
            new RoomStore.RoomRow(
                "fresh", "acme", "Fresh", "ada", null, null, null, null, null, null));
    var messages = new MessageStore(node.db);
    var posted = messages.append("fresh", "ada", "first words", null);

    var refused = assertThrows(SyncTransportException.class, () -> round(ADA, "message"));

    assertTrue(
        refused.getMessage().contains("does not hold room 'fresh' yet"), refused.getMessage());
    assertTrue(messages.findById(posted.id()).isPresent());
    round(ADA, "room");
    assertEquals(1, round(ADA, "message").report().pushed());
    assertTrue(new MessageStore(main.db).findById(posted.id()).isPresent());
  }

  @Test
  void aMessageInARoomMainHasDeletedIsDeniedAndLeavesTheRoom() throws IOException {
    sharedRoom();
    main.db.execute(
        """
        INSERT INTO rooms (id, title, project, assignee, created_at, updated_at)
        VALUES ('gone', 'Gone', 'acme', 'ada', 'now', 'now')""");
    new RoomStore(main.db).delete("gone");
    node.db.execute(
        """
        INSERT INTO rooms (id, title, project, assignee, created_at, updated_at)
        VALUES ('gone', 'Gone', 'acme', 'ada', 'now', 'now')""");
    var messages = new MessageStore(node.db);
    var posted = messages.append("gone", "ada", "into the void", null);

    var report = round(ADA, "message");

    assertEquals(
        List.of(posted.id()), report.denials().stream().map(SyncSession.Denial::id).toList());
    assertTrue(messages.findById(posted.id()).isEmpty(), "main held the room, so it decides");
    assertNextRoundIsClean(ADA, "message", replica("message"));
  }

  @Test
  void aLiveRunMainDeniesIsKeptWithItsCredentialUntilItFinishes() throws IOException {
    var runs = new RunStore(node.db);
    var id = startRun(runs, "ada");

    var live = round(ADA_VIEWING, "run");

    assertNull(live.failure());
    assertEquals(List.of(id), live.denials().stream().map(SyncSession.Denial::id).toList());
    assertEquals(0, live.report().pulled(), "work still under way here is never rewritten");
    assertEquals("running", runs.findById(id).orElseThrow().status());
    assertEquals(1, credentials(id), "the agent can still act as its run");
    assertEquals(
        List.of(id),
        round(ADA_VIEWING, "run").denials().stream().map(SyncSession.Denial::id).toList());
    runs.complete(id, "completed", 0);

    var finished = round(ADA_VIEWING, "run");

    assertEquals(1, finished.report().pulled());
    assertTrue(runs.findById(id).isEmpty(), "once finished, main holding none removes it");
    assertFalse(history("run", id).isEmpty());
    assertNextRoundIsClean(ADA_VIEWING, "run", replica("run"));
  }

  @Test
  void aRunStampedWithAnotherNodesHandleIsDeniedAndTheNodeHoldsMainsRun() throws IOException {
    var id = DateTimeUtils.newId().toString();
    new RunStore(main.db)
        .create(
            id,
            "acme",
            "auth",
            "grace",
            "grace",
            "build",
            "claude-code",
            "feat/auth",
            "do it",
            1,
            null,
            "/log",
            "unit");
    var unhandled = SyncedEntities.replicas(node.db, "node", "").get("run");
    round(ADA, "run", unhandled);
    var runs = new RunStore(node.db);
    runs.complete(id, "stopped", 0);

    var report = round(ADA, "run", unhandled);

    assertNull(report.failure());
    assertEquals(List.of(id), report.denials().stream().map(SyncSession.Denial::id).toList());
    assertTrue(report.denials().getFirst().reason().contains("'ada'"));
    assertEquals("running", runs.findById(id).orElseThrow().status());
    assertEquals(new RunStore(main.db).latestRev(id), runs.latestRev(id));
    assertEquals("running", new RunStore(main.db).findById(id).orElseThrow().status());
    assertNextRoundIsClean(ADA, "run", unhandled);
  }
}
