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

/** Table tests for the pure dispatch placement: where a spec runs, whoever asks. */
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

  private static DispatchDecision.Refused refuse(Spec spec, String localHandle) {
    return assertInstanceOf(
        DispatchDecision.Refused.class, DispatchPolicy.check(spec, localHandle));
  }

  @Test
  void blankLocalHandleRefusesNodeHandleUnset() {
    var refused = refuse(spec(NODE), "");
    assertEquals(ErrorCode.NODE_HANDLE_UNSET, refused.code());
    assertTrue(refused.fix().plain().contains("sync-handle"), refused.fix().plain());
  }

  @Test
  void nullLocalHandleRefusesNodeHandleUnset() {
    assertEquals(ErrorCode.NODE_HANDLE_UNSET, refuse(spec(NODE), null).code());
  }

  @Test
  void nodeHandleUnsetHelperCarriesTheCode() {
    var refused = DispatchPolicy.nodeHandleUnset();
    assertEquals(ErrorCode.NODE_HANDLE_UNSET, refused.code());
    assertTrue(refused.message().contains("execution node"), refused.message());
  }

  @Test
  void specAssignedToAnotherNodeRefusesRunsOnOtherNode() {
    var refused = refuse(spec("raj"), NODE);
    assertEquals(ErrorCode.RUNS_ON_OTHER_NODE, refused.code());
    assertTrue(refused.message().contains("'raj'"), refused.message());
    assertTrue(refused.fix().plain().contains("--assignee " + NODE), refused.fix().plain());
  }

  @Test
  void unassignedSpecRefusesRunsOnOtherNodeNamingUnassigned() {
    var refused = refuse(spec(null), NODE);
    assertEquals(ErrorCode.RUNS_ON_OTHER_NODE, refused.code());
    assertTrue(refused.message().contains("unassigned"), refused.message());
    assertTrue(refused.fix().plain().contains("--assignee " + NODE), refused.fix().plain());
  }

  @Test
  void aSpecAssignedToThisNodeRunsHere() {
    assertInstanceOf(DispatchDecision.Allowed.class, DispatchPolicy.check(spec(NODE), NODE));
  }
}
