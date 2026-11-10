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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The terminal door: who a PTY session acts as, and whether it may pin a room: a session opened
 * under another project is told the room's, whoever asks, and then the post is decided. A room the
 * FDE may not read is not found ({@code ScopedReadsTest}).
 */
class HostAccessTest {

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
  void aSessionWithNoTokenIsTheBoxsFdeWithItsRoleAndABoxWithNoneIsRefused() throws IOException {
    assertEquals(
        new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API)),
        access.identity(null, "root"));
    assertEquals(
        new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API)),
        access.identity(" ", "ada"));
    assertEquals(
        "This box has no FDE identity. Set one with 'sail host config set sync-handle <handle>' or"
            + " connect through the gateway.",
        assertThrows(Remedied.Io.class, () -> access.identity(null, " ")).getMessage());
    assertEquals(
        "Session token is not valid or has expired.",
        assertThrows(IOException.class, () -> access.identity("nope", "ada")).getMessage());
  }

  @Test
  void aSessionTokenIsItsFdeWithItsRole() throws IOException {
    var sessions = new AuthSessionStore(db);
    var fdes = new FdeStore(db);
    var root = sessions.create(fdes.byHandle("root").orElseThrow().id(), Duration.ofHours(1));
    var ada = sessions.create(fdes.byHandle("ada").orElseThrow().id(), Duration.ofHours(1));

    assertEquals(
        new PtyIdentity.Person(new Actor("root", Role.ADMIN, Actor.Lane.API)),
        access.identity(root.token(), "ada"));
    assertEquals(
        new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API)),
        access.identity(ada.token(), "root"));
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
                        new PtyIdentity.Person(new Actor("vic", Role.MEMBER, Actor.Lane.API))))
            .getMessage());

    access.admit(
        "lobby", "acme", new PtyIdentity.Person(new Actor("ada", Role.MEMBER, Actor.Lane.API)));
  }
}
