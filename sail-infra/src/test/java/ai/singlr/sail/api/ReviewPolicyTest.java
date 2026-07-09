/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Table tests for the pure review access policy: spec-assignee-or-admin, failing closed. */
class ReviewPolicyTest {

  private static final String REVIEW = "rev-1";
  private static final String SPEC = "auth";

  private static Actor actor(String handle, Role role) {
    return new Actor(handle, role, Actor.Lane.API);
  }

  private static void assertAllowed(AccessDecision decision) {
    assertInstanceOf(AccessDecision.Allowed.class, decision);
  }

  private static AccessDecision.Refused refused(AccessDecision decision) {
    return assertInstanceOf(AccessDecision.Refused.class, decision);
  }

  @Test
  void adminMayDecideAnyReview() {
    assertAllowed(ReviewPolicy.decide(actor("ops", Role.ADMIN), REVIEW, SPEC, "raj"));
  }

  @Test
  void assigneeMayApproveTheirOwnReview() {
    assertAllowed(ReviewPolicy.decide(actor("uday", Role.MEMBER), REVIEW, SPEC, "uday"));
  }

  @Test
  void nonAssigneeMemberIsRefusedNamingTheAssignee() {
    var r = refused(ReviewPolicy.decide(actor("uday", Role.MEMBER), REVIEW, SPEC, "raj"));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("raj"), r.message());
    assertTrue(r.fix().contains("approve or dismiss"), r.fix());
  }

  @Test
  void unassignedSpecAllowsOnlyAdmin() {
    var r = refused(ReviewPolicy.decide(actor("uday", Role.MEMBER), REVIEW, SPEC, ""));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertAllowed(ReviewPolicy.decide(actor("ops", Role.ADMIN), REVIEW, SPEC, ""));
  }

  @Test
  void machineTokenWithoutHandleNeverMatchesAssignee() {
    var r = refused(ReviewPolicy.decide(actor(null, Role.MEMBER), REVIEW, SPEC, "raj"));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
  }
}
