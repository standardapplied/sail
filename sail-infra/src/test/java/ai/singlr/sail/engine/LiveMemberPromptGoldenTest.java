/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins every prompt a live member is given, whole, as text. The expected strings here never change;
 * only the expressions that produce them do.
 */
class LiveMemberPromptGoldenTest {

  private static final String ROOM =
      """
      ## How this room works

      - What you say is your reply: sail posts the text of each of your turns to the room
        as your message. Do not run `spec comment` to answer — your reply would be posted
        twice.
      - `spec comment` is for one thing only, a question only a human can settle:
        `spec comment %s --body <text> --question`. The flag pages the engineer on the
        board until a human replies.
      - Each prompt after this one is what was posted since your last turn. Between turns
        you stay in the room, and nothing is needed from you to remain here.
      - Never change a spec's status.

      """;

  private static final String FULL_ACCESS =
      """
      ## Your lane: full access

      Your tools are unrestricted. Answer, critique, draft, or work in the workspace when
      asked — diagrams, files, experiments: this session holds the repo reservation.
      Nothing forces a commit: leave work in the worktree and say where it is, or commit
      and push when the change is worth keeping and the conversation asks for it.

      """;

  private static final String READ_ONLY =
      """
      ## Your lane: read only

      This membership is read only, and the harness enforces it: file edits and spec
      state changes are denied, and the only shell commands that run are the `spec` CLI
      and `cd` — use the Read and Grep tools for files (git commands are denied too). Do
      not fight a denial; work within the lane. If the conversation asks for code changes
      or shapes real work, describe the change or the spec it deserves — a human can
      dispatch, or re-engage you with full access.

      """;

  private static final String CONVERSATION =
      """
      ## Conversation in this room

      uday (2026-08-11T12:00:00Z):
      what is it stuck on?

      claude/room-7 (2026-08-11T12:00:00Z):
      The token refresh.""";

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

  private static List<MessageStore.MessageRow> conversation() {
    return List.of(
        message("m1", "uday", "what is it stuck on?"),
        message("m2", "claude/room-7", "The token refresh."));
  }

  @Test
  void aFullMemberOfASpecsRoom() {
    assertEquals(
        """
        You are the engaged agent of spec "OAuth flow" (id: auth, status: review): a member of \
        its room. Humans post here and you answer, and the conversation continues across turns \
        in this one session.

        """
            + ROOM.formatted("auth")
            + FULL_ACCESS
            + """
            Draft this spec's body with `spec content auth --body-file <file>` when the
            conversation is shaping it, or create sibling specs with
            `spec create --id <id> --title "<title>" --body-file <file>`. New specs are born \
            draft; a human promotes them on the board.

            ## Spec body

            Build the OAuth flow.

            """
            + CONVERSATION,
        LiveMemberPrompt.fresh(spec(), "Build the OAuth flow.", conversation(), true));
  }

  @Test
  void aReadOnlyMemberOfASpecsRoom() {
    assertEquals(
        """
        You are the engaged agent of spec "OAuth flow" (id: auth, status: review): a member of \
        its room. Humans post here and you answer, and the conversation continues across turns \
        in this one session.

        """
            + ROOM.formatted("auth")
            + READ_ONLY
            + """
            ## Spec body

            Build the OAuth flow.

            """
            + CONVERSATION,
        LiveMemberPrompt.fresh(spec(), "Build the OAuth flow.", conversation(), false));
  }

  @Test
  void aFullMemberOfARoomWithNoSpec() {
    assertEquals(
        """
        You are a member of the room "Lounge" (id: lounge). Humans and agents talk here; no \
        spec is attached — this is a conversation, not a work-item. Humans post and you answer, \
        and the conversation continues across turns in this one session.

        """
            + ROOM.formatted("lounge")
            + FULL_ACCESS
            + """
            When the conversation shapes real work, capture it as a spec with
            `spec create --id <id> --title "<title>" --body-file <file>`. New specs are born \
            draft; a human promotes them on the board.

            """
            + CONVERSATION,
        LiveMemberPrompt.freshForRoom("lounge", "Lounge", conversation(), true));
  }

  @Test
  void aReadOnlyMemberOfARoomWithNoSpecAndNothingSaidYet() {
    assertEquals(
        """
        You are a member of the room "Lounge" (id: lounge). Humans and agents talk here; no \
        spec is attached — this is a conversation, not a work-item. Humans post and you answer, \
        and the conversation continues across turns in this one session.

        """
            + ROOM.formatted("lounge")
            + READ_ONLY,
        LiveMemberPrompt.freshForRoom("lounge", "Lounge", List.of(), false));
  }

  @Test
  void aLaterPromptIsTheNewMessagesAlone() {
    assertEquals(
        """
        uday (2026-08-11T12:00:00Z):
        what is it stuck on?

        claude/room-7 (2026-08-11T12:00:00Z):
        The token refresh.""",
        LiveMemberPrompt.messages(conversation()));
    assertEquals("", LiveMemberPrompt.messages(List.of()));
  }
}
