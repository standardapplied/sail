/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
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
}
