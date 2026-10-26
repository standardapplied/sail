/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The pure restart decision behind both dispatch lanes: the full matrix of {status} x {restart
 * on/off} x {spec id present/absent} x {branch recorded/blank}, with no store, git, or shell in
 * sight. A refusal that a re-dispatch would clear names that command as data, for each door to
 * render.
 */
class RestartResolutionTest {

  private static final Remedy.Command REDISPATCH =
      Remedy.sail("spec dispatch", "-p", "test", "--spec", "oauth-flow", "--restart");

  private static Spec spec(SpecStatus status, String branch) {
    return new Spec(
        "oauth-flow",
        "test",
        "OAuth flow",
        status,
        "me",
        List.of(),
        List.of(),
        null,
        null,
        null,
        branch);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void autoSelectLaneWithoutRestartHasNothingToDecide(boolean blankAsNull) {
    var decision = RestartResolution.decide(blankAsNull ? null : "  ", null, false);

    assertInstanceOf(RestartResolution.NotRestarted.class, decision);
  }

  @Test
  void restartWithoutASpecIdIsRefusedAsACallerError() {
    var decision = RestartResolution.decide(null, null, true);

    var refused = assertInstanceOf(RestartResolution.Refused.class, decision);
    assertEquals(ErrorCode.INVALID_REQUEST, refused.code());
    assertTrue(refused.message().contains("restart"));
    assertTrue(refused.message().contains("spec id"));
    assertEquals(
        Remedy.of("Set the spec id of the non-pending spec to re-dispatch."), refused.fix());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void unknownSpecIsRefusedNotFoundRegardlessOfRestart(boolean restart) {
    var decision = RestartResolution.decide("nope", null, restart);

    var refused = assertInstanceOf(RestartResolution.Refused.class, decision);
    assertEquals(ErrorCode.SPEC_NOT_FOUND, refused.code());
    assertTrue(refused.message().contains("nope"));
    assertEquals(Remedy.NONE, refused.fix());
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void pendingSpecHasNothingToRestart(boolean restart) {
    assertInstanceOf(
        RestartResolution.NotRestarted.class,
        RestartResolution.decide("oauth-flow", spec(SpecStatus.PENDING, "agent/x"), restart));
    assertInstanceOf(
        RestartResolution.NotRestarted.class,
        RestartResolution.decide("oauth-flow", spec(SpecStatus.PENDING, null), restart));
  }

  @ParameterizedTest
  @EnumSource(
      value = SpecStatus.class,
      names = {"PENDING"},
      mode = EnumSource.Mode.EXCLUDE)
  void everyNonPendingStatusWithoutRestartIsRefusedSpecNotReady(SpecStatus status) {
    var decision = RestartResolution.decide("oauth-flow", spec(status, "agent/x"), false);

    var refused = assertInstanceOf(RestartResolution.Refused.class, decision);
    assertEquals(ErrorCode.SPEC_NOT_READY, refused.code());
    assertTrue(refused.message().contains("oauth-flow"));
    assertTrue(refused.message().contains(status.wire()));
    assertEquals(
        "To dispatch it again, run sail spec dispatch -p test --spec oauth-flow --restart (this"
            + " resets its status to pending and records the restart as a lifecycle event).",
        refused.fix().plain());
    assertEquals(List.of(REDISPATCH), refused.fix().commands());
  }

  @ParameterizedTest
  @EnumSource(
      value = SpecStatus.class,
      names = {"PENDING"},
      mode = EnumSource.Mode.EXCLUDE)
  void everyNonPendingStatusWithRestartResolvesToARestart(SpecStatus status) {
    var withPriorBranch = RestartResolution.decide("oauth-flow", spec(status, "agent/x"), true);
    var withoutPriorBranch = RestartResolution.decide("oauth-flow", spec(status, null), true);

    assertEquals(new RestartResolution.Restarted(status.wire()), withPriorBranch);
    assertEquals(new RestartResolution.Restarted(status.wire()), withoutPriorBranch);
  }

  @Test
  void branchCheckoutCreatesAFreshBranchWhenItDoesNotExist() {
    var args =
        RestartResolution.branchCheckoutArgs("/w/mast", "agent/x", false, false, Optional.empty());

    assertEquals(List.of("git", "-C", "/w/mast", "checkout", "-b", "agent/x"), args);
  }

  @Test
  void branchCheckoutForceReusesAnExistingBranchOnRestart() {
    var args =
        RestartResolution.branchCheckoutArgs(
            "/w/mast", "agent/x", true, true, Optional.of(REDISPATCH));

    assertEquals(
        List.of("git", "-C", "/w/mast", "checkout", "-f", "agent/x"),
        args,
        "a restart must land on the existing branch even over a dirty tree from the prior run "
            + "(untracked scaffold that would otherwise abort a plain checkout)");
  }

  @Test
  void branchCheckoutStillCreatesWhenRestartingWithNoPriorBranch() {
    var args =
        RestartResolution.branchCheckoutArgs(
            "/w/mast", "agent/x", false, true, Optional.of(REDISPATCH));

    assertEquals(List.of("git", "-C", "/w/mast", "checkout", "-b", "agent/x"), args);
  }

  @Test
  void branchCheckoutFailsLoudOnACollisionForAFreshDispatch() {
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                RestartResolution.branchCheckoutArgs(
                    "/w/mast", "agent/x", true, false, Optional.of(REDISPATCH)));

    assertEquals(ErrorCode.BRANCH_CREATE_FAILED, ex.failure().errorCode());
    assertEquals("Branch 'agent/x' already exists.", ex.getMessage());
    assertEquals(
        Remedy.of("To dispatch onto it, run ", REDISPATCH, ", or delete it first."),
        ex.failure().remedy());
  }

  @Test
  void aCollisionOfARunWithNoSpecIsToldToDeleteTheBranch() {
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                RestartResolution.branchCheckoutArgs(
                    "/w/mast", "agent/x", true, false, Optional.empty()));

    assertEquals(Remedy.of("Delete it first."), ex.failure().remedy());
  }

  @Test
  void freshBranchForksOffTheFetchedUpstreamTipWhenOriginBaseIsAvailable() {
    var args = RestartResolution.freshBranchArgs("/w/mast", "agent/x", "main", true);

    assertEquals(
        List.of("git", "-C", "/w/mast", "checkout", "-b", "agent/x", "origin/main"),
        args,
        "a fresh branch must fork from origin/main so it never inherits a stale local base");
  }

  @Test
  void freshBranchFallsBackToLocalHeadWhenOriginBaseIsUnavailable() {
    var offline = RestartResolution.freshBranchArgs("/w/mast", "agent/x", "main", false);
    var detached = RestartResolution.freshBranchArgs("/w/mast", "agent/x", "", false);

    var localCreate = List.of("git", "-C", "/w/mast", "checkout", "-b", "agent/x");
    assertEquals(localCreate, offline, "an unreachable origin must not block the dispatch");
    assertEquals(localCreate, detached, "a blank base falls back to the current HEAD");
  }
}
