/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import static ai.singlr.sail.authority.Actors.ADMIN;
import static ai.singlr.sail.authority.Actors.ADMIN_SYNC;
import static ai.singlr.sail.authority.Actors.AGENT;
import static ai.singlr.sail.authority.Actors.MAIN;
import static ai.singlr.sail.authority.Actors.OWNER_API;
import static ai.singlr.sail.authority.Actors.OWNER_CLI;
import static ai.singlr.sail.authority.Actors.OWNER_SYNC;
import static ai.singlr.sail.authority.Actors.SYSTEM;
import static ai.singlr.sail.authority.Actors.VIEWER;
import static ai.singlr.sail.authority.Actors.VIEWER_SYNC;
import static ai.singlr.sail.authority.Actors.projection;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.authority.Refusal.Kind;
import ai.singlr.sail.identity.Actor;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The project rule's matrix: any writer edits or deletes a descriptor, and a rename — the deletion
 * that blocks the old name — is an admin's alone, on this box's lanes and pushed.
 */
class ProjectAuthorityTest {

  private static final Map<String, Object> HELD = projection("definition", "name: acme\n");
  private static final Map<String, Object> EDITED = projection("definition", "name: acme\nx: 1\n");
  private static final Map<String, Object> RENAMED = projection("_blocks_resurrection", true);

  private Board board;
  private ProjectAuthority rule;

  @BeforeEach
  void setUp() {
    board = new Board();
    rule = new ProjectAuthority(board.db);
  }

  @AfterEach
  void tearDown() {
    board.close();
  }

  record Case(String name, Actor actor, Map<String, Object> next, Kind refused) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Case> matrix() {
    return Stream.of(
        new Case("a member edits it", OWNER_API, EDITED, null),
        new Case("a member deletes it", OWNER_API, null, null),
        new Case("a viewer edits none", VIEWER, EDITED, Kind.READ_ONLY),
        new Case("a viewer renames none", VIEWER, RENAMED, Kind.READ_ONLY),
        new Case("a viewer's box pushes no rename", VIEWER_SYNC, RENAMED, Kind.READ_ONLY),
        new Case("a member cannot rename over the API", OWNER_API, RENAMED, Kind.ADMIN_ONLY),
        new Case("a member cannot rename on the CLI", OWNER_CLI, RENAMED, Kind.ADMIN_ONLY),
        new Case("an agent cannot rename", AGENT, RENAMED, Kind.ADMIN_ONLY),
        new Case("a member's box cannot push a rename", OWNER_SYNC, RENAMED, Kind.ADMIN_ONLY),
        new Case("an admin renames", ADMIN, RENAMED, null),
        new Case("an admin's box pushes a rename", ADMIN_SYNC, RENAMED, null),
        new Case("main's rename is decided", MAIN, RENAMED, null),
        new Case("the machinery renames", SYSTEM, RENAMED, null));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void decides(Case row) {
    assertEquals(
        Optional.ofNullable(row.refused()),
        rule.decide(row.actor(), "acme", HELD, row.next()).map(Refusal::kind));
  }

  @Test
  void aRenamesRefusalSaysWhatItMovesAndWhoMay() {
    assertEquals(
        Optional.of(
            new Refusal(
                Kind.ADMIN_ONLY,
                "Renaming project 'acme' moves every spec and file in it, whoever's they are, and"
                    + " is an admin-only action.",
                "Ask an admin to rename it.")),
        rule.decide(OWNER_API, "acme", HELD, RENAMED));
  }
}
