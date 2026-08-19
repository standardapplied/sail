/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoomWakePromptTest {

  private static SpecStore.SpecRow spec() {
    return new SpecStore.SpecRow(
        "auth",
        "acme",
        "OAuth flow",
        SpecStatus.REVIEW,
        "uday",
        null,
        null,
        null,
        null,
        0,
        "uday",
        "",
        "",
        null,
        List.of(),
        List.of());
  }

  private static MessageStore.MessageRow message(String id, String author, String body) {
    return new MessageStore.MessageRow(
        id, "auth", author, body, null, "2026-08-11T12:00:00Z", "1-a", null, false);
  }

  @Test
  void aFreshWakeIsPrimedWithBodyConversationAndTheStandingInstruction() {
    var question = message("m1", "uday", "what is it stuck on?");

    var built =
        RoomWakePrompt.build(spec(), "Build the OAuth flow.", List.of(question), false, null);

    assertTrue(built.prompt().contains("standing agent of spec \"OAuth flow\""));
    assertTrue(built.prompt().contains("id: auth, status: review"));
    assertTrue(built.prompt().contains("## Spec body"));
    assertTrue(built.prompt().contains("Build the OAuth flow."));
    assertTrue(built.prompt().contains("what is it stuck on?"));
    assertTrue(built.prompt().contains("## Room Duty"));
    assertTrue(built.prompt().contains("spec comment auth --body"));
    assertTrue(built.prompt().contains("read-only chat session, and the harness enforces it"));
    assertTrue(built.prompt().contains("sail spec dispatch auth --restart"));
    assertTrue(
        built.prompt().contains("spec comment auth --question --body"),
        "the wake lane teaches the question verb for what it cannot answer alone");
    assertEquals(List.of(question), built.renderedMessages());
  }

  @Test
  void aResumedWakeSkipsTheSpecBodyItsConversationAlreadyCarries() {
    var built =
        RoomWakePrompt.build(
            spec(), "Build the OAuth flow.", List.of(message("m1", "uday", "hello?")), true, null);

    assertFalse(built.prompt().contains("## Spec body"));
    assertTrue(built.prompt().contains("## Room Duty"));
  }

  @Test
  void anEmptyRoomBuildsAPromptWithNoConversationBlock() {
    var built = RoomWakePrompt.build(spec(), "body", List.of(), false, null);

    assertFalse(built.prompt().contains("## Conversation on this spec"));
    assertTrue(built.renderedMessages().isEmpty());
  }

  @Test
  void anEngagedReadOnlyTurnIsAContinuingConversationNotAVisit() {
    var engagement = Engagement.of("claude-code", "read-only", null, "2026-08-18T00:00:00Z");

    var built = RoomWakePrompt.build(spec(), "body", List.of(), false, engagement);

    assertTrue(built.prompt().contains("You are engaged in this room"));
    assertTrue(built.prompt().contains("## Engaged Turn (read only)"));
    assertTrue(built.prompt().contains("git commands are denied too"));
    assertTrue(built.prompt().contains("you remain engaged and will be resumed"));
    assertTrue(built.prompt().contains("Never say goodbye"));
    assertFalse(
        built.prompt().contains("When you have answered, stop."),
        "the one-turn stop instruction belongs to the wake lane only");
  }

  @Test
  void anEngagedFullTurnTeachesWorkspaceDraftingAndNoForcedCommit() {
    var engagement = Engagement.of("codex", "full", null, "2026-08-18T00:00:00Z");

    var built = RoomWakePrompt.build(spec(), "body", List.of(), true, engagement);

    assertTrue(built.prompt().contains("## Engaged Turn (full access)"));
    assertTrue(built.prompt().contains("spec content auth --body-file"));
    assertTrue(built.prompt().contains("Nothing forces a commit at turn end"));
    assertTrue(built.prompt().contains("you remain engaged and will be resumed"));
    assertFalse(built.prompt().contains("## Spec body"), "a resumed turn skips the body");
  }

  @Test
  void theWakeDutyNeverPromisesGitTheAllowlistDenies() {
    var built = RoomWakePrompt.build(spec(), "body", List.of(), false, null);

    assertTrue(built.prompt().contains("git commands are denied too"));
    assertFalse(built.prompt().contains("read-only git (log, show, diff"));
  }
}
