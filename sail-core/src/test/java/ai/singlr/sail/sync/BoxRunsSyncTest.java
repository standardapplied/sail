/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.SyncConflicts;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * I4 and L4 over a real session: every run a box executes carries its handle as {@code node} and
 * {@code owner} by the time main can see it, so main takes it and what its agent writes, and main's
 * version never rewrites a run still live on the box that executes it, while another box's run is
 * always adopted as main holds it.
 */
class BoxRunsSyncTest {

  private static final Actor UDAY = Actor.sync("uday", Role.MEMBER);
  private static final Actor BOB = Actor.sync("bob", Role.MEMBER);

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

  private static void ownSpec(SyncBox box, String owner, String id) {
    Acting.as(
        owner,
        () -> {
          box.specs.create(
              new SpecStore.SpecRow(
                  id,
                  "acme",
                  "Spec " + id,
                  SpecStatus.PENDING,
                  owner,
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
                  List.of()));
          new RoomStore(box.db)
              .create(
                  new RoomStore.RoomRow(
                      id, "acme", "Spec " + id, owner, null, null, null, null, null, null));
        });
  }

  private static RunStore runs(SyncBox box) {
    return new RunStore(box.db);
  }

  private static String reserve(SyncBox box, String handle, String specId, String role) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            runs(box)
                .reserveDispatch(
                    id,
                    "acme",
                    specId,
                    handle,
                    role,
                    List.of(),
                    "claude-code",
                    "b",
                    "t",
                    "/l",
                    "u"));
    return id;
  }

  private static String credential(SyncBox box, String handle, String specId) {
    var id = DateTimeUtils.newId().toString();
    var reserved =
        Acting.system(
            () ->
                runs(box)
                    .reserveDispatch(
                        id,
                        "acme",
                        specId,
                        handle,
                        "build",
                        List.of(),
                        "claude-code",
                        "b",
                        "t",
                        "/l",
                        "u"));
    return ((RunStore.Reservation.Reserved) reserved).credential();
  }

  private static void finish(SyncBox box, String id) {
    Acting.system(() -> runs(box).complete(id, "completed", 0));
  }

  private static Actor agentOf(SyncBox box, String runId) {
    var run = runs(box).findById(runId).orElseThrow();
    return Actor.agentPrincipal(run.principal(), run.owner());
  }

  private static void resolveMine(SyncBox box, SyncConflicts.Conflict parked) {
    Acting.as(
        box.handle(),
        () ->
            box.conflicts.resolve(
                parked.id(),
                runs(box)
                    .resolveConflict(
                        parked.entityId(),
                        YamlUtil.parseMap(parked.localSnapshot()),
                        YamlUtil.parseMap(parked.remoteSnapshot()))));
  }

  private static void assertNoDenials(List<SyncSession.TypeReport> round) {
    for (var report : round) {
      assertNull(report.failure(), report.toString());
      assertEquals(List.of(), report.denials(), report.toString());
    }
  }

  @Test
  void aStandaloneBoxsRunsLandOnMainWithWhatTheirAgentsWroteWhenItJoins() {
    ownSpec(ada, "ada", "mine");
    var build = reserve(ada, null, "mine", "build");
    finish(ada, build);
    var finishedAdhoc = reserve(ada, null, null, "adhoc");
    finish(ada, finishedAdhoc);
    var liveAdhoc = reserve(ada, null, null, "adhoc");
    var review = DateTimeUtils.newId().toString();
    Acting.system(
        () -> runs(ada).createReview(review, "acme", "mine", null, "codex", "b", "t", "/l", "u"));
    var agent = agentOf(ada, build);
    var posted =
        Acting.by(
            agent, () -> new MessageStore(ada.db).append("mine", agent.handle(), "on it", null));
    Acting.by(agent, () -> ada.specs.updateStatus("mine", SpecStatus.IN_PROGRESS));

    for (var report : SyncBox.round(main, ada)) {
      assertEquals(List.of(), report.denials(), report.toString());
    }
    SyncBox.quiesce(main, ada);

    SyncBox.assertEqualToMain(main, ada);
    for (var id : List.of(finishedAdhoc, liveAdhoc, build, review)) {
      var held = runs(main).findById(id).orElseThrow();
      assertEquals("ada", held.node(), id);
      assertEquals("ada", held.owner(), id);
      assertTrue(runs(ada).findById(id).isPresent(), "never deleted: " + id);
    }
    assertEquals("running", runs(ada).findById(liveAdhoc).orElseThrow().status());
    assertTrue(new MessageStore(main.db).findById(posted.id()).isPresent());
    assertEquals(SpecStatus.IN_PROGRESS, main.specs.findById("mine").orElseThrow().status());
  }

  @Test
  void aRunThatActedForNoOneVouchesForItsAgentsPostOnceTheRoundStampsIt() {
    ownSpec(main, "ada", "mine");
    SyncBox.quiesce(main, ada);
    var ownerless = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            runs(ada)
                .create(
                    ownerless,
                    "acme",
                    "mine",
                    "ada",
                    null,
                    "build",
                    "claude-code",
                    "b",
                    "t",
                    null,
                    null,
                    "/l",
                    "u"));
    var agent = Actor.agentPrincipal(runs(ada).findById(ownerless).orElseThrow().principal(), "");
    var posted =
        Acting.by(agent, () -> new MessageStore(ada.db).append("mine", agent.handle(), "hi", null));

    SyncBox.quiesce(main, ada);

    SyncBox.assertEqualToMain(main, ada);
    assertEquals("ada", runs(main).findById(ownerless).orElseThrow().owner());
    assertTrue(new MessageStore(main.db).findById(posted.id()).isPresent());
  }

  @Test
  void mainDeniesARunThatActsForNoOne() throws IOException {
    ownSpec(main, "ada", "mine");
    SyncBox.quiesce(main, ada);
    var ownerless = DateTimeUtils.newId().toString();
    Acting.system(
        () ->
            runs(ada)
                .create(
                    ownerless,
                    "acme",
                    "mine",
                    "ada",
                    null,
                    "build",
                    "claude-code",
                    "b",
                    "t",
                    null,
                    null,
                    "/l",
                    "u"));

    try (var link = SyncBox.connect(main.server(ada.session()), ada)) {
      var report = link.reconcile("run", ada.replicas().get("run"));

      assertEquals(List.of(ownerless), report.denials().stream().map(d -> d.id()).toList());
      assertTrue(report.denials().getFirst().reason().contains("acts for ''"));
    }
    assertTrue(runs(ada).findById(ownerless).isPresent(), "a live run stays as it is");

    SyncBox.quiesce(main, ada);
    SyncBox.assertEqualToMain(main, ada);
    assertEquals("ada", runs(main).findById(ownerless).orElseThrow().owner(), "stamped, it lands");
  }

  @Test
  void aHandleChangeWaitsForTheRunsMainHoldsUnderTheOldHandleAndThenStampsOnlyTheRest() {
    ownSpec(main, "ada", "mine");
    SyncBox.quiesce(main, ada);
    var acknowledged = reserve(ada, "ada", "mine", "build");
    SyncBox.quiesce(main, ada);
    Acting.system(() -> runs(ada).recordSession(acknowledged, "sess-1", "claude", "/t"));

    assertEquals(
        List.of(acknowledged),
        runs(ada).strandedByHandleChange("ada", false, List.of()).stream()
            .map(RunStore.RunRow::id)
            .toList(),
        "a live run main holds under the old handle refuses the change");
    finish(ada, acknowledged);
    assertEquals(
        List.of(acknowledged),
        runs(ada).strandedByHandleChange("ada", false, List.of()).stream()
            .map(RunStore.RunRow::id)
            .toList(),
        "so does its change main has not taken");
    SyncBox.quiesce(main, ada);
    assertEquals(List.of(), runs(ada).strandedByHandleChange("ada", false, List.of()));

    var finishedUnheld = reserve(ada, "ada", "mine", "room");
    finish(ada, finishedUnheld);
    var liveUnheld = reserve(ada, "ada", "mine", "room");
    assertEquals(List.of(), runs(ada).strandedByHandleChange("ada", false, List.of()));
    assertEquals(
        List.of(finishedUnheld, liveUnheld),
        runs(ada).stamp("uday", runs(ada).unacknowledged()),
        "only the unheld");
    SyncBox.quiesce(main, ada.syncsAs(UDAY));

    SyncBox.assertEqualToMain(main, ada);
    assertEquals("ada", runs(main).findById(acknowledged).orElseThrow().node());
    assertEquals("sess-1", runs(main).findById(acknowledged).orElseThrow().sessionId());
    assertEquals("uday", runs(main).findById(liveUnheld).orElseThrow().owner());
    assertEquals("uday", runs(main).findById(finishedUnheld).orElseThrow().node());
    assertEquals("running", runs(ada).findById(liveUnheld).orElseThrow().status());
  }

  @Test
  void aLiveRunMainTookWhoseAnswerWasLostIsAcknowledgedAndSettlesOnceItFinishes()
      throws IOException {
    ownSpec(main, "ada", "mine");
    SyncBox.quiesce(main, ada);
    var live = reserve(ada, "ada", "mine", "build");
    SyncBox.pushLosingTheAnswer(main, ada);
    assertTrue(runs(main).findById(live).isPresent(), "main took it");
    assertNull(runs(ada).baseRevOf(live), "but this box never heard");
    Acting.system(() -> runs(ada).recordSession(live, "sess-1", "claude", "/t"));

    assertNoDenials(SyncBox.round(main, ada));
    assertNotEquals(null, runs(ada).baseRevOf(live), "adopted as acknowledged");
    assertEquals("sess-1", runs(ada).findById(live).orElseThrow().sessionId());
    finish(ada, live);
    SyncBox.quiesce(main, ada);

    SyncBox.assertEqualToMain(main, ada);
    assertEquals("completed", runs(main).findById(live).orElseThrow().status());
    assertEquals("sess-1", runs(main).findById(live).orElseThrow().sessionId());
  }

  @Test
  void aRunMainTookWhoseAnswerWasLostKeepsTheStampMainHoldsItUnder() throws IOException {
    ownSpec(main, "ada", "mine");
    SyncBox.quiesce(main, ada);
    var run = reserve(ada, "ada", "mine", "build");
    finish(ada, run);
    SyncBox.pushLosingTheAnswer(main, ada);

    assertNoDenials(SyncBox.round(main, ada.syncsAs(UDAY)));

    assertEquals("ada", runs(ada).findById(run).orElseThrow().node(), "never re-stamped");
    SyncBox.quiesce(main, ada);
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aRunMainTookWhoseAnswerWasLostAndThenChangedParksTheChangeForTheFdeAndSettlesOnceDecided()
      throws IOException {
    var run = reserve(ada, "ada", null, "adhoc");
    finish(ada, run);
    SyncBox.pushLosingTheAnswer(main, ada);
    Acting.system(() -> runs(main).recordSession(run, "main-session", "claude", "/main-t"));

    assertNoDenials(SyncBox.round(main, ada));

    assertEquals("main-session", runs(main).findById(run).orElseThrow().sessionId());
    assertNull(runs(ada).findById(run).orElseThrow().sessionId(), "nor taken unasked");
    var parked = ada.conflicts.pendingFor("run", run).orElseThrow();
    assertTrue(parked.fields().contains("session_id"), parked.fields().toString());
    resolveMine(ada, parked);
    SyncBox.quiesce(main, ada);

    assertNull(runs(main).findById(run).orElseThrow().sessionId(), "the FDE's decision lands");
    SyncBox.assertEqualToMain(main, ada);
    assertEquals("ada", runs(ada).findById(run).orElseThrow().node(), "never re-stamped");
  }

  @Test
  void aRunMainTookWhoseAnswerWasLostAndThenClearedNeverGetsTheClearedValueBack()
      throws IOException {
    var run = reserve(ada, "ada", null, "adhoc");
    finish(ada, run);
    Acting.system(() -> runs(ada).recordSession(run, "offered-session", "claude", "/t"));
    SyncBox.pushLosingTheAnswer(main, ada);
    Acting.system(() -> runs(main).recordSession(run, null, "claude", "/t"));

    assertNoDenials(SyncBox.round(main, ada));

    assertNull(runs(main).findById(run).orElseThrow().sessionId(), "main keeps its clear");
    assertEquals("offered-session", runs(ada).findById(run).orElseThrow().sessionId(), "nor lost");
    var parked = ada.conflicts.pendingFor("run", run).orElseThrow();
    assertTrue(parked.fields().contains("session_id"), parked.fields().toString());
    resolveMine(ada, parked);
    SyncBox.quiesce(main, ada);

    assertEquals("offered-session", runs(main).findById(run).orElseThrow().sessionId());
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aRunMainTookWhoseAnswerWasLostAndThenSetBackToAnEarlierStateNeverRevertsMainsChange()
      throws IOException {
    var run = reserve(ada, "ada", null, "adhoc");
    finish(ada, run);
    Acting.system(() -> runs(ada).recordSession(run, "old-session", "claude", "/t"));
    Acting.system(() -> runs(ada).recordSession(run, "offered-session", "claude", "/t"));
    SyncBox.pushLosingTheAnswer(main, ada);
    Acting.system(() -> runs(main).recordSession(run, "old-session", "claude", "/t"));

    assertNoDenials(SyncBox.round(main, ada));

    assertEquals("old-session", runs(main).findById(run).orElseThrow().sessionId());
    assertEquals("offered-session", runs(ada).findById(run).orElseThrow().sessionId(), "nor lost");
    var parked = ada.conflicts.pendingFor("run", run).orElseThrow();
    assertTrue(parked.fields().contains("session_id"), parked.fields().toString());
  }

  @Test
  void aRunBothSidesChangedAfterALostAnswerParksAConflictForTheFde() throws IOException {
    var run = reserve(ada, "ada", null, "adhoc");
    SyncBox.pushLosingTheAnswer(main, ada);
    Acting.system(() -> runs(main).recordSession(run, "main-session", "claude", "/main-t"));
    Acting.system(() -> runs(ada).recordSession(run, "ada-session", "claude", "/t"));
    finish(ada, run);

    assertNoDenials(SyncBox.round(main, ada));

    assertEquals("main-session", runs(main).findById(run).orElseThrow().sessionId());
    assertEquals("ada-session", runs(ada).findById(run).orElseThrow().sessionId(), "nor lost");
    var parked = ada.conflicts.pendingFor("run", run).orElseThrow();
    assertTrue(parked.fields().contains("session_id"), parked.fields().toString());
  }

  @Test
  void mainsChangeMergedWithTheBoxsOwnNeverRewritesARunStillLiveHere() {
    ownSpec(main, "ada", "mine");
    SyncBox.quiesce(main, ada);
    var live = reserve(ada, "ada", "mine", "build");
    SyncBox.quiesce(main, ada);
    Acting.system(() -> runs(main).complete(live, "completed", 0));
    Acting.system(() -> runs(ada).recordSession(live, "sess-1", "claude", "/t"));
    var rev = runs(ada).latestRev(live);

    assertNoDenials(SyncBox.round(main, ada));
    assertEquals("running", runs(ada).findById(live).orElseThrow().status(), "not merged into it");
    assertEquals(rev, runs(ada).latestRev(live));

    finish(ada, live);
    assertNoDenials(SyncBox.round(main, ada));
    var parked = ada.conflicts.pendingFor("run", live).orElseThrow();
    assertEquals(List.of("completed_at"), parked.fields(), "both completed it: the FDE decides");
    assertEquals("completed", runs(ada).findById(live).orElseThrow().status());
    assertEquals("sess-1", runs(ada).findById(live).orElseThrow().sessionId(), "nothing is lost");
  }

  @Test
  void mainsVersionNeverRewritesARunStillLiveHereAndItsCredentialStillAuthenticates() {
    ownSpec(main, "ada", "mine");
    SyncBox.quiesce(main, ada);
    var credential = credential(ada, "ada", "mine");
    var live = runs(ada).findByCredential(credential).orElseThrow().id();
    SyncBox.quiesce(main, ada);
    var rev = runs(ada).latestRev(live);

    Acting.system(() -> runs(main).recordExitCode(live, null));
    assertNoDenials(SyncBox.round(main, ada));
    assertEquals(rev, runs(ada).latestRev(live), "a converged version is not adopted over it");

    Acting.system(() -> runs(main).recordSession(live, "mains", "claude", "/t"));
    assertNoDenials(SyncBox.round(main, ada));
    assertNull(runs(ada).findById(live).orElseThrow().sessionId(), "nor is main's newer one");
    assertEquals(rev, runs(ada).latestRev(live));
    assertTrue(runs(ada).findByCredential(credential).isPresent(), "its credential still works");

    finish(ada, live);
    SyncBox.quiesce(main, ada);

    SyncBox.assertEqualToMain(main, ada);
    var settled = runs(main).findById(live).orElseThrow();
    assertEquals("completed", settled.status());
    assertEquals("mains", settled.sessionId(), "once finished, both sides' changes settle");
  }

  @Test
  void anotherBoxsRunPulledWhileRunningIsAdoptedFinishedOnceMainHasIt() {
    try (var bob = new SyncBox("bob").syncsAs(BOB)) {
      ownSpec(main, "bob", "theirs");
      SyncBox.quiesce(main, bob);
      var run = reserve(bob, "bob", "theirs", "build");
      SyncBox.quiesce(main, bob, ada);
      assertEquals("running", runs(ada).findById(run).orElseThrow().status());

      finish(bob, run);
      SyncBox.quiesce(main, bob, ada);

      assertEquals("completed", runs(ada).findById(run).orElseThrow().status());
      SyncBox.assertEqualToMain(main, ada);
      SyncBox.assertEqualToMain(main, bob);
    }
  }
}
