/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.engine.LiveStream;
import java.util.Map;
import java.util.Optional;

/**
 * What a run of Claude Code reported in its log, read from the {@code result} lines it writes
 * there: what the run spent ({@link ClaudeCode#usage}) and what came of its login ({@link
 * ClaudeCode#loginOutcome}).
 */
final class ClaudeCodeReport {

  /** What every {@code result} line holds. */
  static final String RESULT = "\"type\":\"result\"";

  private ClaudeCodeReport() {}

  static RunUsage usage(String report) {
    var found = RunUsage.last(report, RESULT, ClaudeCodeReport::reports);
    if (found.unreadable()) {
      return null;
    }
    return found.line() == null ? RunUsage.NONE : usageOf(found.line());
  }

  static Optional<LoginOutcome> loginOutcome(String report) {
    var result = RunUsage.last(report, RESULT, line -> "result".equals(line.get("type"))).line();
    if (result == null || !Boolean.TRUE.equals(result.get("is_error"))) {
      return Optional.empty();
    }
    return LiveStream.loginOutcome(
        result.get("result") instanceof String text ? text : "",
        RunUsage.count(result, "api_error_status"));
  }

  private static boolean reports(Map<String, Object> line) {
    if (!"result".equals(line.get("type"))) {
      return false;
    }
    var usage = usageOf(line);
    return (usage.costUsd() != null && usage.costUsd() > 0)
        || (usage.tokens() != null && usage.tokens() > 0);
  }

  private static RunUsage usageOf(Map<String, Object> result) {
    var cost = result.get("total_cost_usd") instanceof Number usd ? usd.doubleValue() : null;
    var models = RunUsage.map(result, "modelUsage");
    if (models.isEmpty()) {
      var usage = RunUsage.map(result, "usage");
      return new RunUsage(
          null,
          RunUsage.count(usage, "input_tokens"),
          RunUsage.count(usage, "output_tokens"),
          RunUsage.count(usage, "cache_read_input_tokens"),
          RunUsage.count(usage, "cache_creation_input_tokens"),
          cost,
          RunUsage.Source.RESULT);
    }
    String model = null;
    var most = -1.0;
    long in = 0;
    long out = 0;
    long read = 0;
    long write = 0;
    for (var entry : models.entrySet()) {
      var figures = RunUsage.map(models, entry.getKey());
      in += figure(figures, "inputTokens");
      out += figure(figures, "outputTokens");
      read += figure(figures, "cacheReadInputTokens");
      write += figure(figures, "cacheCreationInputTokens");
      var spent = figures.get("costUSD") instanceof Number usd ? usd.doubleValue() : 0;
      if (spent > most) {
        most = spent;
        model = entry.getKey();
      }
    }
    return new RunUsage(model, in, out, read, write, cost, RunUsage.Source.RESULT);
  }

  private static long figure(Map<String, Object> figures, String key) {
    var count = RunUsage.count(figures, key);
    return count == null ? 0 : count;
  }
}
