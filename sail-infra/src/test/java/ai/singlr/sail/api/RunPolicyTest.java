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
import ai.singlr.sail.store.RunStore;
import java.util.List;
import java.util.Optional;
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
    assertAllowed(
        Actor.call(actor("ops", Role.ADMIN), () -> RunPolicy.access(RUN, SPEC, List.of("raj"))));
  }

  @Test
  void assigneeMayAccessTheirRun() {
    assertAllowed(
        Actor.call(actor("uday", Role.MEMBER), () -> RunPolicy.access(RUN, SPEC, List.of("uday"))));
  }

  @Test
  void viewerAssigneeMayReadTheirRun() {
    assertAllowed(
        Actor.call(actor("uday", Role.VIEWER), () -> RunPolicy.access(RUN, SPEC, List.of("uday"))));
  }

  @Test
  void nonAssigneeMemberIsRefusedNamingTheAssignee() {
    var r =
        refused(
            Actor.call(
                actor("uday", Role.MEMBER), () -> RunPolicy.access(RUN, SPEC, List.of("raj"))));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("raj"), r.message());
    assertTrue(r.message().contains(RUN), r.message());
    assertTrue(r.fix().contains("raj"), r.fix());
  }

  @Test
  void aSpecWithNoOwnerAllowsOnlyAdmin() {
    var r =
        refused(
            Actor.call(actor("uday", Role.MEMBER), () -> RunPolicy.access(RUN, SPEC, List.of())));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("no owner"), r.message());
    assertAllowed(
        Actor.call(actor("ops", Role.ADMIN), () -> RunPolicy.access(RUN, SPEC, List.of())));
  }

  @Test
  void machineTokenWithoutHandleNeverMatchesAssignee() {
    var r =
        refused(
            Actor.call(
                actor(null, Role.MEMBER), () -> RunPolicy.access(RUN, SPEC, List.of("raj"))));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
  }

  @Test
  void adhocLauncherMayAccessTheirOwnRun() {
    assertAllowed(
        Actor.call(actor("uday", Role.MEMBER), () -> RunPolicy.access(RUN, null, List.of("uday"))));
  }

  @Test
  void adhocRunRefusesOtherMembersNamingTheLauncher() {
    var r =
        refused(
            Actor.call(
                actor("raj", Role.MEMBER), () -> RunPolicy.access(RUN, null, List.of("uday"))));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("ad-hoc session"), r.message());
    assertTrue(r.message().contains("uday"), r.message());
  }

  @Test
  void adhocRunFromAHandlelessBoxAllowsOnlyAdmin() {
    var r =
        refused(
            Actor.call(actor("uday", Role.MEMBER), () -> RunPolicy.access(RUN, null, List.of())));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, r.code());
    assertTrue(r.message().contains("ad-hoc session"), r.message());
    assertAllowed(
        Actor.call(actor("ops", Role.ADMIN), () -> RunPolicy.access(RUN, null, List.of())));
  }

  @Test
  void aRunsOwnFdeMayStillReachItAfterItsSpecMoved() {
    var run = run("uday", SPEC, "uday");
    var owners = RunPolicy.owners(run, spec -> Optional.of("raj"));

    assertEquals(List.of("uday", "raj"), owners);
    assertAllowed(
        Actor.call(actor("uday", Role.MEMBER), () -> RunPolicy.access(RUN, SPEC, owners)));
    assertAllowed(Actor.call(actor("raj", Role.MEMBER), () -> RunPolicy.access(RUN, SPEC, owners)));
    var r =
        refused(Actor.call(actor("mady", Role.MEMBER), () -> RunPolicy.access(RUN, SPEC, owners)));
    assertTrue(r.fix().contains("uday or raj"), r.fix());
  }

  @Test
  void anAdhocRunIsOwnedByItsFdeAndTheBoxThatLaunchedIt() {
    assertEquals(
        List.of("uday", "box"),
        RunPolicy.owners(run("uday", null, "box"), spec -> Optional.empty()));
    assertEquals(
        List.of("box"), RunPolicy.owners(run(null, null, "box"), spec -> Optional.empty()));
  }

  @Test
  void aRunWhoseSpecIsGoneAndWhichNamesNoFdeHasNoOwner() {
    assertEquals(List.of(), RunPolicy.owners(run(" ", SPEC, "box"), spec -> Optional.empty()));
    assertEquals(
        List.of("uday"), RunPolicy.owners(run("uday", SPEC, "box"), spec -> Optional.of("uday")));
  }

  private static RunStore.RunRow run(String owner, String specId, String node) {
    return new RunStore.RunRow(
        RUN,
        "acme",
        specId,
        node,
        "build",
        "claude-code",
        "b",
        "t",
        null,
        null,
        "running",
        null,
        "/log",
        "unit",
        "now",
        null,
        List.of(),
        null,
        "claude/" + RUN,
        owner);
  }
}
