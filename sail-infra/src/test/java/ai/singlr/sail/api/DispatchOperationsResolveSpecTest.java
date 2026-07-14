/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

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

/**
 * The shared resolution behind both dispatch lanes reads the control-plane DB and picks strictly
 * this box's FDE-assigned specs, honoring {@code --restart} for non-pending re-dispatch.
 */
class DispatchOperationsResolveSpecTest {

  private static final String PROJECT = "acme-health";
  private static final String FDE = "me";
  private static final Actor OPERATOR = Actor.cliOperator(FDE);

  @TempDir Path dbDir;

  private SpecStore store() {
    var db = Sqlite.open(dbDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    return new SpecStore(db);
  }

  private static SpecStore.SpecRow row(String id, String status) {
    return row(id, status, FDE);
  }

  private static SpecStore.SpecRow row(String id, String status, String assignee) {
    return new SpecStore.SpecRow(
        id,
        PROJECT,
        "Title for " + id,
        SpecStatus.fromWire(status),
        assignee,
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

  private static DispatchOperations.SpecResolution resolve(
      String specId, boolean restart, List<Spec> specs, SpecStore store) {
    return DispatchOperations.resolveSpec(specs, specId, restart, OPERATOR, FDE);
  }

  @Test
  void autoSelectsThisFdesNextPendingWhenNoSpecGiven() {
    var store = store();
    store.create(row("done-spec", "done"));
    store.create(row("oauth-flow", "pending"));

    var resolution = resolve(null, false, specsOf(store), store);

    assertEquals("oauth-flow", resolution.spec().id());
    assertFalse(resolution.restarted());
    assertNull(resolution.previousStatus());
  }

  @Test
  void autoSelectSkipsUnassignedAndOtherFdeSpecs() {
    var store = store();
    store.create(row("unassigned", "pending", null));
    store.create(row("someone-else", "pending", "mady"));

    assertNull(resolve(null, false, specsOf(store), store).spec());
  }

  @Test
  void autoSelectReturnsNullSpecWhenNothingIsPending() {
    var store = store();
    store.create(row("done-spec", "done"));

    var resolution = resolve(null, false, specsOf(store), store);

    assertNull(resolution.spec());
    assertFalse(resolution.restarted());
  }

  @Test
  void explicitSpecPassesWhenPendingAndAssignedToThisFde() {
    var store = store();
    store.create(row("oauth-flow", "pending"));

    var resolution = resolve("oauth-flow", false, specsOf(store), store);

    assertEquals("oauth-flow", resolution.spec().id());
    assertFalse(resolution.restarted());
  }

  @Test
  void explicitUnassignedSpecIsRejected() {
    var store = store();
    store.create(row("unscoped", "pending", null));

    var ex =
        assertThrows(ApiException.class, () -> resolve("unscoped", false, specsOf(store), store));
    assertEquals(ErrorCode.RUNS_ON_OTHER_NODE, ex.failure().errorCode());
    assertTrue(ex.getMessage().contains("unassigned"));
    assertTrue(ex.failure().action().contains("--assignee " + FDE));
  }

  @Test
  void explicitSpecOfAnotherFdeIsRejected() {
    var store = store();
    store.create(row("theirs", "pending", "mady"));

    var ex =
        assertThrows(ApiException.class, () -> resolve("theirs", false, specsOf(store), store));
    assertEquals(ErrorCode.RUNS_ON_OTHER_NODE, ex.failure().errorCode());
    assertTrue(ex.getMessage().contains("assigned to 'mady'"));
    assertTrue(ex.failure().action().contains("--assignee"));
  }

  @Test
  void explicitPendingSpecWithRestartIsANoOp() {
    var store = store();
    store.create(row("oauth-flow", "pending"));

    var resolution = resolve("oauth-flow", true, specsOf(store), store);

    assertEquals("oauth-flow", resolution.spec().id());
    assertFalse(resolution.restarted(), "already pending — nothing to restart");
    assertEquals(SpecStatus.PENDING, store.findById("oauth-flow").orElseThrow().status());
  }

  @Test
  void unknownSpecIsANotFoundRefusal() {
    var store = store();
    store.create(row("oauth-flow", "pending"));

    var ex = assertThrows(ApiException.class, () -> resolve("nope", false, specsOf(store), store));
    assertEquals(ErrorCode.SPEC_NOT_FOUND, ex.failure().errorCode());
    assertTrue(ex.getMessage().contains("nope"));
  }

  @Test
  void explicitDependencyBlockedSpecIsRefusedNotReady() {
    var store = store();
    store.create(row("auth", "pending"));
    var blocked = row("billing", "pending");
    store.create(
        new SpecStore.SpecRow(
            blocked.id(),
            blocked.project(),
            blocked.title(),
            blocked.status(),
            blocked.assignee(),
            null,
            null,
            null,
            null,
            0,
            "me",
            null,
            null,
            "me",
            List.of("auth"),
            List.of()));

    var ex =
        assertThrows(ApiException.class, () -> resolve("billing", false, specsOf(store), store));
    assertEquals(ErrorCode.SPEC_NOT_READY, ex.failure().errorCode());
  }

  @Test
  void nonPendingSpecWithoutRestartThrowsHelpfulError() {
    var store = store();
    store.create(row("oauth-flow", "in_progress"));

    var ex =
        assertThrows(ApiException.class, () -> resolve("oauth-flow", false, specsOf(store), store));
    assertEquals(ErrorCode.SPEC_NOT_READY, ex.failure().errorCode());
    assertTrue(ex.getMessage().contains("oauth-flow"));
    assertTrue(ex.getMessage().contains("in_progress"));
    assertTrue(ex.failure().action().contains("restart"));
  }

  @Test
  void restartWithoutASpecIdIsRefusedBeforeAutoSelection() {
    var store = store();
    store.create(row("oauth-flow", "in_progress"));

    var ex = assertThrows(ApiException.class, () -> resolve(null, true, specsOf(store), store));

    assertEquals(ErrorCode.INVALID_REQUEST, ex.failure().errorCode());
    assertTrue(ex.getMessage().contains("spec id"));
    assertEquals(
        SpecStatus.IN_PROGRESS,
        store.findById("oauth-flow").orElseThrow().status(),
        "a refused restart must never reset any spec's status");
  }

  @Test
  void doneAndReviewSpecsWithoutRestartThrow() {
    var store = store();
    store.create(row("done-one", "done"));
    store.create(row("review-one", "review"));
    store.create(row("parked-one", "awaiting_merge"));
    var specs = specsOf(store);

    assertThrows(ApiException.class, () -> resolve("done-one", false, specs, store));
    assertThrows(ApiException.class, () -> resolve("review-one", false, specs, store));
    assertThrows(ApiException.class, () -> resolve("parked-one", false, specs, store));
  }

  @Test
  void restartReportsTheRestartWithoutTouchingTheStore() {
    var store = store();
    store.create(row("oauth-flow", "in_progress"));

    var resolution = resolve("oauth-flow", true, specsOf(store), store);

    assertEquals("oauth-flow", resolution.spec().id());
    assertTrue(resolution.restarted(), "non-pending spec + --restart must mark restarted=true");
    assertEquals(
        "in_progress",
        resolution.previousStatus(),
        "previousStatus carries the pre-reset status so the executor publishes spec_restarted");
    assertEquals(
        SpecStatus.IN_PROGRESS,
        store.findById("oauth-flow").orElseThrow().status(),
        "resolution is pure — the executor resets the status only after every gate has passed");
  }

  @Test
  void restartReportsAnAwaitingMergeSpecToo() {
    var store = store();
    store.create(row("parked", "awaiting_merge"));

    var resolution = resolve("parked", true, specsOf(store), store);

    assertTrue(resolution.restarted());
    assertEquals("awaiting_merge", resolution.previousStatus());
  }

  @Test
  void restartOfAnotherFdesSpecIsRefusedBeforeAnyReset() {
    var store = store();
    store.create(row("theirs", "in_progress", "mady"));

    assertThrows(ApiException.class, () -> resolve("theirs", true, specsOf(store), store));
    assertEquals(
        SpecStatus.IN_PROGRESS,
        store.findById("theirs").orElseThrow().status(),
        "a refused caller must never reset a spec's status");
  }
}
