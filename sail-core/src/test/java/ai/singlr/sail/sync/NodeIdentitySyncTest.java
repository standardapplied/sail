/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FdeBoxes;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * I3 over a real session: main and the node agree who the node is. Main's welcome names the handle
 * it authenticated the session as, a node configured with another handle, or none, does nothing,
 * and one box syncs as each FDE.
 */
class NodeIdentitySyncTest {

  private static final Actor ADA = Actor.sync("ada", Role.MEMBER);
  private static final Actor UDAY = Actor.sync("uday", Role.MEMBER);

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
    Acting.as(owner, () -> box.specs.create(spec(id, owner)));
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

  private static String finishedRun(SyncBox box, String handle, String specId) {
    var id = DateTimeUtils.newId().toString();
    var runs = new RunStore(box.db);
    Acting.system(
        () -> {
          runs.reserveDispatch(
              id, "acme", specId, handle, "build", List.of(), "claude-code", "b", "t", "/l", "u");
          runs.complete(id, "completed", 0);
        });
    return id;
  }

  private static UnaryOperator<OutputStream> welcomingAsAnOlderMain() {
    return out ->
        new FilterOutputStream(out) {
          @Override
          public void write(byte[] buffer, int offset, int length) throws IOException {
            var line = new String(buffer, offset, length, StandardCharsets.UTF_8);
            var older =
                line.contains("\"op\": \"welcome\"")
                    ? line.replaceAll(", \"handle\": \"[^\"]*\"", "")
                    : line;
            out.write(older.getBytes(StandardCharsets.UTF_8));
          }
        };
  }

  @Test
  void theWelcomeNamesTheHandleMainAuthenticatedTheSessionAs() throws IOException {
    try (var link = SyncBox.connect(main.server(UDAY), ada)) {
      assertEquals("uday", link.session().handle().orElseThrow());
    }
    SyncBox.quiesce(main, ada);
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aNodeMainKnowsByAnotherHandleOffersAndAdoptsNothing() {
    ownSpec(main, "ada", "mine");
    SyncBox.round(main, ada);
    var finished = finishedRun(ada, "ada", "mine");
    Acting.as("ada", () -> ada.specs.updateStatus("mine", SpecStatus.IN_PROGRESS));
    ownSpec(main, "ada", "later");

    var failure =
        assertThrows(
            SyncTransportException.class,
            () -> SyncBox.round(main, ada.syncsAs(UDAY).configuredAs("ada")));

    assertTrue(failure.getMessage().contains("'uday'"), failure.getMessage());
    assertTrue(failure.getMessage().contains("sync-handle uday"), failure.getMessage());
    assertTrue(new RunStore(ada.db).findById(finished).isPresent(), "nothing is removed");
    assertEquals("ada", new RunStore(ada.db).findById(finished).orElseThrow().node());
    assertTrue(new RunStore(main.db).findById(finished).isEmpty(), "nothing is offered");
    assertTrue(ada.specs.findById("later").isEmpty(), "nothing is adopted");
    assertEquals(SpecStatus.PENDING, main.specs.findById("mine").orElseThrow().status());

    SyncBox.quiesce(main, ada.syncsAs(ADA));
    SyncBox.assertEqualToMain(main, ada);
    assertTrue(new RunStore(main.db).findById(finished).isPresent(), "once they agree, it lands");
  }

  @Test
  void aNodeWhoseHandleDiffersFromTheOneMainKnowsFailsNamingBoth() throws IOException {
    try (var link = SyncBox.connect(main.server(UDAY), ada)) {
      var failure =
          assertThrows(
              SyncTransportException.class, () -> NodeRound.begin(link.session(), ada.db, "ada"));

      assertEquals("refused", failure.kind());
      assertTrue(failure.getMessage().contains("FDE 'uday'"), failure.getMessage());
      assertTrue(failure.getMessage().contains("sync handle is 'ada'"), failure.getMessage());
      assertTrue(failure.getMessage().contains("FDE 'ada' on main"), failure.getMessage());
    }
    SyncBox.quiesce(main, ada);
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aNodeWithNoHandleFailsNamingTheOneMainKnows() throws IOException {
    var unstamped = finishedRun(ada, null, null);
    try (var link = SyncBox.connect(main.server(ADA), ada)) {
      var failure =
          assertThrows(
              SyncTransportException.class, () -> NodeRound.begin(link.session(), ada.db, null));

      assertTrue(failure.getMessage().contains("FDE 'ada'"), failure.getMessage());
      assertTrue(failure.getMessage().contains("no sync handle"), failure.getMessage());
    }
    assertEquals(null, new RunStore(ada.db).findById(unstamped).orElseThrow().node());
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    SyncBox.assertEqualToMain(main, ada);
    assertEquals("ada", new RunStore(main.db).findById(unstamped).orElseThrow().owner());
  }

  @Test
  void againstAMainThatOmitsTheHandleTheRoundRunsAsBefore() throws IOException {
    ownSpec(main, "ada", "mine");
    var unstamped = finishedRun(ada, null, "mine");
    try (var link =
        SyncBox.connect(main.server(ADA), ada, SyncWire.MAX_FRAME, welcomingAsAnOlderMain())) {
      assertTrue(link.session().handle().isEmpty());
      Actor.run(Actor.main(), () -> NodeRound.begin(link.session(), ada.db, "ada"));
      var report = link.reconcile("run", ada.replicas().get("run"));

      assertEquals(1, report.report().pushed(), report.toString());
    }
    assertEquals("ada", new RunStore(main.db).findById(unstamped).orElseThrow().owner());
    NodeRound.requireAgreed(Optional.empty(), "anyone");
    SyncBox.quiesce(main, ada.syncsAs(ADA));
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aSecondBoxSyncingAsAnFdeTiedToABoxIsRefusedUntilItIsReleased() throws IOException {
    ownSpec(main, "ada", "mine");
    SyncBox.round(main, ada);
    try (var laptop = new SyncBox("ada-laptop").syncsAs(ADA)) {
      var refused = assertThrows(SyncTransportException.class, () -> SyncBox.round(main, laptop));

      assertEquals("refused", refused.kind());
      assertTrue(refused.getMessage().contains("FDE 'ada'"), refused.getMessage());
      assertTrue(refused.getMessage().contains("box 'ada'"), refused.getMessage());
      assertTrue(refused.getMessage().contains("'ada-laptop'"), refused.getMessage());
      assertTrue(refused.getMessage().contains("sail fde release-box ada"), refused.getMessage());
      assertTrue(laptop.specs.findById("mine").isEmpty(), "nothing is exchanged");

      assertTrue(new FdeBoxes(main.db).release("ada"));
      SyncBox.quiesce(main, laptop);
      SyncBox.assertEqualToMain(main, laptop);
      assertTrue(laptop.specs.findById("mine").isPresent());
    }
    assertThrows(SyncTransportException.class, () -> SyncBox.round(main, ada));
  }

  @Test
  void mainsOwnFdeSyncsFromNoBoxButMain() throws IOException {
    var server =
        SyncRpcServer.over(
            main.db,
            "main",
            "ada",
            ADA,
            FdeRoster.EMPTY,
            SyncTransitionSink.NONE,
            SyncWire.UPGRADE_FLOOR);

    var refused =
        assertThrows(SyncTransportException.class, () -> SyncBox.connect(server, ada).close());

    assertTrue(refused.getMessage().contains("main's own FDE"), refused.getMessage());
    assertTrue(refused.getMessage().contains("main ('main')"), refused.getMessage());
    assertFalse(refused.getMessage().contains("release-box"), "releasing does nothing for it");
    assertTrue(new FdeBoxes(main.db).boxOf("ada").isEmpty(), "main's FDE records no other box");
    SyncBox.quiesce(main, ada);
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aSessionNamingNoFdeClaimsNoBox() throws IOException {
    try (var link = SyncBox.connect(main.server(Actor.sync(null, Role.VIEWER)), ada)) {
      assertEquals("", link.session().handle().orElseThrow());
    }
    try (var other = new SyncBox("other");
        var link = SyncBox.connect(main.server(Actor.sync(null, Role.VIEWER)), other)) {
      assertInstanceOf(SyncSession.class, link.session());
    }
    SyncBox.quiesce(main, ada);
    SyncBox.assertEqualToMain(main, ada);
  }

  @Test
  void aRoundOfTheOneBoxOfAnFdeLeavesItsFleetEqualToMain() {
    ownSpec(main, "ada", "mine");
    finishedRun(ada, "ada", "mine");

    SyncBox.quiesce(main, ada);

    SyncBox.assertEqualToMain(main, ada);
    assertEquals("ada", new FdeBoxes(main.db).boxOf("ada").orElseThrow());
  }
}
