/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;

/**
 * Builds the prompt a room wake hands its agent: the spec's identity, the room's recent
 * conversation, and the standing chat-lane instruction — answer in the room, never touch the
 * worktrees, "dispatch to change code" is the answer to change requests. A fresh session is
 * additionally primed with the spec body, exactly like dispatch; a resumed session already carries
 * that context in its conversation and gets only the room tail.
 */
public final class RoomWakePrompt {

  private RoomWakePrompt() {}

  /**
   * A built wake prompt and the room messages it rendered in full — the exact set the launcher may
   * acknowledge as delivered. Delivery derives from presentation, exactly like {@link
   * AgentTaskPrompt.Built}.
   */
  public record Built(String prompt, List<MessageStore.MessageRow> renderedMessages) {}

  public static Built build(
      SpecStore.SpecRow spec,
      String body,
      List<MessageStore.MessageRow> messages,
      boolean resumed) {
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
    var prompt =
        "You are the standing agent of spec \""
            + spec.title()
            + "\" (id: "
            + spec.id()
            + ", status: "
            + spec.status().wire()
            + "). A human posted in this spec's room while no run was live; you were woken to"
            + " answer.\n\n"
            + conversationBlock
            + specBlock
            + roomDuty(spec.id());
    return new Built(prompt, conversation.fullyRendered());
  }

  private static String roomDuty(String specId) {
    return """
        ## Room Duty

        Read the conversation above, investigate in the workspace as needed (reading is fine),
        and answer in the room with `spec comment %s --body <text>` (or `--body -`
        for stdin).

        This is a read-only chat session: never modify the worktrees, never commit, never
        push, and never change the spec's status. If the conversation asks for code changes,
        answer that dispatch is the lane that changes code — describe what a re-dispatch
        (`sail spec dispatch %s --restart`) or a follow-up spec should do instead of doing
        it here.

        The room stays live while you work: replies posted in the meantime are delivered
        into your context automatically after a tool call finishes, and unread messages
        block your first attempt to stop. When you have answered, stop.
        """
        .formatted(specId, specId);
  }
}
