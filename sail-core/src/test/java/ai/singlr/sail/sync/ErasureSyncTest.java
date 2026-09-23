/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.EraseRequests;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pruning over a real session: main authors the erasure and every node adopts it through its
 * ordinary pages, the node's own prune is asked of main, and neither a cut round nor a stale push
 * can bring an erased entity back.
 */
class ErasureSyncTest {

  private static final SyncPrincipal NODE = new SyncPrincipal("node", true);

  @TempDir Path dir;
  private SyncBox main;
  private SyncBox node;

  @BeforeEach
  void setUp() {
    main = new SyncBox(dir, "main");
    node = new SyncBox(dir, "node");
    new FdeStore(main.db).add("uday", null, null, "admin");
    new FdeStore(main.db).add("node", null, null, "member");
  }

  @AfterEach
  void tearDown() {
    node.db.close();
    main.db.close();
  }

  @Test
  void aSpecPrunedOnMainLeavesNoRowHistoryOrContentOfItOnEitherBoxAfterOneRound()
      throws IOException {
    var body = seedArchivedSpec(main, "old", "uday");
    main.specs.create(SyncBox.spec("kept", "Kept", "pending"));
    round(NODE);
    assertEquals(1, count(node.db, "SELECT count(*) FROM runs WHERE spec_id = 'old'"));
    assertEquals(2, count(node.db, "SELECT count(*) FROM room_messages WHERE room_id = 'old'"));

    var erased = prune(main, "old", "uday");
    new BlobStore(main.db).gc(BlobStore.Compaction.NONE, true);
    round(NODE);

    assertEquals(6, erased.entities().size(), "spec, room, two messages, run, review");
    for (var box : List.of(main, node)) {
      assertNothingOf(box.db, erased.entities());
      assertFalse(new BlobStore(box.db).has(body), box.id + " still holds the pruned body");
      assertTrue(box.specs.findById("kept").isPresent(), "a spec outside the prune is untouched");
    }
    for (var target : erased.entities()) {
      var onMain = erasure(main.db, target);
      assertEquals("uday", onMain.actor(), "main's erasure row names who pruned");
      assertTrue(onMain.recordedAt() != null && !onMain.recordedAt().isBlank());
      assertEquals(onMain.rev(), erasure(node.db, target).rev(), "the node adopted main's row");
    }
    assertEquals(0, count(main.db, "SELECT count(*) FROM events WHERE spec_id = 'old'"));
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> node.specs.restore("old", erasure(node.db, erased.entities().getFirst()).rev()));
    assertTrue(refused.getMessage().contains("was pruned"), refused.getMessage());
  }

  @Test
  void aNodeThatNeverHeldThePrunedSpecRecordsOnlyItsErasure() throws IOException {
    seedArchivedSpec(main, "old", "uday");
    main.specs.create(SyncBox.spec("kept", "Kept", "pending"));
    var erased = prune(main, "old", "uday");
    var entriesBefore = count(node.db, "SELECT count(*) FROM change_log");

    var reports = round(NODE);

    assertNothingOf(node.db, erased.entities());
    assertEquals(
        entriesBefore + erased.entities().size() + 1,
        count(node.db, "SELECT count(*) FROM change_log"),
        "one erasure row per erased entity, plus the kept spec, and nothing else");
    assertTrue(node.specs.findById("kept").isPresent());
    assertTrue(reports.stream().allMatch(report -> report.report().conflicts() == 0));
  }

  @Test
  void aRoundCutAfterTwoErasurePagesResumesAndAdoptsNothingTwice() throws IOException {
    var ids = List.of("s1", "s2", "s3", "s4", "s5", "s6");
    for (var id : ids) {
      main.specs.create(SyncBox.spec(id, id, "archived"));
    }
    round(NODE);
    for (var id : ids) {
      prune(main, id, "uday");
    }
    var pages = new AtomicInteger();
    UnaryOperator<OutputStream> cutAfterTwoPages =
        out ->
            new FilterOutputStream(out) {
              @Override
              public void write(byte[] buffer, int offset, int length) throws IOException {
                if (new String(buffer, offset, length, StandardCharsets.UTF_8)
                        .contains("\"op\": \"page\"")
                    && pages.incrementAndGet() > 2) {
                  throw new IOException("channel cut");
                }
                out.write(buffer, offset, length);
              }
            };
    var link = SyncBox.connect(main.server(NODE), node, 400, cutAfterTwoPages);
    assertThrows(RuntimeException.class, () -> link.reconcile("spec", replicas().get("spec")));
    try {
      link.close();
    } catch (RuntimeException alreadyCut) {
      assertTrue(pages.get() > 2);
    }
    assertEquals(2, erasures(node.db, "spec"), "the two pages that arrived are adopted");

    try (var resumed = SyncBox.connect(main.server(NODE), node, 400, out -> out)) {
      resumed.reconcile("spec", replicas().get("spec"));
    }

    assertEquals(6, erasures(node.db, "spec"), "every erasure adopted exactly once");
    assertEquals(
        main.replica.maxSeq(),
        node.syncState.checkpoint("main", "spec"),
        "the resumed round reaches main's tip");
    assertEquals(0, count(node.db, "SELECT count(*) FROM specs"));
  }

  @Test
  void aNodesPruneIsAskedOfMainWhichErasesItAndTheNodeFollows() throws IOException {
    var body = seedArchivedSpec(main, "mine", "node");
    round(NODE);
    new EraseRequests(node.db).request(Erasure.SPEC, "mine", "node");

    var reports = round(NODE);

    assertEquals(0, new EraseRequests(node.db).pending(Erasure.SPEC).size());
    var onMain = erasure(main.db, new Erasure.Target(Erasure.SPEC, "mine"));
    assertEquals("node", onMain.actor(), "main attributes the erasure to the node that asked");
    for (var box : List.of(main, node)) {
      assertEquals(0, count(box.db, "SELECT count(*) FROM specs WHERE id = 'mine'"));
      assertEquals(0, count(box.db, "SELECT count(*) FROM runs WHERE spec_id = 'mine'"));
      assertEquals(0, count(box.db, "SELECT count(*) FROM room_messages WHERE room_id = 'mine'"));
      assertFalse(new BlobStore(box.db).has(body), box.id + " still holds the pruned body");
    }
    assertEquals(onMain.rev(), erasure(node.db, onMain.target()).rev());
    assertTrue(reports.getFirst().freedBytes() > 0, "the node reports what the collection freed");
  }

  @Test
  void mainRefusesANodesPruneOfASpecThatIsNotItsOwnAndTheAskIsDropped() throws IOException {
    seedArchivedSpec(main, "theirs", "uday");
    round(NODE);
    new EraseRequests(node.db).request(Erasure.SPEC, "theirs", "node");

    try (var link = SyncBox.connect(main.server(NODE), node)) {
      var refused =
          assertThrows(
              SyncTransportException.class, () -> link.reconcile("spec", replicas().get("spec")));
      assertEquals("refused", refused.kind());
      assertTrue(refused.getMessage().contains("belongs to 'uday'"), refused.getMessage());
    }

    assertTrue(main.specs.findById("theirs").isPresent());
    assertTrue(node.specs.findById("theirs").isPresent());
    assertEquals(List.of(), new EraseRequests(node.db).pending(Erasure.SPEC));
  }

  @Test
  void aNodeDemotedToReadOnlyHearsItsPruneRefusedOnceAndStopsAsking() throws IOException {
    seedArchivedSpec(main, "mine", "node");
    round(NODE);
    new EraseRequests(node.db).request(Erasure.SPEC, "mine", "node");
    var viewer = new SyncPrincipal("node", false);

    try (var link = SyncBox.connect(main.server(viewer), node)) {
      var refused =
          assertThrows(
              SyncTransportException.class, () -> link.reconcile("spec", replicas().get("spec")));
      assertTrue(
          refused.getMessage().contains("a read-only role cannot prune"), refused.getMessage());
    }

    assertEquals(List.of(), new EraseRequests(node.db).pending(Erasure.SPEC));
    assertTrue(main.specs.findById("mine").isPresent());
    try (var link = SyncBox.connect(main.server(viewer), node)) {
      assertEquals(0, link.reconcile("spec", replicas().get("spec")).report().total());
    }
  }

  @Test
  void anAdminNodeMayPruneASpecItDoesNotOwn() throws IOException {
    seedArchivedSpec(main, "theirs", "uday");
    var admin = new SyncPrincipal("node", true, true);
    round(admin);
    new EraseRequests(node.db).request(Erasure.SPEC, "theirs", "node");

    round(admin);

    assertTrue(main.specs.findById("theirs").isEmpty());
    assertTrue(node.specs.findById("theirs").isEmpty());
  }

  @Test
  void aNodeThatEditsASpecMainErasesMidRoundAdoptsTheErasureAndCannotResurrectIt()
      throws IOException {
    seedArchivedSpec(main, "old", "node");
    round(NODE);
    node.specs.update(owned("old", "An edit main never sees", "archived", "node"));
    var erasedMidRound = new AtomicBoolean();
    UnaryOperator<OutputStream> eraseAfterTheNeedAnswer =
        out ->
            new FilterOutputStream(out) {
              @Override
              public void write(byte[] buffer, int offset, int length) throws IOException {
                out.write(buffer, offset, length);
                if (new String(buffer, offset, length, StandardCharsets.UTF_8)
                        .contains("\"op\": \"page\"")
                    && erasedMidRound.compareAndSet(false, true)) {
                  prune(main, "old", "uday");
                }
              }
            };

    try (var link =
        SyncBox.connect(main.server(NODE), node, SyncWire.MAX_FRAME, eraseAfterTheNeedAnswer)) {
      var report = link.reconcile("spec", replicas().get("spec"));
      assertEquals(0, report.report().conflicts(), "an erasure is terminal, never a conflict");
    }

    assertTrue(erasedMidRound.get());
    assertTrue(node.specs.findById("old").isEmpty(), "the node adopted the erasure");
    assertTrue(main.specs.findById("old").isEmpty(), "the stale push did not resurrect it");
    assertEquals(0, count(node.db, "SELECT count(*) FROM sync_conflicts"));
  }

  @Test
  void aPrunedIdIsNeverCreatedAgainSoItsErasureIsTheLastWordEveryNodeHears() throws IOException {
    seedArchivedSpec(main, "reborn", "uday");
    round(NODE);
    prune(main, "reborn", "uday");

    assertThrows(
        IllegalArgumentException.class,
        () -> main.specs.create(SyncBox.spec("reborn", "Born again on main", "pending")));
    round(NODE);
    assertThrows(
        IllegalArgumentException.class,
        () -> node.specs.create(SyncBox.spec("reborn", "Born again on the node", "pending")));

    for (var box : List.of(main, node)) {
      assertTrue(box.specs.findById("reborn").isEmpty());
      assertEquals(
          0,
          count(
              box.db,
              "SELECT count(*) FROM change_log WHERE entity_id = 'reborn' AND kind <> 'erasure'"));
    }
  }

  @Test
  void aMemberCannotPruneASpecMainHoldsNothingOfNorTheRoomSharingItsId() throws IOException {
    new RoomStore(main.db)
        .create(
            new RoomStore.RoomRow(
                "lobby", "proj", "Lobby", "uday", null, null, "uday", null, null, "uday"));
    new MessageStore(main.db).append("lobby", "uday", "the lobby's own talk", null);
    round(NODE);
    new EraseRequests(node.db).request(Erasure.SPEC, "lobby", "node");

    try (var link = SyncBox.connect(main.server(NODE), node)) {
      var refused =
          assertThrows(
              SyncTransportException.class, () -> link.reconcile("spec", replicas().get("spec")));
      assertTrue(refused.getMessage().contains("main holds no spec 'lobby'"), refused.getMessage());
    }
    new EraseRequests(node.db).request(Erasure.SPEC, "lobby", "node");
    round(new SyncPrincipal("node", true, true));

    for (var box : List.of(main, node)) {
      assertTrue(new RoomStore(box.db).findById("lobby").isPresent(), box.id + " lost the room");
      assertEquals(1, count(box.db, "SELECT count(*) FROM room_messages WHERE room_id = 'lobby'"));
    }
  }

  @Test
  void askingAgainForASpecMainAlreadyErasedAnswersItsErasure() throws IOException {
    seedArchivedSpec(main, "mine", "node");
    round(NODE);
    var onMain = prune(main, "mine", "uday");
    new EraseRequests(node.db).request(Erasure.SPEC, "mine", "node");

    var reports = round(NODE);

    assertTrue(reports.stream().allMatch(report -> report.failure() == null));
    assertEquals(List.of(), new EraseRequests(node.db).pending(Erasure.SPEC));
    assertNothingOf(node.db, onMain.entities());
    assertEquals("uday", erasure(main.db, new Erasure.Target(Erasure.SPEC, "mine")).actor());
  }

  @Test
  void aThreadMainErasesReachesANodeHoldingItAndTheNodesOwnUnpushedReply() throws IOException {
    new RoomStore(main.db)
        .create(
            new RoomStore.RoomRow(
                "lobby", "proj", "Lobby", "uday", null, null, "uday", null, null, "uday"));
    var mainMessages = new MessageStore(main.db);
    var parent = mainMessages.append("lobby", "uday", "old news", null);
    var reply = mainMessages.append("lobby", "uday", "old reply", parent.id());
    round(NODE);
    var unpushed = new MessageStore(node.db).append("lobby", "node", "late word", reply.id());
    new Erasure(main.db)
        .erase(
            List.of(
                new Erasure.Target(Erasure.MESSAGE, parent.id()),
                new Erasure.Target(Erasure.MESSAGE, reply.id())),
            "sail",
            "retention");

    var reports = round(NODE);

    assertTrue(reports.stream().allMatch(report -> report.failure() == null));
    for (var box : List.of(main, node)) {
      assertEquals(0, count(box.db, "SELECT count(*) FROM room_messages"), box.id);
    }
    assertEquals(2, erasures(node.db, Erasure.MESSAGE), "main's two erasure rows, adopted");
    assertTrue(new MessageStore(node.db).findById(unpushed.id()).isEmpty());
  }

  @Test
  void aRunPushedForASpecMainErasedMidRoundIsRefusedAndGoesWithTheSpec() throws IOException {
    main.specs.create(owned("old", "Old work", "archived", "node"));
    round(NODE);
    var late =
        new RunStore(node.db)
            .create(
                DateTimeUtils.newId().toString(),
                "proj",
                "old",
                "node",
                "node",
                "build",
                "claude",
                "b",
                "t",
                null,
                null,
                "/log",
                "u");

    try (var link = SyncBox.connect(main.server(NODE), node)) {
      var replicas = replicas();
      link.reconcile("spec", replicas.get("spec"));
      prune(main, "old", "uday");
      var refused =
          assertThrows(
              SyncTransportException.class, () -> link.reconcile("run", replicas.get("run")));
      assertEquals("refused", refused.kind());
      assertTrue(
          refused.getMessage().contains("belongs to spec 'old', which was pruned"),
          refused.getMessage());
    }
    round(NODE);

    for (var box : List.of(main, node)) {
      assertEquals(0, count(box.db, "SELECT count(*) FROM runs"), box.id);
    }
    assertEquals(0, count(node.db, "SELECT count(*) FROM change_log WHERE entity_id = ?", late));
  }

  private List<SyncSession.TypeReport> round(SyncPrincipal as) throws IOException {
    var reports = new ArrayList<SyncSession.TypeReport>();
    var replicas = replicas();
    try (var link = SyncBox.connect(main.server(as), node)) {
      for (var entity : SyncedEntities.all()) {
        reports.add(link.reconcile(entity.type(), replicas.get(entity.type())));
      }
    }
    return reports;
  }

  private Map<String, StoreReplica> replicas() {
    return SyncedEntities.replicas(node.db, "node", "node");
  }

  /**
   * An archived spec on {@code box} owned by {@code owner}, with everything a prune takes with it:
   * content only it holds, its identity room with a thread of two messages, a run executed by the
   * node, a review with a stage and a finding, and an event. Answers the body's content hash.
   */
  private static String seedArchivedSpec(SyncBox box, String id, String owner) {
    box.specs.create(owned(id, "Old work " + id, "archived", owner));
    box.specs.setContent(id, "a body only " + id + " holds, " + System.nanoTime(), "plan " + id);
    new RoomStore(box.db)
        .create(
            new RoomStore.RoomRow(
                id, "proj", "Old work", owner, null, null, owner, null, null, owner));
    var messages = new MessageStore(box.db);
    var first = messages.append(id, owner, "the first word", null);
    messages.append(id, owner, "a reply to it", first.id());
    new RunStore(box.db)
        .create(
            DateTimeUtils.newId().toString(),
            "proj",
            id,
            "node",
            "node",
            "build",
            "claude",
            "b",
            "t",
            null,
            null,
            "/log",
            "u");
    var reviews = new ReviewStore(box.db);
    var review = reviews.createReview(id, 1);
    var stage = reviews.createStage(review, "security", "agent");
    reviews.addFinding(
        stage,
        Finding.create(
            Finding.Severity.HIGH,
            Finding.Category.SECURITY,
            "A.java",
            1,
            2,
            "issue",
            "desc",
            "evidence",
            new Finding.Suggestion("a", "b", "c"),
            0.9));
    new EventStore(box.db)
        .insert(
            new EventStore.EventRow(
                0, "2026-09-23T00:00:00Z", "spec_updated", "proj", id, null, "h", "{}"));
    return box.db
        .queryOne("SELECT body_hash FROM specs WHERE id = ?", r -> r.text(0), id)
        .orElseThrow();
  }

  private static SpecStore.SpecRow owned(String id, String title, String status, String owner) {
    var row = SyncBox.spec(id, title, status);
    return new SpecStore.SpecRow(
        row.id(),
        row.project(),
        row.title(),
        row.status(),
        owner,
        null,
        null,
        null,
        null,
        0,
        owner,
        "",
        "",
        owner,
        List.of(),
        List.of());
  }

  private static Erasure.Result prune(SyncBox box, String id, String actor) {
    var erasure = new Erasure(box.db);
    return erasure.erase(
        erasure.closure(List.of(new Erasure.Target(Erasure.SPEC, id))), actor, "local");
  }

  private static void assertNothingOf(Sqlite db, List<Erasure.Target> erased) {
    for (var target : erased) {
      assertEquals(
          0,
          count(
              db,
              "SELECT count(*) FROM change_log WHERE entity_type = ? AND entity_id = ?"
                  + " AND kind <> 'erasure'",
              target.type(),
              target.id()),
          target + " left history behind");
    }
    for (var table :
        List.of(
            "specs WHERE id = 'old'",
            "spec_content WHERE spec_id = 'old'",
            "rooms WHERE id = 'old'",
            "room_messages WHERE room_id = 'old'",
            "runs WHERE spec_id = 'old'",
            "run_principals",
            "reviews WHERE spec_id = 'old'",
            "review_stages",
            "review_findings",
            "sync_conflicts")) {
      assertEquals(0, count(db, "SELECT count(*) FROM " + table), table);
    }
  }

  private record ErasureRow(Erasure.Target target, String rev, String actor, String recordedAt) {}

  private static ErasureRow erasure(Sqlite db, Erasure.Target target) {
    return db.queryOne(
            "SELECT rev, actor, recorded_at FROM change_log WHERE entity_type = ? AND entity_id = ?"
                + " AND kind = 'erasure'",
            row -> new ErasureRow(target, row.text(0), row.text(1), row.text(2)),
            target.type(),
            target.id())
        .orElseThrow(() -> new AssertionError("no erasure row for " + target));
  }

  private static long erasures(Sqlite db, String type) {
    return count(
        db, "SELECT count(*) FROM change_log WHERE entity_type = ? AND kind = 'erasure'", type);
  }

  private static long count(Sqlite db, String sql, Object... args) {
    return db.queryOne(sql, row -> row.integer(0), args).orElseThrow();
  }
}
