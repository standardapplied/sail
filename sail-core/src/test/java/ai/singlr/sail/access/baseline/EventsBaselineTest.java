/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.access.baseline;

import ai.singlr.sail.access.Refusal;
import ai.singlr.sail.authority.EventAuthority;
import ai.singlr.sail.identity.Actor;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A7's differential: who may publish each event type, about each kind of work, first-hand or
 * retold, is answered as main's rules at {@code fd091a53} answered it ({@link Baseline}, {@code
 * core-events}), on ada's box, which executed her runs and none of bob's. The rule reads no scope,
 * so the cast is asked at one; the door's own scope step is {@code infra-doors}'.
 */
class EventsBaselineTest {

  /** Every type a client may publish today, and one it may not. */
  private static final List<String> TYPES =
      List.of(
          "agent_session_started",
          "agent_stop_nudged",
          "agent_tool_started",
          "agent_tool_finished",
          "agent_session_stopped",
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
          "review_escalated",
          "spec_message_posted",
          "snapshot_created",
          "board_updated",
          "spec_created");

  /** What an event names: a label, its conversation and its run, either may be null. */
  private record About(String label, String conversation, String run) {}

  private static final List<About> ABOUT =
      List.of(
          new About("spec s-ada", "s-ada", null),
          new About("spec s-bob", "s-bob", null),
          new About("spec s-open-bob", "s-open-bob", null),
          new About("spec ghost", "ghost", null),
          new About("room lobby", "lobby", null),
          new About("room hall", "hall", null),
          new About("ada's run", null, Cast.ADA_RUN),
          new About("bob's run", null, Cast.BOB_RUN),
          new About("ada's run of s-bob", null, World.HANDED_OVER_RUN),
          new About("ada's ad-hoc run", null, World.ADHOC_RUN),
          new About("ada's room run", null, Cast.ADA_ROOM_RUN),
          new About("nothing", null, null));

  @Test
  void everyEventIsAdmittedOrRefusedAsTheBaselineRecords() {
    try (var world = new World()) {
      var corpus = new Corpus(world.minted());
      var rule = new EventAuthority(world.db, () -> Cast.OWNER);
      var cast =
          Cast.unscoped(Set.of(Actor.Lane.CLI, Actor.Lane.API, Actor.Lane.AGENT, Actor.Lane.ROOM));
      for (var about : ABOUT) {
        var subject = rule.subject("acme", about.conversation(), about.run());
        for (var type : TYPES) {
          for (var retold : List.of(false, true)) {
            for (var member : cast) {
              corpus.put(
                  "publish "
                      + type
                      + " about "
                      + about.label()
                      + (retold ? " retold" : "")
                      + " as "
                      + member.label(),
                  Corpus.decided(publish(rule, member.actor(), type, subject, retold)));
            }
          }
        }
      }
      Baseline.check("core-events", corpus);
    }
  }

  /**
   * The rule's decision of {@code actor} publishing {@code type} about {@code subject}: the one
   * direct call of a rule in this corpus, since no store asks it, made as the event door makes it.
   * The rule asks the access decision itself.
   */
  private static Optional<Refusal> publish(
      EventAuthority rule,
      Actor actor,
      String type,
      EventAuthority.Subject subject,
      boolean retold) {
    return rule.decide(actor, type, subject, retold);
  }
}
