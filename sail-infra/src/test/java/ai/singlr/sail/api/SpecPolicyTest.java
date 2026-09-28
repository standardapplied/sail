/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.util.List;
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
    assertAllowed(Actor.call(admin("ops"), () -> SpecPolicy.mutate(SPEC, "raj", "raj")));
  }

  @Test
  void adminMayMutateAnUnassignedSpecTheyDidNotCreate() {
    assertAllowed(Actor.call(admin("ops"), () -> SpecPolicy.mutate(SPEC, null, "raj")));
  }

  @Test
  void assigneeMayMutateTheirOwnSpec() {
    assertAllowed(Actor.call(member("uday"), () -> SpecPolicy.mutate(SPEC, "uday", "raj")));
  }

  @Test
  void nonAssigneeMemberIsRefusedNamingTheAssignee() {
    var r = refused(Actor.call(member("uday"), () -> SpecPolicy.mutate(SPEC, "raj", "raj")));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("'raj'"), r.message());
    assertTrue(r.message().contains("'auth'"), r.message());
  }

  @Test
  void creatorMayMutateAnUnassignedSpec() {
    assertAllowed(Actor.call(member("uday"), () -> SpecPolicy.mutate(SPEC, null, "uday")));
  }

  @Test
  void nonCreatorMemberIsRefusedOnUnassignedSpecNamingTheCreator() {
    var r = refused(Actor.call(member("uday"), () -> SpecPolicy.mutate(SPEC, "", "raj")));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("raj"), r.message());
    assertTrue(r.message().contains("unassigned"), r.message());
  }

  @Test
  void viewerIsRefusedReadOnlyEvenOnTheirOwnSpec() {
    var r = refused(Actor.call(viewer("uday"), () -> SpecPolicy.mutate(SPEC, "uday", "uday")));
    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, r.code());
  }

  @Test
  void machineTokenWithoutHandleNeverMatchesAnAssignee() {
    var r = refused(Actor.call(member(null), () -> SpecPolicy.mutate(SPEC, "raj", "raj")));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
  }

  @Test
  void machineAdminTokenMayMutate() {
    assertAllowed(Actor.call(admin(null), () -> SpecPolicy.mutate(SPEC, "raj", "raj")));
  }

  @Test
  void adminMayReassignAnyAssignedSpec() {
    assertAllowed(
        Actor.call(admin("ops"), () -> SpecPolicy.reassign(SPEC, "raj", "uday", null, List.of())));
  }

  @Test
  void memberCannotReassignSomeoneElsesSpec() {
    var r =
        refused(
            Actor.call(
                member("uday"), () -> SpecPolicy.reassign(SPEC, "raj", "uday", null, List.of())));
    assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, r.code());
    assertTrue(r.message().contains("admin-only"), r.message());
    assertTrue(r.message().contains("raj"), r.message());
  }

  @Test
  void memberCannotReassignEvenTheirOwnSpecToAnother() {
    var r =
        refused(
            Actor.call(
                member("uday"), () -> SpecPolicy.reassign(SPEC, "uday", "raj", null, List.of())));
    assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, r.code());
  }

  @Test
  void memberMayClaimAnUnassignedSpecForThemselves() {
    assertAllowed(
        Actor.call(member("uday"), () -> SpecPolicy.reassign(SPEC, null, "uday", null, List.of())));
    assertAllowed(
        Actor.call(member("uday"), () -> SpecPolicy.reassign(SPEC, "", "uday", null, List.of())));
  }

  @Test
  void memberCannotClaimAnUnassignedSpecForSomeoneElse() {
    var r =
        refused(
            Actor.call(
                member("uday"), () -> SpecPolicy.reassign(SPEC, null, "raj", null, List.of())));
    assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, r.code());
  }

  @Test
  void aSpecBornInAConversationIsClaimedOnlyByOneWhoMayAlreadyPostThere() {
    assertAllowed(
        Actor.call(
            member("raj"),
            () -> SpecPolicy.reassign(SPEC, null, "raj", "design", List.of("uday", "raj"))));
    assertAllowed(
        Actor.call(
            admin("ops"), () -> SpecPolicy.reassign(SPEC, null, "ops", "design", List.of("uday"))));

    var r =
        refused(
            Actor.call(
                member("carol"),
                () -> SpecPolicy.reassign(SPEC, null, "carol", "design", List.of("uday"))));

    assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, r.code());
    assertTrue(r.message().contains("'design'"), r.message());
    assertTrue(r.fix().contains("Ask an admin"), r.fix());
  }

  @Test
  void anAgentClaimsABornInSpecOnlyWhereItsFdeMayPost() {
    var agent = Actor.agentPrincipal("claude/a1b2c3", "raj");

    assertAllowed(
        Actor.call(agent, () -> SpecPolicy.reassign(SPEC, null, "raj", "design", List.of("raj"))));
    assertEquals(
        ErrorCode.FORBIDDEN_ADMIN_ONLY,
        refused(
                Actor.call(
                    agent, () -> SpecPolicy.reassign(SPEC, null, "raj", "design", List.of())))
            .code());
  }

  @Test
  void viewerCannotReassign() {
    var r =
        refused(
            Actor.call(
                viewer("uday"), () -> SpecPolicy.reassign(SPEC, null, "uday", null, List.of())));
    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, r.code());
  }

  @Test
  void machineMemberTokenCannotClaimUnassigned() {
    var r =
        refused(
            Actor.call(
                member(null), () -> SpecPolicy.reassign(SPEC, null, "raj", null, List.of())));
    assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, r.code());
  }

  @Test
  void agentPrincipalMayMutateItsOwnersSpec() {
    var agent = Actor.agentPrincipal("claude/a1b2c3", "raj");

    assertAllowed(Actor.call(agent, () -> SpecPolicy.mutate(SPEC, "raj", "someone")));
    assertAllowed(Actor.call(agent, () -> SpecPolicy.mutate(SPEC, null, "raj")));
  }

  @Test
  void agentPrincipalMayNotMutateAnotherFdesSpec() {
    var agent = Actor.agentPrincipal("claude/a1b2c3", "raj");

    var refusal = refused(Actor.call(agent, () -> SpecPolicy.mutate(SPEC, "sumesh", "sumesh")));

    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, refusal.code());
  }

  @Test
  void agentPrincipalClaimsAnUnassignedSpecForItsOwningFde() {
    var agent = Actor.agentPrincipal("claude/a1b2c3", "raj");

    assertAllowed(Actor.call(agent, () -> SpecPolicy.reassign(SPEC, null, "raj", null, List.of())));
    assertAllowed(Actor.call(agent, () -> SpecPolicy.reassign(SPEC, "", "raj", null, List.of())));
  }

  @Test
  void agentPrincipalCannotClaimForItsEphemeralRunHandle() {
    var agent = Actor.agentPrincipal("claude/a1b2c3", "raj");

    var r =
        refused(
            Actor.call(
                agent, () -> SpecPolicy.reassign(SPEC, null, "claude/a1b2c3", null, List.of())));

    assertEquals(
        ErrorCode.FORBIDDEN_ADMIN_ONLY,
        r.code(),
        "a run-scoped principal must never enter the assignee field");
  }

  @Test
  void agentPrincipalCannotClaimForAThirdFde() {
    var agent = Actor.agentPrincipal("claude/a1b2c3", "raj");

    var r =
        refused(
            Actor.call(agent, () -> SpecPolicy.reassign(SPEC, null, "sumesh", null, List.of())));

    assertEquals(ErrorCode.FORBIDDEN_ADMIN_ONLY, r.code());
  }

  @Test
  void roomPrincipalMayPostToItsOwnersSpecRoom() {
    var room = Actor.roomPrincipal("claude/room-a1b2c3", "raj");

    assertAllowed(Actor.call(room, () -> SpecPolicy.post(SPEC, List.of("raj"))));
  }

  @Test
  void roomPrincipalMayNotPostToAnotherFdesSpecRoom() {
    var room = Actor.roomPrincipal("claude/room-a1b2c3", "raj");

    var r = refused(Actor.call(room, () -> SpecPolicy.post(SPEC, List.of("sumesh"))));

    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
  }

  @Test
  void roomPrincipalMayPostToTheRoomOfAnUnassignedSpecItsFdeCreated() {
    var room = Actor.roomPrincipal("claude/room-a1b2c3", "raj");

    assertAllowed(Actor.call(room, () -> SpecPolicy.post(SPEC, List.of("raj"))));
    assertEquals(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        refused(Actor.call(room, () -> SpecPolicy.post(SPEC, List.of("sumesh")))).code());
    assertEquals(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        refused(Actor.call(room, () -> SpecPolicy.post(SPEC, List.of()))).code());
  }

  @Test
  void roomPrincipalIsRefusedEveryMutationAsReadOnly() {
    var room = Actor.roomPrincipal("claude/room-a1b2c3", "raj");

    assertEquals(
        ErrorCode.READ_ONLY_CREDENTIAL,
        refused(Actor.call(room, () -> SpecPolicy.mutate(SPEC, "raj", "raj"))).code());
    assertEquals(
        ErrorCode.READ_ONLY_CREDENTIAL,
        refused(Actor.call(room, () -> SpecPolicy.reassign(SPEC, null, "raj", null, List.of())))
            .code());
  }

  @Test
  void nonRoomLanesPostUnderThePlainMutationGate() {
    assertAllowed(Actor.call(member("uday"), () -> SpecPolicy.post(SPEC, List.of("uday"))));
    assertAllowed(
        Actor.call(
            Actor.agentPrincipal("claude/a1b2c3", "raj"),
            () -> SpecPolicy.post(SPEC, List.of("raj"))));
    assertEquals(
        ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
        refused(Actor.call(member("uday"), () -> SpecPolicy.post(SPEC, List.of("raj")))).code());
  }

  @Test
  void anyOwnerOfAConversationMayPostInIt() {
    var owners = List.of("raj", "sumesh");

    assertAllowed(Actor.call(member("sumesh"), () -> SpecPolicy.post(SPEC, owners)));
    assertAllowed(
        Actor.call(
            Actor.roomPrincipal("claude/room-a1b2c3", "raj"), () -> SpecPolicy.post(SPEC, owners)));
    var r = refused(Actor.call(member("uday"), () -> SpecPolicy.post(SPEC, owners)));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("raj or sumesh"), r.message());
  }

  @Test
  void aConversationNoOneOwnsTakesOnlyAnAdminsPost() {
    var r = refused(Actor.call(member("uday"), () -> SpecPolicy.post(SPEC, List.of())));

    assertTrue(r.message().contains("No one owns"), r.message());
    assertTrue(r.fix().contains("--assignee"), r.fix());
    assertAllowed(
        Actor.call(
            new Actor("ops", Role.ADMIN, Actor.Lane.API), () -> SpecPolicy.post(SPEC, List.of())));
  }

  @Test
  void aViewerMayNotPostOffTheRoomLaneEvenAsTheOwner() {
    var viewer = new Actor("raj", Role.VIEWER, Actor.Lane.API);

    assertEquals(
        ErrorCode.READ_ONLY_CREDENTIAL,
        refused(Actor.call(viewer, () -> SpecPolicy.post(SPEC, List.of("raj")))).code());
  }
}
