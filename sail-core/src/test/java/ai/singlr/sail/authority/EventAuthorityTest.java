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
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The event rule's matrix: only a listed type is publishable, a type the machinery acts on only by
 * an admin, an owner of what it names, or this box's FDE for a run this box executed, a narration
 * also by the run's own principal, and a box announcement only by an admin or this box's FDE. What
 * an event names is read from this box's rows, never from its sender.
 */
class EventAuthorityTest {

  private static final String STOP = "agent_session_stopped";
  private static final String TOOL = "agent_tool_started";
  private static final String BOARD = "board_updated";
  private static final String CAROL = "carol";
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
    boxFde = "main";
    rule = new EventAuthority(board.db, () -> boxFde);
  }

  @AfterEach
  void tearDown() {
    board.close();
  }

  record Case(
      String name, Actor actor, String type, String conversation, String run, Kind refused) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Case> matrix() {
    return Stream.of(
        new Case("an admin stops any run", ADMIN, STOP, "auth", OTHER_RUN, null),
        new Case("the FDE a run acts for stops it", OTHER_API, STOP, "auth", OTHER_RUN, null),
        new Case("its spec's owner stops it", CAROL_API, STOP, "auth", OTHER_RUN, null),
        new Case("another member cannot", OWNER_API, STOP, "auth", OTHER_RUN, Kind.NOT_OWNER),
        new Case("a machine credential cannot", MACHINE, STOP, "auth", OTHER_RUN, Kind.NOT_OWNER),
        new Case("a viewer cannot, even its own", VIEWER, STOP, "auth", RUN, Kind.READ_ONLY),
        new Case(
            "a run's own principal cannot finish it",
            AGENT,
            STOP,
            "auth",
            RUN,
            Kind.NOT_PUBLISHABLE),
        new Case(
            "a room principal cannot finish its run",
            ROOM,
            STOP,
            "auth",
            RUN,
            Kind.NOT_PUBLISHABLE),
        new Case(
            "a spec's owner drives it with no run named", OTHER_API, STOP, "billing", null, null),
        new Case(
            "another member cannot drive that spec",
            OWNER_API,
            STOP,
            "billing",
            null,
            Kind.NOT_OWNER),
        new Case("an unassigned spec is its creator's", OWNER_API, STOP, "draft", null, null),
        new Case(
            "an unassigned spec is not another member's",
            OTHER_API,
            STOP,
            "draft",
            null,
            Kind.NOT_OWNER),
        new Case("a room's owner drives it", OTHER_API, STOP, "den", null, null),
        new Case(
            "the owner of a spec born in a room drives it", CAROL_API, STOP, "den", null, null),
        new Case(
            "another member cannot drive that room", OWNER_API, STOP, "den", null, Kind.NOT_OWNER),
        new Case(
            "a voice in a spec's room does not drive the spec",
            CAROL_API,
            STOP,
            "billing",
            null,
            Kind.NOT_OWNER),
        new Case(
            "work this box does not hold is no member's",
            OWNER_API,
            STOP,
            "gone",
            null,
            Kind.NOT_OWNER),
        new Case("an admin drives work this box does not hold", ADMIN, STOP, "gone", null, null),
        new Case(
            "an event naming nothing is no member's", OWNER_API, STOP, null, null, Kind.NOT_OWNER),
        new Case(
            "an unknown run falls to the spec named, another's",
            OWNER_API,
            STOP,
            "billing",
            "no-such-run",
            Kind.NOT_OWNER),
        new Case(
            "an unknown run falls to the spec named, its own", OWNER_API, STOP, "draft", "x", null),
        new Case(
            "a run's owner stops it whatever spec the event names",
            OTHER_API,
            STOP,
            "draft",
            OTHER_RUN,
            null),
        new Case("a run's principal narrates it", AGENT, TOOL, "auth", RUN, null),
        new Case("a room principal narrates its run", ROOM, TOOL, "auth", RUN, null),
        new Case(
            "a principal cannot narrate another run",
            AGENT,
            TOOL,
            "auth",
            OTHER_RUN,
            Kind.NOT_OWNER),
        new Case(
            "a principal cannot narrate another FDE's run",
            OTHERS_AGENT,
            TOOL,
            "auth",
            RUN,
            Kind.NOT_OWNER),
        new Case(
            "a principal narrates nothing without a run",
            AGENT,
            TOOL,
            "auth",
            null,
            Kind.NOT_OWNER),
        new Case("the FDE a run acts for narrates it", OWNER_API, TOOL, "auth", RUN, null),
        new Case("another member cannot narrate it", OTHER_API, TOOL, "auth", RUN, Kind.NOT_OWNER),
        new Case("an admin announces for the box", ADMIN, BOARD, null, null, null),
        new Case(
            "a member who is not the box's FDE cannot",
            OWNER_API,
            BOARD,
            null,
            null,
            Kind.NOT_OWNER),
        new Case(
            "a principal cannot announce for the box",
            AGENT,
            BOARD,
            null,
            null,
            Kind.NOT_PUBLISHABLE),
        new Case("a viewer cannot announce", VIEWER, BOARD, null, null, Kind.READ_ONLY));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void decides(Case row) {
    var subject = rule.subject("acme", row.conversation(), row.run());

    assertEquals(
        Optional.ofNullable(row.refused()),
        rule.decide(row.actor(), row.type(), subject).map(Refusal::kind));
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
      var refusal = rule.decide(actor, type, subject).orElseThrow();
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
    var box = List.of("spec_message_posted", "snapshot_created", BOARD);
    var expected = new TreeMap<String, Rule>();
    drives.forEach(type -> expected.put(type, Rule.DRIVES));
    narrates.forEach(type -> expected.put(type, Rule.NARRATES));
    box.forEach(type -> expected.put(type, Rule.BOX));

    assertEquals(expected, new TreeMap<>(EventAuthority.publishable()));
  }

  @Test
  void thisBoxsFdeDrivesARunThisBoxExecutedWhoeverOwnsIt() {
    board.db.execute("UPDATE runs SET owner = ? WHERE id = ?", OTHER, RUN);
    boxFde = OWNER;

    assertEquals(Optional.empty(), rule.decide(OWNER_API, STOP, rule.subject("acme", "auth", RUN)));
  }

  @Test
  void thisBoxsFdeDoesNotDriveARunAnotherBoxExecuted() {
    boxFde = OWNER;

    assertEquals(
        Optional.of(Kind.NOT_OWNER),
        rule.decide(OWNER_API, STOP, rule.subject("acme", "auth", OTHER_RUN)).map(Refusal::kind));
  }

  @Test
  void aMemberWhoIsNotThisBoxsFdeDoesNotDriveARunItExecuted() {
    board.db.execute("UPDATE runs SET owner = ? WHERE id = ?", OTHER, RUN);
    board.db.execute(
        "UPDATE specs SET assignee = ?, created_by = ? WHERE id = 'auth'", OTHER, OTHER);
    boxFde = OWNER;

    assertEquals(
        Optional.of(Kind.NOT_OWNER),
        rule.decide(CAROL_API, STOP, rule.subject("acme", "auth", RUN)).map(Refusal::kind));
  }

  @Test
  void thisBoxsFdeAnnouncesForItAndItsAgentDoesNot() {
    boxFde = OWNER;
    var nothing = rule.subject("acme", null, null);

    assertEquals(Optional.empty(), rule.decide(OWNER_API, BOARD, nothing));
    assertEquals(
        Optional.of(Kind.NOT_OWNER), rule.decide(OTHER_API, BOARD, nothing).map(Refusal::kind));
    assertEquals(
        Optional.of(Kind.NOT_PUBLISHABLE), rule.decide(AGENT, BOARD, nothing).map(Refusal::kind));
  }

  @Test
  void aBoxWithNoFdeIsAnnouncedForByAnAdminAlone() {
    boxFde = null;
    var nothing = rule.subject("acme", null, null);

    assertEquals(Optional.empty(), rule.decide(ADMIN, BOARD, nothing));
    assertEquals(
        Optional.of(Kind.NOT_OWNER), rule.decide(MACHINE, BOARD, nothing).map(Refusal::kind));
  }

  @Test
  void aRunDecidesWhatItsEventIsAbout() {
    boxFde = OWNER;

    assertEquals(
        new Subject("acme", "auth", RUN, List.of(OWNER, CAROL), true),
        rule.subject("elsewhere", "billing", RUN));
    assertEquals(
        new Subject("acme", "auth", OTHER_RUN, List.of(OTHER, CAROL), false),
        rule.subject("elsewhere", "billing", OTHER_RUN));
  }

  @Test
  void aSpecOrRoomDecidesItsProjectAndOwners() {
    assertEquals(
        new Subject("acme", "billing", null, List.of(OTHER), false),
        rule.subject("elsewhere", "billing", null));
    assertEquals(
        new Subject("acme", "den", null, List.of(CAROL, OTHER), false),
        rule.subject("elsewhere", "den", "no-such-run"));
  }

  @Test
  void workThisBoxDoesNotHoldIsAsItsSenderNamedItAndNoOnes() {
    assertEquals(
        new Subject("elsewhere", "gone", null, List.of(), false),
        rule.subject("elsewhere", "gone", null));
    assertEquals(
        new Subject("elsewhere", null, null, List.of(), false),
        rule.subject("elsewhere", " ", " "));
  }

  @Test
  void aRefusalNamesTheTypeAndWhatTheSenderMayNotDrive() {
    var run = rule.decide(OWNER_API, STOP, rule.subject("acme", "auth", OTHER_RUN)).orElseThrow();
    var spec = rule.decide(OWNER_API, STOP, rule.subject("acme", "billing", null)).orElseThrow();
    var none = rule.decide(OWNER_API, STOP, rule.subject("acme", null, null)).orElseThrow();
    var box = rule.decide(OWNER_API, BOARD, rule.subject("acme", null, null)).orElseThrow();
    var agent = rule.decide(AGENT, TOOL, rule.subject("acme", "auth", OTHER_RUN)).orElseThrow();

    assertEquals(
        "Event type 'agent_session_stopped' makes this box act on run "
            + OTHER_RUN
            + " of 'auth' (owned by 'bob' and 'carol'), which you may not drive.",
        run.message());
    assertEquals(
        "Event type 'agent_session_stopped' makes this box act on 'billing' (owned by 'bob'), which"
            + " you may not drive.",
        spec.message());
    assertEquals(
        "Event type 'agent_session_stopped' makes this box act on work it does not name, which you"
            + " may not drive.",
        none.message());
    assertTrue(box.message().startsWith("Event type 'board_updated' speaks for this box"));
    assertTrue(agent.message().contains("may narrate only its own run"), agent.message());
  }

  @Test
  void anEventNamingUnownedWorkSaysSo() {
    board.db.execute("UPDATE specs SET assignee = NULL, created_by = NULL WHERE id = 'draft'");

    var refusal = rule.decide(OWNER_API, STOP, rule.subject("acme", "draft", null)).orElseThrow();

    assertEquals(
        "Event type 'agent_session_stopped' makes this box act on 'draft' (no owner here), which you"
            + " may not drive.",
        refusal.message());
  }

  @Test
  void theListIsUnmodifiable() {
    var listed = EventAuthority.publishable();

    assertEquals(Map.copyOf(listed), listed);
    assertThrows(UnsupportedOperationException.class, () -> listed.put("made_up", Rule.BOX));
  }
}
