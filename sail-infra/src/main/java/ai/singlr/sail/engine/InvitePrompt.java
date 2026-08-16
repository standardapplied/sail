/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;

/**
 * Builds the prompt an invited agent receives: the spec's identity, the room's recent conversation,
 * the spec body, and the invite duty for the chosen mode. An invite is always a fresh session — the
 * point of inviting is a new participant's perspective, so it never resumes another run's
 * conversation. The duty differs by mode: read only is the room lane's read-and-converse contract
 * under an explicit invitation; full access may draft specs and change code, paid for by the
 * pre-launch snapshot and the repo reservation the launcher took.
 */
public final class InvitePrompt {

  private InvitePrompt() {}

  /**
   * A built invite prompt and the room messages it rendered in full — the exact set the launcher
   * may acknowledge as delivered. Delivery derives from presentation, exactly like {@link
   * RoomWakePrompt.Built}.
   */
  public record Built(String prompt, List<MessageStore.MessageRow> renderedMessages) {}

  public static Built build(
      SpecStore.SpecRow spec, String body, List<MessageStore.MessageRow> messages, boolean full) {
    var conversation =
        PromptConversation.renderNewest(
            messages,
            message ->
                message.author() + " (" + message.createdAt() + "):\n" + message.body() + "\n\n");
    var conversationBlock =
        conversation.text().isEmpty()
            ? ""
            : "## Conversation on this spec\n\n" + conversation.text();
    var prompt =
        "You are an invited agent in the room of spec \""
            + spec.title()
            + "\" (id: "
            + spec.id()
            + ", status: "
            + spec.status().wire()
            + "). A human invited you into this conversation with "
            + (full ? "full access" : "read-only access")
            + " to help: chat, critique, or draft — bring your own perspective.\n\n"
            + conversationBlock
            + "## Spec body\n\n"
            + body
            + "\n\n"
            + (full ? fullDuty(spec.id()) : readOnlyDuty(spec.id()));
    return new Built(prompt, conversation.fullyRendered());
  }

  private static String readOnlyDuty(String specId) {
    return """
        ## Invite Duty (read only)

        Read the conversation and spec body above, investigate in the workspace as needed,
        and post your contribution in the room with `spec comment %s --body <text>`
        (or `--body -` for stdin).

        This is a read-only chat session, and the harness enforces it: file edits and spec
        state changes are denied, and the only shell commands that run are the `spec` CLI
        and `cd` — use the Read and Grep tools for files. Do not fight a denial; work
        within the lane. If the conversation asks for code changes, describe them in the
        room — dispatch (or a full invite) is the lane that changes code. If the
        conversation asks something you cannot answer or decide alone, post it back with
        `spec comment %s --question --body <text>` — the flag pages the engineer on the
        board until a human replies.

        The room stays live while you work: replies posted in the meantime are delivered
        into your context automatically after a tool call finishes, and unread messages
        block your first attempt to stop. When you have contributed, stop.
        """
        .formatted(specId, specId);
  }

  private static String fullDuty(String specId) {
    return """
        ## Invite Duty (full access)

        Read the conversation and spec body above, then help however the conversation
        asks: answer, critique, draft, or change code.

        - Post to the room with `spec comment %s --body <text>` (or `--body -` for
          stdin), and post a final summary there before you stop. For a question only a
          human can settle, add `--question` — the flag pages the engineer on the board.
        - Draft the spec body with `spec content %s --body-file <file>` when the
          conversation is shaping this spec, or create sibling specs with
          `spec create --id <id> --title "<title>" --body-file <file>`. New specs are
          born draft; a human promotes them on the board — never set a status yourself.
        - For code changes: a container snapshot was taken before you launched and you
          hold the repo reservation, so work directly in the workspace. Work on the
          spec's branch when one exists; commit and push before stopping — the stop gate
          holds your turn open until every touched repo is clean and pushed.

        The room stays live while you work: replies posted in the meantime are delivered
        into your context automatically after a tool call finishes, and unread messages
        block your first attempt to stop. When you have contributed, stop.
        """
        .formatted(specId, specId);
  }
}
