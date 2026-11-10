/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.authority.Refusal;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Access arc's scenario matrix, the rows {@link Access#decide} alone answers and whose answer
 * is already today's, played by the fleet's cast: uday is the admin, eng a member of every project,
 * client a member of acme only, obs a viewer of acme and beta. {@code e1} is eng's spec in acme and
 * {@code r1} eng's room there; eng's agent is a build run on {@code e1}, and eng's room agent sits
 * in {@code r1}. Each refusal is asserted whole, its fix included (A12).
 */
class AccessScenarioTest {

  private static final String RUN = "019fee00-0000-7000-8000-0000000000e1";

  private static final Actor UDAY = new Actor("uday", Role.ADMIN, Actor.Lane.API);
  private static final Actor ENG = new Actor("eng", Role.MEMBER, Actor.Lane.API);
  private static final Actor CLIENT =
      new Actor("client", Role.MEMBER, Actor.Lane.API).withScope(ProjectScope.of(List.of("acme")));
  private static final Actor OBS =
      new Actor("obs", Role.VIEWER, Actor.Lane.API)
          .withScope(ProjectScope.of(List.of("acme", "beta")));
  private static final Actor ENGS_AGENT = Actor.agentPrincipal("claude/" + RUN, "eng");
  private static final Actor ENGS_ROOM_AGENT = Actor.roomPrincipal("claude/room-" + RUN, "eng");

  private static final Resource E1 = Resources.spec("e1", "acme", "eng", "eng");
  private static final Resource E1_ROOM = Resources.conversation("e1", "acme", List.of("eng"));
  private static final Resource R1 = Resources.conversation("r1", "acme", List.of("eng"));
  private static final Resource ENGS_RUN = Resources.run(RUN, "acme", "eng", "e1", "eng", "eng");

  private static final Decision ALLOWED = new Decision.Allowed();

  @Test
  void row1ObsReadsAcmesSpecs() {
    assertEquals(ALLOWED, Access.decide(OBS, Action.READ, E1));
  }

  @Test
  void row2ObsCommentingOnE1IsReadOnly() {
    assertEquals(
        refused(
            Refusal.Kind.READ_ONLY,
            "Your credential is read-only and cannot post in rooms.",
            "Ask an admin for a member or admin credential."),
        Access.decide(OBS, Action.POST, E1_ROOM));
  }

  @Test
  void row3ASpecInBetaIsNotFoundForClient() {
    assertEquals(
        new Decision.NotFound(),
        Access.decide(CLIENT, Action.READ, Resources.spec("b1", "beta", "eng", "eng")));
  }

  @Test
  void row4ClientsPushOfABetaSpecFindsNothingOnMain() {
    var pushing = Actor.sync("client", Role.MEMBER).withScope(CLIENT.scope());

    assertEquals(
        new Decision.NotFound(),
        Access.decide(pushing, Action.CHANGE, Resources.spec("b1", "beta", "client", "client")));
  }

  @Test
  void row5AnIdABetaSpecHoldsIsOutsideClientsScope() {
    assertEquals(
        new Decision.NotFound(),
        Access.decide(CLIENT, Action.CREATE, Resources.spec("b1", "beta", "eng", "eng")));
  }

  @Test
  void row6ClientCommentsOnE1() {
    assertEquals(ALLOWED, Access.decide(CLIENT, Action.POST, E1_ROOM));
  }

  @Test
  void row7ClientEditingE1IsRefusedNamingEng() {
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "Spec 'e1' is assigned to 'eng', not you.",
            "Ask eng to make this change, or have an admin do it."),
        Access.decide(CLIENT, Action.CHANGE, E1));
  }

  @Test
  void row8ClientClaimsAnUnassignedSpecButNotOneBornInR1() {
    var unassigned = Resources.spec("a2", "acme", null, "uday");

    assertEquals(ALLOWED, Access.decide(CLIENT, Action.CLAIM, unassigned));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "'r1' belongs to eng: only eng or an admin may start work there, not you.",
            "Ask eng to create it there, or have an admin do it."),
        Access.decide(CLIENT, Action.CLAIM, unassigned.withParent(R1)));
  }

  @Test
  void row9ClientReassigningE1IsAdminOnly() {
    assertEquals(
        refused(
            Refusal.Kind.ADMIN_ONLY,
            "Reassigning spec 'e1' moves work between FDEs and is an admin-only action"
                + " (currently 'eng').",
            "Ask an admin to reassign it. You may grab a spec only while it is unassigned."),
        Access.decide(CLIENT, Action.ASSIGN, E1));
  }

  @Test
  void row10EngAndUdayMayDispatchE1WhereverItsPlacementSendsIt() {
    assertEquals(ALLOWED, Access.decide(ENG, Action.DISPATCH, E1));
    assertEquals(ALLOWED, Access.decide(UDAY, Action.DISPATCH, E1));
  }

  @Test
  void row12EngConfiguresGamma() {
    assertEquals(ALLOWED, Access.decide(ENG, Action.CONFIGURE, Resources.project("gamma", "eng")));
  }

  @Test
  void row13EngsAgentCommentsInE1sRoom() {
    assertEquals(ALLOWED, Access.decide(ENGS_AGENT, Action.POST, E1_ROOM));
  }

  @Test
  void row15EngsAgentEditsTheDraftItCreated() {
    assertEquals(
        ALLOWED,
        Access.decide(ENGS_AGENT, Action.CHANGE, Resources.spec("d1", "acme", null, "eng")));
  }

  @Test
  void row17EngsAgentNarratesItsOwnRunButMayNotOperateIt() {
    assertEquals(ALLOWED, Access.decide(ENGS_AGENT, Action.NARRATE, ENGS_RUN));
    assertEquals(
        refused(
            Refusal.Kind.LANE_FORBIDDEN,
            "Agent principals cannot operate runs.",
            "Only an operator lane (CLI or API token) may do this."),
        Access.decide(ENGS_AGENT, Action.OPERATE_RUN, ENGS_RUN));
  }

  @Test
  void row18EngsRoomAgentPostsInR1AndAcksItsInboxButPostsInNoOneElsesRoom() {
    assertEquals(ALLOWED, Access.decide(ENGS_ROOM_AGENT, Action.POST, R1));
    assertEquals(ALLOWED, Access.decide(ENGS_ROOM_AGENT, Action.NARRATE, ENGS_RUN));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "'u1' belongs to uday: only uday or an admin may post there, not you.",
            "Ask uday to post it, or have an admin do it."),
        Access.decide(
            ENGS_ROOM_AGENT, Action.POST, Resources.conversation("u1", "acme", List.of("uday"))));
  }

  @Test
  void row25EngAddingAPersonIsAdminOnly() {
    assertEquals(
        refused(
            Refusal.Kind.ADMIN_ONLY, "Only an admin may manage people.", "Ask an admin to do it."),
        Access.decide(ENG, Action.MANAGE_PEOPLE, Resources.people()));
    assertEquals(ALLOWED, Access.decide(UDAY, Action.MANAGE_PEOPLE, Resources.people()));
  }

  private static Decision refused(Refusal.Kind kind, String message, String fix) {
    return new Decision.Refused(new Refusal(kind, message, fix));
  }
}
