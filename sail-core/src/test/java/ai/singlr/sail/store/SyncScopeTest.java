/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.identity.ProjectScope;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What a node keeps of the scope main last named, and how it forgets how far it had read. */
class SyncScopeTest {

  private Sqlite db;
  private SyncScope scope;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    scope = new SyncScope(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void aBoxNoWelcomeHasScopedSeesEveryProject() {
    assertEquals(ProjectScope.all(), scope.held());
  }

  @Test
  void eachWelcomeReplacesTheScopeWholeAndNamingNoneMeansEveryProject() {
    scope.record(ProjectScope.of(List.of("alpha", "beta")));
    assertEquals(ProjectScope.of(List.of("alpha", "beta")), scope.held());

    scope.record(ProjectScope.none());
    assertEquals(ProjectScope.none(), scope.held());

    scope.record(ProjectScope.of(List.of("gamma")));
    assertEquals(ProjectScope.of(List.of("gamma")), scope.held());

    scope.record(ProjectScope.all());
    assertEquals(ProjectScope.all(), scope.held());
    assertEquals(
        1L, db.queryOne("SELECT count(*) FROM sync_scope", row -> row.integer(0)).orElseThrow());
  }

  @Test
  void resettingForgetsEveryCheckpointOfThatPeerAlone() {
    var state = new SyncState(db);
    state.advance("main", "spec", 40);
    state.advance("main", "run", 7);
    state.advance("other", "spec", 9);

    state.reset("main");

    assertEquals(0, state.checkpoint("main", "spec"));
    assertEquals(0, state.checkpoint("main", "run"));
    assertEquals(9, state.checkpoint("other", "spec"));
    state.advance("main", "spec", 3);
    assertEquals(3, state.checkpoint("main", "spec"));
  }
}
