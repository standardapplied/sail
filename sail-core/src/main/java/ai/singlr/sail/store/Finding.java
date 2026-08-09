/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single reviewable issue discovered by a review agent. Immutable, evidence-based, and
 * actionable. Every finding cites specific code, explains why it matters, and suggests a fix.
 *
 * <p>A finding has identity across review iterations: when a re-review rules it {@code still_open},
 * a fresh row is attached to the new review with {@code carriedFrom} pointing at its predecessor,
 * so one finding aging across iterations is a single chain walk. A resolution that retires the
 * finding ({@code FIXED} by the reviewer's verdict, {@code DISPUTED} by a ruled argument) records
 * its {@code resolutionEvidence} — nothing resolves silently.
 */
public record Finding(
    String id,
    Severity severity,
    Category category,
    String file,
    int lineStart,
    int lineEnd,
    String title,
    String description,
    String evidence,
    Suggestion suggestion,
    double confidence,
    Resolution resolution,
    String resolutionEvidence,
    String carriedFrom) {

  public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW;

    public static Severity parse(String value) {
      return valueOf(value.strip().toUpperCase());
    }

    public boolean isAtLeast(Severity threshold) {
      return ordinal() <= threshold.ordinal();
    }
  }

  public enum Category {
    SECURITY,
    LOGIC,
    EDGE_CASE,
    PERFORMANCE,
    ERROR_HANDLING,
    CONCURRENCY,
    RESOURCE_LEAK,
    API_CONTRACT;

    public static Category parse(String value) {
      return valueOf(value.strip().toUpperCase());
    }
  }

  public enum Resolution {
    OPEN,
    FIXED,
    DISMISSED,
    DISPUTED
  }

  public record Suggestion(String before, String after, String rationale) {

    @SuppressWarnings("unchecked")
    public static Suggestion fromMap(Object raw) {
      if (raw instanceof Map<?, ?> wildcard) {
        var map = (Map<String, Object>) wildcard;
        return new Suggestion(
            (String) map.getOrDefault("before", ""),
            (String) map.getOrDefault("after", ""),
            (String) map.getOrDefault("rationale", ""));
      }
      return new Suggestion("", "", "");
    }

    public Map<String, Object> toMap() {
      var m = new LinkedHashMap<String, Object>();
      if (!before.isEmpty()) m.put("before", before);
      if (!after.isEmpty()) m.put("after", after);
      if (!rationale.isEmpty()) m.put("rationale", rationale);
      return m;
    }
  }

  public static Finding create(
      Severity severity,
      Category category,
      String file,
      int lineStart,
      int lineEnd,
      String title,
      String description,
      String evidence,
      Suggestion suggestion,
      double confidence) {
    return new Finding(
        DateTimeUtils.newId().toString(),
        severity,
        category,
        file,
        lineStart,
        lineEnd,
        title,
        description,
        evidence,
        suggestion,
        confidence,
        Resolution.OPEN,
        null,
        null);
  }

  /**
   * The open successor row a {@code still_open} verdict attaches to the next review: a fresh
   * identity linked to this finding through {@code carriedFrom}, so the chain records every
   * iteration the finding survived.
   */
  public Finding carriedCopy() {
    return new Finding(
        DateTimeUtils.newId().toString(),
        severity,
        category,
        file,
        lineStart,
        lineEnd,
        title,
        description,
        evidence,
        suggestion,
        confidence,
        Resolution.OPEN,
        null,
        id);
  }

  @SuppressWarnings("unchecked")
  public static Finding fromMap(Map<String, Object> map) {
    return new Finding(
        (String) map.getOrDefault("id", DateTimeUtils.newId().toString()),
        Severity.parse((String) map.get("severity")),
        Category.parse((String) map.get("category")),
        (String) map.get("file"),
        intValue(map.get("line_start")),
        intValue(map.get("line_end")),
        (String) map.get("title"),
        (String) map.get("description"),
        (String) map.getOrDefault("evidence", ""),
        Suggestion.fromMap(map.get("suggestion")),
        doubleValue(map.getOrDefault("confidence", 0.5)),
        Resolution.OPEN,
        null,
        null);
  }

  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("id", id);
    m.put("severity", severity.name());
    m.put("category", category.name());
    if (file != null) m.put("file", file);
    m.put("line_start", lineStart);
    m.put("line_end", lineEnd);
    m.put("title", title);
    m.put("description", description);
    if (evidence != null && !evidence.isEmpty()) m.put("evidence", evidence);
    if (suggestion != null) m.put("suggestion", suggestion.toMap());
    m.put("confidence", confidence);
    m.put("resolution", resolution.name());
    if (resolutionEvidence != null && !resolutionEvidence.isEmpty()) {
      m.put("resolution_evidence", resolutionEvidence);
    }
    if (carriedFrom != null) m.put("carried_from", carriedFrom);
    return m;
  }

  private static int intValue(Object value) {
    if (value instanceof Number n) return n.intValue();
    if (value instanceof String s) return Integer.parseInt(s);
    return 0;
  }

  private static double doubleValue(Object value) {
    if (value instanceof Number n) return n.doubleValue();
    if (value instanceof String s) return Double.parseDouble(s);
    return 0.0;
  }
}
