/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a single line of an agent log into a human-readable progress line.
 *
 * <p>Dispatched Claude Code agents stream newline-delimited JSON events ({@code --output-format
 * stream-json}); this renderer detects such a line and collapses it to readable output — assistant
 * text, a one-line tool-call summary, a tool-result status, or the final result. Any line that is
 * not a recognised stream-json event (Codex's already-readable transcript, or a pre-stream-json
 * log) passes through untouched, so the same renderer is correct for both agents and backward
 * compatible with old logs.
 */
public final class AgentLogRenderer {

  private AgentLogRenderer() {}

  private static final int SUMMARY_LIMIT = 160;

  private static final List<String> SUMMARY_KEYS =
      List.of("command", "file_path", "path", "pattern", "url", "description", "prompt");

  /**
   * Renders one raw log line. Returns the readable form for stream-json events, the line unchanged
   * for plain text, and an empty string for events that carry no progress to show (e.g. the noisy
   * {@code system/init} event).
   */
  public static String render(String line) {
    if (line == null || line.isBlank()) {
      return "";
    }
    Map<String, Object> event;
    try {
      event = YamlUtil.parseMap(line);
    } catch (RuntimeException e) {
      return line;
    }
    if (!(event.get("type") instanceof String type)) {
      return line;
    }
    return switch (type) {
      case "assistant" -> renderAssistant(event);
      case "user" -> renderUser(event);
      case "result" -> renderResult(event);
      case "system" -> "";
      default -> line;
    };
  }

  private static String renderAssistant(Map<String, Object> event) {
    var lines = new ArrayList<String>();
    for (var block : contentBlocks(event)) {
      switch (Objects.toString(block.get("type"), "")) {
        case "text" -> {
          var text = Objects.toString(block.get("text"), "").strip();
          if (!text.isEmpty()) {
            lines.add(text);
          }
        }
        case "tool_use" ->
            lines.add(
                "⚙ "
                    + Objects.toString(block.get("name"), "tool")
                    + summarizeInput(block.get("input")));
        default -> {}
      }
    }
    return String.join("\n", lines);
  }

  private static String renderUser(Map<String, Object> event) {
    var lines = new ArrayList<String>();
    for (var block : contentBlocks(event)) {
      if ("tool_result".equals(block.get("type"))) {
        lines.add(Boolean.TRUE.equals(block.get("is_error")) ? "  ↳ error" : "  ↳ ok");
      }
    }
    return String.join("\n", lines);
  }

  private static String renderResult(Map<String, Object> event) {
    var result = Objects.toString(event.get("result"), "").strip();
    return result.isEmpty() ? "" : "── result ──\n" + result;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> contentBlocks(Map<String, Object> event) {
    if (event.get("message") instanceof Map<?, ?> message
        && message.get("content") instanceof List<?> content) {
      return content.stream()
          .filter(b -> b instanceof Map<?, ?>)
          .map(b -> (Map<String, Object>) b)
          .toList();
    }
    return List.of();
  }

  private static String summarizeInput(Object input) {
    if (!(input instanceof Map<?, ?> map) || map.isEmpty()) {
      return "";
    }
    for (var key : SUMMARY_KEYS) {
      var value = map.get(key);
      if (value != null) {
        return "(" + truncate(oneLine(value.toString())) + ")";
      }
    }
    return "(" + truncate(oneLine(map.keySet().toString())) + ")";
  }

  private static String oneLine(String value) {
    return value.replace('\n', ' ').replace('\r', ' ').strip();
  }

  private static String truncate(String value) {
    return value.length() <= SUMMARY_LIMIT ? value : value.substring(0, SUMMARY_LIMIT - 1) + "…";
  }
}
