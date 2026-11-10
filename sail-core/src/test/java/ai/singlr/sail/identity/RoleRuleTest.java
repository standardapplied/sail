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
  private static final Duration MINUTE = Duration.ofMinutes(1);
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
    return new RoleRule(() -> box, roster).roleOf(handle, cap);
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

    assertEquals(Optional.of(Role.MEMBER), rule.roleOfUnbound(Role.ADMIN));
    roster.update("uday", null, null, "admin");
    assertEquals(Optional.of(Role.ADMIN), rule.roleOfUnbound(Role.ADMIN));
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
        new RoleRule(() -> new SyncConfig("main", null, "root", null), roster)
            .roleOf("root", Role.ADMIN));
  }

  @Test
  void aBlankHandleIsNoFdeAndABoxWithNoRosterKnowsOnlyItsOperator() {
    assertEquals(Optional.empty(), roleOf(MAIN, " ", Role.ADMIN));
    assertEquals(Optional.empty(), roleOf(MAIN, null, Role.ADMIN));
    var bare = new RoleRule(() -> MAIN, null);
    assertEquals(Optional.of(Role.ADMIN), bare.roleOf("uday", Role.ADMIN));
    assertEquals(Optional.empty(), bare.roleOf("mady", Role.ADMIN));
  }

  @Test
  void aCredentialNamingNoFdeActsAsTheBoxsFdeWhenTheBoxHasASyncHandle() {
    assertEquals(
        Optional.of(Role.ADMIN), new RoleRule(() -> MAIN, roster).roleOfUnbound(Role.ADMIN));
    assertEquals(
        Optional.of(Role.MEMBER), new RoleRule(() -> NODE, roster).roleOfUnbound(Role.ADMIN));
    assertEquals(
        Optional.of(Role.MEMBER),
        new RoleRule(() -> STANDALONE, roster).roleOfUnbound(Role.MEMBER));
    assertEquals(
        Optional.of(Role.ADMIN),
        new RoleRule(SyncConfig::unset, roster).roleOfUnbound(Role.ADMIN),
        "a box with no sync handle has no FDE to cap it");
  }

  @Test
  void aCredentialNamingNoFdeOnANodeWhoseRosterLacksItsFdeIsRefused() {
    var stranger = new SyncConfig("node", "sail@main", "stranger", "node-box");

    assertEquals(Optional.empty(), new RoleRule(() -> stranger, roster).roleOfUnbound(Role.ADMIN));
    assertEquals(Optional.empty(), new RoleRule(() -> NODE, null).roleOfUnbound(Role.ADMIN));
  }

  @Test
  void aCredentialNamingNoFdeOnANodeThatNamesNoFdeIsRefused() {
    var anonymous = new SyncConfig("node", "sail@main", null, "node-box");

    assertEquals(Optional.empty(), new RoleRule(() -> anonymous, roster).roleOfUnbound(Role.ADMIN));
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

    assertEquals(ProjectScope.all(), rule.scopeOf("uday"), "main's own FDE acts as an admin");
    assertEquals(
        Optional.of(new Actor("uday", Role.ADMIN, Actor.Lane.API)),
        rule.actorOf("uday", Role.ADMIN, Actor.Lane.API));
    assertEquals(
        Optional.of(
            new Actor("mady", Role.MEMBER, Actor.Lane.SYNC)
                .withScope(ProjectScope.of(List.of("alpha")))),
        rule.actorOf("mady", Role.ADMIN, Actor.Lane.SYNC));
    assertEquals(Optional.empty(), rule.actorOf("nobody", Role.ADMIN, Actor.Lane.API));
    assertEquals(ProjectScope.all(), new RoleRule(() -> MAIN, null).scopeOf("mady"));
  }

  @Test
  void aPersonATokenAndASessionActAsTheFdeTheyNameWithItsRoleCappedAndItsScope() {
    roster.restrict("mady");
    roster.grant("mady", "alpha");
    var rule = new RoleRule(() -> MAIN, roster);
    var alpha = ProjectScope.of(List.of("alpha"));
    var sessions = new AuthSessionStore(db);
    var session =
        sessions
            .validate(sessions.create(roster.byHandle("mady").orElseThrow().id(), MINUTE).token())
            .orElseThrow();

    assertEquals(
        Optional.of(new Actor("mady", Role.MEMBER, Actor.Lane.SYNC).withScope(alpha)),
        rule.principal(Credential.person("mady", Actor.Lane.SYNC)));
    assertEquals(
        Optional.of(new Actor("mady", Role.VIEWER, Actor.Lane.API).withScope(alpha)),
        rule.principal(Credential.token("mady", "viewer")));
    assertEquals(
        Optional.of(new Actor("mady", Role.MEMBER, Actor.Lane.SYNC).withScope(alpha)),
        rule.principal(Credential.session(session, Actor.Lane.SYNC)));
    assertEquals(
        Optional.of(new Actor("raj", Role.ADMIN, Actor.Lane.API)),
        rule.principal(Credential.token("raj", "admin")));
    assertEquals(
        Optional.of(new Actor("raj", Role.ADMIN, Actor.Lane.CLI)),
        rule.principal(Credential.person("raj", Actor.Lane.CLI)));
    var raj = sessions.create(roster.byHandle("raj").orElseThrow().id(), MINUTE).token();
    assertEquals(
        Optional.of(new Actor("raj", Role.ADMIN, Actor.Lane.API)),
        rule.principal(Credential.session(sessions.validate(raj).orElseThrow(), Actor.Lane.API)),
        "a session carries its person's whole role");
  }

  @Test
  void aCredentialNamingNoOneHereIsRefused() {
    var rule = new RoleRule(() -> MAIN, roster);
    var gone = new AuthSessionStore.SessionInfo("no-such-fde", null, null);
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = 'mady'");

    assertEquals(Optional.empty(), rule.principal(Credential.person(" ", Actor.Lane.API)));
    assertEquals(Optional.empty(), rule.principal(Credential.token("mady", "member")));
    assertEquals(Optional.empty(), rule.principal(Credential.session(gone, Actor.Lane.SYNC)));
    var stranger = roster.add("stranger", null, null, "admin");
    assertEquals(
        Optional.empty(),
        new RoleRule(() -> MAIN, null)
            .principal(
                Credential.session(
                    new AuthSessionStore.SessionInfo(stranger.id(), null, null), Actor.Lane.API)),
        "a box that keeps no roster knows no session's FDE");
    assertThrows(NullPointerException.class, () -> rule.principal(null));
    assertThrows(NullPointerException.class, () -> Credential.session(null, Actor.Lane.API));
    assertThrows(NullPointerException.class, () -> Credential.run(null));
  }

  @Test
  void theBoxsOwnCredentialsAndAMachineTokenActWithTheBoxFdesRoleAndScope() {
    roster.restrict("uday");
    roster.grant("uday", "alpha");
    var node = new RoleRule(() -> NODE, roster);
    var alpha = ProjectScope.of(List.of("alpha"));

    assertEquals(
        Optional.of(new Actor("uday", Role.MEMBER, Actor.Lane.CLI).withScope(alpha)),
        node.principal(Credential.box(Actor.Lane.CLI)));
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
        new RoleRule(() -> MAIN, roster).principal(Credential.box(Actor.Lane.CLI)),
        "main's own FDE is its admin, never scoped");
    assertEquals(
        Optional.of(new Actor(null, Role.ADMIN, Actor.Lane.CLI)),
        new RoleRule(SyncConfig::unset, roster).principal(Credential.box(Actor.Lane.CLI)),
        "a box with no sync handle has no FDE to be, and its root is an admin");
    var anonymous = new RoleRule(() -> new SyncConfig("node", "sail@main", null, "n"), roster);
    assertEquals(Optional.empty(), anonymous.principal(Credential.box(Actor.Lane.CLI)));
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
                .withScope(ProjectScope.of(List.of("beta")))),
        main.principal(Credential.run(build)));
    assertEquals(
        Optional.of(
            Actor.roomPrincipal(room.principal(), "mady")
                .withScope(ProjectScope.of(List.of("beta")))),
        main.principal(Credential.run(room)));
    assertEquals(
        Optional.of(
            new Actor(ownerless.principal(), Role.MEMBER, Actor.Lane.AGENT, null)
                .withScope(ProjectScope.of(List.of("alpha")))),
        new RoleRule(() -> NODE, roster).principal(Credential.run(ownerless)),
        "a run acting for no FDE sees what the box's FDE sees, never every project");
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = 'mady'");
    assertEquals(Optional.empty(), main.principal(Credential.run(build)));
  }

  private RunStore.RunRow run(String id, String owner, String lane) {
    var runs = new RunStore(db);
    Acting.system(
        () ->
            runs.create(
                id, "acme", null, owner, lane, "claude-code", "b", "t", null, null, "/l", "u"));
    return runs.findById(id).orElseThrow();
  }
}
