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

/**
 * Says what the review loop did: on the bus, and in the spec's room. {@link ReviewNarration} keeps
 * the words.
 */
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
   * A failure in this handler used to be one journal line and a silently stranded spec — the review
   * never started and nothing downstream noticed (the field incident: a raced SQLite statement
   * killed the kickoff twice in one week). Publish it loudly so Slack shows the failure, and rely
   * on {@link MissedStopReconciler}'s replay to deliver the stop again on a later sweep. Publishing
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

  /**
   * The room's recent messages for the reviewer's prompt and the fix task. Best-effort: the
   * conversation enriches the prompt, it is not a precondition — a room that cannot be read must
   * degrade the review, never error it.
   */
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
   * Posts the pipeline's own narration to the spec's room: the findings themselves, which events
   * cannot carry. This is also the loop's cross-iteration memory: the reviewer's prompt includes
   * the room's recent messages, so the next pass sees the previous verdict. Best-effort — a line
   * that tells of work still under way must never fail that work. The lines that end a review, or
   * record its wait, are written with what they tell of instead ({@link #appendRoom}).
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
   * MessageStore#fitted}): a verdict or a reason is as long as its findings and its error text, and
   * a line too long to write must never undo the review's end it is written with.
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
