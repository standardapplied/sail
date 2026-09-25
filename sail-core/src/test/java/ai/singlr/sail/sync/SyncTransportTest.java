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

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ConflictDetector;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.SyncState;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two nodes and a main joined by the real protocol-4 wire: each round opens a {@link SyncSession}
 * over a pipe to a {@link SyncRpcServer} and the wire log says what it cost.
 */
@ActingAs
class SyncTransportTest {

  private static final int SMALL_FRAME = 1_500;

  @TempDir Path tempDir;
  private SyncBox main;
  private SyncBox nodeA;
  private SyncBox nodeB;

  @BeforeEach
  void setUp() {
    main = new SyncBox(tempDir, "main");
    nodeA = new SyncBox(tempDir, "A");
    nodeB = new SyncBox(tempDir, "B");
  }

  @AfterEach
  void tearDown() {
    nodeB.close();
    nodeA.close();
    main.close();
  }

  private static SpecStore.SpecRow spec(String id, String title, String status) {
    return SyncBox.spec(id, title, status);
  }

  private static SpecStore.SpecRow specBy(String id, String title, String author) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        title,
        SpecStatus.fromWire("pending"),
        null,
        null,
        null,
        null,
        null,
        0,
        author,
        "",
        "",
        author,
        List.of(),
        List.of());
  }

  private SyncBox.Link connect(SyncBox node) throws IOException {
    return SyncBox.connect(main.server(Actor.sync(node.id, Role.MEMBER)), node);
  }

  private SyncBox.Link connect(SyncBox node, int frame, UnaryOperator<OutputStream> serverOut)
      throws IOException {
    return SyncBox.connect(main.server(Actor.sync(node.id, Role.MEMBER)), node, frame, serverOut);
  }

  private SyncSession.TypeReport syncToMain(SyncBox node) throws IOException {
    try (var link = connect(node)) {
      return link.reconcile("spec", node.replica);
    }
  }

  private long syncRows(SyncBox box) {
    return box.db
        .queryOne(
            "SELECT COUNT(*) FROM change_log WHERE entity_type = 'spec' AND origin = 'sync'",
            row -> row.integer(0))
        .orElseThrow();
  }

  private void bigSpec(SyncBox box, String id) {
    box.specs.create(spec(id, id.repeat(600 / id.length()), "pending"));
  }

  @Test
  void replayingAnAlreadyAdoptedRenameTombstoneDoesNotJournalItAgain() throws Exception {
    var projects = new ProjectStore(main.db);
    projects.upsert("old", "name: old\n");
    projects.rename("old", "new", "name: new\n");
    try (var link = connect(nodeA)) {
      link.reconcile(
          "project", SyncedEntities.replicas(nodeA.db, nodeA.id, nodeA.id).get("project"));
    }
    var before = new ChangeLog(nodeA.db).maxSeq("project");
    nodeA.db.execute("DELETE FROM sync_state WHERE peer = 'main' AND entity_type = 'project'");

    try (var link = connect(nodeA)) {
      var report =
          link.reconcile(
              "project", SyncedEntities.replicas(nodeA.db, nodeA.id, nodeA.id).get("project"));
      assertEquals(0, report.report().total());
    }
    assertEquals(before, new ChangeLog(nodeA.db).maxSeq("project"));
    assertTrue(new ProjectStore(nodeA.db).findByName("old").isEmpty());
  }

  @Test
  void anUnbasedLocalRenameConflictsWithMainsExistingProject() throws Exception {
    new ProjectStore(main.db).upsert("old", "name: old\n");
    var projects = new ProjectStore(nodeA.db);
    projects.upsert("old", "name: old\n");
    projects.rename("old", "new", "name: new\n");

    try (var link = connect(nodeA)) {
      assertEquals(
          1,
          link.reconcile(
                  "project", SyncedEntities.replicas(nodeA.db, nodeA.id, nodeA.id).get("project"))
              .report()
              .conflicts());
    }
    assertEquals(
        List.of(ConflictDetector.DELETED_FIELD),
        nodeA.conflicts.pendingFor("project", "old").orElseThrow().fields());
    assertTrue(new ProjectStore(main.db).findByName("old").isPresent());
    assertTrue(projects.findByName("old").isEmpty());
  }

  @Test
  void aReaderAdoptsMainInsteadOfOfferingAMergeOfAForeignRun() throws Exception {
    var runs = new RunStore(main.db);
    var id = ai.singlr.sail.common.DateTimeUtils.newId().toString();
    runs.create(
        id,
        "project",
        "auth",
        "owner",
        "owner",
        "build",
        "codex",
        "original",
        "task",
        123,
        null,
        "/tmp/agent.log",
        "sail-agent-" + id);
    var replica = SyncedEntities.replicas(nodeA.db, nodeA.id, nodeA.id).get("run");
    try (var link = connect(nodeA)) {
      link.reconcile("run", replica);
    }
    new RunStore(nodeA.db).complete(id, "stopped", 0);
    var changed = new LinkedHashMap<>(runs.comparableSnapshot(id));
    changed.put("branch", "from-main");
    runs.commitRevision(id, changed, runs.latestRev(id));
    var mainRev = runs.latestRev(id);

    try (var link = connect(nodeA)) {
      var report = link.reconcile("run", replica).report();
      assertEquals(1, report.pulled());
      assertEquals(0, report.pushed());
      assertEquals(0, report.conflicts());
    }
    assertEquals(mainRev, runs.latestRev(id));
    var adopted = new RunStore(nodeA.db).findById(id).orElseThrow();
    assertEquals("from-main", adopted.branch());
    assertEquals("running", adopted.status());
  }

  @Test
  void localEditsThroughoutEveryAdoptionRetryParkAConflictWithoutOverwritingThem()
      throws Exception {
    main.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    main.specs.update(spec("auth", "Remote title", "pending"));
    var writes = new AtomicInteger();
    var racing =
        new EditingReplica(
            SyncedEntities.replicas(nodeA.db, nodeA.id, nodeA.id).get("spec"),
            () -> {
              assertTrue(writes.incrementAndGet() <= 10, "adoption retries must be bounded");
              nodeA.specs.update(spec("auth", "Auth", "in_progress"));
            });

    try (var link = connect(nodeA)) {
      assertEquals(
          1, link.reconcile("spec", racing.scopedTo(racing.entityIds())).report().conflicts());
    }
    assertEquals(
        List.of("<stale>"), nodeA.conflicts.pendingFor("spec", "auth").orElseThrow().fields());
    assertEquals("Auth", nodeA.specs.findById("auth").orElseThrow().title());
    assertEquals("in_progress", nodeA.specs.findById("auth").orElseThrow().status().wire());
  }

  private record EditingReplica(LocalReplica inner, Runnable afterTransaction)
      implements LocalReplica {
    @Override
    public Set<String> entityIds() {
      return inner.entityIds();
    }

    @Override
    public Set<String> dirtyIds() {
      return inner.dirtyIds();
    }

    @Override
    public <T> T atomically(Supplier<T> work) {
      var result = inner.atomically(work);
      afterTransaction.run();
      return result;
    }

    @Override
    public Map<String, Object> current(String id) {
      return inner.current(id);
    }

    @Override
    public Map<String, Object> base(String id) {
      return inner.base(id);
    }

    @Override
    public String currentRev(String id) {
      return inner.currentRev(id);
    }

    @Override
    public void adopt(String id, Map<String, Object> snapshot, String rev) {
      inner.adopt(id, snapshot, rev);
    }

    @Override
    public void recordConflict(
        String id,
        Map<String, Object> base,
        Map<String, Object> local,
        Map<String, Object> remote,
        List<String> fields) {
      inner.recordConflict(id, base, local, remote, fields);
    }

    @Override
    public long checkpoint(String peer) {
      return inner.checkpoint(peer);
    }

    @Override
    public void advanceCheckpoint(String peer, long seq) {
      inner.advanceCheckpoint(peer, seq);
    }
  }

  @Test
  void aLocalCreatePropagatesAcrossTheWire() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    var pushed = syncToMain(nodeA);
    assertEquals(1, pushed.report().pushed());
    assertEquals("Auth", main.specs.findById("auth").orElseThrow().title());
    var pulled = syncToMain(nodeB);
    assertEquals(1, pulled.report().pulled());
    assertEquals(1, pulled.entries());
    assertEquals("Auth", nodeB.specs.findById("auth").orElseThrow().title());
  }

  @Test
  void authorsReachEveryReplicaOnCreateAndEdit() throws Exception {
    Acting.as("ada", () -> nodeA.specs.create(specBy("auth", "Auth", "ada")));
    syncToMain(nodeA);
    syncToMain(nodeB);
    assertEquals("ada", main.specs.findById("auth").orElseThrow().updatedBy());
    assertEquals("ada", nodeB.specs.findById("auth").orElseThrow().updatedBy());
    Acting.as("bob", () -> nodeB.specs.update(specBy("auth", "Revised", "bob")));
    syncToMain(nodeB);
    syncToMain(nodeA);
    assertEquals("bob", main.specs.findById("auth").orElseThrow().updatedBy());
    assertEquals("bob", nodeA.specs.findById("auth").orElseThrow().updatedBy());
    var committed = main.specs.history("auth").getLast();
    assertEquals("bob", committed.actor(), "main records the author the push offered");
    assertEquals("B", committed.peer(), "and the FDE whose session pushed it");
    var adopted = nodeA.specs.history("auth").getLast();
    assertEquals("bob", adopted.actor(), "a node adopts the author main recorded");
    assertEquals("main", adopted.peer());
  }

  @Test
  void aPushThatOffersNoAuthorIsRecordedAsThePushingFde() throws Exception {
    Acting.as(null, () -> nodeA.specs.create(spec("auth", "Auth", "pending")));

    syncToMain(nodeA);

    var committed = main.specs.history("auth").getLast();
    assertEquals("A", committed.actor());
    assertEquals("A", main.specs.findById("auth").orElseThrow().updatedBy());
  }

  @Test
  void aDeletedSpecThatNeverReachedMainConvergesQuietly() throws Exception {
    nodeA.specs.create(spec("local", "Local", "pending"));
    nodeA.specs.delete("local");
    assertEquals(0, syncToMain(nodeA).report().total());
    assertTrue(main.specs.findById("local").isEmpty());
  }

  @Test
  void disjointEditsAutoMergeOverTheWire() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);
    nodeA.specs.updateStatus("auth", SpecStatus.fromWire("in_progress"));
    nodeB.specs.setContent("auth", "node B body", "");
    syncToMain(nodeA);
    syncToMain(nodeB);
    syncToMain(nodeA);
    assertEquals("in_progress", nodeA.specs.findById("auth").orElseThrow().status().wire());
    assertEquals("in_progress", nodeB.specs.findById("auth").orElseThrow().status().wire());
    assertEquals("node B body", nodeA.specs.getContent("auth").orElseThrow().body());
    assertTrue(nodeA.conflicts.pending().isEmpty());
    assertTrue(nodeB.conflicts.pending().isEmpty());
  }

  @Test
  void sameFieldEditConflictsOverTheWireAndLeavesLocalWorkUntouched() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);
    nodeA.specs.update(spec("auth", "Title from A", "pending"));
    nodeB.specs.update(spec("auth", "Title from B", "pending"));
    syncToMain(nodeA);
    var report = syncToMain(nodeB);
    assertEquals(1, report.report().conflicts());
    assertEquals("Title from A", main.specs.findById("auth").orElseThrow().title());
    assertEquals(List.of("title"), nodeB.conflicts.pending().getFirst().fields());
    assertEquals("Title from B", nodeB.specs.findById("auth").orElseThrow().title());
  }

  @Test
  void aLocalDeletePropagatesAcrossTheWire() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);
    nodeA.specs.delete("auth");
    assertEquals(1, syncToMain(nodeA).report().pushed());
    assertTrue(main.specs.findById("auth").isEmpty());
    syncToMain(nodeB);
    assertTrue(nodeB.specs.findById("auth").isEmpty());
  }

  @Test
  void deleteVersusEditConflictsOverTheWire() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);
    nodeA.specs.delete("auth");
    nodeB.specs.update(spec("auth", "Edited by B", "pending"));
    syncToMain(nodeA);
    var report = syncToMain(nodeB);
    assertEquals(1, report.report().conflicts());
    assertTrue(main.specs.findById("auth").isEmpty());
    assertEquals(List.of("<deleted>"), nodeB.conflicts.pending().getFirst().fields());
  }

  @Test
  void afterASeedOneEditMovesExactlyOneEntryAndTheReportSaysSo() throws Exception {
    for (var id : List.of("a", "b", "c")) {
      main.specs.create(spec(id, "Spec " + id, "pending"));
    }
    var seed = syncToMain(nodeA);
    assertEquals(3, seed.report().pulled());
    assertEquals(1, seed.pages());
    assertEquals(3, seed.entries());
    assertEquals(main.replica.maxSeq(), nodeA.syncState.checkpoint("main", "spec"));

    main.specs.update(spec("b", "Spec b, edited", "pending"));
    try (var link = connect(nodeA)) {
      var round = link.reconcile("spec", nodeA.replica);
      assertEquals(1, round.report().pulled());
      assertEquals(1, round.entries());
      assertEquals(1, round.pages());
      assertFalse(round.skipped());
      assertEquals(List.of("hello", "heads", "pull"), link.ops());
    }
    assertEquals("Spec b, edited", nodeA.specs.findById("b").orElseThrow().title());
    assertEquals(main.replica.maxSeq(), nodeA.syncState.checkpoint("main", "spec"));
  }

  @Test
  void theCheckpointAdvancesOnlyToWhatTheNodeHasSeenAndAnIdleRoundIsOneHeadsExchange()
      throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    try (var link = connect(nodeA)) {
      var first = link.reconcile("spec", nodeA.replica);
      assertEquals(1, first.report().pushed());
      assertEquals(0, first.pages());
      assertEquals(
          List.of("hello", "heads", "need", "announce", "manifest", "done", "done", "push"),
          link.ops());
    }
    assertEquals(0L, nodeA.syncState.checkpoint("main", "spec"), "an own push is not a seen entry");

    try (var link = connect(nodeA)) {
      var second = link.reconcile("spec", nodeA.replica);
      assertEquals(0, second.report().total());
      assertEquals(1, second.entries(), "the node reads its own change back and converges");
      assertFalse(second.skipped());
    }
    assertEquals(main.replica.maxSeq(), nodeA.syncState.checkpoint("main", "spec"));

    try (var link = connect(nodeA)) {
      var idle = link.reconcile("spec", nodeA.replica);
      assertTrue(idle.skipped());
      assertEquals(0, idle.report().total());
      assertTrue(link.session().fetchFdes().isEmpty());
      assertEquals(List.of("hello", "heads", "fetch-fdes"), link.ops());
      assertEquals(0, link.count("pull"));
      assertEquals(0, link.count("need"));
    }
    assertEquals(1, syncRows(nodeA));
  }

  @Test
  void aRoundKilledAfterPageTwoOfSixResumesAtPageThreeWithNothingReAdopted() throws Exception {
    for (var id : List.of("s1", "s2", "s3", "s4", "s5", "s6")) {
      bigSpec(main, id);
    }
    var pages = new AtomicInteger();
    UnaryOperator<OutputStream> killAfterTwoPages =
        out ->
            new java.io.FilterOutputStream(out) {
              @Override
              public void write(byte[] buffer, int offset, int length) throws IOException {
                if (new String(buffer, offset, length).contains("\"op\": \"page\"")
                    && pages.incrementAndGet() > 2) {
                  throw new IOException("channel cut");
                }
                out.write(buffer, offset, length);
              }

              @Override
              public void flush() throws IOException {
                out.flush();
              }

              @Override
              public void close() throws IOException {
                out.close();
              }
            };
    var link = connect(nodeA, SMALL_FRAME, killAfterTwoPages);
    var failure = assertThrows(RuntimeException.class, () -> link.reconcile("spec", nodeA.replica));
    assertTrue(
        failure instanceof SyncTransportException
            || failure instanceof java.io.UncheckedIOException,
        failure.toString());
    try {
      link.close();
    } catch (RuntimeException ignored) {
      link.server().join();
    }
    assertEquals(2, nodeA.replica.entityIds().size(), "pages one and two are adopted");
    assertEquals(2, syncRows(nodeA));
    var checkpoint = nodeA.syncState.checkpoint("main", "spec");
    assertEquals(
        nodeA
                .db
                .queryOne(
                    "SELECT MAX(seq) FROM change_heads WHERE entity_type = 'spec'",
                    r -> r.integer(0))
                .orElseThrow()
            > 0,
        true);
    assertTrue(checkpoint > 0 && checkpoint < main.replica.maxSeq());

    try (var resumed = connect(nodeA, SMALL_FRAME, out -> out)) {
      var round = resumed.reconcile("spec", nodeA.replica);
      assertEquals(4, round.report().pulled());
      assertEquals(4, round.pages());
      assertEquals(4, round.entries());
    }
    assertEquals(6, nodeA.replica.entityIds().size());
    assertEquals(6, syncRows(nodeA), "no entry was adopted twice");
    assertEquals(main.replica.maxSeq(), nodeA.syncState.checkpoint("main", "spec"));
  }

  @Test
  void aLocalEditAbsentFromEveryPageIsPushedThroughNeed() throws Exception {
    main.specs.create(spec("x", "X", "pending"));
    main.specs.create(spec("y", "Y", "pending"));
    syncToMain(nodeA);
    main.specs.update(spec("x", "X from main", "pending"));
    nodeA.specs.update(spec("y", "Y from A", "pending"));
    try (var link = connect(nodeA)) {
      var round = link.reconcile("spec", nodeA.replica);
      assertEquals(1, round.report().pulled());
      assertEquals(1, round.report().pushed());
      assertEquals(1, round.entries(), "the page carried only main's edit");
      assertEquals(List.of("hello", "heads", "pull", "need", "announce", "push"), link.ops());
    }
    assertEquals("Y from A", main.specs.findById("y").orElseThrow().title());
    assertEquals("X from main", nodeA.specs.findById("x").orElseThrow().title());
  }

  private UnaryOperator<OutputStream> afterTheFirstPage(Runnable action) {
    var done = new AtomicInteger();
    return out ->
        new java.io.FilterOutputStream(out) {
          @Override
          public void write(byte[] buffer, int offset, int length) throws IOException {
            if (new String(buffer, offset, length).contains("\"op\": \"page\"")
                && done.getAndIncrement() == 0) {
              action.run();
            }
            out.write(buffer, offset, length);
          }

          @Override
          public void flush() throws IOException {
            out.flush();
          }

          @Override
          public void close() throws IOException {
            out.close();
          }
        };
  }

  @Test
  void aLocalEditRacingADisjointMainEditMergesThroughTheRejectionPath() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);
    nodeA.specs.update(spec("auth", "Title from A", "pending"));
    main.specs.update(spec("auth", "Auth", "pending"));
    var engine = new SyncEngine();
    Runnable bLandsFirst =
        () -> {
          nodeB.specs.updateStatus("auth", SpecStatus.fromWire("in_progress"));
          engine.reconcile(nodeB.replica, main.replica);
        };
    try (var link =
        connect(nodeA, SyncWire.MAX_FRAME, afterTheFirstPage(Actor.carrying(bLandsFirst)))) {
      var round = link.reconcile("spec", nodeA.replica);
      assertEquals(1, round.report().merged());
      assertEquals(2, link.count("push"), "the stale push is rejected and the merge pushed again");
      assertEquals(1, link.count("need"), "main's version arrives through the bounded need path");
    }
    var merged = main.specs.findById("auth").orElseThrow();
    assertEquals("Title from A", merged.title());
    assertEquals("in_progress", merged.status().wire());
    assertEquals("in_progress", nodeA.specs.findById("auth").orElseThrow().status().wire());
    assertTrue(nodeA.conflicts.pending().isEmpty());
  }

  @Test
  void aLocalEditRacingTheSameFieldOnMainParksAConflictAndKeepsLocalWork() throws Exception {
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    syncToMain(nodeA);
    syncToMain(nodeB);
    nodeA.specs.update(spec("auth", "Title from A", "pending"));
    main.specs.update(spec("auth", "Auth", "pending"));
    var engine = new SyncEngine();
    Runnable bLandsFirst =
        () -> {
          nodeB.specs.update(spec("auth", "Title from B", "pending"));
          engine.reconcile(nodeB.replica, main.replica);
        };
    try (var link =
        connect(nodeA, SyncWire.MAX_FRAME, afterTheFirstPage(Actor.carrying(bLandsFirst)))) {
      assertEquals(1, link.reconcile("spec", nodeA.replica).report().conflicts());
    }
    assertEquals("Title from B", main.specs.findById("auth").orElseThrow().title());
    assertEquals(
        List.of("title"), nodeA.conflicts.pendingFor("spec", "auth").orElseThrow().fields());
    assertEquals("Title from A", nodeA.specs.findById("auth").orElseThrow().title());
  }

  @Test
  void needBatchesCountUtf8BytesAndEscapeCharacters() throws Exception {
    var path = String.join("/", java.util.Collections.nCopies(6, "界".repeat(40)));
    var files = new FileStore(nodeA.db);
    for (var i = 0; i < 3; i++)
      files.put("proj", path + i, new java.io.ByteArrayInputStream(new byte[] {1}), 0644);
    try (var link = connect(nodeA, SMALL_FRAME, out -> out)) {
      var session = ((PagedSyncSession) link.session()).frame(SMALL_FRAME);
      assertEquals(
          3,
          SyncBox.reconcile(
                  session,
                  "file",
                  SyncedEntities.replicas(nodeA.db, nodeA.id, nodeA.id).get("file"))
              .report()
              .pushed());
      assertEquals(3, link.count("need"));
    }
  }

  @Test
  void aPushIsSplitIntoBatchesAtTheFrameBound() throws Exception {
    for (var id : List.of("p1", "p2", "p3", "p4")) {
      bigSpec(nodeA, id);
    }
    try (var link = connect(nodeA)) {
      var paged = ((PagedSyncSession) link.session()).frame(SMALL_FRAME);
      var round = SyncBox.reconcile(paged, "spec", nodeA.replica);
      assertEquals(4, round.report().pushed());
      assertEquals(4, link.count("push"));
      assertEquals(1, link.count("need"));
    }
    assertEquals(4, main.replica.entityIds().size());
  }

  @Test
  void anEntryNeitherSideCanFrameFailsTheTypeNamingIt() throws Exception {
    bigSpec(main, "toobig");
    try (var link = connect(nodeA, 700, out -> out)) {
      var failure =
          assertThrows(SyncTransportException.class, () -> link.reconcile("spec", nodeA.replica));
      assertEquals("protocol", failure.kind());
      assertTrue(failure.getMessage().contains("toobig"), failure.getMessage());
      assertTrue(failure.getMessage().contains("bytes"), failure.getMessage());
    }
    bigSpec(nodeB, "mine");
    try (var link = connect(nodeB)) {
      var paged = ((PagedSyncSession) link.session()).frame(700);
      var failure =
          assertThrows(
              SyncTransportException.class, () -> SyncBox.reconcile(paged, "spec", nodeB.replica));
      assertEquals("protocol", failure.kind());
      assertTrue(failure.getMessage().startsWith("spec mine:"), failure.getMessage());
    }
    assertTrue(main.specs.findById("mine").isEmpty());
  }

  @Test
  void aReadOnlyFdeMayPullButItsPushIsDenied() throws Exception {
    main.specs.create(spec("board", "Shared", "pending"));
    var readOnly = main.server(Actor.sync("A", Role.VIEWER));
    try (var link = SyncBox.connect(readOnly, nodeA)) {
      assertEquals(1, link.reconcile("spec", nodeA.replica).report().pulled());
    }
    assertEquals("Shared", nodeA.specs.findById("board").orElseThrow().title());
    nodeA.specs.create(spec("mine", "Local only", "pending"));
    try (var link = SyncBox.connect(main.server(Actor.sync("A", Role.VIEWER)), nodeA)) {
      var report = link.reconcile("spec", nodeA.replica);
      assertNull(report.failure());
      assertEquals("mine", report.denials().getFirst().id());
    }
    assertTrue(main.specs.findById("mine").isEmpty(), "the read-only push never reached main");
  }

  @Test
  void specsAndFilesReconcileOverTheWireInOneSessionAndTheRosterComesAlong() throws Exception {
    var mainFiles = new FileStore(main.db);
    var nodeFiles = new FileStore(nodeA.db);
    var nodeFileReplica =
        new StoreReplica(
            "A", nodeFiles, new ChangeLog(nodeA.db), nodeA.conflicts, new SyncState(nodeA.db));
    nodeA.specs.create(spec("auth", "Auth", "pending"));
    ai.singlr.sail.store.ContentFixtures.put(nodeFiles, "acme", "scripts/deploy.sh", "ZGVwbG95");
    var roster = List.<Map<String, Object>>of(Map.of("handle", "ada", "role", "admin"));
    var server =
        SyncRpcServer.over(
            main.db,
            "main",
            Actor.sync("A", Role.MEMBER),
            () -> roster,
            SyncTransitionSink.NONE,
            SyncWire.UPGRADE_FLOOR);
    try (var link = SyncBox.connect(server, nodeA)) {
      assertEquals(1, link.reconcile("spec", nodeA.replica).report().pushed());
      assertEquals(1, link.reconcile("file", nodeFileReplica).report().pushed());
      assertEquals("ada", link.session().fetchFdes().getFirst().get("handle"));
      assertEquals(1, link.count("heads"), "tips are read once per session");
    }
    assertEquals("Auth", main.specs.findById("auth").orElseThrow().title());
    assertEquals(
        "ZGVwbG95",
        ai.singlr.sail.store.ContentFixtures.text(mainFiles, "acme", "scripts/deploy.sh"));
    assertEquals(0L, new SyncState(nodeA.db).checkpoint("main", "file"));
  }
}
