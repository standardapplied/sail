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
import java.util.function.Supplier;
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
  private static final Supplier<String> ADAS_BOX = () -> "ada";
  private static final Supplier<String> UNREAD =
      () -> {
        throw new AssertionError("only the box gate reads the box's FDE");
      };

  @Test
  void theBoxIsOperatedByItsOwnPersonWhateverTheirRoleOrByAnAdmin() {
    assertDoesNotThrow(() -> Gate.BOX.admit(ADA_VIEWER, ADAS_BOX));
    assertDoesNotThrow(() -> Gate.BOX.admit(ROOT, ADAS_BOX));
    assertDoesNotThrow(() -> Gate.BOX.admit(MACHINE, ADAS_BOX), "minted by an admin");
    assertEquals(
        List.of(
            ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
            "Box 'ada' belongs to 'ada', not you.",
            "Ask ada to operate it, or have an admin do it."),
        refused(Gate.BOX, BOB, ADAS_BOX));
    assertEquals(
        List.of(
            ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
            "Box 'ada' belongs to 'ada', not you.",
            "Ask ada to operate it, or have an admin do it."),
        refused(Gate.BOX, new Actor(null, Role.MEMBER, Actor.Lane.API), ADAS_BOX),
        "a machine token acts for no one, so not for the box's person");
  }

  @Test
  void anAgentNeverOperatesTheBoxAndABoxNamingNoFdeIsAnAdminsAlone() {
    assertEquals(
        List.of(
            ErrorCode.AGENT_LANE_FORBIDDEN,
            "Agent principals cannot operate boxes.",
            "Only an operator lane (CLI or API token) may do this."),
        refused(Gate.BOX, Actor.agentPrincipal("claude/r-1", "ada"), ADAS_BOX));
    assertEquals(
        List.of(
            ErrorCode.FORBIDDEN_NOT_ASSIGNEE,
            "This box has no owner, so only an admin may operate it.",
            "Have an admin operate it."),
        refused(Gate.BOX, ADA_VIEWER, () -> null));
  }

  @Test
  void peopleAreManagedByAnAdminAlone() {
    assertDoesNotThrow(() -> Gate.PEOPLE.admit(ROOT, UNREAD));
    assertEquals(
        List.of(
            ErrorCode.FORBIDDEN_ADMIN_ONLY,
            "Only an admin may manage people.",
            "Ask an admin to do it."),
        refused(Gate.PEOPLE, BOB, UNREAD));
    assertEquals(
        List.of(
            ErrorCode.READ_ONLY_CREDENTIAL,
            "Your credential is read-only and cannot manage people.",
            "Ask an admin for a member or admin credential."),
        refused(Gate.PEOPLE, ADA_VIEWER, UNREAD));
  }

  @Test
  void aGateASeamTheReadFilterOrTheJournalAppliesAdmitsEveryoneAtTheRouter() {
    for (var gate :
        List.of(
            Gate.READS_IN_SCOPE,
            Gate.JOURNAL,
            Gate.decides(Action.OPERATE_RUN, StopOperations.class),
            Gate.unauthenticated("the health probe answers before any credential"))) {
      assertDoesNotThrow(() -> gate.admit(ADA_VIEWER, UNREAD), gate.toString());
    }
  }

  @Test
  void aDecidingGateNamesItsActionAndSeamAndAnUnauthenticatedOneSaysWhy() {
    assertEquals(
        new Gate.Decides(Action.SERVE, ProjectGate.class),
        Gate.decides(Action.SERVE, ProjectGate.class));
    assertThrows(NullPointerException.class, () -> Gate.decides(null, ProjectGate.class));
    assertThrows(NullPointerException.class, () -> Gate.decides(Action.SERVE, null));
    assertEquals(
        "An unauthenticated route says why it needs no caller.",
        assertThrows(IllegalArgumentException.class, () -> Gate.unauthenticated(" ")).getMessage());
    assertEquals("login", ((Gate.Unauthenticated) Gate.unauthenticated("login")).reason());
  }

  private static List<Object> refused(Gate gate, Actor actor, Supplier<String> boxFde) {
    var thrown = assertThrows(ApiException.class, () -> gate.admit(actor, boxFde));
    return List.of(thrown.failure().errorCode(), thrown.reason(), thrown.remedy().plain());
  }
}
