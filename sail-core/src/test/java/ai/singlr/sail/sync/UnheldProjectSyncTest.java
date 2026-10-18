/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
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
  void aSpecInAProjectTheNodeMadeWaitsForTheProjectAndLandsTheRoundAfter() {
    Acting.as(
        "node",
        () -> {
          new ProjectStore(node.db).upsert("fresh", "name: fresh\n");
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
        "",
        "",
        "node",
        List.of(),
        List.of());
  }
}
