/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static ai.singlr.sail.sync.SyncFixtures.assign;
import static ai.singlr.sail.sync.SyncFixtures.findingKeptInChangeLog;
import static ai.singlr.sail.sync.SyncFixtures.ownSpec;
import static ai.singlr.sail.sync.SyncFixtures.principal;
import static ai.singlr.sail.sync.SyncFixtures.room;
import static ai.singlr.sail.sync.SyncFixtures.run;
import static ai.singlr.sail.sync.SyncFixtures.spec;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.EraseRequests;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Main decides every pushed revision by the rules the doors ask, over a real session. Ada's box
 * holds an edited database: whatever it writes that is not Ada's to write — another member's spec,
 * room, review or verdict, a run naming someone else, a post or revision as another FDE — is
 * denied, the node adopts main's version, keeps nothing main refused but its history, and the next
 * round is clean. An admin's session passes every owner rule, and none of the attribution ones.
 */
class PushAuthoritySyncTest {

  private static final Actor ADA = Actor.sync("ada", Role.MEMBER);
  private static final Actor ADA_ADMIN = Actor.sync("ada", Role.ADMIN);
  private static final Actor BOB = Actor.sync("bob", Role.MEMBER);

  private SyncBox main;
  private SyncBox ada;
  private SyncBox bob;

  @BeforeEach
  void setUp() {
    main = new SyncBox("main");
    ada = new SyncBox("ada");
    bob = new SyncBox("bob");
  }

  @AfterEach
  void tearDown() {
    bob.close();
    ada.close();
    main.close();
  }

  private SyncSession.TypeReport push(SyncBox box, Actor as, String type) throws IOException {
    try (var link = SyncBox.connect(main.server(as), box)) {
      return link.reconcile(type, SyncedEntities.replicas(box.db, box.id, box.id).get(type));
    }
  }

  private void sync(SyncBox box, Actor as) throws IOException {
    for (var entity : SyncedEntities.all()) {
      push(box, as, entity.type());
    }
  }

  private static List<String> denied(SyncSession.TypeReport report) {
    return report.denials().stream().map(SyncSession.Denial::id).toList();
  }

  private void assertCleanRound(SyncBox box, Actor as, String type) throws IOException {
    var next = push(box, as, type);
    assertNull(next.failure());
    assertEquals(List.of(), next.denials(), "nothing is offered again");
    assertEquals(0, next.report().total(), next.toString());
  }

  /** Right after a denial, before the round goes on: the node holds main's version of the one. */
  private void assertMainsAgain(String type, String id) {
    var mains = SyncedEntities.replicas(main.db, "main", "main").get(type);
    var nodes = SyncedEntities.replicas(ada.db, "ada", "ada").get(type);
    assertEquals(mains.current(id), nodes.current(id), type + " " + id + " is main's again");
    assertEquals(mains.currentRev(id), nodes.currentRev(id));
  }

  private void assertEveryBoxConverged(Actor adaAs) {
    SyncBox.assertConverged(main, ada.syncsAs(adaAs), bob);
  }

  private static void retitle(SyncBox box, String as, String id, String title) {
    var row = box.specs.findById(id).orElseThrow();
    Acting.as(
        as,
        () ->
            box.specs.update(
                new SpecStore.SpecRow(
                    row.id(),
                    row.project(),
                    title,
                    row.status(),
                    row.assignee(),
                    row.agent(),
                    row.model(),
                    row.reasoningEffort(),
                    row.branch(),
                    row.priority(),
                    row.createdBy(),
                    row.createdAt(),
                    row.updatedAt(),
                    row.updatedBy(),
                    row.dependsOn(),
                    row.repos(),
                    row.roomId())));
  }

  @Test
  void editingDeletingOrMovingAnotherMembersSpecIsDeniedAndBothBoxesConverge() throws IOException {
    ownSpec(main, "bob", "theirs", "bob");
    room(main, "ada", "lobby");
    sync(ada, ADA);

    retitle(ada, "ada", "theirs", "Taken over");
    assertEquals(List.of("theirs"), denied(push(ada, ADA, "spec")));
    assertMainsAgain("spec", "theirs");
    assertCleanRound(ada, ADA, "spec");

    Acting.as("ada", () -> ada.specs.delete("theirs"));
    assertEquals(List.of("theirs"), denied(push(ada, ADA, "spec")));
    assertMainsAgain("spec", "theirs");
    assertEquals("Spec theirs", ada.specs.findById("theirs").orElseThrow().title());

    ada.db.execute("UPDATE specs SET room_id = 'lobby' WHERE id = 'theirs'");
    Acting.unchecked("ada", () -> ada.specs.updateStatus("theirs", SpecStatus.PENDING));
    assertEquals(List.of("theirs"), denied(push(ada, ADA, "spec")));
    assertMainsAgain("spec", "theirs");
    assertEquals("theirs", ada.specs.findById("theirs").orElseThrow().roomId());
    assertCleanRound(ada, ADA, "spec");
    assertEquals("Spec theirs", main.specs.findById("theirs").orElseThrow().title());
    assertEveryBoxConverged(ADA);
  }

  @Test
  void takingAnotherMembersSpecOrClaimingOneBornWhereYouMayNotPostIsDeniedAndNothingIsErased()
      throws IOException {
    ownSpec(main, "bob", "theirs", "bob");
    Acting.as("bob", () -> new MessageStore(main.db).append("theirs", "bob", "mine", null));
    var bobsRun = run(main, "bob", "theirs");
    room(main, "bob", "den");
    Acting.as("bob", () -> main.specs.create(spec("child", null).withRoomId("den")));
    sync(ada, ADA);
    sync(bob, BOB);

    assign(ada, "ada", "theirs", "ada");
    assign(ada, "ada", "child", "ada");
    assertEquals(List.of("theirs", "child"), denied(push(ada, ADA, "spec")));
    assertEquals("bob", main.specs.findById("theirs").orElseThrow().assignee());
    assertNull(main.specs.findById("child").orElseThrow().assignee());

    var requests = new EraseRequests(ada.db);
    for (var id : List.of("theirs", "child")) {
      requests.request(Erasure.SPEC, id, "ada");
      var refused = assertThrows(SyncTransportException.class, () -> push(ada, ADA, "spec"));
      assertTrue(refused.getMessage().contains("bob"), refused.getMessage());
    }
    sync(ada, ADA);
    sync(bob, BOB);

    for (var box : List.of(main, ada, bob)) {
      assertTrue(box.specs.findById("theirs").isPresent(), box.id);
      assertTrue(box.specs.findById("child").isPresent(), box.id);
      assertTrue(new RoomStore(box.db).findById("theirs").isPresent(), box.id);
      assertEquals(1, new MessageStore(box.db).list("theirs", null, 10).size(), box.id);
      assertTrue(new RunStore(box.db).findById(bobsRun).isPresent(), box.id);
    }
    assertEveryBoxConverged(ADA);
  }

  @Test
  void anOfflineEditToASpecAnAdminReassignsIsDeniedAndAdoptedAndTheNextRoundIsClean()
      throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    sync(ada, ADA);
    assign(main, "root", "mine", "bob");

    retitle(ada, "ada", "mine", "Offline edit");
    var report = push(ada, ADA, "spec");

    assertNull(report.failure());
    assertEquals(List.of("mine"), denied(report));
    assertMainsAgain("spec", "mine");
    assertEquals("bob", ada.specs.findById("mine").orElseThrow().assignee());
    assertTrue(
        ada.specs.history("mine").stream()
            .anyMatch(entry -> entry.snapshot().contains("Offline edit")),
        "the node's edit stays in its history");
    assertCleanRound(ada, ADA, "spec");
    assertEveryBoxConverged(ADA);
  }

  @Test
  void anotherMembersRoomReviewAndVerdictAreDeniedAndTheNodeAdoptsMainsReview() throws IOException {
    ownSpec(main, "bob", "theirs", "bob");
    room(main, "bob", "den");
    var review = Acting.as("bob", () -> new ReviewStore(main.db).createReview("theirs", 1));
    sync(ada, ADA);

    Acting.as("ada", () -> new RoomStore(ada.db).updateWake("den", "off"));
    assertEquals(List.of("den"), denied(push(ada, ADA, "room")));
    assertMainsAgain("room", "den");

    var reviews = new ReviewStore(ada.db);
    var finding =
        Acting.as(
            "ada",
            () -> {
              var stage = reviews.createStage(review, "security", "agent");
              var found = finding();
              reviews.addFinding(stage, found);
              reviews.updateReviewStatus(review, "passed");
              return found;
            });
    assertEquals(List.of(review), denied(push(ada, ADA, "review")));

    assertEquals("running", reviews.findReview(review).orElseThrow().status());
    assertEquals(
        List.of(),
        reviews.findingsForReview(review),
        "the node adopts main's version of the review exactly, findings included");
    assertTrue(
        findingKeptInChangeLog(ada, review, finding.id()),
        "the denied revision keeps the findings in the change log");
    assertEquals("running", new ReviewStore(main.db).findReview(review).orElseThrow().status());
    assertEquals(List.of(), new ReviewStore(main.db).findingsForReview(review));
    assertEveryBoxConverged(ADA);
  }

  /**
   * The run create rule at main's commit: a pushed run proves, as it is born, that its box's FDE
   * owned the spec it names. One born ahead of a spec its own box made lands with it — runs sync
   * first — and one on another FDE's spec is denied, so no box holds a run against work that was
   * never its own.
   */
  @Test
  void aRunPushedOnAnotherFdesSpecIsDeniedWhileOneOnItsOwnOrAheadOfItsSpecLands()
      throws IOException {
    ownSpec(main, "bob", "theirs", "bob");
    ownSpec(main, "ada", "mine", "ada");
    sync(ada, ADA);
    ownSpec(ada, "ada", "fresh", "ada");
    var runs = new RunStore(ada.db);
    var forged = run(ada, "ada", "theirs");
    var own = run(ada, "ada", "mine");
    var ahead = run(ada, "ada", "fresh");
    Acting.as(
        "ada", () -> List.of(forged, own, ahead).forEach(id -> runs.complete(id, "completed", 0)));

    var pushed = push(ada, ADA, "run");

    assertEquals(
        List.of(
            new SyncSession.Denial(
                "run",
                forged,
                "Run " + forged + " belongs to spec 'theirs', owned by 'bob', not you.")),
        pushed.denials());
    var mains = new RunStore(main.db);
    assertTrue(mains.findById(forged).isEmpty());
    assertTrue(mains.findById(own).isPresent());
    assertTrue(mains.findById(ahead).isPresent(), "its spec follows in the same round");
    assertCleanRound(ada, ADA, "run");
    sync(ada, ADA);
    assertEveryBoxConverged(ADA);
    assertTrue(runs.findById(forged).isEmpty(), "the node settles on main's word");
    assertTrue(main.specs.findById("fresh").isPresent());
  }

  /**
   * A box claims a spec no one is assigned, runs it and finishes before any of it reaches main:
   * runs sync first, so the run arrives ahead of the claim. It lands, with its agent's edit and the
   * claim behind it, and nothing of the offline work is withdrawn.
   */
  @Test
  void aFinishedRunThatReachesMainAheadOfItsBoxsClaimLandsWithIt() throws IOException {
    ownSpec(main, "bob", "open", null);
    sync(ada, ADA);
    assign(ada, "ada", "open", "ada");
    var runs = new RunStore(ada.db);
    var offline = run(ada, "ada", "open");
    var agent = Actor.agentPrincipal(principal(ada, offline), "ada");
    Acting.by(agent, () -> ada.specs.updateStatus("open", SpecStatus.IN_PROGRESS));
    Acting.as("ada", () -> runs.complete(offline, "completed", 0));

    sync(ada, ADA);

    assertEveryBoxConverged(ADA);
    assertEquals("completed", new RunStore(main.db).findById(offline).orElseThrow().status());
    var claimed = main.specs.findById("open").orElseThrow();
    assertEquals("ada", claimed.assignee());
    assertEquals(SpecStatus.IN_PROGRESS, claimed.status());
  }

  @Test
  void aRunNamingAnFdeOrAnotherRunIsDeniedAndSoIsAPostAsThatFde() throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    sync(ada, ADA);
    var runs = new RunStore(ada.db);
    var namingBob = run(ada, "ada", "mine");
    var namingAnother = run(ada, "ada", "mine");
    ada.db.execute(
        "INSERT INTO run_principals (run_id, principal) VALUES (?, 'bob'), (?, ?)",
        namingBob,
        namingAnother,
        "claude/" + namingBob);
    Acting.unchecked(
        "ada",
        () -> {
          runs.complete(namingBob, "completed", 0);
          runs.complete(namingAnother, "completed", 0);
        });
    var forged =
        Acting.unchecked(
            "ada", () -> new MessageStore(ada.db).append("mine", "bob", "as bob", null));

    assertEquals(List.of(namingBob, namingAnother), denied(push(ada, ADA, "run")));
    assertTrue(new RunStore(main.db).findById(namingBob).isEmpty());
    assertTrue(new RunStore(main.db).findById(namingAnother).isEmpty());
    assertEquals(List.of(forged.id()), denied(push(ada, ADA, "message")));
    assertTrue(new MessageStore(main.db).findById(forged.id()).isEmpty());
    assertCleanRound(ada, ADA, "run");
    assertCleanRound(ada, ADA, "message");
    assertEveryBoxConverged(ADA);
  }

  @Test
  void anAgentPostsInAnotherSpecsRoomOnlyWhereItsFdeMayPost() throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    ownSpec(main, "ada", "also-mine", "ada");
    ownSpec(main, "bob", "theirs", "bob");
    sync(ada, ADA);
    var agentRun = run(ada, "ada", "mine");
    var agent = principal(ada, agentRun);
    var messages = new MessageStore(ada.db);
    var allowed =
        Acting.by(
            Actor.agentPrincipal(agent, "ada"),
            () -> messages.append("also-mine", agent, "here", null));
    var refused = Acting.unchecked(agent, () -> messages.append("theirs", agent, "there", null));

    push(ada, ADA, "run");
    var report = push(ada, ADA, "message");

    assertEquals(List.of(refused.id()), denied(report));
    assertEquals(agent, new MessageStore(main.db).findById(allowed.id()).orElseThrow().author());
    assertTrue(new MessageStore(main.db).findById(refused.id()).isEmpty());
    assertCleanRound(ada, ADA, "message");
    assertEveryBoxConverged(ADA);
  }

  @Test
  void anAgentsSpecAndPostBeforeItsRunIsOnMainAreHeldBackAndLandTheRoundAfterTheRunDoes()
      throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    sync(ada, ADA);
    var agentRun = run(ada, "ada", "mine");
    var agent = Actor.agentPrincipal(principal(ada, agentRun), "ada");
    Acting.by(agent, () -> ada.specs.create(spec("born", null)));
    var post =
        Acting.by(
            agent, () -> new MessageStore(ada.db).append("mine", agent.handle(), "early", null));

    var heldSpec = push(ada, ADA, "spec");
    assertNull(heldSpec.failure(), "the spec waits for its run rather than failing the round");
    assertEquals(0, heldSpec.report().pushed(), "the spec is held back until its run lands");
    assertEquals(0, push(ada, ADA, "message").report().pushed(), "the post waits for its run");
    assertTrue(main.specs.findById("born").isEmpty());

    assertEquals(1, push(ada, ADA, "run").report().pushed());
    assertEquals(1, push(ada, ADA, "spec").report().pushed());
    assertEquals(1, push(ada, ADA, "message").report().pushed());

    assertEquals("ada", main.specs.findById("born").orElseThrow().createdBy());
    assertEquals(agent.handle(), main.specs.findById("born").orElseThrow().updatedBy());
    assertTrue(new MessageStore(main.db).findById(post.id()).isPresent());
    assertEveryBoxConverged(ADA);
  }

  @Test
  void aRevisionOrACreateNamingAnotherFdeIsDeniedEvenForAnAdmin() throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    sync(ada, ADA);

    for (var as : List.of(ADA, ADA_ADMIN)) {
      retitle(ada, "bob", "mine", "As bob");
      assertEquals(List.of("mine"), denied(push(ada, as, "spec")), as.toString());
      assertMainsAgain("spec", "mine");

      Acting.as("bob", () -> ada.specs.create(spec("for-bob", null)));
      assertEquals(List.of("for-bob"), denied(push(ada, as, "spec")), as.toString());
      assertTrue(main.specs.findById("for-bob").isEmpty());
      assertTrue(ada.specs.findById("for-bob").isEmpty());
      assertEquals(
          "bob",
          new ChangeLog(ada.db).history("spec", "for-bob").getFirst().actor(),
          "the denied create stays in the node's history");
    }
    assertEveryBoxConverged(ADA_ADMIN);
  }

  @Test
  void anAdminsSessionPassesEveryOwnerRule() throws IOException {
    ownSpec(main, "bob", "theirs", "bob");
    room(main, "bob", "den");
    var review = Acting.as("bob", () -> new ReviewStore(main.db).createReview("theirs", 1));
    sync(ada, ADA_ADMIN);

    retitle(ada, "ada", "theirs", "Edited by an admin");
    Acting.as("ada", () -> new RoomStore(ada.db).updateWake("den", "off"));
    Acting.as("ada", () -> new ReviewStore(ada.db).updateReviewStatus(review, "passed"));
    for (var type : List.of("spec", "room", "review")) {
      var report = push(ada, ADA_ADMIN, type);
      assertEquals(List.of(), report.denials(), type);
      assertEquals(1, report.report().pushed(), type);
    }
    assign(ada, "ada", "theirs", "ada");
    assertEquals(1, push(ada, ADA_ADMIN, "spec").report().pushed());

    assertEquals("ada", main.specs.findById("theirs").orElseThrow().assignee());
    assertEquals("off", new RoomStore(main.db).findById("den").orElseThrow().wake());
    assertEquals("passed", new ReviewStore(main.db).findReview(review).orElseThrow().status());
    assertEveryBoxConverged(ADA_ADMIN);
  }

  @Test
  void afterAForceReassignTheOldBoxsSpecReviewAndPostWritesAreDeniedButItsRunIsItsOwn()
      throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    sync(ada, ADA);
    Acting.as("ada", () -> ada.specs.updateStatus("mine", SpecStatus.IN_PROGRESS));
    var agentRun = run(ada, "ada", "mine");
    var reviews = new ReviewStore(ada.db);
    var review = Acting.system(() -> reviews.createReview("mine", 1));
    push(ada, ADA, "spec");
    push(ada, ADA, "run");
    push(ada, ADA, "review");
    assign(main, "root", "mine", "bob");

    Acting.as("ada", () -> ada.specs.updateStatus("mine", SpecStatus.REVIEW));
    var finding =
        Acting.system(
            () -> {
              var stage = reviews.createStage(review, "security", "agent");
              var found = finding();
              reviews.addFinding(stage, found);
              reviews.updateReviewStatus(review, "failed");
              return found;
            });
    var narration =
        Acting.system(
            () -> new MessageStore(ada.db).append("mine", MessageStore.SAIL_AUTHOR, "done", null));
    Acting.system(() -> new RunStore(ada.db).complete(agentRun, "stopped", null));

    assertEquals(List.of("mine"), denied(push(ada, ADA, "spec")));
    assertEquals(1, push(ada, ADA, "run").report().pushed(), "the run is still the box's");
    assertEquals(List.of(review), denied(push(ada, ADA, "review")));
    assertEquals(List.of(narration.id()), denied(push(ada, ADA, "message")));

    assertEquals(SpecStatus.IN_PROGRESS, main.specs.findById("mine").orElseThrow().status());
    assertEquals("bob", ada.specs.findById("mine").orElseThrow().assignee());
    assertEquals("stopped", new RunStore(main.db).findById(agentRun).orElseThrow().status());
    assertEquals(
        List.of(), reviews.findingsForReview(review), "the old box holds main's review exactly");
    assertTrue(
        findingKeptInChangeLog(ada, review, finding.id()), "its findings stay in its change log");
    assertTrue(new MessageStore(main.db).findById(narration.id()).isEmpty());
    assertEveryBoxConverged(ADA);
  }

  @Test
  void aSpecBornOnAnotherMembersRoomIdIsDeniedAndTheRoomStaysTheirs() throws IOException {
    room(main, "bob", "den");
    var den = new RoomStore(main.db).findById("den").orElseThrow();
    sync(ada, ADA);

    Acting.as("ada", () -> ada.specs.create(spec("den", "ada")));
    assertEquals(List.of("den"), denied(push(ada, ADA, "spec")));

    assertTrue(main.specs.findById("den").isEmpty());
    assertEquals(List.of("bob"), new RoomStore(main.db).owners("den"));
    Acting.as("ada", () -> new RoomStore(ada.db).updateWake("den", "off"));
    assertEquals(List.of("den"), denied(push(ada, ADA, "room")));
    assertEquals(den, new RoomStore(main.db).findById("den").orElseThrow());
    assertEveryBoxConverged(ADA);
  }

  @Test
  void aSpecWhoseOwnRoomReachedMainFirstStillLands() throws IOException {
    ownSpec(ada, "ada", "mine", "ada");

    assertEquals(List.of(), denied(push(ada, ADA, "room")));
    assertEquals(List.of(), denied(push(ada, ADA, "spec")));

    assertEquals("ada", main.specs.findById("mine").orElseThrow().assignee());
    assertEquals(List.of("ada"), new RoomStore(main.db).owners("mine"));
    assertEveryBoxConverged(ADA);
  }

  @Test
  void aReviewCarryingAnotherReviewsStageIsDeniedAndThatReviewIsUntouched() {
    ownSpec(main, "ada", "ours", "ada");
    ownSpec(main, "bob", "theirs", "bob");
    var reviews = new ReviewStore(main.db);
    var ours = Acting.as("ada", () -> reviews.createReview("ours", 1));
    var theirs = Acting.as("bob", () -> reviews.createReview("theirs", 1));
    var stage =
        Acting.as(
            "bob",
            () -> {
              var created = reviews.createStage(theirs, "security", "agent");
              reviews.addFinding(created, finding());
              return created;
            });
    var replica = SyncedEntities.replicas(main.db, "main", "main").get("review");
    var theirsRev = replica.currentRev(theirs);
    var offer = new LinkedHashMap<>(replica.current(ours));
    offer.put("stages", replica.current(theirs).get("stages"));

    var outcome = Actor.call(ADA, () -> replica.commit(ours, offer, replica.currentRev(ours)));

    assertInstanceOf(CommitOutcome.Denied.class, outcome);
    assertEquals(theirs, reviews.findStage(stage).orElseThrow().reviewId());
    assertEquals(1, reviews.findingsForReview(theirs).size());
    assertEquals(List.of(), reviews.findingsForReview(ours));
    assertEquals(theirsRev, replica.currentRev(theirs));
    assertEveryBoxConverged(ADA);
  }

  @Test
  void restoringAnotherMembersDeletedSpecAsAClaimIsDenied() {
    Acting.as("bob", () -> main.specs.create(spec("orphan", null)));
    Acting.as("bob", () -> main.specs.delete("orphan"));
    var claim = new LinkedHashMap<>(main.specs.held("orphan"));
    claim.put("assignee", "ada");
    claim.put(Snapshots.ACTOR, "ada");

    var outcome =
        Actor.call(
            ADA, () -> main.replica.commit("orphan", claim, main.replica.currentRev("orphan")));

    assertInstanceOf(CommitOutcome.Denied.class, outcome);
    assertTrue(main.specs.findById("orphan").isEmpty());
    assertEveryBoxConverged(ADA);
  }

  @Test
  void anOwnersRestoreClaimsItAndKeepsItsCreatorWhateverTheOfferNames() {
    Acting.as("bob", () -> main.specs.create(spec("orphan", null)));
    Acting.as("bob", () -> main.specs.delete("orphan"));
    var restore = new LinkedHashMap<>(main.specs.held("orphan"));
    restore.put("assignee", "bob");
    restore.put(Snapshots.ACTOR, "bob");
    restore.put(Snapshots.CREATOR, "ada");

    var outcome =
        Actor.call(
            BOB, () -> main.replica.commit("orphan", restore, main.replica.currentRev("orphan")));

    var accepted = assertInstanceOf(CommitOutcome.Accepted.class, outcome);
    assertEquals(new Snapshots.Creator("bob"), accepted.creator());
    var restored = main.specs.findById("orphan").orElseThrow();
    assertEquals("bob", restored.createdBy());
    assertEquals("bob", restored.assignee());
    assertEveryBoxConverged(ADA);
  }

  @Test
  void theCreatorOfARoomAssignedAwayCannotTakeItBackWithASpec() throws IOException {
    Acting.as(
        "ada",
        () ->
            new RoomStore(main.db)
                .create(
                    new RoomStore.RoomRow(
                        "den", "acme", "den", "bob", null, null, null, null, null, null)));
    sync(ada, ADA);

    Acting.as("ada", () -> ada.specs.create(spec("den", "ada")));

    assertEquals(List.of("den"), denied(push(ada, ADA, "spec")));
    assertTrue(main.specs.findById("den").isEmpty());
    assertEquals(List.of("bob"), new RoomStore(main.db).owners("den"));
    assertCleanRound(ada, ADA, "spec");
    assertEveryBoxConverged(ADA);
  }

  @Test
  void aDeletedRoomCannotBeTakenOverWithASpecAndRestoredWithItsConversation() throws IOException {
    room(main, "bob", "den");
    Acting.as("bob", () -> new MessageStore(main.db).append("den", "bob", "mine", null));
    Acting.as("bob", () -> new RoomStore(main.db).delete("den"));
    sync(ada, ADA);

    Acting.as("ada", () -> ada.specs.create(spec("den", "ada")));

    assertEquals(List.of("den"), denied(push(ada, ADA, "spec")));
    assertTrue(main.specs.findById("den").isEmpty());
    assertEquals("bob", new RoomStore(main.db).ownerOf("den", new RoomStore(main.db).held("den")));
    assertEveryBoxConverged(ADA);
  }

  @Test
  void aRoomRestoredByItsOwnerKeepsTheCreatorItsTombstoneRecorded() {
    Acting.as(
        "ada",
        () ->
            new RoomStore(main.db)
                .create(
                    new RoomStore.RoomRow(
                        "nook", "acme", "nook", "bob", null, null, null, null, null, null)));
    Acting.as("bob", () -> new RoomStore(main.db).delete("nook"));
    var rooms = SyncedEntities.replicas(main.db, "main", "main").get("room");
    var restore = new LinkedHashMap<>(new RoomStore(main.db).held("nook"));
    restore.put("created_by", "bob");
    restore.put(Snapshots.ACTOR, "bob");

    var outcome = Actor.call(BOB, () -> rooms.commit("nook", restore, rooms.currentRev("nook")));

    assertInstanceOf(CommitOutcome.Accepted.class, outcome);
    assertEquals("ada", new RoomStore(main.db).findById("nook").orElseThrow().createdBy());
    assertEveryBoxConverged(ADA);
  }

  @Test
  void aSpecBornInTheNodesNewRoomWaitsForTheRoomAndLandsTheRoundAfter() throws IOException {
    room(ada, "ada", "lab");
    Acting.as("ada", () -> ada.specs.create(spec("child", "ada").withRoomId("lab")));

    var first = push(ada, ADA, "spec");
    assertNull(first.failure(), "the round completes");
    assertEquals(0, first.report().pushed(), "the spec waits for its room");
    assertTrue(ada.specs.findById("child").isPresent(), "the node keeps its spec");

    assertEquals(List.of(), denied(push(ada, ADA, "room")));
    assertEquals(1, push(ada, ADA, "spec").report().pushed());

    assertEquals("lab", main.specs.findById("child").orElseThrow().roomIdOrIdentity());
    assertCleanRound(ada, ADA, "spec");
    assertEveryBoxConverged(ADA);
  }

  @Test
  void aRoomReCreatedOverItsTombstoneConvergesOnTheCreatorMainKeeps() throws IOException {
    Acting.as(
        "ada",
        () ->
            new RoomStore(main.db)
                .create(
                    new RoomStore.RoomRow(
                        "nook", "acme", "nook", "bob", null, null, null, null, null, null)));
    sync(bob, BOB);
    var rooms = new RoomStore(bob.db);
    Acting.as("bob", () -> rooms.delete("nook"));
    assertEquals(List.of(), denied(push(bob, BOB, "room")));
    Acting.as(
        "bob",
        () ->
            rooms.create(
                new RoomStore.RoomRow(
                    "nook", "acme", "nook", null, null, null, null, null, null, null)));

    assertEquals(List.of(), denied(push(bob, BOB, "room")));
    push(bob, BOB, "room");

    assertEquals("ada", new RoomStore(main.db).findById("nook").orElseThrow().createdBy());
    assertEquals("ada", rooms.findById("nook").orElseThrow().createdBy(), "the node adopts it");
    assertEveryBoxConverged(ADA);
  }

  @Test
  void aReviewTheNodeIsRunningKeepsItsFindingsThroughADenialUntilItFinishes() throws IOException {
    ownSpec(main, "ada", "mine", "ada");
    sync(ada, ADA);
    var reviews = new ReviewStore(ada.db);
    var review = Acting.system(() -> reviews.createReview("mine", 1));
    var finding =
        Acting.system(
            () -> {
              reviews.updateReviewStatus(review, "running");
              var stage = reviews.createStage(review, "security", "agent");
              var found = finding();
              reviews.addFinding(stage, found);
              return found;
            });
    assign(main, "root", "mine", "bob");
    sync(ada, ADA);

    assertEquals(List.of(review), denied(push(ada, ADA, "review")));
    assertEquals(
        List.of(finding.id()),
        reviews.findingsForReview(review).stream().map(Finding::id).toList(),
        "main's denial never removes a review mid-run");

    Acting.system(() -> reviews.updateReviewStatus(review, "failed"));
    assertEquals(List.of(review), denied(push(ada, ADA, "review")));
    assertTrue(reviews.findReview(review).isEmpty(), "a finished review settles like any other");
    assertCleanRound(ada, ADA, "review");
    assertEveryBoxConverged(ADA);
  }

  private static Finding finding() {
    return Finding.create(
        Finding.Severity.HIGH,
        Finding.Category.SECURITY,
        "A.java",
        1,
        2,
        "issue",
        "desc",
        "evidence",
        new Finding.Suggestion("a", "b", "c"),
        0.9);
  }
}
