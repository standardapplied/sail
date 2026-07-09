/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Table tests for the pure dispatch-authorization policy: every rule × role × assignee shape. */
class DispatchPolicyTest {

  private static final String NODE = "sumesh";

  private static Spec spec(String assignee) {
    return new Spec(
        "auth",
        "acme",
        "Add auth",
        SpecStatus.PENDING,
        assignee,
        List.of(),
        List.of(),
        null,
        null,
        null,
        null);
  }

  private static Actor member(String handle) {
    return new Actor(handle, Role.MEMBER, Actor.Lane.API);
  }

  private static DispatchDecision.Refused refuse(Actor actor, Spec spec, String localHandle) {
    return assertInstanceOf(
        DispatchDecision.Refused.class, DispatchPolicy.check(actor, spec, localHandle));
  }

  @Test
  void blankLocalHandleRefusesNodeHandleUnset() {
    var refused = refuse(member(NODE), spec(NODE), "");
    assertEquals(ErrorCode.NODE_HANDLE_UNSET, refused.code());
    assertTrue(refused.fix().contains("sync-handle"), refused.fix());
  }

  @Test
  void nullLocalHandleRefusesNodeHandleUnset() {
    assertEquals(ErrorCode.NODE_HANDLE_UNSET, refuse(member(NODE), spec(NODE), null).code());
  }

  @Test
  void nodeHandleUnsetHelperCarriesTheCode() {
    var refused = DispatchPolicy.nodeHandleUnset();
    assertEquals(ErrorCode.NODE_HANDLE_UNSET, refused.code());
    assertTrue(refused.message().contains("execution node"), refused.message());
  }

  @Test
  void specAssignedToAnotherNodeRefusesRunsOnOtherNode() {
    var refused = refuse(member(NODE), spec("raj"), NODE);
    assertEquals(ErrorCode.RUNS_ON_OTHER_NODE, refused.code());
    assertTrue(refused.message().contains("'raj'"), refused.message());
    assertTrue(refused.fix().contains("--assignee " + NODE), refused.fix());
  }

  @Test
  void unassignedSpecRefusesRunsOnOtherNodeNamingUnassigned() {
    var refused = refuse(member(NODE), spec(null), NODE);
    assertEquals(ErrorCode.RUNS_ON_OTHER_NODE, refused.code());
    assertTrue(refused.message().contains("unassigned"), refused.message());
    assertTrue(refused.fix().contains("--assignee " + NODE), refused.fix());
  }

  @Test
  void viewerCredentialRefusesReadOnly() {
    var viewer = new Actor(NODE, Role.VIEWER, Actor.Lane.API);
    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, refuse(viewer, spec(NODE), NODE).code());
  }

  @Test
  void memberDispatchingSomeoneElsesLocalSpecRefusesNotYourSpec() {
    var refused = refuse(member("raj"), spec(NODE), NODE);
    assertEquals(ErrorCode.NOT_YOUR_SPEC, refused.code());
    assertTrue(refused.message().contains("'raj'"), refused.message());
    assertTrue(refused.message().contains("'" + NODE + "'"), refused.message());
  }

  @Test
  void memberDispatchingOwnLocalSpecIsAllowed() {
    assertInstanceOf(
        DispatchDecision.Allowed.class, DispatchPolicy.check(member(NODE), spec(NODE), NODE));
  }

  @Test
  void adminMayDispatchAnyLocalSpecEvenWithoutMatchingHandle() {
    var admin = new Actor("ops", Role.ADMIN, Actor.Lane.API);
    assertInstanceOf(DispatchDecision.Allowed.class, DispatchPolicy.check(admin, spec(NODE), NODE));
  }

  @Test
  void adminIsStillBoundByExecutionLocality() {
    var admin = new Actor("ops", Role.ADMIN, Actor.Lane.API);
    assertEquals(ErrorCode.RUNS_ON_OTHER_NODE, refuse(admin, spec("raj"), NODE).code());
  }

  @Test
  void cliOperatorDispatchingOwnSpecIsAllowed() {
    assertInstanceOf(
        DispatchDecision.Allowed.class,
        DispatchPolicy.check(Actor.cliOperator(NODE), spec(NODE), NODE));
  }
}
