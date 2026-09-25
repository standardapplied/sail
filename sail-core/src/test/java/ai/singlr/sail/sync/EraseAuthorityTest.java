/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Main decides a node's request to erase on main's own copy, never the node's. */
@ActingAs
class EraseAuthorityTest {

  private static final Actor MADY = Actor.sync("mady", Role.MEMBER);
  private static final Actor ADMIN = Actor.sync("uday", Role.ADMIN);

  private Sqlite db;
  private SpecStore specs;
  private EraseAuthority authority;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    specs = new SpecStore(db);
    authority = new EraseAuthority(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void theAssigneeOrTheCreatorOfAnUnassignedSpecMayPruneIt() {
    create(spec("assigned", "mady", "uday"));
    create(spec("unassigned", null, "mady"));

    assertEquals(Optional.empty(), authority.refusal(MADY, "spec", "assigned"));
    assertEquals(Optional.empty(), authority.refusal(MADY, "spec", "unassigned"));
  }

  @Test
  void anotherMembersSpecIsRefusedNamingItsOwnerAndAnAdminMayPruneIt() {
    create(spec("theirs", "uday", "uday"));

    assertEquals(
        Optional.of("spec 'theirs' belongs to 'uday'; ask them or an admin"),
        authority.refusal(MADY, "spec", "theirs"));
    assertEquals(
        Optional.empty(), authority.refusal(Actor.sync("mady", Role.ADMIN), "spec", "theirs"));
  }

  @Test
  void aDeletedSpecIsOwnedByWhoItsTombstoneNames() {
    create(spec("gone", "uday", "uday"));
    specs.delete("gone");

    assertTrue(authority.refusal(MADY, "spec", "gone").orElseThrow().contains("'uday'"));
  }

  @Test
  void aSpecMainHoldsNothingOfIsRefusedEvenToAnAdminAndOneWithNoOwnerIsAdminOnly() {
    create(spec("ownerless", null, null));
    var never =
        Optional.of(
            "main holds no spec 'never-here', so it cannot tell whose it is; sync it before"
                + " pruning");

    assertEquals(never, authority.refusal(MADY, "spec", "never-here"));
    assertEquals(never, authority.refusal(ADMIN, "spec", "never-here"));
    assertEquals(
        Optional.of("spec 'ownerless' has no owner; only an admin can prune it"),
        authority.refusal(MADY, "spec", "ownerless"));
    assertEquals(Optional.empty(), authority.refusal(ADMIN, "spec", "ownerless"));
  }

  @Test
  void aSpecStillOnTheBoardIsRefusedWhateverItsOwnerSaysUntilArchivedCancelledOrDeleted() {
    create(spec("live", "mady", "mady", SpecStatus.IN_PROGRESS));
    create(spec("dropped", "mady", "mady", SpecStatus.CANCELLED));
    create(spec("deleted", "mady", "mady", SpecStatus.PENDING));
    specs.delete("deleted");

    assertEquals(
        Optional.of("spec 'live' is in_progress on main; archive or cancel it before pruning"),
        authority.refusal(ADMIN, "spec", "live"));
    assertEquals(Optional.empty(), authority.refusal(MADY, "spec", "dropped"));
    assertEquals(Optional.empty(), authority.refusal(MADY, "spec", "deleted"));
  }

  @Test
  void aPlanWithARunStillGoingMustWait() {
    create(spec("busy", "mady", "mady"));
    var runs = new RunStore(db);
    var going =
        runs.create(
            "r1", "proj", "busy", "node", "node", "build", "claude", "b", "t", null, null, "/l",
            "u");
    var erasure = new Erasure(db);
    var plan = erasure.closure(List.of(new Erasure.Target("spec", "busy")));

    assertEquals(
        Optional.of("run '" + going + "' has not finished; stop it before pruning"),
        authority.busy(plan));

    runs.complete(going, "completed", 0);
    assertEquals(Optional.empty(), authority.busy(plan));
  }

  @Test
  void aSpecMainAlreadyErasedHasNothingLeftToProtect() {
    create(spec("theirs", "uday", "uday"));
    var erasure = new Erasure(db);
    erasure.erase(erasure.closure(List.of(new Erasure.Target("spec", "theirs"))), "local");

    assertEquals(Optional.empty(), authority.refusal(MADY, "spec", "theirs"));
    assertEquals(
        Optional.of("a read-only role cannot prune"),
        authority.refusal(Actor.sync("mady", Role.VIEWER), "spec", "theirs"));
  }

  @Test
  void aViewerAWholeProjectAndAnythingButSpecsAndProjectsAreRefused() {
    assertEquals(
        Optional.of("a read-only role cannot prune"),
        authority.refusal(Actor.sync("mady", Role.VIEWER), "spec", "any"));
    assertEquals(
        Optional.of("pruning a whole project is admin-only"),
        authority.refusal(MADY, "project", "acme"));
    assertEquals(
        Optional.of("main holds no project 'acme'"), authority.refusal(ADMIN, "project", "acme"));
    create(spec("in-acme", "mady", "mady", SpecStatus.DONE, "acme"));
    assertEquals(Optional.empty(), authority.refusal(ADMIN, "project", "acme"));
    assertEquals(
        Optional.of("only specs and projects are pruned on request, not a message"),
        authority.refusal(Actor.sync("uday", Role.ADMIN), "message", "m"));
  }

  private void create(SpecStore.SpecRow row) {
    Acting.as(row.createdBy(), () -> specs.create(row));
  }

  private static SpecStore.SpecRow spec(String id, String assignee, String createdBy) {
    return spec(id, assignee, createdBy, SpecStatus.ARCHIVED);
  }

  private static SpecStore.SpecRow spec(
      String id, String assignee, String createdBy, SpecStatus status) {
    return spec(id, assignee, createdBy, status, "proj");
  }

  private static SpecStore.SpecRow spec(
      String id, String assignee, String createdBy, SpecStatus status, String project) {
    return new SpecStore.SpecRow(
        id, project, id, status, assignee, null, null, null, null, 0, createdBy, "", "", createdBy,
        List.of(), List.of());
  }
}
