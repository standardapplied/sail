/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SpecStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Says what the review loop did, on the bus and in the room; {@link ReviewNarration} words it. */
final class LoopNarrator {

  private final SpecStore specStore;
  private final EventBus eventBus;
  private final Runnable syncTrigger;
  private MessageStore messageStore;

  LoopNarrator(SpecStore specStore, EventBus eventBus, Runnable syncTrigger) {
    this.specStore = specStore;
    this.eventBus = eventBus;
    this.syncTrigger = syncTrigger;
  }

  void useMessages(MessageStore messages) {
    this.messageStore = messages;
  }

  /**
   * A failure in the pipeline used to be one journal line and a silently stranded spec. Published,
   * Slack shows it, and {@link MissedStopReconciler} replays the stop on a later sweep. Publishing
   * must never mask the original failure.
   */
  void publishPipelineError(String project, String specId, Exception failure) {
    try {
      publishEvent(
          project,
          specId,
          "review_pipeline_error",
          Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
    } catch (RuntimeException e) {
      System.err.println("review-pipeline: could not publish pipeline error: " + e.getMessage());
    }
  }

  private String roomOf(String specId) {
    return specStore.findById(specId).map(SpecStore.SpecRow::roomIdOrIdentity).orElse(specId);
  }

  /** The room's recent messages, for a prompt. A room that cannot be read degrades it only. */
  List<MessageStore.MessageRow> roomMessages(String specId) {
    if (messageStore == null) {
      return List.of();
    }
    try {
      return messageStore.list(roomOf(specId), null, 20);
    } catch (RuntimeException e) {
      System.err.println(
          "review-pipeline: could not read the room of spec " + specId + ": " + e.getMessage());
      return List.of();
    }
  }

  /**
   * Posts the pipeline's narration to the spec's room: the findings themselves, which events cannot
   * carry, and the loop's cross-iteration memory, since the reviewer's prompt includes the room's
   * recent messages. Best-effort — a line that tells of work still under way must never fail that
   * work. The lines that end a review, or record its wait, are written with it ({@link
   * #appendRoom}).
   */
  void postRoom(String specId, String body) {
    try {
      appendRoom(specId, body);
      syncTrigger.run();
    } catch (RuntimeException e) {
      System.err.println(
          "review-pipeline: could not post to the room of spec " + specId + ": " + e.getMessage());
    }
  }

  /**
   * Writes one line to the spec's room, cut to what a room message can hold ({@link
   * MessageStore#fitted}): a line too long to write must never undo the review's end.
   */
  void appendRoom(String specId, String body) {
    if (messageStore != null) {
      messageStore.append(
          roomOf(specId), MessageStore.SAIL_AUTHOR, MessageStore.fitted(body), null);
    }
  }

  void publishGuardrail(String project, String specId, String reason, String action) {
    if (eventBus == null) return;
    eventBus.publish(
        Event.of(
            project,
            specId,
            Event.WellKnownTypes.GUARDRAIL_TRIGGERED,
            Event.SAIL_AGENT,
            HostInfo.hostname(),
            Map.of("reason", reason, "action", action)));
  }

  void publishEvent(String project, String specId, String type, String detail) {
    publishEvent(project, specId, type, detail, Map.of());
  }

  void publishEvent(
      String project, String specId, String type, String detail, Map<String, Object> findings) {
    if (eventBus == null) return;
    var data = new LinkedHashMap<String, Object>();
    if (detail != null) {
      data.put("detail", detail);
    }
    if (!findings.isEmpty()) {
      data.put("findings", findings);
    }
    eventBus.publish(Event.of(project, specId, type, Event.SAIL_AGENT, HostInfo.hostname(), data));
  }
}
