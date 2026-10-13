/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The one predicate every door asks, and how main's roster answers what an FDE's scope is. */
class ProjectScopeTest {

  private Sqlite db;
  private FdeStore fdes;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    fdes = new FdeStore(db);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void everyProjectIsAdmittedByAllAndOnlyTheGrantedOnesByAGrant() {
    var granted = ProjectScope.of(List.of("beta", "alpha"));

    assertTrue(ProjectScope.all().admits("anything"));
    assertTrue(ProjectScope.all().admits(null), "a row of no known project is an unscoped one's");
    assertTrue(granted.admits("alpha"));
    assertFalse(granted.admits("gamma"));
    assertFalse(granted.admits(null));
    assertFalse(ProjectScope.none().admits("alpha"));
    assertFalse(ProjectScope.none().admits(null));
    assertEquals(List.of("alpha", "beta"), List.copyOf(granted.projects()));
    assertSame(ProjectScope.none(), ProjectScope.of(List.of()));
    assertEquals(ProjectScope.all(), new ProjectScope(true, Set.of("ignored")));
    assertTrue(ProjectScope.all().unrestricted());
    assertFalse(ProjectScope.none().unrestricted());
  }

  @Test
  void anActorSeesEveryProjectUnlessADoorScopesIt() {
    var actor = new Actor("ada", Role.MEMBER, Actor.Lane.API);

    assertEquals(ProjectScope.all(), actor.scope());
    assertEquals(ProjectScope.all(), Actor.agentPrincipal("claude/r", "ada").scope());
    assertEquals(ProjectScope.all(), Actor.sync("ada", Role.MEMBER).scope());
    assertEquals(ProjectScope.all(), Actor.main().scope());
    assertEquals(ProjectScope.all(), Actor.system().scope());
    assertEquals(ProjectScope.all(), Actor.cliOperator("ada").scope());
    var scoped = actor.withScope(ProjectScope.none());
    assertEquals(new Actor("ada", Role.MEMBER, Actor.Lane.API, null, ProjectScope.none()), scoped);
    assertThrows(
        NullPointerException.class,
        () -> new Actor("ada", Role.MEMBER, Actor.Lane.API, null, null));
  }

  @Test
  void anFdeIsScopedOnlyOnceMainsRosterSaysSoAndSeesNothingUntilGranted() {
    fdes.add("ada", "Ada", "ada@x.dev", "admin");
    fdes.add("bob", "Bob", "bob@x.dev", "member");

    assertEquals(ProjectScope.all(), ProjectScope.of(db, "bob"));
    assertEquals(ProjectScope.all(), ProjectScope.of(db, "unknown"));
    assertEquals(ProjectScope.all(), ProjectScope.of(db, null));
    assertEquals(ProjectScope.all(), ProjectScope.of(db, " "));

    assertTrue(fdes.restrict("bob"));
    assertFalse(fdes.restrict("unknown"));

    assertEquals(ProjectScope.none(), fdes.scopeOf("bob"));
    assertEquals(ProjectScope.all(), fdes.scopeOf("ada"));
    fdes.grant("bob", "alpha");
    fdes.grant("bob", "alpha");
    fdes.grant("bob", "beta");
    assertEquals(ProjectScope.of(List.of("alpha", "beta")), fdes.scopeOf("bob"));
    assertEquals(ProjectScope.of(List.of("alpha", "beta")), fdes.grantsOf("bob"));
    fdes.revoke("bob", "alpha");
    fdes.revoke("bob", "alpha");
    assertEquals(ProjectScope.of(List.of("beta")), fdes.scopeOf("bob"));
  }

  @Test
  void onlyAScopedFdeIsGrantedAndItsGrantsLeaveWithIt() {
    fdes.add("ada", null, null, "admin");
    var bob = fdes.add("bob", null, null, "member");
    fdes.restrict("bob");
    fdes.grant("bob", "alpha");

    assertEquals(
        "FDE 'ada' sees every project, so there is nothing to grant or revoke. Only an FDE added"
            + " with 'sail fde add <handle> --scoped' is granted projects.",
        assertThrows(IllegalArgumentException.class, () -> fdes.grant("ada", "alpha"))
            .getMessage());
    assertEquals(
        "Unknown FDE 'nobody'.",
        assertThrows(IllegalArgumentException.class, () -> fdes.revoke("nobody", "alpha"))
            .getMessage());
    assertThrows(IllegalArgumentException.class, () -> fdes.grant("bob", "Not A Name"));

    fdes.remove(bob.id());

    assertEquals(
        0L,
        db.queryOne("SELECT count(*) FROM project_grants", row -> row.integer(0)).orElseThrow());
  }

  @Test
  void anAdminIsNeverScopedAndAScopedFdeNeverMadeAdmin() {
    fdes.add("ada", null, null, "admin");
    fdes.add("bob", null, null, "member");
    fdes.restrict("bob");
    var refusal =
        "FDE '%s' cannot be both scoped and an admin: an admin edits the roster, and so could"
            + " grant itself any project. Scope a member or a viewer.";

    assertEquals(
        refusal.formatted("ada"),
        assertThrows(IllegalArgumentException.class, () -> fdes.restrict("ada")).getMessage());
    assertEquals(
        refusal.formatted("bob"),
        assertThrows(IllegalArgumentException.class, () -> fdes.update("bob", null, null, "admin"))
            .getMessage());
    assertEquals(ProjectScope.all(), fdes.scopeOf("ada"));
    assertEquals("member", fdes.byHandle("bob").orElseThrow().role());
    assertEquals("viewer", fdes.update("bob", null, null, "viewer").orElseThrow().role());
    assertEquals("admin", fdes.update("ada", "Ada", null, "admin").orElseThrow().role());
  }

  @Test
  void aScopedReaderReadsOnlyTheFdesWhoShareAProjectWithItAndNoEmailButItsOwn() {
    fdes.add("ada", "Ada", "ada@x.dev", "admin");
    fdes.add("uma", "Uma", "uma@x.dev", "member");
    for (var scoped : List.of("nia", "sam", "zed", "bob")) {
      fdes.add(scoped, scoped.toUpperCase(), scoped + "@x.dev", "member");
      fdes.restrict(scoped);
    }
    fdes.grant("nia", "alpha");
    fdes.grant("sam", "alpha");
    fdes.grant("sam", "beta");
    fdes.grant("zed", "beta");

    var roster = fdes.visibleTo("nia", fdes.scopeOf("nia"));

    assertEquals(
        List.of("ada", "nia", "sam", "uma"), roster.stream().map(FdeStore.Fde::handle).toList());
    assertEquals(
        List.of("", "nia@x.dev", "", ""), roster.stream().map(FdeStore.Fde::email).toList());
    assertEquals(
        List.of("Ada", "NIA", "SAM", "Uma"),
        roster.stream().map(FdeStore.Fde::displayName).toList());
    assertEquals(
        List.of("bob"),
        fdes.visibleTo("bob", fdes.scopeOf("bob")).stream().map(FdeStore.Fde::handle).toList(),
        "a scoped FDE with no grant reads no one but itself");
    assertEquals(List.of(), fdes.visibleTo(null, ProjectScope.none()));
    assertEquals(fdes.list(), fdes.visibleTo("ada", ProjectScope.all()));
  }

  @Test
  void theRoleRuleResolvesAScopeBesideARole() {
    fdes.add("bob", null, null, "member");
    fdes.restrict("bob");
    fdes.grant("bob", "alpha");
    var box = new SyncConfig("main", null, "ada", "main-box");

    assertEquals(ProjectScope.of(List.of("alpha")), new RoleRule(() -> box, fdes).scopeOf("bob"));
    assertEquals(ProjectScope.all(), new RoleRule(() -> box, fdes).scopeOf("ada"));
    assertEquals(ProjectScope.all(), new RoleRule(() -> box, null).scopeOf("bob"));
  }
}
