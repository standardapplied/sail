/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.api.TestAuth;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The terminal door: who a PTY session acts as, and whether it may pin a room: a session opened
 * under another project is told the room's, whoever asks, and then the post is decided. A room the
 * FDE may not read is not found ({@code ScopedReadsTest}).
 */
class HostAccessTest {

  private static final SyncConfig ADAS_NODE = new SyncConfig("node", "sail@main", "ada", "ada-box");

  private Sqlite db;
  private HostAccess access;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    var fdes = new FdeStore(db);
    Acting.system(
        () -> {
          fdes.add("root", "Root", "root@x", "admin");
          fdes.add("ada", "Ada", "ada@x", "member");
          fdes.add("vic", "Vic", "vic@x", "viewer");
          new RoomStore(db)
              .createJournaled(
                  new RoomStore.RoomRow(
                      "lobby",
                      "acme",
                      "lobby",
                      "ada",
                      null,
                      null,
                      "ada",
                      DateTimeUtils.now(),
                      DateTimeUtils.now(),
                      null));
        });
    access = new HostAccess(db, TestAuth.roles(db, new SyncConfig("main", null, "root", "box")));
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void aSessionWithNoTokenIsTheBoxsPersonOnTheCliLaneAndABoxWithNoneIsRefused() throws IOException {
    var fdes = new FdeStore(db);
    fdes.restrict("ada");
    fdes.grant("ada", "acme");
    var adas = new HostAccess(db, TestAuth.roles(db, ADAS_NODE));

    assertEquals(
        new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.CLI)),
        access.identity(null));
    assertEquals(
        new PtyIdentity.Person(
            new Actor("ada", Role.MEMBER, Actor.Lane.CLI)
                .withScope(ProjectScope.of(List.of("acme")))),
        adas.identity(" "));
    assertEquals(
        "This box has no FDE identity. Set one with 'sail host config set sync-handle <handle>' or"
            + " connect through the gateway.",
        assertThrows(
                Remedied.Io.class,
                () -> new HostAccess(db, TestAuth.roles(db, SyncConfig.unset())).identity(null))
            .getMessage());
    assertEquals(
        "Session token is not valid or has expired.",
        assertThrows(IOException.class, () -> access.identity("nope")).getMessage());
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = 'ada'");
    assertEquals(
        "FDE 'ada' is disabled or not in this box's roster. Ask an admin to re-enable it, or run"
            + " 'sail sync' on a node that has not synced its roster.",
        assertThrows(Remedied.Io.class, () -> adas.identity(null)).getMessage());
  }

  @Test
  void aSessionTokenIsItsFdeOnTheApiLaneWithItsRoleAndScope() throws IOException {
    var sessions = new AuthSessionStore(db);
    var fdes = new FdeStore(db);
    fdes.restrict("ada");
    fdes.grant("ada", "acme");
    var root = sessions.create(fdes.byHandle("root").orElseThrow().id(), Duration.ofHours(1));
    var ada = sessions.create(fdes.byHandle("ada").orElseThrow().id(), Duration.ofHours(1));

    assertEquals(
        new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API)),
        access.identity(root.token()));
    assertEquals(
        new PtyIdentity.Person(
            new Actor("ada", Role.MEMBER, Actor.Lane.API)
                .withScope(ProjectScope.of(List.of("acme")))),
        access.identity(ada.token()));
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

    access.admitProject("acme", new PtyIdentity.Person(held));
    access.admitProject("acme", new PtyIdentity.Person(viewer));
    assertEquals(
        absent,
        assertThrows(
                Remedied.Io.class, () -> access.admitProject("beta", new PtyIdentity.Person(held)))
            .getMessage());
    assertEquals(
        "Project 'acme' is not in the catalog. Create it with 'sail project create acme', or sync"
            + " it from main with 'sail sync'.",
        assertThrows(
                Remedied.Io.class,
                () -> access.admitProject("acme", new PtyIdentity.Person(elsewhere)))
            .getMessage());
    assertEquals(
        "Project 'gone' is not in the catalog. Create it with 'sail project create gone', or sync"
            + " it from main with 'sail sync'.",
        assertThrows(
                Remedied.Io.class,
                () ->
                    access.admitProject(
                        "gone",
                        new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API))))
            .getMessage());
  }

  @Test
  void theDispatchAuthorityOpensNoTerminalAndPinsNoRoom() {
    assertEquals(
        "The host's dispatch authority opens no terminal.",
        assertThrows(IOException.class, () -> access.admitProject("acme", PtyIdentity.DISPATCH))
            .getMessage());
    assertEquals(
        "The host's dispatch authority opens no terminal.",
        assertThrows(IOException.class, () -> access.admit("lobby", "acme", PtyIdentity.DISPATCH))
            .getMessage());
  }

  @Test
  void aSessionUnderAnotherProjectIsToldTheRoomsWhoeverAsksAndThenThePostIsDecided()
      throws IOException {
    var mismatch = "Room 'lobby' belongs to project 'acme'; open the session with --project acme.";
    for (var fde : new String[] {"ada", "vic"}) {
      assertEquals(
          mismatch,
          assertThrows(
                  IOException.class,
                  () ->
                      access.admit(
                          "lobby",
                          "beta",
                          new PtyIdentity.Person(new Actor(fde, Role.MEMBER, Actor.Lane.API))))
              .getMessage(),
          fde);
    }
    assertEquals(
        "Your credential is read-only and cannot post in rooms. Ask an admin for a member or admin"
            + " credential.",
        assertThrows(
                Remedied.Io.class,
                () ->
                    access.admit(
                        "lobby",
                        "acme",
                        new PtyIdentity.Person(new Actor("vic", Role.VIEWER, Actor.Lane.API))))
            .getMessage());

    access.admit(
        "lobby", "acme", new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API)));
  }
}
