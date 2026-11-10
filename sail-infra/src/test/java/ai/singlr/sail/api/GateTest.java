/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.access.Action;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A route's gate: what the router decides itself before the handler runs (operating the box,
 * managing people), refused in the words and code every door answers that refusal with, and nothing
 * for a gate an operation's seam, the read filter or the journal applies.
 */
class GateTest {

  private static final Actor ADA_VIEWER = new Actor("ada", Role.VIEWER, Actor.Lane.API);
  private static final Actor BOB = new Actor("bob", Role.MEMBER, Actor.Lane.CLI);
  private static final Actor ROOT = new Actor("root", Role.ADMIN, Actor.Lane.API);
  private static final Actor MACHINE = new Actor(null, Role.ADMIN, Actor.Lane.API);
  private static final Gate ADAS_BOX = Gate.box(() -> "ada");

  @Test
  void theBoxIsOperatedByItsOwnPersonWhateverTheirRoleOrByAnAdmin() {
    assertDoesNotThrow(() -> ADAS_BOX.admit(ADA_VIEWER));
    assertDoesNotThrow(() -> ADAS_BOX.admit(ROOT));
    assertDoesNotThrow(() -> ADAS_BOX.admit(MACHINE), "minted by an admin");
    assertEquals(
        List.of(
            ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
            "Box 'ada' belongs to 'ada', not you.",
            "Ask ada to operate it, or have an admin do it."),
        refused(ADAS_BOX, BOB));
    assertEquals(
        List.of(
            ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
            "Box 'ada' belongs to 'ada', not you.",
            "Ask ada to operate it, or have an admin do it."),
        refused(ADAS_BOX, new Actor(null, Role.MEMBER, Actor.Lane.API)),
        "a machine token acts for no one, so not for the box's person");
  }

  @Test
  void anAgentNeverOperatesTheBoxAndABoxNamingNoFdeIsAnAdminsAlone() {
    assertEquals(
        List.of(
            ErrorCode.AGENT_LANE_FORBIDDEN,
            "Agent principals cannot operate boxes.",
            "Only an operator lane (CLI or API token) may do this."),
        refused(ADAS_BOX, Actor.agentPrincipal("claude/r-1", "ada")));
    assertEquals(
        List.of(
            ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
            "This box has no owner, so only an admin may operate it.",
            "Have an admin operate it."),
        refused(Gate.box(() -> null), ADA_VIEWER));
  }

  @Test
  void peopleAreManagedByAnAdminAlone() {
    assertDoesNotThrow(() -> Gate.PEOPLE.admit(ROOT));
    assertEquals(
        List.of(
            ErrorCode.FORBIDDEN_ADMIN_ONLY,
            "Only an admin may manage people.",
            "Ask an admin to do it."),
        refused(Gate.PEOPLE, BOB));
    assertEquals(
        List.of(
            ErrorCode.READ_ONLY_CREDENTIAL,
            "Your credential is read-only and cannot manage people.",
            "Ask an admin for a member or admin credential."),
        refused(Gate.PEOPLE, ADA_VIEWER));
  }

  @Test
  void aGateASeamTheReadFilterOrTheJournalAppliesAdmitsEveryoneAtTheRouter() {
    for (var gate :
        List.of(
            Gate.READS_IN_SCOPE,
            Gate.JOURNAL,
            Gate.decides(Action.OPERATE_RUN, StopOperations.class),
            Gate.UNSERVED,
            Gate.unauthenticated("the health probe answers before any credential"))) {
      assertDoesNotThrow(() -> gate.admit(ADA_VIEWER), gate.toString());
    }
  }

  @Test
  void anUnauthenticatedRouteSaysWhyItNeedsNoCaller() {
    assertEquals(
        "An unauthenticated route says why it needs no caller.",
        assertThrows(IllegalArgumentException.class, () -> Gate.unauthenticated(" ")).getMessage());
  }

  private static List<Object> refused(Gate gate, Actor actor) {
    var thrown = assertThrows(ApiException.class, () -> gate.admit(actor));
    return List.of(thrown.failure().errorCode(), thrown.reason(), thrown.remedy().plain());
  }
}
