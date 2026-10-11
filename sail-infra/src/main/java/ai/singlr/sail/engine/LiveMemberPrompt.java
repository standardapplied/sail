/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;

/**
 * What a live member is prompted with. A fresh session's first prompt says who the member is, how
 * its room works and what its lane allows, then the spec and the room's recent conversation; every
 * later prompt, and the first of a resumed session, is the new messages alone, since the session
 * already holds the rest.
 *
 * <p>The contract differs from a wake's in one way that everything else follows from: sail reads
 * the member's process and posts what it says, so the member never posts its own answer, and it is
 * never told to end its turn or that it will be resumed, because it does not leave.
 */
public final class LiveMemberPrompt {

  private LiveMemberPrompt() {}

  /** The first prompt of a fresh session in the room of {@code spec}. */
  public static String fresh(
      SpecStore.SpecRow spec,
      String body,
      List<MessageStore.MessageRow> conversation,
      boolean full) {
    return "You are the engaged agent of spec \""
        + spec.title()
        + "\" (id: "
        + spec.id()
        + ", status: "
        + spec.status().wire()
        + "): a member of its room. Humans post here and you answer, and the conversation"
        + " continues across turns in this one session.\n\n"
        + room(spec.id())
        + (full ? fullLane(specWork(spec.id())) : readOnlyLane())
        + "## Spec body\n\n"
        + body
        + "\n\n"
        + conversationBlock(conversation);
  }

  /** The first prompt of a fresh session in a room with no spec. */
  public static String freshForRoom(
      String roomId, String title, List<MessageStore.MessageRow> conversation, boolean full) {
    return "You are a member of the room \""
        + title
        + "\" (id: "
        + roomId
        + "). Humans and agents talk here; no spec is attached — this is a conversation, not a"
        + " work-item. Humans post and you answer, and the conversation continues across turns"
        + " in this one session.\n\n"
        + room(roomId)
        + (full ? fullLane(ROOM_WORK) : readOnlyLane())
        + conversationBlock(conversation);
  }

  /** A prompt that is {@code messages} alone: what was posted since the member's last turn. */
  public static String messages(List<MessageStore.MessageRow> messages) {
    return render(messages).strip();
  }

  private static String conversationBlock(List<MessageStore.MessageRow> conversation) {
    var rendered = render(conversation);
    return rendered.isEmpty() ? "" : "## Conversation in this room\n\n" + rendered.strip();
  }

  private static String render(List<MessageStore.MessageRow> messages) {
    return PromptConversation.renderNewest(
            messages,
            message ->
                message.author() + " (" + message.createdAt() + "):\n" + message.body() + "\n\n")
        .text();
  }

  private static String room(String roomId) {
    return """
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

        """
        .formatted(roomId);
  }

  private static String fullLane(String specWork) {
    return """
        ## Your lane: full access

        Your tools are unrestricted. Answer, critique, draft, or work in the workspace when
        asked — diagrams, files, experiments: this session holds the repo reservation.
        Nothing forces a commit: leave work in the worktree and say where it is, or commit
        and push when the change is worth keeping and the conversation asks for it.

        %s New specs are born draft; a human promotes them on the board.

        """
        .formatted(specWork);
  }

  private static String specWork(String specId) {
    return """
        Draft this spec's body with `spec content %s --body-file <file>` when the
        conversation is shaping it, or create sibling specs with
        `spec create --id <id> --title "<title>" --body-file <file>`."""
        .formatted(specId);
  }

  private static final String ROOM_WORK =
      """
      When the conversation shapes real work, capture it as a spec with
      `spec create --id <id> --title "<title>" --body-file <file>`.""";

  private static String readOnlyLane() {
    return """
        ## Your lane: read only

        This membership is read only, and the harness enforces it: file edits and spec
        state changes are denied, and the only shell commands that run are the `spec` CLI
        and `cd` — use the Read and Grep tools for files (git commands are denied too). Do
        not fight a denial; work within the lane. If the conversation asks for code changes
        or shapes real work, describe the change or the spec it deserves — a human can
        dispatch, or re-engage you with full access.

        """;
  }
}
