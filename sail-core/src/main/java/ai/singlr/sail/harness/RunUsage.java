/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.config.YamlUtil;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * What a run cost, as its harness reported it: the model it ran on, the tokens it read uncached,
 * wrote, read from the cache and wrote to it, the dollars, and where the report came from. A figure
 * the harness did not report is null, never zero; a run whose harness reported nothing is {@link
 * #NONE}, every figure null. {@code tokensIn} excludes the cached part of the input, so the four
 * token figures add up to every token the run reported, whichever harness counted them.
 */
public record RunUsage(
    String model,
    Long tokensIn,
    Long tokensOut,
    Long tokensCacheRead,
    Long tokensCacheWrite,
    Double costUsd,
    Source source) {

  /** A run whose harness reported nothing. */
  public static final RunUsage NONE = new RunUsage(null, null, null, null, null, null, Source.NONE);

  /** The longest line a report is read from: a result line carries the run's final text too. */
  static final int MAX_LINE_CHARS = 16 << 20;

  /** Where a run's usage was read: its log's result line, its transcript, or nowhere. */
  public enum Source {
    RESULT("result"),
    TRANSCRIPT("transcript"),
    NONE("none");

    private final String wire;

    Source(String wire) {
      this.wire = wire;
    }

    public String wire() {
      return wire;
    }

    /** The source a row names, or null for a row that names none. */
    public static Source fromWire(String wire) {
      if (wire == null) {
        return null;
      }
      for (var source : values()) {
        if (source.wire.equals(wire)) {
          return source;
        }
      }
      throw new IllegalArgumentException("Unknown usage source: '" + wire + "'.");
    }
  }

  public RunUsage {
    Objects.requireNonNull(source, "source");
  }

  /** Whether the harness reported any usage. */
  public boolean reported() {
    return source != Source.NONE;
  }

  /** The sum of the four token figures, or null when the harness reported no tokens. */
  public Long tokens() {
    if (tokensIn == null
        || tokensOut == null
        || tokensCacheRead == null
        || tokensCacheWrite == null) {
      return null;
    }
    return tokensIn + tokensOut + tokensCacheRead + tokensCacheWrite;
  }

  /**
   * This report less {@code earlier}, what the same conversation's other runs already recorded: a
   * harness resuming a conversation reports the conversation's whole spend, so what this run spent
   * is the difference. A report below {@code earlier} in any figure is a harness that started
   * counting over, and it is this run's alone; so is a report with nothing recorded before it.
   */
  public RunUsage beyond(RunUsage earlier) {
    if (earlier == null || !reported() || !earlier.reported() || below(earlier)) {
      return this;
    }
    return new RunUsage(
        model,
        less(tokensIn, earlier.tokensIn),
        less(tokensOut, earlier.tokensOut),
        less(tokensCacheRead, earlier.tokensCacheRead),
        less(tokensCacheWrite, earlier.tokensCacheWrite),
        costUsd == null || earlier.costUsd == null
            ? costUsd
            : Double.valueOf(costUsd - earlier.costUsd),
        source);
  }

  private boolean below(RunUsage earlier) {
    return lower(tokensIn, earlier.tokensIn)
        || lower(tokensOut, earlier.tokensOut)
        || lower(tokensCacheRead, earlier.tokensCacheRead)
        || lower(tokensCacheWrite, earlier.tokensCacheWrite)
        || (costUsd != null && earlier.costUsd != null && costUsd < earlier.costUsd);
  }

  private static boolean lower(Long figure, Long earlier) {
    return figure != null && earlier != null && figure < earlier;
  }

  private static Long less(Long figure, Long earlier) {
    return figure == null || earlier == null ? figure : figure - earlier;
  }

  /**
   * The last line of {@code text} holding {@code marker} that parses as a JSON object {@code is}
   * accepts; empty when there is none. Lines are scanned from the end and only a line holding the
   * marker is parsed, so a long log costs a parse per candidate, not per line.
   */
  static Optional<Map<String, Object>> last(
      String text, String marker, Predicate<Map<String, Object>> is) {
    if (text == null) {
      return Optional.empty();
    }
    var end = text.length();
    while (end > 0) {
      var start = text.lastIndexOf('\n', end - 1) + 1;
      var line = text.substring(start, end).strip();
      if (line.contains(marker)) {
        var parsed = parsed(line);
        if (parsed.isPresent() && is.test(parsed.get())) {
          return parsed;
        }
      }
      end = start - 1;
    }
    return Optional.empty();
  }

  private static Optional<Map<String, Object>> parsed(String line) {
    try {
      return Optional.of(YamlUtil.parseJsonLine(line, MAX_LINE_CHARS));
    } catch (IllegalArgumentException unreadable) {
      return Optional.empty();
    }
  }

  /** The map under {@code key}, or an empty one. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Map<String, Object> from, String key) {
    return from.get(key) instanceof Map<?, ?> found ? (Map<String, Object>) found : Map.of();
  }

  /** The whole number under {@code key}, or null. */
  static Long count(Map<String, Object> from, String key) {
    return from.get(key) instanceof Number number ? number.longValue() : null;
  }
}
