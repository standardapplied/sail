/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Table tests for the pure spec access policy: every {role × ownership × verb} shape. */
class SpecPolicyTest {

  private static final String SPEC = "auth";

  private static Actor actor(String handle, Role role) {
    return new Actor(handle, role, Actor.Lane.API);
  }

  private static Actor admin(String handle) {
    return actor(handle, Role.ADMIN);
  }

  private static Actor member(String handle) {
    return actor(handle, Role.MEMBER);
  }

  private static Actor viewer(String handle) {
    return actor(handle, Role.VIEWER);
  }

  private static void assertAllowed(AccessDecision decision) {
    assertInstanceOf(AccessDecision.Allowed.class, decision);
  }

  private static AccessDecision.Refused refused(AccessDecision decision) {
    return assertInstanceOf(AccessDecision.Refused.class, decision);
  }

  @Test
  void adminMayMutateAnyAssignedSpec() {
    assertAllowed(SpecPolicy.mutate(admin("ops"), SPEC, "raj", "raj"));
  }

  @Test
  void adminMayMutateAnUnassignedSpecTheyDidNotCreate() {
    assertAllowed(SpecPolicy.mutate(admin("ops"), SPEC, null, "raj"));
  }

  @Test
  void assigneeMayMutateTheirOwnSpec() {
    assertAllowed(SpecPolicy.mutate(member("uday"), SPEC, "uday", "raj"));
  }

  @Test
  void nonAssigneeMemberIsRefusedNamingTheAssignee() {
    var r = refused(SpecPolicy.mutate(member("uday"), SPEC, "raj", "raj"));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("'raj'"), r.message());
    assertTrue(r.message().contains("'auth'"), r.message());
  }

  @Test
  void creatorMayMutateAnUnassignedSpec() {
    assertAllowed(SpecPolicy.mutate(member("uday"), SPEC, null, "uday"));
  }

  @Test
  void nonCreatorMemberIsRefusedOnUnassignedSpecNamingTheCreator() {
    var r = refused(SpecPolicy.mutate(member("uday"), SPEC, "", "raj"));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("raj"), r.message());
    assertTrue(r.message().contains("unassigned"), r.message());
  }

  @Test
  void viewerIsRefusedReadOnlyEvenOnTheirOwnSpec() {
    var r = refused(SpecPolicy.mutate(viewer("uday"), SPEC, "uday", "uday"));
    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, r.code());
  }

  @Test
  void machineTokenWithoutHandleNeverMatchesAnAssignee() {
    var r = refused(SpecPolicy.mutate(member(null), SPEC, "raj", "raj"));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
  }

  @Test
  void machineAdminTokenMayMutate() {
    assertAllowed(SpecPolicy.mutate(admin(null), SPEC, "raj", "raj"));
  }

  @Test
  void adminMayReassignAnyAssignedSpec() {
    assertAllowed(SpecPolicy.reassign(admin("ops"), SPEC, "raj", "uday"));
  }

  @Test
  void memberCannotReassignSomeoneElsesSpec() {
    var r = refused(SpecPolicy.reassign(member("uday"), SPEC, "raj", "uday"));
    assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, r.code());
    assertTrue(r.message().contains("admin-only"), r.message());
    assertTrue(r.message().contains("raj"), r.message());
  }

  @Test
  void memberCannotReassignEvenTheirOwnSpecToAnother() {
    var r = refused(SpecPolicy.reassign(member("uday"), SPEC, "uday", "raj"));
    assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, r.code());
  }

  @Test
  void memberMayClaimAnUnassignedSpecForThemselves() {
    assertAllowed(SpecPolicy.reassign(member("uday"), SPEC, null, "uday"));
    assertAllowed(SpecPolicy.reassign(member("uday"), SPEC, "", "uday"));
  }

  @Test
  void memberCannotClaimAnUnassignedSpecForSomeoneElse() {
    var r = refused(SpecPolicy.reassign(member("uday"), SPEC, null, "raj"));
    assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, r.code());
  }

  @Test
  void viewerCannotReassign() {
    var r = refused(SpecPolicy.reassign(viewer("uday"), SPEC, null, "uday"));
    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, r.code());
  }

  @Test
  void machineMemberTokenCannotClaimUnassigned() {
    var r = refused(SpecPolicy.reassign(member(null), SPEC, null, "raj"));
    assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, r.code());
  }

  @Test
  void agentPrincipalMayMutateItsOwnersSpec() {
    var agent = Actor.agentPrincipal("claude/a1b2c3", "raj");

    assertAllowed(SpecPolicy.mutate(agent, SPEC, "raj", "someone"));
    assertAllowed(SpecPolicy.mutate(agent, SPEC, null, "raj"));
  }

  @Test
  void agentPrincipalMayNotMutateAnotherFdesSpec() {
    var agent = Actor.agentPrincipal("claude/a1b2c3", "raj");

    var refusal = refused(SpecPolicy.mutate(agent, SPEC, "sumesh", "sumesh"));

    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, refusal.code());
  }
}
