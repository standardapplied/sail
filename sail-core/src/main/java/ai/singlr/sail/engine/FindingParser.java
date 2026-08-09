/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.Finding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Parses the verdict envelope from review agent output. The envelope is the review contract's only
 * valid response shape, from iteration one:
 *
 * <pre>{@code {"verdicts": [...], "findings": [...]}}</pre>
 *
 * where each verdict rules on a finding carried forward from the previous review and each finding
 * is a newly discovered issue. Anything else — a bare findings array included — is unparseable
 * reviewer output: the reviewer and this parser ship in the same binary, so no legacy shape is
 * tolerated and an off-contract response errors the stage rather than passing as a clean review.
 * Within a valid envelope, malformed entries are skipped with a warning, never crashing the
 * pipeline.
 */
public final class FindingParser {

  private FindingParser() {}

  /** How the reviewer ruled on a carried finding. */
  public enum Ruling {
    FIXED,
    STILL_OPEN,
    DISPUTED;

    static Ruling parse(String value) {
      return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
  }

  /** One ruling on one carried finding, with the evidence the ruling rests on. */
  public record Verdict(String findingId, Ruling ruling, String evidence) {

    static Verdict fromMap(Map<String, Object> map) {
      var findingId = (String) map.get("finding_id");
      if (Strings.isBlank(findingId)) {
        throw new IllegalArgumentException("verdict requires a finding_id");
      }
      var ruling = Ruling.parse((String) map.get("verdict"));
      return new Verdict(findingId, ruling, Objects.toString(map.get("evidence"), "").strip());
    }
  }

  /** A parsed envelope, or the proof that no candidate in the transcript was one. */
  public sealed interface ParseResult {

    record Parsed(List<Verdict> verdicts, List<Finding> findings, List<String> warnings)
        implements ParseResult {}

    record Unparseable(List<String> warnings) implements ParseResult {}
  }

  /**
   * Parses the verdict envelope out of a review agent's raw transcript. Transcripts are noisy: the
   * agent may echo the prompt (which itself names the {@code ```json} convention) and may emit
   * fenced blocks mid-reasoning, so candidates are tried <em>last to first</em> and the last block
   * that parses as an envelope wins — the response convention puts the verdict at the end. Fenced
   * blocks are tried first; when none parses, the transcript is scanned for an unfenced JSON object
   * before giving up — the format instruction is a request, not a guarantee, and an envelope
   * without its fence is still a verdict. The hardening locates the envelope in noisy output; it
   * does not admit alternative formats.
   */
  public static ParseResult parse(String agentOutput) {
    var warnings = new ArrayList<String>();
    for (var candidates :
        List.of(extractJsonBlocks(agentOutput), extractEmbeddedObjects(agentOutput))) {
      for (var i = candidates.size() - 1; i >= 0; i--) {
        switch (parseEnvelope(candidates.get(i))) {
          case ParseResult.Parsed parsed -> {
            return parsed;
          }
          case ParseResult.Unparseable rejected -> warnings.addAll(rejected.warnings());
        }
      }
    }
    if (warnings.isEmpty()) {
      warnings.add("No JSON block found in agent output.");
    }
    return new ParseResult.Unparseable(List.copyOf(warnings));
  }

  @SuppressWarnings("unchecked")
  private static ParseResult parseEnvelope(String json) {
    Map<String, Object> map;
    try {
      map = YamlUtil.parseMap(json);
    } catch (Exception e) {
      return new ParseResult.Unparseable(List.of("Failed to parse JSON: " + e.getMessage()));
    }
    var verdictsRaw = map.get("verdicts");
    var findingsRaw = map.get("findings");
    if (!(verdictsRaw instanceof List) || !(findingsRaw instanceof List)) {
      return new ParseResult.Unparseable(
          List.of(
              "Not a verdict envelope — expected a JSON object with both \"verdicts\" and"
                  + " \"findings\" arrays."));
    }

    var warnings = new ArrayList<String>();
    var verdicts = new ArrayList<Verdict>();
    var verdictEntries = (List<Object>) verdictsRaw;
    for (var i = 0; i < verdictEntries.size(); i++) {
      try {
        verdicts.add(Verdict.fromMap((Map<String, Object>) verdictEntries.get(i)));
      } catch (Exception e) {
        warnings.add("Verdict " + i + ": " + e.getMessage());
      }
    }
    var findings = new ArrayList<Finding>();
    var findingEntries = (List<Object>) findingsRaw;
    for (var i = 0; i < findingEntries.size(); i++) {
      try {
        findings.add(Finding.fromMap((Map<String, Object>) findingEntries.get(i)));
      } catch (Exception e) {
        warnings.add("Finding " + i + ": " + e.getMessage());
      }
    }
    if (verdicts.isEmpty() && findings.isEmpty() && !warnings.isEmpty()) {
      return new ParseResult.Unparseable(List.copyOf(warnings));
    }
    return new ParseResult.Parsed(
        List.copyOf(verdicts), List.copyOf(findings), List.copyOf(warnings));
  }

  /**
   * Fail-closed reconciliation of the reviewer's verdicts against the carried findings it was asked
   * to rule on: every carried finding gets exactly one effective ruling. A carried finding missing
   * from the verdicts defaults to {@code still_open}; {@code fixed} or {@code disputed} without
   * evidence downgrades to {@code still_open}; a verdict naming an unknown finding is a warning,
   * never a crash. Incomplete-answer semantics — a finding can never resolve by omission.
   */
  public static Reconciled reconcile(List<Finding> carried, List<Verdict> verdicts) {
    var warnings = new ArrayList<String>();
    var rulings = new LinkedHashMap<String, Verdict>();
    for (var finding : carried) {
      rulings.put(finding.id(), new Verdict(finding.id(), Ruling.STILL_OPEN, ""));
    }
    for (var verdict : verdicts) {
      if (!rulings.containsKey(verdict.findingId())) {
        warnings.add("Verdict for unknown finding " + verdict.findingId() + " ignored.");
        continue;
      }
      if (verdict.ruling() != Ruling.STILL_OPEN && Strings.isBlank(verdict.evidence())) {
        warnings.add(
            "Verdict '"
                + verdict.ruling().name().toLowerCase(Locale.ROOT)
                + "' on finding "
                + verdict.findingId()
                + " carries no evidence; treated as still_open.");
        continue;
      }
      rulings.put(verdict.findingId(), verdict);
    }
    return new Reconciled(Map.copyOf(rulings), List.copyOf(warnings));
  }

  /** The effective ruling per carried finding id, plus what fail-closed defaulting rejected. */
  public record Reconciled(Map<String, Verdict> rulings, List<String> warnings) {}

  static List<String> extractJsonBlocks(String output) {
    if (Strings.isBlank(output)) return List.of();

    var blocks = new ArrayList<String>();
    collectBlocks(output, "```json", blocks);
    if (blocks.isEmpty()) {
      collectBlocks(output, "```\n{", blocks);
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
   * Markers match case-insensitively, so a {@code ```JSON} fence is not mistaken for prose.
   */
  private static void collectBlocks(String output, String marker, List<String> blocks) {
    var haystack = output.toLowerCase(Locale.ROOT);
    var from = 0;
    while (true) {
      var start = haystack.indexOf(marker, from);
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

  /**
   * Last-resort candidates for a reviewer that dropped the fence: every {@code {"} …{@code }} span
   * (an object opening straight into a key — the envelope shape, rare in prose). Spans are greedy
   * to the last closing brace and validated by parsing, so a prompt echo's inline placeholder
   * envelope (which is not valid JSON by design) never counts as a verdict.
   */
  static List<String> extractEmbeddedObjects(String output) {
    if (Strings.isBlank(output)) return List.of();

    var blocks = new ArrayList<String>();
    var end = output.lastIndexOf('}');
    var from = 0;
    while (end >= 0) {
      var start = output.indexOf("{\"", from);
      if (start < 0 || start > end) break;
      blocks.add(output.substring(start, end + 1));
      from = start + 1;
    }
    return List.copyOf(blocks);
  }

  private static String tryBareJson(String output) {
    var trimmed = output.strip();
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed;
    return null;
  }
}
