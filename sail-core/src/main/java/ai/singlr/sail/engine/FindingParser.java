/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.Finding;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses structured JSON findings from review agent output. Extracts the JSON array between {@code
 * ```json} and {@code ```} markers. Tolerant of malformed output — unparseable entries are skipped
 * with a warning, never crashing the pipeline.
 */
public final class FindingParser {

  private FindingParser() {}

  public record ParseResult(List<Finding> findings, List<String> warnings) {}

  public static ParseResult parse(String agentOutput) {
    var json = extractJsonBlock(agentOutput);
    if (json == null) {
      return new ParseResult(List.of(), List.of("No JSON block found in agent output."));
    }
    return parseJsonArray(json);
  }

  private static ParseResult parseJsonArray(String json) {
    try {
      var list = YamlUtil.parseList(json);
      var findings = new ArrayList<Finding>();
      var warnings = new ArrayList<String>();

      for (var i = 0; i < list.size(); i++) {
        try {
          findings.add(Finding.fromMap(list.get(i)));
        } catch (Exception e) {
          warnings.add("Finding " + i + ": " + e.getMessage());
        }
      }

      return new ParseResult(List.copyOf(findings), List.copyOf(warnings));
    } catch (Exception e) {
      return new ParseResult(List.of(), List.of("Failed to parse JSON: " + e.getMessage()));
    }
  }

  static String extractJsonBlock(String output) {
    if (Strings.isBlank(output)) return null;

    var start = output.indexOf("```json");
    if (start < 0) {
      start = output.indexOf("```\n[");
      if (start < 0) return tryBareJson(output);
    }

    var contentStart = output.indexOf('\n', start);
    if (contentStart < 0) return null;
    contentStart++;

    var end = output.indexOf("```", contentStart);
    if (end < 0) return output.substring(contentStart).strip();

    return output.substring(contentStart, end).strip();
  }

  private static String tryBareJson(String output) {
    var trimmed = output.strip();
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) return trimmed;
    return null;
  }
}
