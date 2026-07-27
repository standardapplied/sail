/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Table tests for the pure run access policy: run's-spec-assignee-or-admin, failing closed. */
class RunPolicyTest {

  private static final String RUN = "run-1";
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
  void adminMayAccessAnyRun() {
    assertAllowed(RunPolicy.access(actor("ops", Role.ADMIN), RUN, SPEC, "raj"));
  }

  @Test
  void assigneeMayAccessTheirRun() {
    assertAllowed(RunPolicy.access(actor("uday", Role.MEMBER), RUN, SPEC, "uday"));
  }

  @Test
  void viewerAssigneeMayReadTheirRun() {
    assertAllowed(RunPolicy.access(actor("uday", Role.VIEWER), RUN, SPEC, "uday"));
  }

  @Test
  void nonAssigneeMemberIsRefusedNamingTheAssignee() {
    var r = refused(RunPolicy.access(actor("uday", Role.MEMBER), RUN, SPEC, "raj"));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("raj"), r.message());
    assertTrue(r.message().contains(RUN), r.message());
    assertTrue(r.fix().contains("raj"), r.fix());
  }

  @Test
  void unassignedSpecAllowsOnlyAdmin() {
    var r = refused(RunPolicy.access(actor("uday", Role.MEMBER), RUN, SPEC, null));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("unassigned"), r.message());
    assertAllowed(RunPolicy.access(actor("ops", Role.ADMIN), RUN, SPEC, null));
  }

  @Test
  void machineTokenWithoutHandleNeverMatchesAssignee() {
    var r = refused(RunPolicy.access(actor(null, Role.MEMBER), RUN, SPEC, "raj"));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
  }

  @Test
  void adhocLauncherMayAccessTheirOwnRun() {
    assertAllowed(RunPolicy.access(actor("uday", Role.MEMBER), RUN, null, "uday"));
  }

  @Test
  void adhocRunRefusesOtherMembersNamingTheLauncher() {
    var r = refused(RunPolicy.access(actor("raj", Role.MEMBER), RUN, null, "uday"));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("ad-hoc session"), r.message());
    assertTrue(r.message().contains("uday"), r.message());
  }

  @Test
  void adhocRunFromAHandlelessBoxAllowsOnlyAdmin() {
    var r = refused(RunPolicy.access(actor("uday", Role.MEMBER), RUN, null, ""));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("ad-hoc session"), r.message());
    assertAllowed(RunPolicy.access(actor("ops", Role.ADMIN), RUN, null, ""));
  }
}
