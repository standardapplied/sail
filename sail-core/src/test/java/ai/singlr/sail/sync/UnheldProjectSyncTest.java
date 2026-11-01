/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Main takes a spec only into a project it holds, as every box does. A row a forged or older node
 * offers into any other project is denied with the reason a box's own door gives, and the node
 * keeps it, offering it again each round: sync never takes back work a box accepted. A project the
 * node made is awaited, never denied.
 */
@ActingAs
class UnheldProjectSyncTest {

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

  @Test
  void aSpecInAProjectMainDoesNotHoldIsDeniedAndStaysOnTheNode() {
    Acting.unchecked("node", () -> node.specs.create(spec("forged", "zeta")));

    var first = specs(SyncBox.round(main, node));
    var second = specs(SyncBox.round(main, node));

    var denial =
        new SyncSession.Denial(
            "spec", "forged", "Project 'zeta' isn't on main. Projects there: acme, proj.");
    assertEquals(List.of(denial), first.denials());
    assertEquals(List.of(denial), second.denials());
    assertEquals("zeta", node.specs.findById("forged").orElseThrow().project());
    assertTrue(main.specs.findById("forged").isEmpty());
  }

  @Test
  void aScopedPusherHearsOnlyTheScopeRuleWhateverMainHolds() {
    var scoped = Actor.sync("node", Role.MEMBER).withScope(ProjectScope.of(List.of("acme")));
    Acting.unchecked(
        "node",
        () -> {
          node.specs.create(spec("hidden", "proj"));
          node.specs.create(spec("beyond", "zeta"));
        });

    var hidden = commitAs(scoped, "hidden");
    var beyond = commitAs(scoped, "beyond");

    assertEquals(
        "Project 'proj' was not found.",
        assertInstanceOf(CommitOutcome.Denied.class, hidden).reason());
    assertEquals(
        "Project 'zeta' was not found.",
        assertInstanceOf(CommitOutcome.Denied.class, beyond).reason());
  }

  @Test
  void aRestoreOverMainsTombstoneIntoAProjectMainDoesNotHoldIsDeniedAndStaysOnTheNode() {
    HeldProjects.hold(node.db, "retired");
    Acting.unchecked("node", () -> main.specs.create(spec("old", "retired")));
    SyncBox.round(main, node);
    var born = main.specs.history("old").getFirst().rev();
    Acting.as("node", () -> main.specs.delete("old"));
    SyncBox.round(main, node);

    Acting.as("node", () -> node.specs.restore("old", born));
    var report = specs(SyncBox.round(main, node));

    assertEquals(
        List.of(
            new SyncSession.Denial(
                "spec", "old", "Project 'retired' isn't on main. Projects there: acme, proj.")),
        report.denials());
    assertTrue(main.specs.findById("old").isEmpty());
    assertEquals("retired", node.specs.findById("old").orElseThrow().project());
  }

  private CommitOutcome commitAs(Actor pusher, String id) {
    return Actor.call(pusher, () -> main.replica.commit(id, node.specs.currentForSync(id), null));
  }

  @Test
  void aSpecInAProjectTheNodeMadeWaitsForTheProjectAndLandsTheRoundAfter() {
    Acting.as(
        "node",
        () -> {
          new ProjectStore(node.db).upsert(TestDefinitions.named("fresh"));
          node.specs.create(spec("idea", "fresh"));
        });

    var first = SyncBox.round(main, node);
    var heldBack = main.specs.findById("idea").isEmpty();
    var second = SyncBox.round(main, node);

    assertTrue(heldBack, "the spec waits for its project");
    assertEquals(List.of(), first.stream().flatMap(type -> type.denials().stream()).toList());
    assertEquals(List.of(), first.stream().flatMap(type -> type.refusals().stream()).toList());
    assertEquals(List.of(), second.stream().flatMap(type -> type.denials().stream()).toList());
    assertEquals("fresh", main.specs.findById("idea").orElseThrow().project());
    SyncBox.assertConverged(main, node);
  }

  @Test
  void anEditOfASpecMainHoldsInAProjectNoBoxHoldsIsTaken() {
    Acting.unchecked("node", () -> main.specs.create(spec("legacy", "retired")));
    SyncBox.round(main, node);

    Acting.as("node", () -> node.specs.updateStatus("legacy", SpecStatus.PENDING));
    var report = specs(SyncBox.round(main, node));

    assertEquals(List.of(), report.denials());
    assertEquals(SpecStatus.PENDING, main.specs.findById("legacy").orElseThrow().status());
  }

  private static SyncSession.TypeReport specs(List<SyncSession.TypeReport> types) {
    return types.stream().filter(type -> type.type().equals("spec")).findFirst().orElseThrow();
  }

  private static SpecStore.SpecRow spec(String id, String project) {
    return new SpecStore.SpecRow(
        id,
        project,
        "Title " + id,
        SpecStatus.DRAFT,
        null,
        null,
        null,
        null,
        null,
        0,
        "node",
        null,
        null,
        "node",
        List.of(),
        List.of());
  }
}
