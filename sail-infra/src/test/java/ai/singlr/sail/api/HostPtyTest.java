/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The terminal facet's project gate: a session opens only in a project its person holds here. */
class HostPtyTest {

  private Sqlite db;
  private HostLanes.Pty pty;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    pty =
        new HostLanes.Pty(
            db, TestAuth.roles(db, new SyncConfig("main", null, "root", "box")), null);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void aTerminalOpensOnlyInAProjectItsPersonHoldsOnThisBoxWhateverTheirRole() throws IOException {
    var absent =
        "Project 'beta' is not in the catalog. Create it with 'sail project create beta', or sync"
            + " it from main with 'sail sync'.";
    var held =
        new Actor("ada", Role.MEMBER, Actor.Lane.API).withScope(ProjectScope.of(List.of("acme")));
    var viewer =
        new Actor("vic", Role.VIEWER, Actor.Lane.API).withScope(ProjectScope.of(List.of("acme")));
    var elsewhere =
        new Actor("ada", Role.MEMBER, Actor.Lane.API).withScope(ProjectScope.of(List.of("beta")));
    HeldProjects.hold(db, "beta");

    pty.admitProject("acme", new PtyIdentity.Person(held));
    pty.admitProject("acme", new PtyIdentity.Person(viewer));
    assertEquals(
        absent,
        assertThrows(
                Remedied.Io.class, () -> pty.admitProject("beta", new PtyIdentity.Person(held)))
            .getMessage());
    assertEquals(
        "Project 'acme' is not in the catalog. Create it with 'sail project create acme', or sync"
            + " it from main with 'sail sync'.",
        assertThrows(
                Remedied.Io.class,
                () -> pty.admitProject("acme", new PtyIdentity.Person(elsewhere)))
            .getMessage());
    assertEquals(
        "Project 'gone' is not in the catalog. Create it with 'sail project create gone', or sync"
            + " it from main with 'sail sync'.",
        assertThrows(
                Remedied.Io.class,
                () ->
                    pty.admitProject(
                        "gone",
                        new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API))))
            .getMessage());
  }

  @Test
  void theDispatchAuthorityOpensNoTerminal() {
    assertEquals(
        "The host's dispatch authority opens no terminal.",
        assertThrows(IOException.class, () -> pty.admitProject("acme", PtyIdentity.DISPATCH))
            .getMessage());
  }
}
