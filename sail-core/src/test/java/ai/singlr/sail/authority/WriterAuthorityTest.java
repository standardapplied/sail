/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import static ai.singlr.sail.authority.Actors.ADMIN;
import static ai.singlr.sail.authority.Actors.ADMIN_SYNC;
import static ai.singlr.sail.authority.Actors.AGENT;
import static ai.singlr.sail.authority.Actors.AGENT_HANDLE;
import static ai.singlr.sail.authority.Actors.MACHINE;
import static ai.singlr.sail.authority.Actors.MAIN;
import static ai.singlr.sail.authority.Actors.OTHER;
import static ai.singlr.sail.authority.Actors.OTHER_API;
import static ai.singlr.sail.authority.Actors.OTHER_SYNC;
import static ai.singlr.sail.authority.Actors.OWNER;
import static ai.singlr.sail.authority.Actors.OWNER_API;
import static ai.singlr.sail.authority.Actors.OWNER_SYNC;
import static ai.singlr.sail.authority.Actors.ROOM;
import static ai.singlr.sail.authority.Actors.SYSTEM;
import static ai.singlr.sail.authority.Actors.VIEWER;
import static ai.singlr.sail.authority.Actors.VIEWER_SYNC;
import static ai.singlr.sail.authority.Actors.projection;
import static ai.singlr.sail.authority.Actors.with;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.authority.Refusal.Kind;
import ai.singlr.sail.identity.Actor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The rule for files and projects: any role that writes, on every lane, with a push naming only
 * whom its pusher may write as.
 */
class WriterAuthorityTest {

  private static final Map<String, Object> FILE =
      projection("project", "acme", "path", "a.txt", "content_hash", "h");

  private Board board;
  private WriterAuthority rule;

  @BeforeEach
  void setUp() {
    board = new Board();
    rule = new WriterAuthority(board.db, "files");
  }

  @AfterEach
  void tearDown() {
    board.close();
  }

  record Case(
      String name, Actor actor, Map<String, Object> held, Map<String, Object> next, Kind refused) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Case> matrix() {
    var edited = with(FILE, "content_hash", "h2");
    var rows = Stream.<Case>builder();
    for (var actor :
        List.of(
            ADMIN, OWNER_API, OTHER_API, MACHINE, AGENT, OWNER_SYNC, OTHER_SYNC, MAIN, SYSTEM)) {
      rows.add(new Case("create by " + actor, actor, null, FILE, null));
      rows.add(new Case("edit by " + actor, actor, FILE, edited, null));
      rows.add(new Case("delete by " + actor, actor, FILE, null, null));
    }
    for (var actor : List.of(VIEWER, ROOM, VIEWER_SYNC)) {
      rows.add(new Case("create by " + actor, actor, null, FILE, Kind.READ_ONLY));
      rows.add(new Case("edit by " + actor, actor, FILE, edited, Kind.READ_ONLY));
      rows.add(new Case("delete by " + actor, actor, FILE, null, Kind.READ_ONLY));
    }
    rows.add(
        new Case(
            "a push as another FDE",
            OWNER_SYNC,
            FILE,
            with(edited, "_actor", OTHER),
            Kind.NOT_AUTHOR));
    rows.add(
        new Case(
            "an admin's push as another FDE",
            ADMIN_SYNC,
            FILE,
            with(edited, "_actor", OWNER),
            Kind.NOT_AUTHOR));
    rows.add(
        new Case(
            "a push as its run", OWNER_SYNC, FILE, with(edited, "_actor", AGENT_HANDLE), null));
    rows.add(
        new Case(
            "a local write names its actor", OWNER_API, FILE, with(edited, "_actor", OTHER), null));
    return rows.build();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void decides(Case row) {
    assertEquals(
        Optional.ofNullable(row.refused()),
        rule.decide(row.actor(), "f", row.held(), row.next()).map(Refusal::kind));
  }

  @Test
  void aRunsRecordedPrincipalThatDoesNotNameItNeverLendsItsOwnerAnotherName() {
    board.db.execute(
        "INSERT INTO run_principals (run_id, principal) VALUES (?, ?)", Actors.RUN, OTHER);

    assertEquals(
        Optional.of(Kind.NOT_AUTHOR),
        rule.decide(OWNER_SYNC, "f", FILE, with(FILE, "_actor", OTHER)).map(Refusal::kind));
  }

  @Test
  void aReadOnlyRoleIsToldWhatItCannotChange() {
    assertEquals(
        "Your credential is read-only and cannot change files.",
        rule.decide(VIEWER, "f", FILE, FILE).orElseThrow().message());
  }
}
