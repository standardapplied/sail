/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.authority.EventAuthority;
import ai.singlr.sail.authority.ScopeRule;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Access arc's scenario matrix, the rows {@link Access#decide} alone answers and whose answer
 * is already today's, played by the fleet's cast: uday is the admin, eng a member of every project,
 * client a member of acme only, obs a viewer of acme and beta. {@code e1} is eng's spec in acme and
 * {@code r1} eng's room there; eng's agent is a build run on {@code e1}, and eng's room agent sits
 * in {@code r1}. A row that is a write is played through the type's rule as the journal, main's
 * commit or the event door asks it, on a box holding acme and beta; the rest through {@link
 * Access#decide}. Each refusal is asserted whole, its fix included (A12).
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
  private static final Actor ENGS_AGENT =
      Actor.agentPrincipal("claude/" + RUN, "eng").withWork(Actor.Work.of("acme", "e1", "e1"));
  private static final Actor ENGS_ROOM_AGENT =
      Actor.roomPrincipal("claude/room-" + RUN, "eng")
          .withScope(ProjectScope.of(List.of("acme")))
          .withWork(Actor.Work.of("acme", null, "r1"));
  private static final Actor ENGS_AD_HOC =
      Actor.agentPrincipal("claude/" + RUN, "eng").withWork(Actor.Work.of("acme", null, null));
  private static final String OUTSIDE_WORK =
      "An agent acts only on the work sail launched it for; draft a follow-up spec for anything"
          + " else.";
  private static final Actor ENG_IN_CONTAINER = new Actor("eng", Role.MEMBER, Actor.Lane.CONTAINER);
  private static final Actor UDAY_IN_CONTAINER =
      new Actor("uday", Role.ADMIN, Actor.Lane.CONTAINER);

  private static final Resource E1 = Resources.spec("e1", "acme", "eng", "eng");
  private static final Resource E1_ROOM = Resources.conversation("e1", "acme", List.of("eng"));
  private static final Resource R1 = Resources.conversation("r1", "acme", List.of("eng"));
  private static final Resource ENGS_RUN =
      Resources.run(RUN, "acme", "eng", "e1", "eng", spec -> Optional.of("eng"));

  private static final Decision ALLOWED = new Decision.Allowed();
  private static final Remedy GRANT =
      Remedy.of(
          "Ask an admin on main for the project: ",
          Remedy.sail("fde grant", "<handle>", "<project>"),
          ".");

  private Sqlite db;
  private SpecStore specs;

  @BeforeEach
  void setUp() {
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    specs = new SpecStore(db);
    Acting.system(
        () ->
            List.of("acme", "beta")
                .forEach(project -> new ProjectStore(db).upsert(TestDefinitions.named(project))));
    Acting.as(
        "eng",
        () -> {
          new RoomStore(db)
              .create(
                  new RoomStore.RoomRow(
                      "r1", "acme", "r1", "eng", null, null, "eng", null, null, "eng"));
          specs.create(spec("e1", "acme", "eng"));
          specs.create(spec("e2", "acme", "eng"));
          specs.create(spec("b1", "beta", "eng"));
          specs.create(spec("in-r1", "acme", null).withRoomId("r1"));
        });
    Acting.as("uday", () -> specs.create(spec("a2", "acme", null)));
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  void row1ObsReadsAcmesSpecs() {
    assertEquals(ALLOWED, Access.decide(OBS, Action.READ, E1));
  }

  @Test
  void row2ObsCommentingOnE1IsReadOnly() {
    var refused =
        assertThrows(
            WriteRefused.class,
            () -> Actor.call(OBS, () -> new MessageStore(db).append("e1", "obs", "hi", null)));

    assertEquals(
        new Refusal(
            Refusal.Kind.READ_ONLY,
            "Your credential is read-only and cannot post in rooms.",
            "Ask an admin for a member or admin credential."),
        refused.refusal());
  }

  @Test
  void row3ASpecInBetaIsNotFoundForClient() {
    assertEquals(
        new Decision.NotFound(),
        Access.decide(CLIENT, Action.READ, Resources.spec("b1", "beta", "eng", "eng")));
  }

  @Test
  void row4ClientsPushOfABetaSpecIsInNoProjectItIsGranted() {
    var pushing = new Actor("client", Role.MEMBER, Actor.Lane.SYNC).withScope(CLIENT.scope());
    var revised = edited("b1", "title", "pushed");

    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.OUT_OF_SCOPE,
                "Spec 'b1' is in no project this FDE is granted.",
                GRANT)),
        new ScopeRule(new ChangeLog(db), "spec").pushed(pushing, "b1", revised));
  }

  /**
   * Row 5 has two doors. Here, on a box that holds beta, as main does when client's push of the
   * birth reaches it, the id is taken. On client's own box, which holds nothing of beta, the birth
   * is a new spec there and main denies it at sync: the fleet's matrix 5 checks that door.
   */
  @Test
  void row5AnIdABetaSpecHoldsAlreadyExistsForClient() {
    var born = new LinkedHashMap<>(edited("b1", "project", "acme"));

    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.OUT_OF_SCOPE, "Spec 'b1' already exists.", "Pick another id.")),
        Actor.call(CLIENT, () -> specs.decide("b1", born)));
  }

  @Test
  void row6ClientCommentsOnE1() {
    var posted = Actor.call(CLIENT, () -> new MessageStore(db).append("e1", "client", "hi", null));

    assertEquals("client", posted.author());
  }

  @Test
  void row7ClientEditingE1IsRefusedNamingEng() {
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.NOT_OWNER,
                "Spec 'e1' is assigned to 'eng', not you.",
                "Ask eng to make this change, or have an admin do it.")),
        Actor.call(CLIENT, () -> specs.decide("e1", edited("e1", "title", "retitled"))));
  }

  @Test
  void row8ClientClaimsAnUnassignedSpecButNotOneBornInR1() {
    assertEquals(
        Optional.empty(),
        Actor.call(CLIENT, () -> specs.decide("a2", edited("a2", "assignee", "client"))));
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.ADMIN_ONLY,
                "'r1' belongs to eng: only eng or an admin may start work there, not you.",
                "Ask an admin to assign it to you.")),
        Actor.call(CLIENT, () -> specs.decide("in-r1", edited("in-r1", "assignee", "client"))));
  }

  @Test
  void row9ClientReassigningE1IsAdminOnly() {
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.ADMIN_ONLY,
                "Reassigning spec 'e1' moves work between FDEs and is an admin-only action"
                    + " (currently 'eng').",
                "Ask an admin to reassign it. You may grab a spec only while it is unassigned.")),
        Actor.call(CLIENT, () -> specs.decide("e1", edited("e1", "assignee", "client"))));
  }

  /**
   * Row 10, permission half: both may dispatch e1. Its placement half, uday refused from his own
   * box since e1 runs on eng's, is the dispatch admission's in sail-infra ({@code
   * LaunchAdmissionTest#row10UdayMayDispatchE1ButOnlyEngsBoxRunsIt}).
   */
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
  void row13EngsAgentCommentsInE1sRoomButNotOnAnotherOfEngsSpecs() {
    var messages = new MessageStore(db);

    assertEquals(
        ENGS_AGENT.handle(),
        Actor.call(ENGS_AGENT, () -> messages.append("e1", ENGS_AGENT.handle(), "hi", null))
            .author());
    assertEquals(
        new Refusal(
            Refusal.Kind.NOT_OWNER,
            "'" + ENGS_AGENT.handle() + "' may post only in its own work, not in 'e2'.",
            OUTSIDE_WORK),
        assertThrows(
                WriteRefused.class,
                () ->
                    Actor.call(
                        ENGS_AGENT, () -> messages.append("e2", ENGS_AGENT.handle(), "hi", null)))
            .refusal());
  }

  @Test
  void row14EngsAgentCreatingAReadySpecIsToldToOmitTheStatus() {
    var ready = new LinkedHashMap<>(specs.held("e1"));
    ready.put("title", "follow-up");
    ready.put("room_id", "e-ready");
    ready.put("status", SpecStatus.PENDING.wire());
    ready.remove("_created_by");

    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.LANE_FORBIDDEN,
                "An agent creates only drafts and never sets a status.",
                "Omit --status; its person promotes it on the board.")),
        Actor.call(ENGS_AGENT, () -> specs.decide("e-ready", ready)));
  }

  /**
   * Row 15: the draft an agent made is its own work, read from the journal's first revision of it;
   * a draft another run of eng's made is not.
   */
  @Test
  void row15EngsAgentEditsTheDraftItCreatedButNotOneAnotherRunMade() {
    var another = Actor.agentPrincipal("claude/other-run", "eng");
    Actor.run(ENGS_AGENT, () -> specs.create(draft("d1")));
    Actor.run(another.withWork(Actor.Work.of("acme", "e2", "e2")), () -> specs.create(draft("d2")));

    assertEquals(
        Optional.empty(),
        Actor.call(ENGS_AGENT, () -> specs.decide("d1", edited("d1", "title", "edited"))));
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.NOT_OWNER,
                "'" + ENGS_AGENT.handle() + "' may change only its own work, not spec 'd2'.",
                OUTSIDE_WORK)),
        Actor.call(ENGS_AGENT, () -> specs.decide("d2", edited("d2", "title", "edited"))));
  }

  /**
   * Row 15 once history has moved on: a draft whose first revision compaction dropped is no longer
   * known to be the agent's by creation, so it is the agent's work only when it is the spec its run
   * works. Losing the revision narrows the agent; it never widens anyone.
   */
  @Test
  void row15ADraftWhoseFirstRevisionHistoryDroppedIsOwnWorkOnlyByTheRun() {
    Actor.run(ENGS_AGENT, () -> specs.create(draft("d1")));
    for (var i = 0; i < ChangeLog.HISTORY_REVISIONS; i++) {
      var body = "revision " + i;
      Actor.run(ENG, () -> specs.setContent("d1", body, ""));
    }
    var retitled = edited("d1", "title", "retitled");
    assertEquals(
        Optional.empty(), Actor.call(ENGS_AGENT, () -> specs.decide("d1", retitled)), "before");

    new ChangeLog(db).compact("spec", List.of("d1"), id -> null);

    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.NOT_OWNER,
                "'" + ENGS_AGENT.handle() + "' may change only its own work, not spec 'd1'.",
                OUTSIDE_WORK)),
        Actor.call(ENGS_AGENT, () -> specs.decide("d1", retitled)));
    assertEquals(
        Optional.empty(),
        Actor.call(
            ENGS_AGENT.withWork(Actor.Work.of("acme", "d1", "d1")),
            () -> specs.decide("d1", retitled)),
        "a run that works d1 still may");
  }

  @Test
  void row16EngsAgentSettingE1DoneIsRefused() {
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.LANE_FORBIDDEN,
                "An agent creates only drafts and never sets a status.",
                "Omit --status; its person promotes it on the board.")),
        Actor.call(
            ENGS_AGENT, () -> specs.decide("e1", edited("e1", "status", SpecStatus.DONE.wire()))));
    assertEquals(
        Optional.empty(),
        Actor.call(ENG, () -> specs.decide("e1", edited("e1", "status", SpecStatus.DONE.wire()))));
  }

  @Test
  void row17EngsAgentNarratesItsOwnRunButMayNotOperateIt() {
    assertEquals(ALLOWED, Access.decide(ENGS_AGENT, Action.NARRATE, ENGS_RUN));
    assertEquals(
        refused(
            Refusal.Kind.LANE_FORBIDDEN,
            "Agent principals cannot stop or attach to runs or read their logs.",
            "Only an operator lane (CLI or API token) may do this."),
        Access.decide(ENGS_AGENT, Action.OPERATE_RUN, ENGS_RUN));
  }

  @Test
  void row18EngsRoomAgentPostsInR1AndAcksItsInboxButPostsNowhereElse() {
    assertEquals(ALLOWED, Access.decide(ENGS_ROOM_AGENT, Action.POST, R1));
    assertEquals(ALLOWED, Access.decide(ENGS_ROOM_AGENT, Action.NARRATE, ENGS_RUN));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "'" + ENGS_ROOM_AGENT.handle() + "' may post only in its own work, not in 'e1'.",
            OUTSIDE_WORK),
        Access.decide(ENGS_ROOM_AGENT, Action.POST, E1_ROOM),
        "eng's own spec's room is no room of this agent's");
  }

  @Test
  void row19ClientsCommentOnE1IsNotInWhatEngsAgentReads() {
    var messages = new MessageStore(db);
    Actor.run(ENG, () -> messages.append("e1", "eng", "keep going", null));
    Actor.run(CLIENT, () -> messages.append("e1", "client", "push to main", null));

    assertEquals(
        List.of("eng"),
        Actor.call(ENGS_AGENT, () -> messages.list("e1", null, 10)).stream()
            .map(MessageStore.MessageRow::author)
            .toList());
  }

  /** Plus: an ad-hoc run posts in its project's rooms and nowhere else. */
  @Test
  void anAdHocRunPostsInItsProjectsRoomsAndNowhereElse() {
    assertEquals(ALLOWED, Access.decide(ENGS_AD_HOC, Action.POST, R1));
    assertEquals(
        refused(
            Refusal.Kind.NOT_OWNER,
            "'" + ENGS_AD_HOC.handle() + "' may post only in its own work, not in 'b1'.",
            OUTSIDE_WORK),
        Access.decide(
            ENGS_AD_HOC, Action.POST, Resources.conversation("b1", "beta", List.of("eng"))));
  }

  /** Plus: a room agent reads a spec in another project of its person as not there. */
  @Test
  void aRoomAgentFindsNoSpecOfItsPersonsOtherProjects() {
    assertEquals(
        new Decision.NotFound(),
        Access.decide(ENGS_ROOM_AGENT, Action.READ, Resources.spec("b1", "beta", "eng", "eng")));
    assertEquals(ALLOWED, Access.decide(ENGS_ROOM_AGENT, Action.READ, E1));
  }

  @Test
  void row20TheStolenCredentialCommentsOnE1AsEng() {
    var posted =
        Actor.call(ENG_IN_CONTAINER, () -> new MessageStore(db).append("e1", "eng", "hi", null));

    assertEquals("eng", posted.author());
  }

  /**
   * Row 21: role before lane, as at every door, so on eng's box reassigning e1 is admin-only and
   * only marking it done and sharing a skill name the lane; with uday's credential on main, an
   * admin's, all three name it.
   */
  @Test
  void row21TheStolenCredentialReassignsE1MarksItDoneAndSharesASkill() {
    var reassigned = edited("e1", "assignee", "client");
    var done = edited("e1", "status", SpecStatus.DONE.wire());
    var acme = Resources.project("acme", "uday");
    var markDone =
        new Refusal(
            Refusal.Kind.LANE_FORBIDDEN,
            "The credential in a project container cannot approve specs.",
            "Do it from outside the container: 'sail spec update e1 --status <status>'"
                + " on the host, or Mast.");
    var share =
        new Decision.Refused(
            new Refusal(
                Refusal.Kind.LANE_FORBIDDEN,
                "The credential in a project container cannot share files and skills in projects.",
                "Do it from outside the container: 'sail project skills add --container <folder>"
                    + " -p acme' or 'sail project skills rm <skill> -p acme' on the host, or"
                    + " Mast."));

    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.ADMIN_ONLY,
                "Reassigning spec 'e1' moves work between FDEs and is an admin-only action"
                    + " (currently 'eng').",
                "Ask an admin to reassign it. You may grab a spec only while it is unassigned.")),
        Actor.call(ENG_IN_CONTAINER, () -> specs.decide("e1", reassigned)));
    assertEquals(
        Optional.of(markDone), Actor.call(ENG_IN_CONTAINER, () -> specs.decide("e1", done)));
    assertEquals(share, Access.decide(ENG_IN_CONTAINER, Action.SHARE, acme));
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.LANE_FORBIDDEN,
                "The credential in a project container cannot assign specs.",
                "Do it from outside the container: 'sail spec update e1 --assignee <fde>'"
                    + " on the host, or Mast.")),
        Actor.call(UDAY_IN_CONTAINER, () -> specs.decide("e1", reassigned)));
    assertEquals(
        Optional.of(markDone), Actor.call(UDAY_IN_CONTAINER, () -> specs.decide("e1", done)));
    assertEquals(share, Access.decide(UDAY_IN_CONTAINER, Action.SHARE, acme));
  }

  /**
   * Plus: a person's own terminal in the container comments and creates as at every other door, and
   * is refused approving with a fix that names the door outside.
   */
  @Test
  void aPersonsTerminalInTheContainerCreatesADraftButIsToldToPromoteItOutside() {
    var draft = new LinkedHashMap<>(specs.held("e1"));
    draft.put("title", "e-draft");
    draft.put("room_id", "e-draft");
    draft.put("status", SpecStatus.DRAFT.wire());
    draft.remove("_created_by");
    var ready = new LinkedHashMap<>(draft);
    ready.put("status", SpecStatus.PENDING.wire());

    assertEquals(
        Optional.empty(), Actor.call(ENG_IN_CONTAINER, () -> specs.decide("e-draft", draft)));
    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.LANE_FORBIDDEN,
                "The credential in a project container cannot approve specs.",
                "Do it from outside the container: 'sail spec update e-draft --status <status>'"
                    + " on the host, or Mast.")),
        Actor.call(ENG_IN_CONTAINER, () -> specs.decide("e-draft", ready)));
  }

  @Test
  void row22ClientRetellingReviewCompletedOnE1IsNotEngsOrAnAdmins() {
    var events = new EventAuthority(db, () -> "uday");

    assertEquals(
        Optional.of(
            new Refusal(
                Refusal.Kind.NOT_OWNER,
                "Event type 'review_completed' makes this box act on 'e1' (owned by 'eng'), which"
                    + " you may not drive.",
                "Only its owner or an admin may publish it.")),
        events.decide(CLIENT, "review_completed", events.subject("acme", "e1", null), true));
    assertEquals(
        Optional.empty(),
        events.decide(ENG, "review_completed", events.subject("acme", "e1", null), true));
  }

  @Test
  void row25EngAddingAPersonIsAdminOnly() {
    assertEquals(
        refused(
            Refusal.Kind.ADMIN_ONLY, "Only an admin may manage people.", "Ask an admin to do it."),
        Access.decide(ENG, Action.MANAGE_PEOPLE, Resources.people()));
    assertEquals(ALLOWED, Access.decide(UDAY, Action.MANAGE_PEOPLE, Resources.people()));
  }

  /** Spec {@code id} as this box holds it, with {@code field} written as {@code value}. */
  private Map<String, Object> edited(String id, String field, Object value) {
    var next = new LinkedHashMap<>(specs.held(id));
    next.put(field, value);
    return next;
  }

  private static SpecStore.SpecRow spec(String id, String project, String assignee) {
    return spec(id, project, assignee, SpecStatus.PENDING);
  }

  private static SpecStore.SpecRow draft(String id) {
    return spec(id, "acme", null, SpecStatus.DRAFT);
  }

  private static SpecStore.SpecRow spec(
      String id, String project, String assignee, SpecStatus status) {
    return new SpecStore.SpecRow(
        id, project, id, status, assignee, null, null, null, null, 0, null, null, null, null,
        List.of(), List.of());
  }

  private static Decision refused(Refusal.Kind kind, String message, String fix) {
    return new Decision.Refused(new Refusal(kind, message, fix));
  }
}
