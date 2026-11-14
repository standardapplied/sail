/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * B1: a terminal resuming a spec's conversation in its workspace holds that place as a run does, so
 * the loop's lanes of the spec wait on it, as they waited on a terminal in the project's container
 * on main, and nothing the loop runs writes the tree a person's agent is writing.
 */
class TerminalHoldTest {

  private static final String NODE = "eng";

  @TempDir Path dir;

  private RunStore runs;

  private HostLanes.Dispatching lanes() {
    return new HostLanes.Dispatching(null, null, runs, id -> java.util.Optional.empty(), null);
  }

  private RunStore.RunRow completedBuild(String spec) {
    var id = DateTimeUtils.newId().toString();
    Acting.system(
        () -> {
          runs.reserveDispatch(
              id, "acme", spec, NODE, "build", List.of("app"), "claude-code", null, "t", "l", "u");
          runs.recordSession(id, "sess-1", "hook", null);
          runs.finish(id, "running", "completed", 0, null);
        });
    return runs.findById(id).orElseThrow();
  }

  private RunStore.Reservation reserve(String spec, String role) {
    return Acting.system(
        () ->
            runs.reserveDispatch(
                DateTimeUtils.newId().toString(),
                "acme",
                spec,
                NODE,
                role,
                List.of("app"),
                "claude-code",
                null,
                "t",
                "l",
                "u"));
  }

  @Test
  void aHeldTerminalKeepsEveryLaneOfItsSpecWaitingAndNoneOfAnotherSpecUntilItEnds() {
    try (var db = Sqlite.open(dir.resolve("t.db"))) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
      runs = new RunStore(db);
      var build = completedBuild("auth");

      var hold = Acting.system(() -> lanes().holdForTerminal(build, NODE));

      var held = runs.findById(hold).orElseThrow();
      assertEquals(build.place(), held.place());
      assertEquals("sess-1", held.sessionId());
      assertEquals(HostLanes.TERMINAL, held.sessionSource());
      for (var role : List.of("build", "review", "fix", DispatchGate.ROOM_FULL_ROLE)) {
        var waiting =
            assertInstanceOf(RunStore.Reservation.Conflicted.class, reserve("auth", role));
        assertEquals(hold, waiting.conflict().run().runId(), role);
      }
      assertInstanceOf(RunStore.Reservation.Reserved.class, reserve("billing", "review"));

      Acting.system(() -> lanes().releaseTerminal(hold, 0));

      assertEquals("completed", runs.findById(hold).orElseThrow().status());
      assertInstanceOf(RunStore.Reservation.Reserved.class, reserve("auth", "review"));
    }
  }

  @Test
  void aTerminalIsNotHeldBesideALiveRunOfItsSpecSuchAsAFixThatWillCommitTheTree() {
    try (var db = Sqlite.open(dir.resolve("t.db"))) {
      new SchemaManager(db).migrate();
      HeldProjects.hold(db, "acme");
      runs = new RunStore(db);
      var build = completedBuild("auth");
      assertInstanceOf(RunStore.Reservation.Reserved.class, reserve("auth", "fix"));

      var refused =
          assertThrows(
              IllegalStateException.class,
              () -> Acting.system(() -> lanes().holdForTerminal(build, NODE)));

      assertTrue(refused.getMessage().contains("(fix) is live in acme-auth"), refused.getMessage());
      assertEquals(2, runs.listForProject("acme").size(), "nothing was written for the terminal");
    }
  }
}
