/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Decidability;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncLimits;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Liveness audit (L1-L3, L6): every offer a legitimate node makes settles in bounded rounds. */
class LivenessAuditTest {

  private static final Actor ADA = Actor.sync("ada", Role.MEMBER);

  @TempDir Path dir;
  private SyncBox main;
  private SyncBox ada;

  @BeforeEach
  void setUp() {
    main = new SyncBox("main");
    ada = new SyncBox("ada");
  }

  @AfterEach
  void tearDown() {
    ada.close();
    main.close();
  }

  /** One full round of {@code box} as {@code as}: the types' reports, with every failure. */
  private List<SyncSession.TypeReport> round(SyncBox box, Actor as) {
    return SyncBox.round(main, box.syncsAs(as));
  }

  private static void assertNothingFails(List<SyncSession.TypeReport> reports, String why) {
    for (var report : reports) {
      assertNull(report.failure(), why + ": " + report);
    }
  }

  /** The scenario settles within {@code rounds}: a clean round, every box equal to main. */
  private void assertConvergedWithin(int rounds, SyncBox box, Actor as) {
    SyncBox.assertConvergedWithin(rounds, main, box.syncsAs(as));
  }

  private SyncSession.TypeReport only(SyncBox box, Actor as, String type) throws IOException {
    var replicas = SyncedEntities.replicas(box.db, box.id, box.id);
    try (var link = SyncBox.connect(main.server(as), box)) {
      Actor.run(Actor.main(), () -> NodeRound.begin(link.session(), box.db, box.id));
      return link.reconcile(type, replicas.get(type));
    }
  }

  private static SpecStore.SpecRow spec(String id, String assignee) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        "Spec " + id,
        SpecStatus.PENDING,
        assignee,
        null,
        null,
        null,
        null,
        0,
        null,
        "",
        "",
        null,
        List.of(),
        List.of());
  }

  private static void ownSpec(SyncBox box, String as, String id, String assignee) {
    Acting.as(
        as,
        () -> {
          box.specs.create(spec(id, assignee));
          new RoomStore(box.db)
              .create(
                  new RoomStore.RoomRow(
                      id, "acme", "Spec " + id, assignee, null, null, null, null, null, null));
        });
  }

  private static void room(SyncBox box, String as, String id) {
    Acting.as(
        as,
        () ->
            new RoomStore(box.db)
                .create(
                    new RoomStore.RoomRow(id, "acme", id, as, null, null, null, null, null, null)));
  }

  private static String run(SyncBox box, String fde, String specId) {
    var id = DateTimeUtils.newId().toString();
    Acting.as(
        fde,
        () ->
            new RunStore(box.db)
                .create(
                    id,
                    "acme",
                    specId,
                    fde,
                    "build",
                    "claude-code",
                    "b",
                    "t",
                    null,
                    null,
                    "/log",
                    "unit"));
    return id;
  }

  private static String principal(SyncBox box, String runId) {
    return new RunStore(box.db).findById(runId).orElseThrow().principal();
  }

  /** L1/L2: a post in a room this box created and deleted before its first sync. */
  @Test
  void aPostInARoomDeletedBeforeItsFirstSyncSettles() throws IOException {
    room(ada, "ada", "lab");
    Acting.as("ada", () -> new MessageStore(ada.db).append("lab", "ada", "hello", null));
    Acting.as("ada", () -> new RoomStore(ada.db).delete("lab"));

    assertConvergedWithin(3, ada, ADA);
  }

  /** L1/L2: a comment on a spec this box created and deleted (with its room) before syncing. */
  @Test
  void aCommentOnASpecDeletedBeforeItsFirstSyncSettles() throws IOException {
    ownSpec(ada, "ada", "oops", "ada");
    Acting.as("ada", () -> new MessageStore(ada.db).append("oops", "ada", "a note", null));
    Acting.as(
        "ada",
        () -> {
          ada.specs.delete("oops");
          new RoomStore(ada.db).delete("oops");
        });

    assertConvergedWithin(3, ada, ADA);
  }

  /** L1/L2: a member demoted to viewer while offline: its new room is denied, its post is not. */
  @Test
  void aPostInANewRoomOfAMemberDemotedWhileOfflineSettles() throws IOException {
    room(ada, "ada", "lab");
    Acting.as("ada", () -> new MessageStore(ada.db).append("lab", "ada", "hello", null));

    assertConvergedWithin(3, ada, Actor.sync("ada", Role.VIEWER));
  }

  /**
   * L3: an agent's spec, room, review and file revisions land in the round its run does, since runs
   * sync first; none is offered before its run and none fails the round.
   */
  @Test
  void anAgentsRevisionsOfEveryTypeLandInTheRoundItsRunDoes() {
    ownSpec(main, "ada", "mine", "ada");
    round(ada, ADA);
    var runId = run(ada, "ada", "mine");
    var agent = Actor.agentPrincipal(principal(ada, runId), "ada");
    Acting.by(agent, () -> ada.specs.create(spec("born", null)));
    Acting.by(
        agent,
        () ->
            new RoomStore(ada.db)
                .create(
                    new RoomStore.RoomRow(
                        "den", "acme", "den", "ada", null, null, null, null, null, null)));
    Acting.by(
        agent,
        () ->
            new FileStore(ada.db)
                .put("acme", "notes.txt", new ByteArrayInputStream("notes".getBytes()), 0644));
    var review = Acting.by(agent, () -> new ReviewStore(ada.db).createReview("mine", 1));

    var reports = round(ada, ADA);

    assertNothingFails(reports, "L3: nothing fails; everything waits for its run");
    assertTrue(main.specs.findById("born").isPresent(), "the spec lands with its run");
    assertTrue(new RoomStore(main.db).findById("den").isPresent(), "the room too");
    assertTrue(new FileStore(main.db).find("acme", "notes.txt").isPresent(), "the file too");
    assertTrue(new ReviewStore(main.db).findReview(review).isPresent(), "the review too");
    assertConvergedWithin(1, ada, ADA);
  }

  /** L3: a spec born in a room main has not taken is withheld, not offered and refused. */
  @Test
  void aSpecBornInAnUnsyncedRoomIsWithheldUntilItsRoomIsOnMain() throws IOException {
    room(ada, "ada", "lab");
    Acting.as("ada", () -> ada.specs.create(spec("child", "ada").withRoomId("lab")));

    var specs = only(ada, ADA, "spec");

    assertEquals(0, specs.report().pushed(), "withheld, not pushed");
    assertEquals(List.of(), specs.refusals(), "and never refused");
    assertTrue(ada.specs.findById("child").isPresent());
    assertConvergedWithin(3, ada, ADA);
    assertEquals("lab", main.specs.findById("child").orElseThrow().roomId());
  }

  /**
   * L6: a message batch where one offer is refused — a post in a room main does not hold yet, which
   * the node offers because it holds the room live — settles the offers beside it in that round,
   * and the next round leaves the accepted one at its one revision, with no conflict parked.
   */
  @Test
  void anOfferAcceptedInARefusedBatchConvergesWithoutConflictOrSecondRevision() throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    round(ada, ADA);
    room(ada, "ada", "ghost");
    var lost =
        Acting.as("ada", () -> new MessageStore(ada.db).append("ghost", "ada", "lost", null));
    var kept = Acting.as("ada", () -> new MessageStore(ada.db).append("mine", "ada", "kept", null));

    only(ada, ADA, "message");

    assertTrue(
        new MessageStore(main.db).findById(kept.id()).isPresent(), "the accepted offer lands");
    assertTrue(new MessageStore(main.db).findById(lost.id()).isEmpty(), "the refused one does not");

    only(ada, ADA, "message");
    var changes = new ChangeLog(main.db);
    assertEquals(1, changes.history("message", kept.id()).size(), "no second revision on main");
    assertEquals(List.of(), ada.conflicts.pendingIds("message"), "no conflict parked");

    assertConvergedWithin(3, ada, ADA);
    assertTrue(
        new MessageStore(main.db).findById(lost.id()).isPresent(), "the room lands, then it");
  }

  /** L1: a shared file main's limits.file_max refuses; and the types after it in the session. */
  @Test
  void aFileAboveMainsFileMaxSettlesAndNeverTakesTheLaterTypesDown() throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    round(ada, ADA);
    Acting.as("ada", () -> new MessageStore(ada.db).append("mine", "ada", "hello", null));
    Acting.as(
        "ada",
        () ->
            new FileStore(ada.db)
                .put("acme", "big.txt", new ByteArrayInputStream("0123456789".getBytes()), 0644));
    main.limits(new FileLimits(4));

    var first = round(ada, ADA);

    assertNothingFails(first, "a refused upload never fails later types");
    assertConvergedWithin(2, ada, ADA);
    assertTrue(new FileStore(ada.db).find("acme", "big.txt").isEmpty(), "withdrawn once");
  }

  /**
   * L1: a spec created in a project main erased is denied gone, and goes with the project's erasure
   * the round pages right after, so the round that hears gone leaves nothing behind.
   */
  @Test
  void aNewSpecInAProjectMainErasedSettlesInTheRoundThatHearsIt() {
    ownSpec(main, "ada", "old", "ada");
    round(ada, ADA);
    Acting.as("ada", () -> ada.specs.create(spec("fresh", "ada")));
    eraseProject();

    var reports = round(ada, ADA);

    assertNothingFails(reports, "a gone answer never fails the type");
    assertEquals(
        List.of("fresh"),
        reports.stream().flatMap(r -> r.denials().stream()).map(SyncSession.Denial::id).toList());
    assertTrue(ada.specs.findById("fresh").isEmpty(), "gone with its project, in that round");
    assertConvergedWithin(1, ada, ADA);
  }

  /** L1: that same spec never fails the round, before or after its project's erasure pages. */
  @Test
  void aNewSpecInAProjectMainErasedNeverFailsTheRound() throws IOException {
    ownSpec(main, "ada", "old", "ada");
    round(ada, ADA);
    Acting.as("ada", () -> ada.specs.create(spec("fresh", "ada")));
    eraseProject();

    assertNothingFails(round(ada, ADA), "L3: nothing fails");
    assertConvergedWithin(2, ada, ADA);
  }

  /** L1: a spec born in the room of a spec deleted, with that room, before either synced. */
  @Test
  void aSpecBornInTheRoomOfASpecDeletedBeforeItsFirstSyncSettles() throws IOException {
    ownSpec(ada, "ada", "parent", "ada");
    Acting.as("ada", () -> ada.specs.create(spec("child", "ada").withRoomId("parent")));
    Acting.as(
        "ada",
        () -> {
          ada.specs.delete("parent");
          new RoomStore(ada.db).delete("parent");
        });

    assertConvergedWithin(3, ada, ADA);
  }

  /** L2/L3: a finished review of a spec held back for its new room is denied, not held back. */
  @Test
  void aReviewOfASpecWaitingForItsRoomWaitsWithItAndLands() throws IOException {
    room(ada, "ada", "lab");
    Acting.as("ada", () -> ada.specs.create(spec("child", "ada").withRoomId("lab")));
    var reviews = new ReviewStore(ada.db);
    var review =
        Acting.system(
            () -> {
              var id = reviews.createReview("child", 1);
              reviews.updateReviewStatus(id, "failed");
              return id;
            });

    assertConvergedWithin(3, ada, ADA);
    assertTrue(
        reviews.findReview(review).isPresent(),
        "the node keeps its review; main held: "
            + new ReviewStore(main.db).findReview(review).isPresent());
    assertTrue(
        new ReviewStore(main.db).findReview(review).isPresent(),
        "main takes the review once its spec lands");
  }

  /** A spec an agent authored, whose run main denied, is withdrawn with its history kept. */
  @Test
  void aSpecOfADeniedRunIsWithdrawnButKeepsItsHistory() throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    round(ada, ADA);
    var run = run(ada, "ada", "mine");
    var agent = Actor.agentPrincipal(principal(ada, run), "ada");
    Acting.by(agent, () -> ada.specs.create(spec("born", null)));
    Acting.by(agent, () -> ada.specs.updateStatus("born", SpecStatus.IN_PROGRESS));
    Acting.system(() -> new RunStore(ada.db).complete(run, "stopped", null));
    var before = new ChangeLog(ada.db).history("spec", "born").size();

    assertConvergedWithin(3, ada, Actor.sync("ada", Role.VIEWER));

    assertTrue(ada.specs.findById("born").isEmpty(), "the spec is withdrawn");
    assertTrue(
        new ChangeLog(ada.db).history("spec", "born").size() > before,
        "its history stays, with the withdrawal on top");
  }

  /** A born-in spec of a denied run is withdrawn, never re-homed into a publishable offer. */
  @Test
  void aSpecOfADeniedRunBornInALiveRoomIsWithdrawnNotReHomed() throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    room(main, "ada", "lab");
    round(ada, ADA);
    var run = run(ada, "ada", "mine");
    ada.db.execute("INSERT INTO run_principals (run_id, principal) VALUES (?, 'bob')", run);
    var agent = Actor.agentPrincipal(principal(ada, run), "ada");
    Acting.by(agent, () -> ada.specs.create(spec("born", null).withRoomId("lab")));
    Acting.system(() -> new RunStore(ada.db).complete(run, "stopped", null));

    assertConvergedWithin(3, ada, ADA);

    assertTrue(new RunStore(main.db).findById(run).isEmpty(), "main denied the run");

    assertTrue(ada.specs.findById("born").isEmpty(), "withdrawn here");
    assertTrue(main.specs.findById("born").isEmpty(), "never on main");
    assertTrue(new RoomStore(main.db).findById("lab").isPresent(), "its room stays live");
  }

  /**
   * A born-in spec of a denied run whose room is gone here is withdrawn too: re-homing it keeps its
   * author, so the run it still names decides it, never the box's own machinery.
   */
  @Test
  void aSpecOfADeniedRunBornInARoomDeletedHereIsWithdrawnNotRepublished() throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    room(main, "ada", "lab");
    round(ada, ADA);
    var run = run(ada, "ada", "mine");
    ada.db.execute("INSERT INTO run_principals (run_id, principal) VALUES (?, 'bob')", run);
    var agent = Actor.agentPrincipal(principal(ada, run), "ada");
    Acting.by(agent, () -> ada.specs.create(spec("born", null).withRoomId("lab")));
    Acting.system(() -> new RunStore(ada.db).complete(run, "stopped", null));
    Acting.as("ada", () -> new RoomStore(ada.db).delete("lab"));
    var before = new ChangeLog(ada.db).history("spec", "born").size();

    assertConvergedWithin(3, ada, ADA);

    assertTrue(new RunStore(main.db).findById(run).isEmpty(), "main denied the run");
    assertTrue(ada.specs.findById("born").isEmpty(), "withdrawn here");
    assertTrue(main.specs.findById("born").isEmpty(), "never on main");
    assertTrue(
        new ChangeLog(ada.db).history("spec", "born").size() > before,
        "its history stays, with the withdrawal on top");
  }

  /**
   * Two unpublished specs born in one room main denies never anchor each other: an unacknowledged
   * sibling is no evidence main holds the room, so each is re-homed and decided on its own.
   */
  @Test
  void twoUnpublishedSpecsBornInADeniedRoomSettle() throws IOException {
    room(ada, "ada", "lab");
    Acting.as("ada", () -> ada.specs.create(spec("first", "ada").withRoomId("lab")));
    Acting.as("ada", () -> ada.specs.create(spec("second", "ada").withRoomId("lab")));

    assertConvergedWithin(5, ada, Actor.sync("ada", Role.VIEWER));

    assertTrue(new RoomStore(ada.db).findById("lab").isEmpty(), "the room was denied");
    assertTrue(ada.specs.findById("first").isEmpty(), "the first spec settled");
    assertTrue(ada.specs.findById("second").isEmpty(), "the second spec settled");
  }

  /** A born-in spec whose room was deleted here is re-homed and lands under its own author. */
  @Test
  void aSpecBornInARoomDeletedHereLandsReHomedUnderItsAuthor() throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    room(main, "ada", "lab");
    round(ada, ADA);
    var run = run(ada, "ada", "mine");
    var agent = Actor.agentPrincipal(principal(ada, run), "ada");
    Acting.by(agent, () -> ada.specs.create(spec("born", null).withRoomId("lab")));
    Acting.system(() -> new RunStore(ada.db).complete(run, "stopped", null));
    Acting.as("ada", () -> new RoomStore(ada.db).delete("lab"));

    assertConvergedWithin(3, ada, ADA);

    var landed = main.specs.findById("born").orElseThrow();
    assertEquals("born", landed.roomId(), "re-homed into its own identity room");
    assertEquals(agent.handle(), landed.updatedBy(), "under its author, not this box");
  }

  /**
   * A review this box is still running is never withdrawn under its pipeline, even once its spec is
   * denied; it settles the round after it finishes.
   */
  @Test
  void aRunningReviewOfADeniedSpecIsKeptUntilItFinishes() throws IOException {
    Acting.as("ada", () -> ada.specs.create(spec("child", "ada")));
    var reviews = new ReviewStore(ada.db);
    var review = Acting.system(() -> reviews.createReview("child", 1));
    Acting.system(() -> reviews.updateReviewStatus(review, "running"));
    Acting.as(
        "ada",
        () ->
            new RunStore(ada.db)
                .create(
                    review,
                    "acme",
                    "child",
                    "ada",
                    "review",
                    "claude-code",
                    "b",
                    "t",
                    null,
                    null,
                    "/log",
                    "unit"));
    var viewer = Actor.sync("ada", Role.VIEWER);

    round(ada, viewer);
    round(ada, viewer);

    assertTrue(ada.specs.findById("child").isEmpty(), "main denied the spec");
    assertTrue(
        reviews.findReview(review).isPresent(), "the running review is kept for its pipeline");

    Acting.system(() -> new RunStore(ada.db).complete(review, "stopped", null));
    Acting.system(() -> reviews.updateReviewStatus(review, "failed"));

    assertConvergedWithin(3, ada, viewer);

    assertTrue(reviews.findReview(review).isEmpty(), "withdrawn once it finished");
  }

  /** A ceiling main lowered reverts an oversized edit to the version main holds, never deletes. */
  @Test
  void anOversizedEditOfASyncedFileRevertsToMainsVersionUnderALoweredCeiling() throws IOException {
    var files = new FileStore(ada.db);
    Acting.as(
        "ada", () -> files.put("acme", "a.txt", new ByteArrayInputStream("hi".getBytes()), 0644));
    main.limits(new FileLimits(16));
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    Acting.as(
        "ada",
        () -> files.put("acme", "a.txt", new ByteArrayInputStream("123456789".getBytes()), 0644));
    main.limits(new FileLimits(4));

    assertConvergedWithin(3, ada, ADA);

    assertEquals(2, new FileStore(main.db).find("acme", "a.txt").orElseThrow().size());
    assertEquals(2, files.find("acme", "a.txt").orElseThrow().size(), "reverted, not deleted");
  }

  /**
   * An upload that replaces an oversized offer while the round is settling it waits its turn: what
   * the round settles is what it judged, so a replacement under the ceiling is kept and lands.
   */
  @Test
  void aReplacementUnderTheCeilingUploadedDuringSettlementIsKept() throws Exception {
    try (var node = new SyncBox(dir, "node");
        var uploadDb = Sqlite.open(dir.resolve("node.db"));
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var files = new FileStore(node.db);
      Acting.as(
          "node",
          () -> files.put("acme", "a.txt", new ByteArrayInputStream("123456789".getBytes()), 0644));
      new SyncLimits(node.db).recordMainFileMax(4);
      var replaced = new CountDownLatch(1);
      var commit = new CountDownLatch(1);
      var upload =
          executor.submit(
              () ->
                  Acting.as(
                      "node",
                      () ->
                          uploadDb.transaction(
                              () -> {
                                new FileStore(uploadDb)
                                    .put(
                                        "acme",
                                        "a.txt",
                                        new ByteArrayInputStream("hi".getBytes()),
                                        0644);
                                replaced.countDown();
                                try {
                                  commit.await();
                                } catch (InterruptedException e) {
                                  Thread.currentThread().interrupt();
                                  throw new IllegalStateException(e);
                                }
                                return null;
                              })));
      assertTrue(replaced.await(5, TimeUnit.SECONDS));
      var settlement = new Settlement(node.db, Decidability.onNode(node.db), "node");
      var settling = executor.submit(settlement::settle);
      commit.countDown();
      upload.get(5, TimeUnit.SECONDS);

      assertEquals(List.of(), settling.get(5, TimeUnit.SECONDS));
      assertEquals(2, files.find("acme", "a.txt").orElseThrow().size(), "the replacement stays");
      main.limits(new FileLimits(4));
      assertConvergedWithin(2, node, Actor.sync("node", Role.MEMBER));
      assertEquals(2, new FileStore(main.db).find("acme", "a.txt").orElseThrow().size());
    }
  }

  /** A reply to a narrator post main accepted under another FDE's run lands as the replier. */
  @Test
  void aReplyToAnAcceptedNarratorPostOfAnotherFdesRunLands() throws IOException {
    try (var bob = new SyncBox("bob")) {
      var bobSyncs = Actor.sync("bob", Role.MEMBER);
      ownSpec(main, "bob", "mine", "bob");
      round(bob, bobSyncs);
      var run = run(bob, "bob", "mine");
      var verdict =
          Acting.system(
              () -> new MessageStore(bob.db).append("mine", MessageStore.SAIL_AUTHOR, "ok", null));
      Acting.system(() -> new RunStore(bob.db).complete(run, "stopped", null));
      assertConvergedWithin(3, bob, bobSyncs);
      var adaIsAdmin = Actor.sync("ada", Role.ADMIN);
      round(ada, adaIsAdmin);
      var reply =
          Acting.as(
              "ada", () -> new MessageStore(ada.db).append("mine", "ada", "thanks", verdict.id()));

      assertConvergedWithin(3, ada, adaIsAdmin);

      assertTrue(new MessageStore(main.db).findById(reply.id()).isPresent(), "the reply lands");
    }
  }

  /** A narrator post whose only supporting run main denied is withdrawn, not offered forever. */
  @Test
  void aNarratorPostOfADeniedRunSettles() throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    round(ada, ADA);
    var run = run(ada, "ada", "mine");
    var post =
        Acting.system(
            () -> new MessageStore(ada.db).append("mine", MessageStore.SAIL_AUTHOR, "done", null));
    Acting.system(() -> new RunStore(ada.db).complete(run, "stopped", null));

    assertConvergedWithin(3, ada, Actor.sync("ada", Role.VIEWER));

    assertTrue(new MessageStore(ada.db).findById(post.id()).isEmpty(), "withdrawn here");
    assertTrue(new MessageStore(main.db).findById(post.id()).isEmpty(), "never on main");
  }

  /** L1/C4: a reply is never lost because its parent's commit threw once beside it. */
  @Test
  void aReplyWhoseParentsCommitThrowsOnceLandsWithItsParent() {
    ownSpec(main, "ada", "mine", "ada");
    round(ada, ADA);
    var messages = new MessageStore(ada.db);
    var parent = Acting.as("ada", () -> messages.append("mine", "ada", "parent", null));
    var reply = Acting.as("ada", () -> messages.append("mine", "ada", "reply", parent.id()));

    var first =
        SyncBox.round(
            main.serverFailingCommitsOf(ADA, "message", parent.id(), new AtomicInteger(1)), ada);

    assertNothingFails(first, "a throwing commit is that offer's refusal");
    assertTrue(messages.findById(reply.id()).isPresent(), "the reply waits with its parent");
    assertConvergedWithin(2, ada, ADA);
    assertTrue(new MessageStore(main.db).findById(reply.id()).isPresent(), "the reply lands");
  }

  /**
   * L1: a commit that throws refuses that offer alone, the offers beside it land, and it settles.
   */
  @Test
  void anOfferWhoseCommitThrowsIsRefusedAloneAndLandsOnceTheFaultIsGone() {
    ownSpec(main, "ada", "mine", "ada");
    round(ada, ADA);
    Acting.as("ada", () -> ada.specs.create(spec("bad", "ada")));
    Acting.as("ada", () -> ada.specs.create(spec("good", "ada")));
    var faults = new AtomicInteger(1);

    var reports = SyncBox.round(main.serverFailingCommitsOf(ADA, "spec", "bad", faults), ada);

    assertNothingFails(reports, "one offer's fault never fails the type");
    var specs = reports.stream().filter(r -> r.type().equals("spec")).findFirst().orElseThrow();
    assertEquals(List.of("bad"), specs.refusals().stream().map(SyncSession.Refusal::id).toList());
    assertTrue(main.specs.findById("good").isPresent(), "the offer beside it lands");
    assertTrue(main.specs.findById("bad").isEmpty());
    assertConvergedWithin(2, ada, ADA);
    assertTrue(main.specs.findById("bad").isPresent(), "it lands once the fault is gone");
  }

  /** A spec main holds stays editable from a node after the room it was born in is deleted here. */
  @Test
  void anEditToASyncedSpecWhoseRoomWasDeletedHereLands() {
    room(ada, "ada", "lab");
    Acting.as("ada", () -> ada.specs.create(spec("child", "ada").withRoomId("lab")));
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    Acting.as("ada", () -> new RoomStore(ada.db).delete("lab"));
    Acting.as("ada", () -> ada.specs.updateStatus("child", SpecStatus.IN_PROGRESS));

    assertConvergedWithin(2, ada, ADA);

    assertEquals(SpecStatus.IN_PROGRESS, main.specs.findById("child").orElseThrow().status());
  }

  /** A spec main holds stays editable from a node after main deleted the room it was born in. */
  @Test
  void aSpecWhoseRoomMainDeletedStaysEditableOnTheNode() {
    room(main, "ada", "lab");
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    Acting.as("ada", () -> ada.specs.create(spec("child", "ada").withRoomId("lab")));
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    Acting.as("ada", () -> new RoomStore(main.db).delete("lab"));
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    Acting.as("ada", () -> ada.specs.updateStatus("child", SpecStatus.IN_PROGRESS));

    assertConvergedWithin(2, ada, ADA);

    assertEquals(SpecStatus.IN_PROGRESS, main.specs.findById("child").orElseThrow().status());
  }

  /** A review finishing after its spec was deleted here still reaches main. */
  @Test
  void aReviewFinishingAfterItsSpecWasDeletedHereLands() {
    ownSpec(main, "ada", "mine", "ada");
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    var reviews = new ReviewStore(ada.db);
    var review = Acting.system(() -> reviews.createReview("mine", 1));
    Acting.system(() -> reviews.updateReviewStatus(review, "running"));
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    Acting.as("ada", () -> ada.specs.delete("mine"));
    Acting.system(() -> reviews.updateReviewStatus(review, "failed"));

    assertConvergedWithin(2, ada, ADA);

    assertEquals("failed", new ReviewStore(main.db).findReview(review).orElseThrow().status());
  }

  /**
   * A post in a room this box deleted after it synced is main's to decide, never withdrawn here:
   * main, holding the room's deletion, denies it and says so, where the node used to drop it
   * silently.
   */
  @Test
  void aPostInARoomThisBoxDeletedAfterSyncIsMainsToDecide() {
    room(ada, "ada", "lab");
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    var post =
        Acting.as("ada", () -> new MessageStore(ada.db).append("lab", "ada", "last word", null));
    Acting.as("ada", () -> new RoomStore(ada.db).delete("lab"));

    var round = SyncBox.roundSettling(main, ada.syncsAs(ADA));

    assertEquals(List.of(), round.settled(), "the node settles nothing main can decide");
    var messages = round.types().stream().filter(r -> r.type().equals("message")).findFirst();
    assertEquals(
        List.of(post.id()),
        messages.orElseThrow().denials().stream().map(SyncSession.Denial::id).toList(),
        "main decides it, and says so");
    assertConvergedWithin(2, ada, ADA);
  }

  /** A born-in spec whose room main deleted before the node offered it is re-homed and lands. */
  @Test
  void aSpecBornInARoomMainDeletedIsReHomedAndLands() {
    room(main, "ada", "lab");
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    Acting.as("ada", () -> ada.specs.create(spec("born", "ada").withRoomId("lab")));
    Acting.as("ada", () -> new RoomStore(main.db).delete("lab"));

    var first = SyncBox.roundSettling(main, ada.syncsAs(ADA));

    assertEquals(1, first.settled().size(), "re-homed in the round that heard gone: " + first);
    assertTrue(first.settled().getFirst().describe().contains("re-homed"));
    assertConvergedWithin(2, ada, ADA);
    assertEquals("born", main.specs.findById("born").orElseThrow().roomId(), "re-homed");
    assertTrue(new RoomStore(main.db).findById("lab").isEmpty(), "the room stays deleted");
  }

  /**
   * A spec re-created over main's deletion, born in a room gone here, is main's to decide: its room
   * is fixed at birth, so main denies the move and every box settles on main's deletion.
   */
  @Test
  void aSpecReCreatedOverMainsDeletionInAnotherRoomIsDeniedAndSettles() {
    ownSpec(main, "ada", "mine", "ada");
    room(ada, "ada", "lab");
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    Acting.as("ada", () -> main.specs.delete("mine"));
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    Acting.as("ada", () -> new RoomStore(ada.db).delete("lab"));
    Acting.as("ada", () -> ada.specs.create(spec("mine", "ada").withRoomId("lab")));

    assertConvergedWithin(3, ada, ADA);

    assertTrue(main.specs.findById("mine").isEmpty(), "main keeps its deletion");
    assertTrue(ada.specs.findById("mine").isEmpty(), "and the node settles on it");
  }

  /** A file re-added over main's deletion above main's ceiling is withdrawn, never an error. */
  @Test
  void aFileReAddedAboveTheCeilingOverMainsDeletionIsWithdrawn() {
    var files = new FileStore(ada.db);
    Acting.as(
        "ada", () -> files.put("acme", "a.txt", new ByteArrayInputStream("hi".getBytes()), 0644));
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    Acting.as("ada", () -> new FileStore(main.db).delete("acme", "a.txt"));
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    main.limits(new FileLimits(4));
    Acting.as(
        "ada",
        () -> files.put("acme", "a.txt", new ByteArrayInputStream("123456789".getBytes()), 0644));

    assertConvergedWithin(2, ada, ADA);

    assertTrue(files.find("acme", "a.txt").isEmpty(), "withdrawn to main's deletion");
  }

  /** Settlement waits for an offer whose answer may merely be lost, which the round recovers. */
  @Test
  void aLostAcceptanceOfABornInSpecIsRecoveredBeforeItIsSettled() throws IOException {
    room(ada, "ada", "lab");
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    Acting.as("ada", () -> ada.specs.create(spec("kid", "ada").withRoomId("lab")));
    SyncBox.pushLosingTheAnswer(main, ada.syncsAs(ADA), "spec");
    assertTrue(main.specs.findById("kid").isPresent(), "main took the spec");
    Acting.as("ada", () -> new RoomStore(ada.db).delete("lab"));

    var round = SyncBox.roundSettling(main, ada.syncsAs(ADA));

    assertEquals(List.of(), round.settled(), "nothing settled over a lost answer");
    assertConvergedWithin(2, ada, ADA);
    assertEquals("lab", main.specs.findById("kid").orElseThrow().roomId(), "never re-homed");
  }

  /** What a round settles is announced, with where the work is kept. */
  @Test
  void aSettlementIsAnnounced() {
    room(ada, "ada", "lab");
    Acting.as("ada", () -> new MessageStore(ada.db).append("lab", "ada", "hello", null));
    Acting.as("ada", () -> new RoomStore(ada.db).delete("lab"));

    var round = SyncBox.roundSettling(main, ada.syncsAs(ADA));

    assertEquals(1, round.settled().size(), round.settled().toString());
    assertTrue(round.settled().getFirst().describe().contains("withdrawn"));
    assertConvergedWithin(1, ada, ADA);
  }

  private void eraseProject() {
    var erasure = new Erasure(main.db);
    Acting.as(
        "root",
        () ->
            erasure.erase(
                erasure.closure(List.of(new Erasure.Target(Erasure.PROJECT, "acme"))), "local"));
  }
}
