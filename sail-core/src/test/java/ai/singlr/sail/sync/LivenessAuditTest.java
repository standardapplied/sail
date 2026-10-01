/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Liveness audit (L1-L3, L6): every offer a legitimate node makes settles in bounded rounds. */
class LivenessAuditTest {

  private static final Actor ADA = Actor.sync("ada", Role.MEMBER);

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

  /** One session running every type in registry order, as SyncOperations does: type -> failure. */
  private Map<String, String> round(SyncBox box, SyncRpcServer server) throws IOException {
    var failures = new LinkedHashMap<String, String>();
    var replicas = SyncedEntities.replicas(box.db, box.id, box.id);
    try (var link = SyncBox.connect(server, box)) {
      Actor.run(Actor.main(), () -> NodeRound.begin(link.session(), box.db, box.id));
      for (var entity : SyncedEntities.all()) {
        try {
          link.reconcile(entity.type(), replicas.get(entity.type()));
        } catch (RuntimeException e) {
          failures.put(entity.type(), e.getMessage());
        }
      }
    }
    return failures;
  }

  private Map<String, String> round(SyncBox box, Actor as) throws IOException {
    return round(box, main.server(as));
  }

  private void assertSettlesWithin(int rounds, SyncBox box, Actor as) throws IOException {
    var history = new ArrayList<Map<String, String>>();
    for (var i = 0; i < rounds; i++) {
      var failures = round(box, as);
      history.add(failures);
      if (failures.isEmpty() && settled(box)) {
        return;
      }
    }
    throw new AssertionError("no settled round within " + rounds + " rounds: " + history);
  }

  /**
   * Whether {@code box} holds nothing back, has nothing left to settle and parks no conflict —
   * nothing left waiting.
   */
  private static boolean settled(SyncBox box) {
    var rule = new Decidability(box.db);
    for (var entity : SyncedEntities.all()) {
      if (!box.conflicts.pendingIds(entity.type()).isEmpty()) {
        return false;
      }
      for (var id : entity.store(box.db).dirtyIds()) {
        if (rule.forNode(entity.type(), id, box.id) != Decidability.Status.HELD) {
          return false;
        }
      }
    }
    return true;
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

    assertSettlesWithin(3, ada, ADA);
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

    assertSettlesWithin(3, ada, ADA);
  }

  /** L1/L2: a member demoted to viewer while offline: its new room is denied, its post is not. */
  @Test
  void aPostInANewRoomOfAMemberDemotedWhileOfflineSettles() throws IOException {
    room(ada, "ada", "lab");
    Acting.as("ada", () -> new MessageStore(ada.db).append("lab", "ada", "hello", null));

    assertSettlesWithin(3, ada, Actor.sync("ada", Role.VIEWER));
  }

  /** L3: an agent's spec revision is offered before its run reaches main and fails the round. */
  @Test
  void anAgentsSpecIsHeldBackUntilItsRunIsOnMainRatherThanFailingTheRound() throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    round(ada, ADA);
    var agent = Actor.agentPrincipal(principal(ada, run(ada, "ada", "mine")), "ada");
    Acting.by(agent, () -> ada.specs.create(spec("born", null)));

    assertEquals(Map.of(), round(ada, ADA), "L3: nothing fails; the spec waits for its run");
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

    assertSettlesWithin(3, ada, ADA);
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
    var server = main.server(ADA).content(main.db, new FileLimits(4));

    var first = round(ada, server);
    assertFalse(first.containsKey("message"), "a refused upload fails later types: " + first);
    for (var i = 0; i < 2; i++) {
      if (round(ada, main.server(ADA).content(main.db, new FileLimits(4))).isEmpty()) {
        return;
      }
    }
    throw new AssertionError("the file is refused every round: " + first);
  }

  /** L1 (bounded): a spec created in a project main erased settles once the erasure pages. */
  @Test
  void aNewSpecInAProjectMainErasedSettlesWithinTwoRounds() throws IOException {
    ownSpec(main, "ada", "old", "ada");
    round(ada, ADA);
    Acting.as("ada", () -> ada.specs.create(spec("fresh", "ada")));
    eraseProject();

    assertSettlesWithin(2, ada, ADA);
  }

  /** L3: that same spec is offered and refused before its project's erasure pages. */
  @Test
  void aNewSpecInAProjectMainErasedIsNotOfferedBeforeTheErasureArrives() throws IOException {
    ownSpec(main, "ada", "old", "ada");
    round(ada, ADA);
    Acting.as("ada", () -> ada.specs.create(spec("fresh", "ada")));
    eraseProject();

    assertEquals(Map.of(), round(ada, ADA), "L3: nothing fails");
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

    assertSettlesWithin(3, ada, ADA);
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

    assertSettlesWithin(3, ada, ADA);
    assertTrue(
        reviews.findReview(review).isPresent(),
        "the node keeps its review; main held: "
            + new ReviewStore(main.db).findReview(review).isPresent());
    assertTrue(
        new ReviewStore(main.db).findReview(review).isPresent(),
        "main takes the review once its spec lands");
  }

  /** L1: a file main's limits.file_max refuses is offered, and refused, every round. */
  @Test
  void aFileAboveMainsFileMaxSettles() throws IOException {
    Acting.as(
        "ada",
        () ->
            new FileStore(ada.db)
                .put("acme", "big.txt", new ByteArrayInputStream("0123456789".getBytes()), 0644));
    var history = new ArrayList<Map<String, String>>();
    for (var i = 0; i < 3; i++) {
      var failures = round(ada, main.server(ADA).content(main.db, new FileLimits(4)));
      history.add(failures);
      if (failures.isEmpty()) {
        return;
      }
    }
    throw new AssertionError("no clean round within 3 rounds: " + history);
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

    assertSettlesWithin(3, ada, Actor.sync("ada", Role.VIEWER));

    assertTrue(ada.specs.findById("born").isEmpty(), "the spec is withdrawn");
    assertTrue(
        new ChangeLog(ada.db).history("spec", "born").size() > before,
        "its history stays, with the withdrawal on top");
  }

  /** A ceiling main lowered reverts an oversized edit to the version main holds, never deletes. */
  @Test
  void anOversizedEditOfASyncedFileRevertsToMainsVersionUnderALoweredCeiling() throws IOException {
    var files = new FileStore(ada.db);
    Acting.as(
        "ada", () -> files.put("acme", "a.txt", new ByteArrayInputStream("hi".getBytes()), 0644));
    round(ada, main.server(ADA).content(main.db, new FileLimits(16)));
    round(ada, main.server(ADA).content(main.db, new FileLimits(16)));
    Acting.as(
        "ada",
        () -> files.put("acme", "a.txt", new ByteArrayInputStream("123456789".getBytes()), 0644));

    for (var i = 0; i < 3; i++) {
      round(ada, main.server(ADA).content(main.db, new FileLimits(4)));
    }

    assertEquals(2, new FileStore(main.db).find("acme", "a.txt").orElseThrow().size());
    assertEquals(2, files.find("acme", "a.txt").orElseThrow().size(), "reverted, not deleted");
    SyncBox.assertEqualToMain(main, ada);
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
      assertSettlesWithin(3, bob, bobSyncs);
      var adaIsAdmin = Actor.sync("ada", Role.ADMIN);
      round(ada, adaIsAdmin);
      var reply =
          Acting.as(
              "ada", () -> new MessageStore(ada.db).append("mine", "ada", "thanks", verdict.id()));

      assertSettlesWithin(3, ada, adaIsAdmin);

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

    assertSettlesWithin(3, ada, Actor.sync("ada", Role.VIEWER));

    assertTrue(new MessageStore(ada.db).findById(post.id()).isEmpty(), "withdrawn here");
    assertTrue(new MessageStore(main.db).findById(post.id()).isEmpty(), "never on main");
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
