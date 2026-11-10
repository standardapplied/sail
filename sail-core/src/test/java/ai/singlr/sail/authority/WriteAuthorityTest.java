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
import static ai.singlr.sail.authority.Actors.OWNER_CLI;
import static ai.singlr.sail.authority.Actors.OWNER_SYNC;
import static ai.singlr.sail.authority.Actors.ROOM;
import static ai.singlr.sail.authority.Actors.SYSTEM;
import static ai.singlr.sail.authority.Actors.VIEWER;
import static ai.singlr.sail.authority.Actors.VIEWER_SYNC;
import static ai.singlr.sail.authority.Actors.projection;
import static ai.singlr.sail.authority.Actors.with;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.authority.Refusal.Kind;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
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
 * The seam every write rule is asked through, and the types the permission table decides alone: a
 * project's shared files are written by whoever may share in it, on every lane; its definition by
 * whoever may configure it, and a rename — the deletion that blocks the old name — by whoever may
 * also purge it, an admin; a push names only whom its pusher may write as. The journal asks no rule
 * of a pushed revision, decided at main's commit, nor of Sail itself.
 */
class WriteAuthorityTest {

  private static final String FILE_ID = "acme/a.txt";
  private static final Map<String, Object> FILE = projection("path", "a.txt", "content_hash", "h");
  private static final Map<String, Object> HELD = projection("definition", "name: acme\n");
  private static final Map<String, Object> EDITED = projection("definition", "name: acme\nx: 1\n");
  private static final Map<String, Object> RENAMED = projection("_blocks_resurrection", true);

  private Board board;
  private WriteAuthority files;
  private WriteAuthority projects;

  @BeforeEach
  void setUp() {
    board = new Board();
    files = new FileStore(board.db).authority();
    projects = new ProjectStore(board.db).authority();
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

  static Stream<Case> files() {
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
  @MethodSource("files")
  void decidesAFile(Case row) {
    assertEquals(
        Optional.ofNullable(row.refused()),
        files.decide(row.actor(), FILE_ID, row.held(), row.next()).map(Refusal::kind));
  }

  static Stream<Case> projects() {
    return Stream.of(
        new Case("a member edits it", OWNER_API, HELD, EDITED, null),
        new Case("a member deletes it", OWNER_API, HELD, null, null),
        new Case("an agent edits it", AGENT, HELD, EDITED, null),
        new Case("a viewer edits none", VIEWER, HELD, EDITED, Kind.READ_ONLY),
        new Case("a viewer renames none", VIEWER, HELD, RENAMED, Kind.READ_ONLY),
        new Case("a viewer's box pushes no rename", VIEWER_SYNC, HELD, RENAMED, Kind.READ_ONLY),
        new Case("a member cannot rename over the API", OWNER_API, HELD, RENAMED, Kind.ADMIN_ONLY),
        new Case("a member cannot rename on the CLI", OWNER_CLI, HELD, RENAMED, Kind.ADMIN_ONLY),
        new Case("an agent cannot rename", AGENT, HELD, RENAMED, Kind.ADMIN_ONLY),
        new Case("a member's box cannot push a rename", OWNER_SYNC, HELD, RENAMED, Kind.ADMIN_ONLY),
        new Case("an admin renames", ADMIN, HELD, RENAMED, null),
        new Case("an admin's box pushes a rename", ADMIN_SYNC, HELD, RENAMED, null),
        new Case("main's rename is decided", MAIN, HELD, RENAMED, null),
        new Case("the machinery renames", SYSTEM, HELD, RENAMED, null),
        new Case(
            "a push as another FDE",
            OWNER_SYNC,
            HELD,
            with(EDITED, "_actor", OTHER),
            Kind.NOT_AUTHOR));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("projects")
  void decidesAProject(Case row) {
    assertEquals(
        Optional.ofNullable(row.refused()),
        projects.decide(row.actor(), "acme", row.held(), row.next()).map(Refusal::kind));
  }

  @Test
  void aRunsRecordedPrincipalThatDoesNotNameItNeverLendsItsOwnerAnotherName() {
    board.db.execute(
        "INSERT INTO run_principals (run_id, principal) VALUES (?, ?)", Actors.RUN, OTHER);

    assertEquals(
        Optional.of(Kind.NOT_AUTHOR),
        files.decide(OWNER_SYNC, FILE_ID, FILE, with(FILE, "_actor", OTHER)).map(Refusal::kind));
  }

  @Test
  void refusalsSpeakTheTableOfWhatTheyTake() {
    assertEquals(
        new Refusal(
            Kind.READ_ONLY,
            "Your credential is read-only and cannot share files and skills in projects.",
            "Ask an admin for a member or admin credential."),
        files.decide(VIEWER, FILE_ID, FILE, FILE).orElseThrow());
    assertEquals(
        new Refusal(
            Kind.READ_ONLY,
            "Your credential is read-only and cannot configure projects.",
            "Ask an admin for a member or admin credential."),
        projects.decide(VIEWER, "acme", HELD, RENAMED).orElseThrow(),
        "a rename configures the project before it purges the old name");
    assertEquals(
        new Refusal(
            Kind.ADMIN_ONLY,
            "Only an admin may erase the name of project 'acme' everywhere, as purging or renaming"
                + " it does.",
            "Ask an admin to do it."),
        projects.decide(OWNER_API, "acme", HELD, RENAMED).orElseThrow());
  }

  @Test
  void aRowOutsideItsActorsAudienceIsInNoProjectItIsGranted() {
    var scoped = OWNER_API.withScope(ProjectScope.of(List.of("beta")));

    assertEquals(
        Optional.of(
            new Refusal(
                Kind.OUT_OF_SCOPE,
                "Project 'acme' is in no project this FDE is granted.",
                Remedy.of(
                    "Ask an admin on main for the project: ",
                    Remedy.sail("fde grant", "<handle>", "<project>"),
                    "."))),
        WriteAuthority.refused(
            scoped, WriteAuthority.Taking.of(Resources.project("acme", OWNER), Action.CONFIGURE)));
    assertEquals(
        Optional.empty(),
        WriteAuthority.refused(
            scoped, WriteAuthority.Taking.of(Resources.project("beta", OWNER), Action.SHARE)));
  }

  @Test
  void theJournalAsksNoRuleOfAPushOrOfSailItself() {
    WriteAuthority refusing = (actor, id, held, next) -> Refusal.readOnly("change anything");

    for (var decided : List.of(OWNER_SYNC, MAIN, SYSTEM)) {
      assertEquals(
          Optional.empty(),
          Actor.call(decided, () -> WriteAuthority.local(refusing, "x", null, null)),
          decided.lane() + " was decided already");
    }
    assertEquals(
        Refusal.readOnly("change anything"),
        Actor.call(ADMIN, () -> WriteAuthority.local(refusing, "x", null, null)));
  }
}
