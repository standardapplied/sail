/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.authority.Refusal;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A box takes a spec or a room only into a project its catalog holds, so the next round never takes
 * back what it accepted: a birth or a move into any other project is refused before anything is
 * written, naming the projects here and, on a scoped box, the grant that unblocks it.
 */
class UnheldProjectTest {

  private Sqlite db;
  private SpecStore specs;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme", "beta");
    specs = new SpecStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void aSpecIsBornOnlyIntoAProjectThisBoxHoldsAndNothingIsWritten() {
    var refused =
        assertThrows(
            WriteRefused.class, () -> Acting.as("ada", () -> specs.create(spec("idea", "nosuch"))));

    assertEquals(
        new Refusal(
            Refusal.Kind.UNHELD_PROJECT,
            "Project 'nosuch' isn't on this box. Projects here: acme, beta.",
            null),
        refused.refusal());
    assertEquals(0, count("SELECT count(*) FROM specs"));
    assertEquals(0, count("SELECT count(*) FROM change_log WHERE entity_type = 'spec'"));
  }

  @Test
  void aScopedNodeNamesTheGrantThatUnblocksIt() {
    db.execute("DELETE FROM projects WHERE name = 'beta'");
    new SyncScope(db).record(ProjectScope.of(List.of("acme")));

    var refused =
        assertThrows(
            WriteRefused.class, () -> Acting.as("ada", () -> specs.create(spec("c-beta", "beta"))));

    assertEquals(
        new Refusal(
            Refusal.Kind.UNHELD_PROJECT,
            "Project 'beta' isn't on this box. Projects here: acme.",
            "Ask an admin to grant it on main: sudo sail fde grant ada beta."),
        refused.refusal());
    assertEquals(0, count("SELECT count(*) FROM specs"));
  }

  @Test
  void aScopedNodeTakesNothingIntoAProjectBeyondWhatMainGrantsEvenOneItHolds() {
    new SyncScope(db).record(ProjectScope.of(List.of("acme")));

    var refused =
        assertThrows(
            WriteRefused.class, () -> Acting.as("ada", () -> specs.create(spec("c-beta", "beta"))));

    assertEquals(
        new Refusal(
            Refusal.Kind.UNHELD_PROJECT,
            "Project 'beta' isn't on this box. Projects here: acme.",
            "Ask an admin to grant it on main: sudo sail fde grant ada beta."),
        refused.refusal());
    assertEquals(0, count("SELECT count(*) FROM specs"));
  }

  @Test
  void aRestoreIsABirthIntoItsProject() {
    Acting.as("ada", () -> specs.create(spec("idea", "beta")));
    var born = specs.history("idea").getFirst().rev();
    Acting.as("ada", () -> specs.delete("idea"));
    db.execute("DELETE FROM projects WHERE name = 'beta'");

    var refused =
        assertThrows(
            WriteRefused.class,
            () -> Acting.as("ada", () -> specs.restore("idea", born, OperatorCommand.PLAIN)));

    assertEquals(
        "Project 'beta' isn't on this box. Projects here: acme.", refused.refusal().message());
    assertTrue(specs.findById("idea").isEmpty());
  }

  @Test
  void anActorIsToldOnlyOfTheProjectsItMaySee() {
    var scoped =
        new Actor(
            "ada", Role.MEMBER, Actor.Lane.API, null, ProjectScope.of(List.of("acme", "gamma")));

    var refused =
        assertThrows(
            WriteRefused.class, () -> Acting.by(scoped, () -> specs.create(spec("x", "gamma"))));

    assertEquals(
        new Refusal(
            Refusal.Kind.UNHELD_PROJECT,
            "Project 'gamma' isn't on this box. Projects here: acme.",
            null),
        refused.refusal());
  }

  @Test
  void aBoxHoldingNoProjectSaysSo() {
    db.execute("DELETE FROM projects");

    var refused =
        assertThrows(
            WriteRefused.class, () -> Acting.as("ada", () -> specs.create(spec("x", "acme"))));

    assertEquals(
        "Project 'acme' isn't on this box. Projects here: none.", refused.refusal().message());
  }

  @Test
  void aSpecMovesOnlyIntoAProjectThisBoxHolds() {
    Acting.as("ada", () -> specs.create(spec("idea", "acme")));

    var refused =
        assertThrows(
            WriteRefused.class, () -> Acting.as("ada", () -> specs.update(spec("idea", "nosuch"))));
    Acting.as("ada", () -> specs.update(spec("idea", "beta")));

    assertEquals(
        "Project 'nosuch' isn't on this box. Projects here: acme, beta.",
        refused.refusal().message());
    assertEquals("beta", specs.findById("idea").orElseThrow().project());
  }

  @Test
  void aSpecStaysInAProjectThisBoxNoLongerHolds() {
    Acting.unchecked("ada", () -> specs.create(spec("legacy", "retired")));

    Acting.as("ada", () -> specs.updateStatus("legacy", SpecStatus.PENDING));

    assertEquals(SpecStatus.PENDING, specs.findById("legacy").orElseThrow().status());
  }

  @Test
  void aRoomIsBornOnlyIntoAProjectThisBoxHolds() {
    var rooms = new RoomStore(db);

    var refused =
        assertThrows(
            WriteRefused.class,
            () ->
                Acting.as(
                    "ada",
                    () ->
                        rooms.create(
                            new RoomStore.RoomRow(
                                "lounge", "nosuch", "Lounge", null, null, null, null, null, null,
                                null))));

    assertEquals(
        "Project 'nosuch' isn't on this box. Projects here: acme, beta.",
        refused.refusal().message());
    assertEquals(0, count("SELECT count(*) FROM rooms"));
  }

  @Test
  void whatMainDecidedAndThisBoxsMachineryAreNotAsked() {
    Acting.by(Actor.main("ada"), () -> specs.create(spec("pulled", "nosuch")));
    Acting.system(() -> specs.create(spec("seeded", "nosuch")));

    assertEquals(2, count("SELECT count(*) FROM specs WHERE project = 'nosuch'"));
  }

  private long count(String sql) {
    return db.queryOne(sql, row -> row.integer(0)).orElseThrow();
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
        "ada",
        "",
        "",
        "ada",
        List.of(),
        List.of());
  }
}
