/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import static ai.singlr.sail.authority.Actors.ADMIN;
import static ai.singlr.sail.authority.Actors.AGENT;
import static ai.singlr.sail.authority.Actors.MACHINE;
import static ai.singlr.sail.authority.Actors.OTHER;
import static ai.singlr.sail.authority.Actors.OTHERS_AGENT;
import static ai.singlr.sail.authority.Actors.OTHER_API;
import static ai.singlr.sail.authority.Actors.OTHER_RUN;
import static ai.singlr.sail.authority.Actors.OWNER;
import static ai.singlr.sail.authority.Actors.OWNER_API;
import static ai.singlr.sail.authority.Actors.ROOM;
import static ai.singlr.sail.authority.Actors.RUN;
import static ai.singlr.sail.authority.Actors.VIEWER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.authority.EventAuthority.Rule;
import ai.singlr.sail.authority.EventAuthority.Subject;
import ai.singlr.sail.authority.Refusal.Kind;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.RunStore;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The event rule's matrix: only a listed type is publishable. This box's FDE reports what this box
 * did and observed; anyone else only retells, and only about a spec or room they own, so a run
 * another box pushed is never a key to the spec it names. A run's principal narrates its own run
 * and nothing else. What an event names is read from this box's rows, never from its sender.
 */
class EventAuthorityTest {

  private static final String STOP = "agent_session_stopped";
  private static final String TOOL = "agent_tool_started";
  private static final String BOARD = "board_updated";
  private static final String POSTED = "spec_message_posted";
  private static final String CAROL = "carol";
  private static final String ADHOC = "019fee00-0000-7000-8000-00000000c0c0";
  private static final Actor CAROL_API = new Actor(CAROL, Role.MEMBER, Actor.Lane.API);

  private Board board;
  private String boxFde;
  private EventAuthority rule;

  @BeforeEach
  void setUp() {
    board = new Board();
    board.spec("auth", "auth", CAROL, CAROL);
    board.spec("billing", "billing", OTHER, OTHER);
    board.spec("draft", "draft", null, OWNER);
    board.spec("guest", "den", CAROL, CAROL);
    board.spec("ledger", "billing", CAROL, CAROL);
    Acting.system(
        () ->
            new RunStore(board.db)
                .create(
                    ADHOC,
                    "acme",
                    null,
                    OTHER,
                    "adhoc",
                    "claude-code",
                    "b",
                    "t",
                    null,
                    null,
                    "/l",
                    "u"));
    boxFde = "main";
    rule = new EventAuthority(board.db, () -> boxFde);
  }

  @AfterEach
  void tearDown() {
    board.close();
  }

  record Case(
      String name,
      Actor actor,
      String type,
      String conversation,
      String run,
      boolean retold,
      Kind refused) {
    @Override
    public String toString() {
      return name;
    }
  }

  private static Case retold(
      String name, Actor actor, String type, String conversation, String run, Kind refused) {
    return new Case(name, actor, type, conversation, run, true, refused);
  }

  private static Case observed(
      String name, Actor actor, String type, String conversation, String run, Kind refused) {
    return new Case(name, actor, type, conversation, run, false, refused);
  }

  static Stream<Case> onABoxThatExecutedNoneOfTheRuns() {
    return Stream.of(
        observed("an admin reports any run's stop", ADMIN, STOP, "auth", OTHER_RUN, null),
        retold(
            "its spec's owner retells a pushed run's stop",
            CAROL_API,
            STOP,
            "auth",
            OTHER_RUN,
            null),
        observed(
            "its spec's owner is not taken at their word for what this box observed",
            CAROL_API,
            STOP,
            "auth",
            OTHER_RUN,
            Kind.NOT_OWNER),
        retold(
            "a pushed run is no key to the spec it names",
            OTHER_API,
            STOP,
            "auth",
            OTHER_RUN,
            Kind.NOT_OWNER),
        retold("another member cannot", OWNER_API, STOP, "auth", OTHER_RUN, Kind.NOT_OWNER),
        retold("a machine credential cannot", MACHINE, STOP, "auth", OTHER_RUN, Kind.NOT_OWNER),
        retold("a viewer cannot, even its own", VIEWER, STOP, "draft", null, Kind.READ_ONLY),
        retold(
            "a run's own principal cannot finish it",
            AGENT,
            STOP,
            "auth",
            RUN,
            Kind.NOT_PUBLISHABLE),
        retold(
            "a room principal cannot finish its run",
            ROOM,
            STOP,
            "auth",
            RUN,
            Kind.NOT_PUBLISHABLE),
        retold(
            "the FDE a run acts for retells a run that works no spec",
            OTHER_API,
            STOP,
            null,
            ADHOC,
            null),
        retold(
            "another member cannot retell a run that works no spec",
            OWNER_API,
            STOP,
            null,
            ADHOC,
            Kind.NOT_OWNER),
        retold(
            "a spec's owner retells it with no run named", OTHER_API, STOP, "billing", null, null),
        observed(
            "a spec's owner does not report it as observed here",
            OTHER_API,
            STOP,
            "billing",
            null,
            Kind.NOT_OWNER),
        retold(
            "another member cannot drive that spec",
            OWNER_API,
            STOP,
            "billing",
            null,
            Kind.NOT_OWNER),
        retold("an unassigned spec is its creator's", OWNER_API, STOP, "draft", null, null),
        retold(
            "an unassigned spec is not another member's",
            OTHER_API,
            STOP,
            "draft",
            null,
            Kind.NOT_OWNER),
        retold("a room's owner retells for it", OTHER_API, STOP, "den", null, null),
        retold(
            "the owner of a spec born in a room retells for it",
            CAROL_API,
            STOP,
            "den",
            null,
            null),
        retold(
            "another member cannot drive that room", OWNER_API, STOP, "den", null, Kind.NOT_OWNER),
        retold(
            "a voice in a spec's room does not drive the spec",
            CAROL_API,
            STOP,
            "billing",
            null,
            Kind.NOT_OWNER),
        retold(
            "work this box does not hold is no member's",
            OWNER_API,
            STOP,
            "gone",
            null,
            Kind.NOT_OWNER),
        observed("an admin drives work this box does not hold", ADMIN, STOP, "gone", null, null),
        retold(
            "an event naming nothing is no member's", OWNER_API, STOP, null, null, Kind.NOT_OWNER),
        retold(
            "an unknown run falls to the spec named, another's",
            OWNER_API,
            STOP,
            "billing",
            "no-such-run",
            Kind.NOT_OWNER),
        retold(
            "an unknown run falls to the spec named, its own", OWNER_API, STOP, "draft", "x", null),
        retold(
            "a run on another's spec does not carry its event to the sender's own",
            OTHER_API,
            STOP,
            "billing",
            OTHER_RUN,
            Kind.NOT_OWNER),
        observed("a run's principal narrates it", AGENT, TOOL, "auth", RUN, null),
        observed("a room principal narrates its run", ROOM, TOOL, "auth", RUN, null),
        observed(
            "a principal cannot narrate another run",
            AGENT,
            TOOL,
            "auth",
            OTHER_RUN,
            Kind.NOT_OWNER),
        observed(
            "a principal cannot narrate another FDE's run",
            OTHERS_AGENT,
            TOOL,
            "auth",
            RUN,
            Kind.NOT_OWNER),
        observed(
            "a principal narrates nothing without a run",
            AGENT,
            TOOL,
            "auth",
            null,
            Kind.NOT_OWNER),
        observed("an admin narrates any run", ADMIN, TOOL, "auth", RUN, null),
        retold(
            "a run is not narrated from a box that did not execute it, even by its spec's owner",
            CAROL_API,
            TOOL,
            "auth",
            RUN,
            Kind.NOT_OWNER),
        observed("an admin announces for the box", ADMIN, BOARD, null, null, null),
        retold(
            "a member who is not the box's FDE cannot",
            OWNER_API,
            BOARD,
            null,
            null,
            Kind.NOT_OWNER),
        observed(
            "a principal cannot announce for the box",
            AGENT,
            BOARD,
            null,
            null,
            Kind.NOT_PUBLISHABLE),
        observed("a viewer cannot announce", VIEWER, BOARD, null, null, Kind.READ_ONLY),
        observed("an admin announces a message", ADMIN, POSTED, "den", null, null),
        retold(
            "who may post in a conversation retells a message in it",
            OTHER_API,
            POSTED,
            "den",
            null,
            null),
        retold(
            "the owner of a spec born in a room retells a message in it",
            CAROL_API,
            POSTED,
            "den",
            null,
            null),
        retold(
            "the owner of a spec born in a spec's room retells a message in it",
            CAROL_API,
            POSTED,
            "billing",
            null,
            null),
        retold(
            "a member with no voice in a spec's room cannot announce a message in it",
            OWNER_API,
            POSTED,
            "billing",
            null,
            Kind.NOT_OWNER),
        retold(
            "an announcement in no conversation is no member's",
            OWNER_API,
            POSTED,
            null,
            null,
            Kind.NOT_OWNER),
        observed(
            "who may post there does not announce it as this box's own",
            OTHER_API,
            POSTED,
            "den",
            null,
            Kind.NOT_OWNER),
        retold(
            "a member with no voice there cannot announce it",
            OWNER_API,
            POSTED,
            "den",
            null,
            Kind.NOT_OWNER),
        retold(
            "a principal cannot announce a message",
            AGENT,
            POSTED,
            "den",
            null,
            Kind.NOT_PUBLISHABLE));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("onABoxThatExecutedNoneOfTheRuns")
  void decides(Case row) {
    var subject = rule.subject("acme", row.conversation(), row.run());

    assertEquals(
        Optional.ofNullable(row.refused()),
        rule.decide(row.actor(), row.type(), subject, row.retold()).map(Refusal::kind));
  }

  static Stream<Case> onTheBoxOfTheFdeWhoExecutedTheRun() {
    return Stream.of(
        observed("this box's FDE reports its run's stop", OWNER_API, STOP, "auth", RUN, null),
        observed("this box's FDE narrates its run", OWNER_API, TOOL, "auth", RUN, null),
        observed("this box's FDE reports on a spec it owns", OWNER_API, STOP, "draft", null, null),
        observed(
            "this box's FDE announces what its sync pulled", OWNER_API, POSTED, "den", null, null),
        observed("this box's FDE announces for it", OWNER_API, BOARD, null, null, null),
        observed(
            "this box's FDE does not report on a spec it does not own",
            OWNER_API,
            STOP,
            "billing",
            null,
            Kind.NOT_OWNER),
        observed(
            "this box's FDE does not report a run another box executed",
            OWNER_API,
            STOP,
            "auth",
            OTHER_RUN,
            Kind.NOT_OWNER),
        observed(
            "this box's FDE does not narrate a run another box executed",
            OWNER_API,
            TOOL,
            "auth",
            OTHER_RUN,
            Kind.NOT_OWNER),
        retold(
            "its spec's owner does not speak for a run this box executed",
            CAROL_API,
            STOP,
            "auth",
            RUN,
            Kind.NOT_OWNER),
        observed(
            "another member does not narrate a run this box executed",
            OTHER_API,
            TOOL,
            "auth",
            RUN,
            Kind.NOT_OWNER),
        observed(
            "this box's FDE's agent does not announce for it",
            AGENT,
            BOARD,
            null,
            null,
            Kind.NOT_PUBLISHABLE),
        retold(
            "another member does not announce for it",
            OTHER_API,
            BOARD,
            null,
            null,
            Kind.NOT_OWNER));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("onTheBoxOfTheFdeWhoExecutedTheRun")
  void decidesOnTheExecutingBox(Case row) {
    boxFde = OWNER;
    var subject = rule.subject("acme", row.conversation(), row.run());

    assertEquals(
        Optional.ofNullable(row.refused()),
        rule.decide(row.actor(), row.type(), subject, row.retold()).map(Refusal::kind));
  }

  static Stream<String> unlisted() {
    return Stream.of(
        "spec_status_changed",
        "agent_session_completed",
        "agent_log_chunk",
        "agent_presence",
        "agent_conversation_started",
        "spec_engaged",
        "spec_disengaged",
        "spec_engage_failed",
        "snapshot_restored",
        "snapshot_deleted",
        "guardrail_triggered",
        "review_pipeline_error",
        "spec_stranded",
        "sync_degraded",
        "sync_recovered",
        "pty_session_started",
        "made_up");
  }

  @ParameterizedTest
  @MethodSource("unlisted")
  void aTypeTheRuleDoesNotListIsNoClients(String type) {
    var subject = rule.subject("acme", "draft", RUN);

    for (var actor : List.of(ADMIN, OWNER_API, AGENT)) {
      var refusal = rule.decide(actor, type, subject, true).orElseThrow();
      assertEquals(Kind.NOT_PUBLISHABLE, refusal.kind());
      assertTrue(refusal.message().contains("'" + type + "'"), refusal.message());
    }
  }

  @Test
  void theListIsExactlyWhatClientsPublish() {
    var drives =
        List.of(
            STOP,
            "agent_cancelled",
            "agent_failed",
            "spec_dispatched",
            "spec_restarted",
            "review_iteration_started",
            "review_stage_started",
            "review_stage_passed",
            "review_stage_failed",
            "review_completed",
            "review_errored",
            "review_escalated");
    var narrates =
        List.of("agent_session_started", "agent_stop_nudged", TOOL, "agent_tool_finished");
    var expected = new TreeMap<String, Rule>();
    drives.forEach(type -> expected.put(type, Rule.DRIVES));
    narrates.forEach(type -> expected.put(type, Rule.NARRATES));
    expected.put(POSTED, Rule.ANNOUNCES);
    expected.put("snapshot_created", Rule.BOX);
    expected.put(BOARD, Rule.BOX);

    assertEquals(expected, new TreeMap<>(EventAuthority.publishable()));
    assertThrows(
        UnsupportedOperationException.class,
        () -> EventAuthority.publishable().put("made_up", Rule.BOX));
  }

  @Test
  void aBoxWithNoFdeIsSpokenForByAnAdminAlone() {
    boxFde = null;
    var nothing = rule.subject("acme", null, null);

    assertEquals(Optional.empty(), rule.decide(ADMIN, BOARD, nothing, false));
    assertEquals(
        Optional.of(Kind.NOT_OWNER),
        rule.decide(MACHINE, BOARD, nothing, false).map(Refusal::kind));
    assertEquals(
        Optional.of(Kind.NOT_OWNER),
        rule.decide(MACHINE, STOP, rule.subject("acme", "auth", RUN), false).map(Refusal::kind));
  }

  @Test
  void aRunDecidesWhatItsEventIsAboutAndItsSpecDecidesWhoseItIs() {
    boxFde = OWNER;

    assertEquals(
        new Subject("acme", "auth", RUN, List.of(CAROL), List.of(CAROL), true),
        rule.subject("elsewhere", "billing", RUN));
    assertEquals(
        new Subject("acme", "auth", OTHER_RUN, List.of(CAROL), List.of(CAROL), false),
        rule.subject("elsewhere", "billing", OTHER_RUN));
    assertEquals(
        new Subject("acme", null, ADHOC, List.of(OTHER), List.of(), false),
        rule.subject("elsewhere", "billing", ADHOC));
  }

  @Test
  void aRunsSpecDecidesItsEventsProjectNotTheRowItsPusherWrote() {
    board.db.execute("UPDATE runs SET project = 'elsewhere' WHERE id IN (?, ?)", OTHER_RUN, ADHOC);

    assertEquals("acme", rule.subject("elsewhere", null, OTHER_RUN).project());
    assertEquals("elsewhere", rule.subject("acme", null, ADHOC).project());
  }

  @Test
  void aRunOnASpecThisBoxDoesNotHoldIsNoOnes() {
    board.db.execute("DELETE FROM specs WHERE id = 'auth'");

    assertEquals(
        new Subject("acme", "auth", OTHER_RUN, List.of(), List.of(), false),
        rule.subject("elsewhere", null, OTHER_RUN));
  }

  @Test
  void aSpecOrRoomDecidesItsProjectItsOwnersAndWhoHasAVoiceInIt() {
    assertEquals(
        new Subject("acme", "billing", null, List.of(OTHER), List.of(OTHER, CAROL), false),
        rule.subject("elsewhere", "billing", null));
    assertEquals(
        new Subject("acme", "den", null, List.of(CAROL, OTHER), List.of(CAROL, OTHER), false),
        rule.subject("elsewhere", "den", "no-such-run"));
  }

  @Test
  void workThisBoxDoesNotHoldIsAsItsSenderNamedItAndNoOnes() {
    assertEquals(
        new Subject("elsewhere", "gone", null, List.of(), List.of(), false),
        rule.subject("elsewhere", "gone", null));
    assertEquals(
        new Subject("elsewhere", null, null, List.of(), List.of(), false),
        rule.subject("elsewhere", " ", " "));
  }

  @Test
  void aRefusalNamesTheTypeAndWhatTheSenderMayNotDrive() {
    var run = rule.decide(OWNER_API, STOP, rule.subject("acme", "auth", OTHER_RUN), true);
    var spec = rule.decide(OWNER_API, STOP, rule.subject("acme", "billing", null), true);
    var none = rule.decide(OWNER_API, STOP, rule.subject("acme", null, null), true);
    var observed = rule.decide(OTHER_API, STOP, rule.subject("acme", "billing", null), false);
    var box = rule.decide(OWNER_API, BOARD, rule.subject("acme", null, null), false);
    var posted = rule.decide(OWNER_API, POSTED, rule.subject("acme", "billing", null), true);
    var agent = rule.decide(AGENT, TOOL, rule.subject("acme", "auth", OTHER_RUN), false);

    assertEquals(
        "Event type 'agent_session_stopped' makes this box act on run "
            + OTHER_RUN
            + " of 'auth' (owned by 'carol'), which you may not drive.",
        run.orElseThrow().message());
    assertEquals(
        "Event type 'agent_session_stopped' makes this box act on 'billing' (owned by 'bob'), which"
            + " you may not drive.",
        spec.orElseThrow().message());
    assertEquals(
        "Event type 'agent_session_stopped' makes this box act on work it does not name, which you"
            + " may not drive.",
        none.orElseThrow().message());
    assertEquals(
        "Event type 'agent_session_stopped' reports what this box observed of 'billing' (owned by"
            + " 'bob'), which you may not drive: only this box's FDE or an admin reports that.",
        observed.orElseThrow().message());
    assertEquals(
        "Event type 'spec_message_posted' makes this box act on 'billing' (owned by 'bob' and"
            + " 'carol'), which you may not drive.",
        posted.orElseThrow().message());
    assertTrue(
        box.orElseThrow().message().startsWith("Event type 'board_updated' speaks for this"));
    assertTrue(agent.orElseThrow().message().contains("may narrate only its own run"));
  }

  @Test
  void anEventNamingUnownedWorkSaysSo() {
    board.db.execute("UPDATE specs SET assignee = NULL, created_by = NULL WHERE id = 'draft'");

    var refusal =
        rule.decide(OWNER_API, STOP, rule.subject("acme", "draft", null), true).orElseThrow();

    assertEquals(
        "Event type 'agent_session_stopped' makes this box act on 'draft' (no owner here), which you"
            + " may not drive.",
        refusal.message());
  }
}
