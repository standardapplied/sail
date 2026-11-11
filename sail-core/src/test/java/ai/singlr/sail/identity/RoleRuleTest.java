/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.RoleRule.Credential;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RoleRuleTest {

  private static final SyncConfig MAIN = new SyncConfig("main", null, "uday", "main-box");
  private static final SyncConfig NODE = new SyncConfig("node", "sail@main", "uday", "node-box");
  private static final SyncConfig STANDALONE = new SyncConfig(null, null, "uday", null);

  private Sqlite db;
  private FdeStore roster;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    roster = new FdeStore(db);
    roster.add("uday", null, null, "member");
    roster.add("mady", null, null, "member");
    roster.add("raj", null, null, "admin");
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private Optional<Role> roleOf(SyncConfig box, String handle, Role cap) {
    return roleOf(new RoleRule(() -> box, roster), handle, cap);
  }

  /** The role {@code rule} gives a credential naming FDE {@code handle}, minted as {@code cap}. */
  private static Optional<Role> roleOf(RoleRule rule, String handle, Role cap) {
    return rule.principal(new Credential.Person(handle, cap, Actor.Lane.API)).map(Actor::role);
  }

  /** The role {@code rule} gives a credential naming no FDE, minted as {@code cap}. */
  private static Optional<Role> unbound(RoleRule rule, Role cap) {
    return rule.principal(new Credential.Machine(cap)).map(Actor::role);
  }

  @Test
  void theOperatorOfMainOrAStandaloneBoxIsAdminWhateverTheRoster() {
    assertEquals(Optional.of(Role.ADMIN), roleOf(MAIN, "uday", Role.ADMIN));
    assertEquals(Optional.of(Role.ADMIN), roleOf(STANDALONE, "uday", Role.ADMIN));
  }

  @Test
  void aNodesFdeActsWithTheRoleMainsRosterGivesIt() {
    assertEquals(Optional.of(Role.MEMBER), roleOf(NODE, "uday", Role.ADMIN));
  }

  @Test
  void everyOtherFdeActsWithItsRosterRole() {
    assertEquals(Optional.of(Role.MEMBER), roleOf(MAIN, "mady", Role.ADMIN));
    assertEquals(Optional.of(Role.ADMIN), roleOf(MAIN, "raj", Role.ADMIN));
  }

  @Test
  void theCredentialsOwnRoleCapsWhateverTheRosterOrTheBoxGives() {
    assertEquals(Optional.of(Role.VIEWER), roleOf(MAIN, "uday", Role.VIEWER));
    assertEquals(Optional.of(Role.MEMBER), roleOf(MAIN, "raj", Role.MEMBER));
    assertEquals(Optional.of(Role.MEMBER), roleOf(NODE, "uday", Role.ADMIN));
  }

  @Test
  void onANodeEveryOtherFdeReadsOnlyWhateverItsRoleOrItsCredential() {
    assertEquals(Optional.of(Role.VIEWER), roleOf(NODE, "raj", Role.ADMIN), "an admin included");
    assertEquals(Optional.of(Role.VIEWER), roleOf(NODE, "mady", Role.MEMBER));
    assertEquals(Optional.of(Role.VIEWER), roleOf(NODE, "mady", Role.VIEWER));
    assertEquals(Optional.of(Role.MEMBER), roleOf(NODE, "uday", Role.MEMBER), "the box's own FDE");
    assertEquals(Optional.of(Role.ADMIN), roleOf(MAIN, "raj", Role.ADMIN), "main caps no one");
    assertEquals(Optional.of(Role.MEMBER), roleOf(STANDALONE, "mady", Role.ADMIN));
  }

  @Test
  void onANodeACredentialNamingNoFdeActsWithTheBoxFdesRole() {
    var rule = new RoleRule(() -> NODE, roster);

    assertEquals(Optional.of(Role.MEMBER), unbound(rule, Role.ADMIN));
    roster.update("uday", null, null, "admin");
    assertEquals(Optional.of(Role.ADMIN), unbound(rule, Role.ADMIN));
  }

  @Test
  void aDemotedFdeActsWithItsDemotedRole() {
    roster.update("raj", null, null, "viewer");

    assertEquals(Optional.of(Role.VIEWER), roleOf(MAIN, "raj", Role.ADMIN));
  }

  @Test
  void aDisabledFdeIsRefusedEvenAsTheOperator() {
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle IN ('uday', 'mady')");

    assertEquals(Optional.empty(), roleOf(MAIN, "uday", Role.ADMIN));
    assertEquals(Optional.empty(), roleOf(MAIN, "mady", Role.ADMIN));
  }

  @Test
  void anFdeTheRosterDoesNotKnowIsRefusedUnlessItIsTheOperator() {
    assertEquals(Optional.empty(), roleOf(MAIN, "stranger", Role.ADMIN));
    assertEquals(Optional.empty(), roleOf(NODE, "stranger", Role.ADMIN));
    assertEquals(
        Optional.of(Role.ADMIN),
        roleOf(
            new RoleRule(() -> new SyncConfig("main", null, "root", null), roster),
            "root",
            Role.ADMIN));
  }

  @Test
  void aBlankHandleIsNoFdeAndABoxWithNoRosterKnowsOnlyItsOperator() {
    assertEquals(Optional.empty(), roleOf(MAIN, " ", Role.ADMIN));
    assertEquals(Optional.empty(), roleOf(MAIN, null, Role.ADMIN));
    var bare = new RoleRule(() -> MAIN, null);
    assertEquals(Optional.of(Role.ADMIN), roleOf(bare, "uday", Role.ADMIN));
    assertEquals(Optional.empty(), roleOf(bare, "mady", Role.ADMIN));
  }

  @Test
  void aCredentialNamingNoFdeActsAsTheBoxsFdeWhenTheBoxHasASyncHandle() {
    assertEquals(Optional.of(Role.ADMIN), unbound(new RoleRule(() -> MAIN, roster), Role.ADMIN));
    assertEquals(Optional.of(Role.MEMBER), unbound(new RoleRule(() -> NODE, roster), Role.ADMIN));
    assertEquals(
        Optional.of(Role.MEMBER), unbound(new RoleRule(() -> STANDALONE, roster), Role.MEMBER));
    assertEquals(
        Optional.of(Role.ADMIN),
        unbound(new RoleRule(SyncConfig::unset, roster), Role.ADMIN),
        "a box with no sync handle has no FDE to cap it");
  }

  @Test
  void aCredentialNamingNoFdeOnANodeWhoseRosterLacksItsFdeIsRefused() {
    var stranger = new SyncConfig("node", "sail@main", "stranger", "node-box");

    assertEquals(Optional.empty(), unbound(new RoleRule(() -> stranger, roster), Role.ADMIN));
    assertEquals(Optional.empty(), unbound(new RoleRule(() -> NODE, null), Role.ADMIN));
  }

  @Test
  void aCredentialNamingNoFdeOnANodeThatNamesNoFdeIsRefused() {
    var anonymous = new SyncConfig("node", "sail@main", null, "node-box");

    assertEquals(Optional.empty(), unbound(new RoleRule(() -> anonymous, roster), Role.ADMIN));
  }

  @Test
  void aRoleIsCappedByTheLesserOfTheTwo() {
    assertEquals(Role.MEMBER, Role.ADMIN.cappedBy(Role.MEMBER));
    assertEquals(Role.VIEWER, Role.VIEWER.cappedBy(Role.ADMIN));
    assertEquals(Role.MEMBER, Role.MEMBER.cappedBy(Role.MEMBER));
    assertEquals("viewer", Role.VIEWER.attribute());
  }

  @Test
  void theOperatorIsNeverScopedWhateverTheRosterSaysAndAnActorCarriesItsRoleAndScopeTogether() {
    roster.restrict("uday");
    roster.restrict("mady");
    roster.grant("mady", "alpha");
    var rule = new RoleRule(() -> MAIN, roster);

    assertEquals(
        Optional.of(ProjectScope.all()),
        rule.principal(Credential.person("uday", Actor.Lane.API)).map(Actor::scope),
        "main's own FDE acts as an admin");
    assertEquals(
        Optional.of(new Actor("uday", Role.ADMIN, Actor.Lane.API)),
        rule.principal(Credential.person("uday", Actor.Lane.API)));
    assertEquals(
        Optional.of(
            new Actor("mady", Role.MEMBER, Actor.Lane.SYNC)
                .withScope(ProjectScope.of(List.of("alpha")))),
        rule.principal(Credential.person("mady", Actor.Lane.SYNC)));
    assertEquals(Optional.empty(), rule.principal(Credential.person("nobody", Actor.Lane.API)));
    assertEquals(
        Optional.of(ProjectScope.all()),
        new RoleRule(() -> MAIN, null)
            .principal(new Credential.Machine(Role.ADMIN))
            .map(Actor::scope),
        "a box that keeps no roster scopes no one");
  }

  @Test
  void aPersonAndATokenActAsTheFdeTheyNameWithItsRoleCappedAndItsScope() {
    roster.restrict("mady");
    roster.grant("mady", "alpha");
    var rule = new RoleRule(() -> MAIN, roster);
    var alpha = ProjectScope.of(List.of("alpha"));

    assertEquals(
        Optional.of(new Actor("mady", Role.MEMBER, Actor.Lane.SYNC).withScope(alpha)),
        rule.principal(Credential.person("mady", Actor.Lane.SYNC)));
    assertEquals(
        Optional.of(new Actor("mady", Role.VIEWER, Actor.Lane.API).withScope(alpha)),
        rule.principal(Credential.token("mady", "viewer")));
    assertEquals(
        Optional.of(new Actor("raj", Role.ADMIN, Actor.Lane.API)),
        rule.principal(Credential.token("raj", "admin")));
    assertEquals(
        Optional.of(new Actor("raj", Role.ADMIN, Actor.Lane.CLI)),
        rule.principal(Credential.person("raj", Actor.Lane.CLI)));
    assertEquals(
        Optional.of(new Actor("raj", Role.ADMIN, Actor.Lane.API)),
        rule.principal(Credential.person("raj", Actor.Lane.API)),
        "a person's own credential carries their whole role");
  }

  @Test
  void aSessionIsItsPersonWithThePrincipalTheRuleGivesThemOrNothing() {
    var rule = new RoleRule(() -> MAIN, roster);
    var sessions = new AuthSessionStore(db);
    var mady = sessions.create(roster.byHandle("mady").orElseThrow().id(), Duration.ofMinutes(1));

    assertEquals(Optional.empty(), rule.session(sessions, "sess_bogus", Actor.Lane.API));
    assertEquals(
        Optional.of(new Actor("mady", Role.MEMBER, Actor.Lane.SYNC)),
        rule.session(sessions, mady.token(), Actor.Lane.SYNC).orElseThrow().principal());
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = 'mady'");
    assertEquals(
        Optional.empty(),
        rule.session(sessions, mady.token(), Actor.Lane.API).orElseThrow().principal(),
        "a disabled FDE's session is its person, refused a principal");
    assertEquals(
        Optional.empty(),
        new RoleRule(() -> MAIN, null).session(sessions, mady.token(), Actor.Lane.API),
        "a box that keeps no roster knows no session's person");
  }

  @Test
  void aCredentialNamingNoOneHereIsRefused() {
    var rule = new RoleRule(() -> MAIN, roster);
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = 'mady'");

    assertEquals(Optional.empty(), rule.principal(Credential.person(" ", Actor.Lane.API)));
    assertEquals(Optional.empty(), rule.principal(Credential.token("mady", "member")));
    roster.add("stranger", null, null, "admin");
    assertEquals(
        Optional.empty(),
        new RoleRule(() -> MAIN, null).principal(Credential.person("stranger", Actor.Lane.API)),
        "a box that keeps no roster knows no one but its operator");
    assertThrows(NullPointerException.class, () -> rule.principal(null));
    assertThrows(NullPointerException.class, () -> Credential.run(null, null));
  }

  @Test
  void theBoxsOwnCredentialsAndAMachineTokenActWithTheBoxFdesRoleAndScope() {
    roster.restrict("uday");
    roster.grant("uday", "alpha");
    var node = new RoleRule(() -> NODE, roster);
    var alpha = ProjectScope.of(List.of("alpha"));

    assertEquals(
        Optional.of(new Actor("uday", Role.MEMBER, Actor.Lane.CLI).withScope(alpha)),
        node.principal(Credential.box()));
    assertEquals(
        Optional.of(new Actor("uday", Role.VIEWER, Actor.Lane.API).withScope(alpha)),
        node.principal(Credential.hostToken("viewer")));
    assertEquals(
        Optional.of(new Actor(null, Role.MEMBER, Actor.Lane.API).withScope(alpha)),
        node.principal(Credential.machine("admin")),
        "a machine token acts as no one, seeing no more than the box's FDE");
    assertEquals(
        Optional.of(new Actor(null, Role.VIEWER, Actor.Lane.API).withScope(alpha)),
        node.principal(Credential.machine("viewer")),
        "and no more than it was minted with");
    assertEquals(
        Optional.of(new Actor("uday", Role.ADMIN, Actor.Lane.CLI)),
        new RoleRule(() -> MAIN, roster).principal(Credential.box()),
        "main's own FDE is its admin, never scoped");
    assertEquals(
        Optional.of(new Actor("uday", Role.MEMBER, Actor.Lane.CONTAINER).withScope(alpha)),
        node.principal(Credential.container()),
        "the box credential in a container is the box's FDE, on the container lane");
    assertEquals(
        Optional.of(new Actor(null, Role.ADMIN, Actor.Lane.CLI)),
        new RoleRule(SyncConfig::unset, roster).principal(Credential.box()),
        "a box with no sync handle has no FDE to be, and its root is an admin");
    var anonymous = new RoleRule(() -> new SyncConfig("node", "sail@main", null, "n"), roster);
    assertEquals(Optional.empty(), anonymous.principal(Credential.box()));
    assertEquals(Optional.empty(), anonymous.principal(Credential.machine("member")));
  }

  @Test
  void aRunIsItsPrincipalForTheFdeItActsForOrForTheBoxsFde() {
    roster.restrict("uday");
    roster.grant("uday", "alpha");
    roster.restrict("mady");
    roster.grant("mady", "beta");
    var main = new RoleRule(() -> MAIN, roster);
    var build = run("019fee00-0000-7000-8000-000000000001", "mady", "build");
    var room = run("019fee00-0000-7000-8000-000000000002", "mady", "room");
    var ownerless = run("019fee00-0000-7000-8000-000000000003", null, "build");

    assertEquals(
        Optional.of(
            Actor.agentPrincipal(build.principal(), "mady")
                .withScope(ProjectScope.of(List.of("beta")))
                .withWork(new Actor.Work("acme", "s1", "s1"))),
        main.principal(Credential.run(build, "s1")));
    assertEquals(
        Optional.of(
            Actor.roomPrincipal(room.principal(), "mady")
                .withScope(ProjectScope.of(List.of()))
                .withWork(new Actor.Work("acme", "s1", "r1"))),
        main.principal(Credential.run(room, "r1")),
        "a read-only run reads only its own project, and mady is not granted acme");
    assertEquals(
        Optional.of(
            new Actor(ownerless.principal(), Role.MEMBER, Actor.Lane.AGENT, null)
                .withScope(ProjectScope.of(List.of("alpha")))
                .withWork(new Actor.Work("acme", "s1", "s1"))),
        new RoleRule(() -> NODE, roster).principal(Credential.run(ownerless, "s1")),
        "a run acting for no FDE sees what the box's FDE sees, never every project");
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = 'mady'");
    assertEquals(Optional.empty(), main.principal(Credential.run(build, "s1")));
  }

  /**
   * A run works what it was launched for: a build, a review, a fix and a room member its spec and
   * that spec's room, or a spec-less room member its room; an ad-hoc run its whole project. A
   * read-only run reads the project it was launched in alone, of what its person reads.
   */
  @Test
  void aRunWorksWhatItWasLaunchedForAndAReadOnlyRunReadsOnlyItsProject() {
    var main = new RoleRule(() -> MAIN, roster);
    var adhoc = run("019fee00-0000-7000-8000-000000000004", "mady", "adhoc", null);
    var full = run("019fee00-0000-7000-8000-000000000005", "mady", "room-full", null);
    var wake = run("019fee00-0000-7000-8000-000000000006", "mady", "room", null);

    assertEquals(
        new Actor.Work("acme", null, null),
        main.principal(Credential.run(adhoc, "lobby")).orElseThrow().work());
    assertEquals(
        new Actor.Work("acme", null, "lobby"),
        main.principal(Credential.run(full, "lobby")).orElseThrow().work());
    assertEquals(
        ProjectScope.all(), main.principal(Credential.run(full, "lobby")).orElseThrow().scope());
    assertEquals(
        ProjectScope.of(List.of("acme")),
        main.principal(Credential.run(wake, "lobby")).orElseThrow().scope());
  }

  private RunStore.RunRow run(String id, String owner, String lane) {
    return run(id, owner, lane, "s1");
  }

  private RunStore.RunRow run(String id, String owner, String lane, String spec) {
    var runs = new RunStore(db);
    Acting.system(
        () ->
            runs.create(
                id, "acme", spec, owner, lane, "claude-code", "b", "t", null, null, "/l", "u"));
    return runs.findById(id).orElseThrow();
  }
}
