/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.YamlUtil;
import java.util.Map;

/**
 * Pulls an agent's final answer out of a streamed ({@code --output-format stream-json}) log: the
 * {@code result} field of the last {@code type:result} event. This is what lets a reviewer stream
 * to its log live (so it is watchable and its stall timer resets) while its findings still parse
 * from a single clean block, which is why review no longer needs a separate non-streaming path.
 *
 * <p>When there is no {@code result} event — Codex's already-readable transcript, or a non-streamed
 * run — the input is returned unchanged, so one caller parses both uniformly.
 */
public final class StreamJsonResult {

  private StreamJsonResult() {}

  /** The terminal result text of a stream-json log, or the input unchanged when there is none. */
  public static String extract(String streamLog) {
    if (streamLog == null || streamLog.isBlank()) {
      return "";
    }
    String result = null;
    for (var line : streamLog.split("\n")) {
      if (line.isBlank()) {
        continue;
      }
      Map<String, Object> event;
      try {
        event = YamlUtil.parseMap(line);
      } catch (RuntimeException e) {
        continue;
      }
      if ("result".equals(event.get("type")) && event.get("result") instanceof String r) {
        result = r;
      }
    }
    return result != null ? result : streamLog;
  }
}
