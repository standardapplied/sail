/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Main decides a node's request to erase on main's own copy, never the node's. */
class EraseAuthorityTest {

  private static final SyncPrincipal MADY = new SyncPrincipal("mady", true);

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
    specs.create(spec("assigned", "mady", "uday"));
    specs.create(spec("unassigned", null, "mady"));

    assertEquals(Optional.empty(), authority.refusal(MADY, "spec", "assigned"));
    assertEquals(Optional.empty(), authority.refusal(MADY, "spec", "unassigned"));
  }

  @Test
  void anotherMembersSpecIsRefusedNamingItsOwnerAndAnAdminMayPruneIt() {
    specs.create(spec("theirs", "uday", "uday"));

    assertEquals(
        Optional.of("spec 'theirs' belongs to 'uday'; ask them or an admin"),
        authority.refusal(MADY, "spec", "theirs"));
    assertEquals(
        Optional.empty(),
        authority.refusal(new SyncPrincipal("mady", true, true), "spec", "theirs"));
  }

  @Test
  void aDeletedSpecIsOwnedByWhoItsTombstoneNames() {
    specs.create(spec("gone", "uday", "uday"));
    specs.delete("gone");

    assertTrue(authority.refusal(MADY, "spec", "gone").orElseThrow().contains("'uday'"));
  }

  @Test
  void aSpecMainDoesNotHoldHasNothingToProtectButAnOwnerlessOneIsAdminOnly() {
    specs.create(spec("ownerless", null, null));

    assertEquals(Optional.empty(), authority.refusal(MADY, "spec", "never-here"));
    assertEquals(
        Optional.of("spec 'ownerless' has no owner; only an admin can prune it"),
        authority.refusal(MADY, "spec", "ownerless"));
  }

  @Test
  void aViewerAWholeProjectAndAnythingButSpecsAndProjectsAreRefused() {
    assertEquals(
        Optional.of("a read-only role cannot prune"),
        authority.refusal(new SyncPrincipal("mady", false), "spec", "any"));
    assertEquals(
        Optional.of("pruning a whole project is admin-only"),
        authority.refusal(MADY, "project", "acme"));
    assertEquals(
        Optional.empty(),
        authority.refusal(new SyncPrincipal("uday", true, true), "project", "acme"));
    assertEquals(
        Optional.of("only specs and projects are pruned on request, not a message"),
        authority.refusal(new SyncPrincipal("uday", true, true), "message", "m"));
  }

  private static SpecStore.SpecRow spec(String id, String assignee, String createdBy) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        id,
        SpecStatus.ARCHIVED,
        assignee,
        null,
        null,
        null,
        null,
        0,
        createdBy,
        "",
        "",
        createdBy,
        List.of(),
        List.of());
  }
}
