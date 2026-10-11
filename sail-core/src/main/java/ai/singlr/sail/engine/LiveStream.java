/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.harness.Harness;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The two directions of a live member's pipes, as Claude Code's streaming mode speaks them: the
 * JSON line that prompts it, and what each JSON line it writes says happened. Everything a line
 * carries is the agent's and so untrusted: a session id is taken only when it is safe to hand back
 * as an argument, a tool's name and summary are clipped where they are read, a line too long to be
 * worth parsing (a large tool result) says nothing, and a line that is not one of these says
 * nothing either, so a harness that adds a line type breaks no reader.
 *
 * <p>Only the member's own lines count. A subagent's lines carry the tool call that started it, and
 * what a subagent writes is not what the member says to the room.
 */
public final class LiveStream {

  /** A longer line is skipped unparsed: the frame ceiling, in characters. */
  public static final int MAX_LINE_CHARS = 1 << 20;

  /** How much of a tool's first string argument a reader of the room is shown. */
  public static final int SUMMARY_CODE_POINTS = 120;

  private static final int TOOL_NAME_CODE_POINTS = 64;

  private LiveStream() {}

  /** What one line of a live member's stdout says. */
  public sealed interface Step {

    /** The process named the conversation it is in. */
    record Init(String sessionId) implements Step {}

    /** A block of the member's text begins: what follows is not glued to what came before. */
    record TextStarted() implements Step {}

    /** The member wrote {@code text}, a fragment of what it is saying. */
    record Text(String text) implements Step {}

    /** The member called tools. */
    record ToolCalls(List<ToolCall> calls) implements Step {}

    /** The calls named by {@code callIds} came back. */
    record ToolResults(List<String> callIds) implements Step {}

    /**
     * The turn is over. {@code reason} is why it failed, {@code null} for a turn that did not;
     * {@code text} is the turn's final text as the harness reports it.
     */
    record Result(boolean error, String reason, String text) implements Step {}

    /** The member is at work on its turn, with nothing to show for this line. */
    record Working() implements Step {}

    /** Nothing a reader acts on. */
    record Other() implements Step {}
  }

  /** One tool call: its id, the tool, and its first string argument, clipped. */
  public record ToolCall(String id, String tool, String summary) {}

  /** The stdin line that prompts a live member with {@code text}. */
  public static String userMessage(String text) {
    var block = new LinkedHashMap<String, Object>();
    block.put("type", "text");
    block.put("text", text);
    var message = new LinkedHashMap<String, Object>();
    message.put("role", "user");
    message.put("content", List.of(block));
    var line = new LinkedHashMap<String, Object>();
    line.put("type", "user");
    line.put("message", message);
    return YamlUtil.dumpJson(line);
  }

  /** What {@code line} says; {@link Step.Other} for a line that is too long or is not JSON. */
  public static Step read(String line) {
    Map<String, Object> event;
    try {
      event = YamlUtil.parseJsonLine(line, MAX_LINE_CHARS);
    } catch (RuntimeException unreadable) {
      return new Step.Other();
    }
    var type = Objects.toString(event.get("type"), "");
    if ("result".equals(type)) {
      return result(event);
    }
    if ("system".equals(type)) {
      return init(event);
    }
    if (event.get("parent_tool_use_id") != null) {
      return new Step.Other();
    }
    return switch (type) {
      case "stream_event" -> streamed(map(event.get("event")));
      case "assistant" -> called(blocks(event));
      case "user" -> returned(blocks(event));
      default -> new Step.Other();
    };
  }

  private static Step result(Map<String, Object> event) {
    var error = Boolean.TRUE.equals(event.get("is_error"));
    return new Step.Result(
        error,
        error ? Objects.toString(event.get("subtype"), "error") : null,
        event.get("result") instanceof String text ? text : "");
  }

  private static Step init(Map<String, Object> event) {
    return "init".equals(event.get("subtype"))
            && event.get("session_id") instanceof String id
            && Harness.isSafeSessionId(id)
        ? new Step.Init(id)
        : new Step.Other();
  }

  private static Step streamed(Map<String, Object> event) {
    var type = Objects.toString(event.get("type"), "");
    if ("content_block_start".equals(type)
        && "text".equals(map(event.get("content_block")).get("type"))) {
      return new Step.TextStarted();
    }
    var delta = map(event.get("delta"));
    return "content_block_delta".equals(type)
            && "text_delta".equals(delta.get("type"))
            && delta.get("text") instanceof String text
            && !text.isEmpty()
        ? new Step.Text(text)
        : new Step.Working();
  }

  private static Step called(List<Map<String, Object>> blocks) {
    var calls =
        blocks.stream()
            .filter(block -> "tool_use".equals(block.get("type")))
            .map(
                block ->
                    new ToolCall(
                        Objects.toString(block.get("id"), ""),
                        Strings.clip(
                            Objects.toString(block.get("name"), ""), TOOL_NAME_CODE_POINTS),
                        summary(map(block.get("input")))))
            .toList();
    return calls.isEmpty() ? new Step.Working() : new Step.ToolCalls(calls);
  }

  private static Step returned(List<Map<String, Object>> blocks) {
    var ids =
        blocks.stream()
            .filter(block -> "tool_result".equals(block.get("type")))
            .map(block -> Objects.toString(block.get("tool_use_id"), ""))
            .toList();
    return ids.isEmpty() ? new Step.Other() : new Step.ToolResults(ids);
  }

  private static String summary(Map<String, Object> input) {
    return input.values().stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .findFirst()
        .map(first -> Strings.clip(first, SUMMARY_CODE_POINTS))
        .orElse("");
  }

  private static List<Map<String, Object>> blocks(Map<String, Object> event) {
    return map(event.get("message")).get("content") instanceof List<?> content
        ? content.stream().map(LiveStream::map).toList()
        : List.of();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }
}
