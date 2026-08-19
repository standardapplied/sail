/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import java.util.LinkedHashMap;

/**
 * A room's standing agent: who is engaged, with what access, since when. Stored on the spec row as
 * one compact JSON value so sync merges it atomically — an engagement is set and cleared as a
 * whole, and field-level merging across boxes must never stitch one box's agent to another box's
 * mode. {@code full} is the default mode: conversations produce artifacts (diagrams, drafts,
 * files), so write access is the normal case and {@code read-only} is the explicit narrow choice.
 */
public record Engagement(String agent, String mode, String model, String engagedAt) {

  public static final String MODE_FULL = "full";
  public static final String MODE_READ_ONLY = "read-only";

  /**
   * A validated engagement. A blank mode defaults to {@link #MODE_FULL}; a blank model means the
   * agent's default.
   */
  public static Engagement of(String agent, String mode, String model, String engagedAt) {
    if (Strings.isBlank(agent)) {
      throw new IllegalArgumentException("An engagement names its agent; got a blank one.");
    }
    if (Strings.isBlank(engagedAt)) {
      throw new IllegalArgumentException("An engagement records when it began; got a blank time.");
    }
    var effectiveMode = Strings.isBlank(mode) ? MODE_FULL : mode;
    if (!MODE_FULL.equals(effectiveMode) && !MODE_READ_ONLY.equals(effectiveMode)) {
      throw new IllegalArgumentException(
          "Engagement mode must be '"
              + MODE_FULL
              + "' or '"
              + MODE_READ_ONLY
              + "', got '"
              + mode
              + "'.");
    }
    return new Engagement(agent, effectiveMode, Strings.isBlank(model) ? null : model, engagedAt);
  }

  public boolean full() {
    return MODE_FULL.equals(mode);
  }

  /** This engagement as the compact JSON the spec row stores. */
  public String toJson() {
    var map = new LinkedHashMap<String, Object>();
    map.put("agent", agent);
    map.put("mode", mode);
    if (model != null) {
      map.put("model", model);
    }
    map.put("engaged_at", engagedAt);
    return YamlUtil.dumpJson(map);
  }

  /**
   * The engagement a spec row's stored value describes, or {@code null} for a blank or unparseable
   * value — a corrupt column must read as "not engaged", never take the store down.
   */
  public static Engagement fromJson(String json) {
    if (Strings.isBlank(json)) {
      return null;
    }
    try {
      var map = YamlUtil.parseMap(json);
      var agent = asText(map.get("agent"));
      var engagedAt = asText(map.get("engaged_at"));
      if (Strings.isBlank(agent) || Strings.isBlank(engagedAt)) {
        return null;
      }
      return of(agent, asText(map.get("mode")), asText(map.get("model")), engagedAt);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String asText(Object value) {
    return value == null ? null : value.toString();
  }
}
