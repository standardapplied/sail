/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvitePromptTest {

  private static SpecStore.SpecRow spec() {
    return new SpecStore.SpecRow(
        "auth",
        "acme",
        "OAuth flow",
        SpecStatus.DRAFT,
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
        id, "auth", author, body, null, "2026-08-16T12:00:00Z", "1-a", null, false);
  }

  @Test
  void aTaskInviteIsPrimedWithBodyAndConversationAndIsAlwaysFullAccess() {
    var ask = message("m1", "uday", "poke holes in this design");

    var built = InvitePrompt.build(spec(), "Build the OAuth flow.", List.of(ask));

    assertTrue(built.prompt().contains("invited agent in the room of spec \"OAuth flow\""));
    assertTrue(built.prompt().contains("id: auth, status: draft"));
    assertTrue(built.prompt().contains("full access"));
    assertTrue(built.prompt().contains("## Spec body"));
    assertTrue(built.prompt().contains("Build the OAuth flow."));
    assertTrue(built.prompt().contains("poke holes in this design"));
    assertFalse(
        built.prompt().contains("## Invite Duty (read only)"),
        "the read-only invite is superseded by engagement");
    assertEquals(List.of(ask), built.renderedMessages());
  }

  @Test
  void aFullInviteTeachesTheDraftAndCodeLanesAndTheGitProtocol() {
    var built = InvitePrompt.build(spec(), "Build the OAuth flow.", List.of());

    assertTrue(built.prompt().contains("full access"));
    assertTrue(built.prompt().contains("## Invite Duty (full access)"));
    assertTrue(built.prompt().contains("spec content auth --body-file"));
    assertTrue(built.prompt().contains("spec create --id"));
    assertTrue(
        built.prompt().contains("born draft"),
        "new specs from a room conversation are drafts a human promotes");
    assertTrue(built.prompt().contains("commit and push before stopping"));
    assertFalse(built.prompt().contains("harness enforces it"));
  }

  @Test
  void anEmptyRoomBuildsAPromptWithNoConversationBlock() {
    var built = InvitePrompt.build(spec(), "body", List.of());

    assertFalse(built.prompt().contains("## Conversation on this spec"));
    assertTrue(built.renderedMessages().isEmpty());
  }
}
