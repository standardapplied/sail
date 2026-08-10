/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scripted reviewer responses for review-loop tests, all speaking the verdict envelope — the only
 * response shape the parser admits. The helpers read the carried-finding ids straight out of the
 * review prompt's carry-forward section, exactly as a real reviewer would, so a scripted re-review
 * can rule on findings whose ids were minted at parse time.
 */
final class ReviewScripts {

  static final String CLEAN_REVIEW = "{\"verdicts\": [], \"findings\": []}";

  private ReviewScripts() {}

  /** The carried findings listed in a review prompt, id → title, in listed order. */
  static Map<String, String> carriedFromPrompt(String prompt) {
    return prompt
        .lines()
        .filter(line -> line.startsWith("- finding_id "))
        .collect(
            Collectors.toMap(
                line -> line.split(" ")[2],
                line -> line.substring(line.indexOf("] ") + 2).split(" \\(")[0],
                (a, b) -> a,
                LinkedHashMap::new));
  }

  /** An envelope ruling every carried finding in the prompt {@code fixed} with evidence. */
  static String fixAllCarried(String prompt) {
    return fixAllCarried(prompt, "[]");
  }

  static String fixAllCarried(String prompt, String findingsJson) {
    var verdicts =
        carriedFromPrompt(prompt).keySet().stream()
            .map(
                id ->
                    "{\"finding_id\": \"%s\", \"verdict\": \"fixed\", \"evidence\": \"commit abc\"}"
                        .formatted(id))
            .collect(Collectors.joining(", "));
    return "{\"verdicts\": [" + verdicts + "], \"findings\": " + findingsJson + "}";
  }

  /** An envelope ruling every carried finding {@code still_open} with the given evidence. */
  static String stillOpenAllCarried(String prompt, String evidence) {
    var verdicts =
        carriedFromPrompt(prompt).keySet().stream()
            .map(
                id ->
                    ("{\"finding_id\": \"%s\", \"verdict\": \"still_open\","
                            + " \"evidence\": \"%s\"}")
                        .formatted(id, evidence))
            .collect(Collectors.joining(", "));
    return "{\"verdicts\": [" + verdicts + "], \"findings\": []}";
  }

  /** An envelope disputing every carried finding in the prompt, evidence included. */
  static String disputeAllCarried(String prompt) {
    var verdicts =
        carriedFromPrompt(prompt).keySet().stream()
            .map(
                id ->
                    ("{\"finding_id\": \"%s\", \"verdict\": \"disputed\","
                            + " \"evidence\": \"input is validated upstream\"}")
                        .formatted(id))
            .collect(Collectors.joining(", "));
    return "{\"verdicts\": [" + verdicts + "], \"findings\": []}";
  }

  /** The id of the carried finding with this title, from the prompt's carry-forward section. */
  static String carriedId(String prompt, String title) {
    return carriedFromPrompt(prompt).entrySet().stream()
        .filter(entry -> entry.getValue().equals(title))
        .findFirst()
        .orElseThrow(() -> new AssertionError("finding '" + title + "' not carried in prompt"))
        .getKey();
  }
}
