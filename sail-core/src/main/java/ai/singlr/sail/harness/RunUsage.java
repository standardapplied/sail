/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import ai.singlr.sail.config.YamlUtil;
import java.util.Map;
import java.util.Objects;
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

  /**
   * Where a run's usage was read: its log's result line, its transcript, or nowhere; or a report
   * read whose share of its session's running total cannot be told ({@link #attributedIn}).
   */
  public enum Source {
    RESULT("result"),
    TRANSCRIPT("transcript"),
    NONE("none"),
    UNATTRIBUTABLE("unattributable");

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

  /** Whether this holds figures its harness reported. */
  public boolean reported() {
    return source == Source.RESULT || source == Source.TRANSCRIPT;
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
   * A harness session's usage on this box: the highest running total it reported, and how much of
   * that its runs were given as their own. A report below the highest was not one any run's share
   * can be told from, so what was given falls short of the highest only by the shares taken back
   * from such runs. {@link #NEW} is a session nothing was reported of yet.
   */
  public record Session(RunUsage highest, RunUsage given) {

    public static final Session NEW = new Session(NONE, NONE);

    public Session {
      Objects.requireNonNull(highest, "highest");
      Objects.requireNonNull(given, "given");
    }
  }

  /** A run's usage after a report, and its session's after it. */
  public record Attributed(RunUsage own, Session session) {}

  /**
   * This report, the running total of {@code session}, attributed to the run that holds {@code own}
   * so far: its share is the report less what the session's other runs were given, so a resumed
   * session, a run whose sibling was erased and a run reporting after each turn all record what
   * they spent, and nothing twice. A report below the session's highest in any figure (an earlier
   * run finishing late, or a harness that started counting over) leaves the run's own share
   * unknown: it is recorded {@link Source#UNATTRIBUTABLE}, gives back what it was given, and never
   * leaves that state. A report of nothing changes nothing.
   */
  public Attributed attributedIn(Session session, RunUsage own) {
    var held = own == null ? NONE : own;
    if (held.source == Source.UNATTRIBUTABLE || !reported()) {
      return new Attributed(own == null && !reported() ? this : held, session);
    }
    if (below(session.highest)) {
      return new Attributed(
          new RunUsage(model, null, null, null, null, null, Source.UNATTRIBUTABLE),
          held.reported() ? new Session(session.highest, session.given.less(held)) : session);
    }
    var others = session.given.less(held);
    var share =
        new RunUsage(
            model,
            less(tokensIn, others.tokensIn),
            less(tokensOut, others.tokensOut),
            less(tokensCacheRead, others.tokensCacheRead),
            less(tokensCacheWrite, others.tokensCacheWrite),
            less(costUsd, others.costUsd),
            source);
    return new Attributed(share, new Session(over(session.highest), others.plus(share)));
  }

  private boolean below(RunUsage highest) {
    return lower(tokensIn, highest.tokensIn)
        || lower(tokensOut, highest.tokensOut)
        || lower(tokensCacheRead, highest.tokensCacheRead)
        || lower(tokensCacheWrite, highest.tokensCacheWrite)
        || (costUsd != null && highest.costUsd != null && costUsd < highest.costUsd);
  }

  private static boolean lower(Long figure, Long highest) {
    return figure != null && highest != null && figure < highest;
  }

  private RunUsage over(RunUsage highest) {
    return new RunUsage(
        null,
        tokensIn == null ? highest.tokensIn : tokensIn,
        tokensOut == null ? highest.tokensOut : tokensOut,
        tokensCacheRead == null ? highest.tokensCacheRead : tokensCacheRead,
        tokensCacheWrite == null ? highest.tokensCacheWrite : tokensCacheWrite,
        costUsd == null ? highest.costUsd : costUsd,
        Source.RESULT);
  }

  private RunUsage less(RunUsage other) {
    return new RunUsage(
        null,
        less(tokensIn, other.tokensIn),
        less(tokensOut, other.tokensOut),
        less(tokensCacheRead, other.tokensCacheRead),
        less(tokensCacheWrite, other.tokensCacheWrite),
        less(costUsd, other.costUsd),
        Source.RESULT);
  }

  private RunUsage plus(RunUsage other) {
    return new RunUsage(
        null,
        plus(tokensIn, other.tokensIn),
        plus(tokensOut, other.tokensOut),
        plus(tokensCacheRead, other.tokensCacheRead),
        plus(tokensCacheWrite, other.tokensCacheWrite),
        plus(costUsd, other.costUsd),
        Source.RESULT);
  }

  private static Long less(Long figure, Long taken) {
    if (figure == null) {
      return null;
    }
    return taken == null ? figure : Long.valueOf(figure - taken);
  }

  private static Double less(Double figure, Double taken) {
    if (figure == null) {
      return null;
    }
    return taken == null ? figure : Double.valueOf(figure - taken);
  }

  private static Long plus(Long figure, Long added) {
    if (figure == null) {
      return added;
    }
    return added == null ? figure : Long.valueOf(figure + added);
  }

  private static Double plus(Double figure, Double added) {
    if (figure == null) {
      return added;
    }
    return added == null ? figure : Double.valueOf(figure + added);
  }

  /**
   * What a scan of a harness's file for its report found: the line, nothing ({@link #NOTHING}), or
   * a last line holding the marker that does not parse ({@link #UNREADABLE}), whose report is not
   * read rather than taken from an earlier running total.
   */
  record Found(Map<String, Object> line, boolean unreadable) {
    static final Found NOTHING = new Found(null, false);
    static final Found UNREADABLE = new Found(null, true);
  }

  /**
   * The last line of {@code text} holding {@code marker} that parses as a JSON object {@code is}
   * accepts; nothing when there is none; unreadable when a line holding the marker after the last
   * accepted one does not parse. Lines are scanned from the end and only a line holding the marker
   * is parsed, so a long log costs a parse per candidate, not per line.
   */
  static Found last(String text, String marker, Predicate<Map<String, Object>> is) {
    if (text == null) {
      return Found.NOTHING;
    }
    var end = text.length();
    while (end > 0) {
      var start = text.lastIndexOf('\n', end - 1) + 1;
      var line = text.substring(start, end).strip();
      if (line.contains(marker)) {
        var parsed = parsed(line);
        if (parsed == null) {
          return Found.UNREADABLE;
        }
        if (is.test(parsed)) {
          return new Found(parsed, false);
        }
      }
      end = start - 1;
    }
    return Found.NOTHING;
  }

  private static Map<String, Object> parsed(String line) {
    try {
      return YamlUtil.parseJsonLine(line, MAX_LINE_CHARS);
    } catch (IllegalArgumentException unreadable) {
      return null;
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
