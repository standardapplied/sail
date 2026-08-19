/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;

/**
 * Builds the prompt a chat turn hands its agent: the spec's identity, the room's recent
 * conversation, and the lane duty. Three duties exist. A plain wake (no engagement) keeps the
 * original standing-agent contract: answer read-only, dispatch is the lane that changes code, stop
 * when answered. An engaged turn — read-only or full — is one turn of a continuing conversation:
 * the agent answers (or, in full mode, does the asked work in the workspace), posts to the room,
 * and ends its turn knowing it will be resumed for the next message; "stop" never means "leave". A
 * fresh session is additionally primed with the spec body, exactly like dispatch; a resumed session
 * already carries that context in its conversation and gets only the room tail.
 */
public final class RoomWakePrompt {

  private RoomWakePrompt() {}

  /**
   * A built chat prompt and the room messages it rendered in full — the exact set the launcher may
   * acknowledge as delivered. Delivery derives from presentation, exactly like {@link
   * AgentTaskPrompt.Built}.
   */
  public record Built(String prompt, List<MessageStore.MessageRow> renderedMessages) {}

  public static Built build(
      SpecStore.SpecRow spec,
      String body,
      List<MessageStore.MessageRow> messages,
      boolean resumed,
      Engagement engagement) {
    var conversation =
        PromptConversation.renderNewest(
            messages,
            message ->
                message.author() + " (" + message.createdAt() + "):\n" + message.body() + "\n\n");
    var conversationBlock =
        conversation.text().isEmpty()
            ? ""
            : "## Conversation on this spec\n\n" + conversation.text();
    var specBlock = resumed ? "" : "## Spec body\n\n" + body + "\n\n";
    var opening =
        engagement == null
            ? "). A human posted in this spec's room while no run was live; you were woken to"
                + " answer.\n\n"
            : "). You are engaged in this room — a standing participant, not a visitor: humans"
                + " post, you answer, and the conversation continues across turns.\n\n";
    var prompt =
        "You are the standing agent of spec \""
            + spec.title()
            + "\" (id: "
            + spec.id()
            + ", status: "
            + spec.status().wire()
            + opening
            + conversationBlock
            + specBlock
            + duty(spec.id(), engagement);
    return new Built(prompt, conversation.fullyRendered());
  }

  private static String duty(String specId, Engagement engagement) {
    if (engagement == null) {
      return wakeDuty(specId);
    }
    return engagement.full() ? engagedFullDuty(specId) : engagedReadOnlyDuty(specId);
  }

  private static String wakeDuty(String specId) {
    return """
        ## Room Duty

        Read the conversation above, investigate in the workspace as needed, and answer in
        the room with `spec comment %s --body <text>` (or `--body -` for stdin).

        This is a read-only chat session, and the harness enforces it: file edits and spec
        state changes are denied, and the only shell commands that run are the `spec` CLI
        and `cd` — use the Read and Grep tools for files (git commands are denied too;
        read what you need through the tools). Do not fight a denial; work within the
        lane. If the conversation asks for code changes, answer that dispatch is the lane
        that changes code — describe what a re-dispatch (`sail spec dispatch %s --restart`)
        or a follow-up spec should do instead of doing it here. If the
        conversation asks something you cannot answer or decide alone, post it back with
        `spec comment %s --question --body <text>` — the flag pages the engineer on the
        board until a human replies.

        The room stays live while you work: replies posted in the meantime are delivered
        into your context automatically after a tool call finishes, and unread messages
        block your first attempt to stop. When you have answered, stop.
        """
        .formatted(specId, specId, specId);
  }

  private static String engagedReadOnlyDuty(String specId) {
    return """
        ## Engaged Turn (read only)

        Read the newest human messages above, investigate in the workspace as needed, and
        answer in the room with `spec comment %s --body <text>` (or `--body -` for
        stdin).

        This engagement is read only, and the harness enforces it: file edits and spec
        state changes are denied, and the only shell commands that run are the `spec` CLI
        and `cd` — use the Read and Grep tools for files (git commands are denied too).
        Do not fight a denial; work within the lane. If the conversation asks for code
        changes, describe them in the room — a human can dispatch, or re-engage you with
        full access. For a question only a human can settle, use
        `spec comment %s --question --body <text>` — the flag pages the engineer on the
        board until a human replies.

        This is one turn of a continuing conversation. Replies posted while you work are
        delivered into your context automatically after a tool call finishes, and unread
        messages block your first attempt to stop. When you have answered, post your reply
        and end your turn — you remain engaged and will be resumed for the next message.
        Never say goodbye; the room continues.
        """
        .formatted(specId, specId);
  }

  private static String engagedFullDuty(String specId) {
    return """
        ## Engaged Turn (full access)

        Read the newest human messages above, then help however the conversation asks:
        answer, critique, draft, or work in the workspace.

        - Post to the room with `spec comment %s --body <text>` (or `--body -` for
          stdin). For a question only a human can settle, add `--question` — the flag
          pages the engineer on the board until a human replies.
        - Draft this spec's body with `spec content %s --body-file <file>` when the
          conversation is shaping it, or create sibling specs with
          `spec create --id <id> --title "<title>" --body-file <file>`. New specs are
          born draft; a human promotes them on the board — never set a status yourself.
        - Work in the workspace freely when asked — diagrams, files, experiments: this
          turn holds the repo reservation. Nothing forces a commit at turn end: leave
          work in the worktree and say where it is, or commit and push when the change
          is worth keeping and the conversation asks for it.

        This is one turn of a continuing conversation. Replies posted while you work are
        delivered into your context automatically after a tool call finishes, and unread
        messages block your first attempt to stop. When you have done what was asked,
        post your reply and end your turn — you remain engaged and will be resumed for
        the next message. Never say goodbye; the room continues.
        """
        .formatted(specId, specId);
  }
}
