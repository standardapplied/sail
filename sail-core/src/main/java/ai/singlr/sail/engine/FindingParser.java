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

  /**
   * Parses the findings array out of a review agent's raw transcript. Transcripts are noisy: the
   * agent may echo the prompt (which itself names the {@code ```json} convention) and may emit
   * fenced blocks mid-reasoning, so candidates are tried <em>last to first</em> and the last block
   * that parses as a JSON array wins — the response convention puts the verdict at the end. When
   * nothing parses, the result carries warnings so the pipeline can refuse to treat an unreadable
   * review as a clean pass.
   */
  public static ParseResult parse(String agentOutput) {
    var candidates = extractJsonBlocks(agentOutput);
    if (candidates.isEmpty()) {
      return new ParseResult(List.of(), List.of("No JSON block found in agent output."));
    }
    var warnings = new ArrayList<String>();
    for (var i = candidates.size() - 1; i >= 0; i--) {
      var result = parseJsonArray(candidates.get(i));
      if (result.findings().isEmpty() && !result.warnings().isEmpty()) {
        warnings.addAll(result.warnings());
        continue;
      }
      return result;
    }
    return new ParseResult(List.of(), List.copyOf(warnings));
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

  static List<String> extractJsonBlocks(String output) {
    if (Strings.isBlank(output)) return List.of();

    var blocks = new ArrayList<String>();
    collectBlocks(output, "```json", blocks);
    if (blocks.isEmpty()) {
      collectBlocks(output, "```\n[", blocks);
    }
    if (blocks.isEmpty()) {
      var bare = tryBareJson(output);
      if (bare != null) blocks.add(bare);
    }
    return List.copyOf(blocks);
  }

  /**
   * Every marker occurrence starts an independent candidate (advancing past the marker, not the
   * closing fence): a prompt echo's mid-sentence marker would otherwise swallow the real block's
   * opening fence as its terminator. Overlap is harmless — candidates are validated by parsing.
   */
  private static void collectBlocks(String output, String marker, List<String> blocks) {
    var from = 0;
    while (true) {
      var start = output.indexOf(marker, from);
      if (start < 0) return;
      from = start + marker.length();
      var contentStart = output.indexOf('\n', start);
      if (contentStart < 0) return;
      contentStart++;
      var end = output.indexOf("```", contentStart);
      blocks.add(
          (end < 0 ? output.substring(contentStart) : output.substring(contentStart, end)).strip());
    }
  }

  private static String tryBareJson(String output) {
    var trimmed = output.strip();
    if (trimmed.startsWith("[") && trimmed.endsWith("]")) return trimmed;
    return null;
  }
}
