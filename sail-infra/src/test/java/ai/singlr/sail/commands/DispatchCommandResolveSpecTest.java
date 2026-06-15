/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Resolution now reads from the control-plane DB, so a stopped container never blocks dispatch. */
class DispatchCommandResolveSpecTest {

  private static final String PROJECT = "acme-health";

  @TempDir Path dbDir;

  private SpecStore store() {
    var db = Sqlite.open(dbDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    return new SpecStore(db);
  }

  private static SpecStore.SpecRow row(String id, String status) {
    return new SpecStore.SpecRow(
        id,
        PROJECT,
        "Title for " + id,
        SpecStatus.fromWire(status),
        null,
        null,
        null,
        null,
        null,
        0,
        "me",
        null,
        null,
        "me",
        List.of(),
        List.of());
  }

  private List<Spec> specsOf(SpecStore store) {
    return store.projectSpecs(PROJECT);
  }

  @Test
  void autoSelectsTheNextPendingWhenNoSpecGiven() {
    var store = store();
    store.create(row("done-spec", "done"));
    store.create(row("oauth-flow", "pending"));

    var resolution = DispatchCommand.resolveSpec(null, false, specsOf(store), store);

    assertEquals("oauth-flow", resolution.spec().id());
    assertFalse(resolution.restarted());
    assertNull(resolution.previousStatus());
  }

  @Test
  void autoSelectReturnsNullSpecWhenNothingIsPending() {
    var store = store();
    store.create(row("done-spec", "done"));

    var resolution = DispatchCommand.resolveSpec(null, false, specsOf(store), store);

    assertNull(resolution.spec());
    assertFalse(resolution.restarted());
  }

  @Test
  void explicitSpecPassesWhenPending() {
    var store = store();
    store.create(row("oauth-flow", "pending"));

    var resolution = DispatchCommand.resolveSpec("oauth-flow", false, specsOf(store), store);

    assertEquals("oauth-flow", resolution.spec().id());
    assertFalse(resolution.restarted());
  }

  @Test
  void explicitPendingSpecWithRestartIsANoOp() {
    var store = store();
    store.create(row("oauth-flow", "pending"));

    var resolution = DispatchCommand.resolveSpec("oauth-flow", true, specsOf(store), store);

    assertEquals("oauth-flow", resolution.spec().id());
    assertFalse(resolution.restarted(), "already pending — nothing to restart");
    assertEquals(SpecStatus.PENDING, store.findById("oauth-flow").orElseThrow().status());
  }

  @Test
  void unknownSpecThrowsIllegalArgument() {
    var store = store();
    store.create(row("oauth-flow", "pending"));

    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> DispatchCommand.resolveSpec("nope", false, specsOf(store), store));
    assertTrue(ex.getMessage().contains("nope"));
  }

  @Test
  void nonPendingSpecWithoutRestartThrowsHelpfulError() {
    var store = store();
    store.create(row("oauth-flow", "in_progress"));

    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> DispatchCommand.resolveSpec("oauth-flow", false, specsOf(store), store));
    assertTrue(ex.getMessage().contains("oauth-flow"));
    assertTrue(ex.getMessage().contains("in_progress"));
    assertTrue(ex.getMessage().contains("--restart"));
  }

  @Test
  void doneAndReviewSpecsWithoutRestartThrow() {
    var store = store();
    store.create(row("done-one", "done"));
    store.create(row("review-one", "review"));
    var specs = specsOf(store);

    assertThrows(
        IllegalStateException.class,
        () -> DispatchCommand.resolveSpec("done-one", false, specs, store));
    assertThrows(
        IllegalStateException.class,
        () -> DispatchCommand.resolveSpec("review-one", false, specs, store));
  }

  @Test
  void restartResetsTheStatusToPendingInTheDb() {
    var store = store();
    store.create(row("oauth-flow", "in_progress"));

    var resolution = DispatchCommand.resolveSpec("oauth-flow", true, specsOf(store), store);

    assertEquals("oauth-flow", resolution.spec().id());
    assertTrue(resolution.restarted(), "non-pending spec + --restart must mark restarted=true");
    assertEquals(
        "in_progress",
        resolution.previousStatus(),
        "previousStatus carries the pre-reset status so the caller can publish spec_restarted");
    assertEquals(SpecStatus.PENDING, store.findById("oauth-flow").orElseThrow().status());
  }
}
