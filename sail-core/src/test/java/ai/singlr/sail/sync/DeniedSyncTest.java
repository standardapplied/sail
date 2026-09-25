/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.util.List;
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

  private List<String> history(String type, String id) {
    return new ChangeLog(node.db)
        .history(type, id).stream().map(ChangeLog.Entry::snapshot).toList();
  }

  @Test
  void aViewersLocalSpecEditIsDeniedAndTheNodeHoldsMainsVersion() throws IOException {
    main.specs.create(SyncBox.spec("auth", "Auth", "pending"));
    round(ADA, "spec");
    node.specs.update(SyncBox.spec("auth", "Viewer edit", "pending"));

    var report = round(ADA_VIEWING, "spec");

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
  void aDenialWhoseVersionOverflowsTheResultsFrameIsFetchedAndSettledTheSame() throws IOException {
    var first = "First ".repeat(80);
    var second = "Second ".repeat(80);
    main.specs.create(SyncBox.spec("one", first, "pending"));
    main.specs.create(SyncBox.spec("two", second, "pending"));
    round(ADA, "spec");
    node.specs.update(SyncBox.spec("one", "Viewer edit", "pending"));
    node.specs.update(SyncBox.spec("two", "Viewer edit", "pending"));

    var mains = SyncedEntities.replicas(main.db, "main", "main").get("spec");
    var entries = 0;
    var denials = 0;
    for (var id : List.of("one", "two")) {
      var state = mains.state(id);
      entries +=
          SyncWire.encodedLength(new SyncWire.Entry(0, id, state.rev(), false, state.snapshot()))
              + 2;
      denials +=
          SyncWire.encodedLength(
                  new SyncWire.Denied(id, StoreReplica.READ_ONLY, state.rev(), state.snapshot()))
              + 2;
    }
    var frame = 256 + entries;
    assertTrue(256 + denials > frame, "both versions must not fit one batch of results");

    SyncSession.TypeReport report;
    try (var link = SyncBox.connect(main.server(ADA_VIEWING), node, frame, out -> out)) {
      report = link.reconcile("spec", replica("spec"));
      assertEquals(1, link.count("push"), "both offers are decided in one push");
      assertEquals(2, link.count("need"), "the withheld version is fetched after the push");
    }

    assertNull(report.failure());
    assertEquals(
        List.of("one", "two"), report.denials().stream().map(SyncSession.Denial::id).toList());
    assertEquals(first, node.specs.findById("one").orElseThrow().title());
    assertEquals(second, node.specs.findById("two").orElseThrow().title());
    assertEquals(main.specs.latestRev("two"), node.specs.latestRev("two"));
    assertNextRoundIsClean(ADA_VIEWING, "spec", replica("spec"));
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
    for (var box : List.of(main, node)) {
      box.db.execute(
          """
          INSERT INTO rooms (id, title, project, assignee, created_at, updated_at)
          VALUES ('room', 'Room', 'acme', 'ada', 'now', 'now')""");
    }
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
