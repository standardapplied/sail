/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentReviewCommandTest {

  private static final Map<String, Object> PASSED_REVIEW =
      Map.of(
          "iteration",
          1,
          "status",
          "passed",
          "stages",
          List.of(
              Map.of(
                  "name", "codeandsecurity",
                  "reviewer", "codex",
                  "status", "passed",
                  "finding_count", 0)));

  @Test
  void rendersEachAttemptWithItsStagesAndFindingCounts() {
    var superseded =
        Map.of(
            "iteration",
            1,
            "status",
            "failed",
            "superseded_at",
            "2026-07-01T20:00:00Z",
            "stages",
            List.of(
                Map.of(
                    "name", "codeandsecurity",
                    "reviewer", "codex",
                    "status", "failed",
                    "finding_count", 2)));

    var out =
        String.join(
            "\n",
            AgentReviewCommand.render(
                "mast-app-shell", "review", List.of(superseded, PASSED_REVIEW)));

    assertTrue(out.contains("mast-app-shell"));
    assertTrue(out.contains("iteration 1"), out);
    assertTrue(out.contains("superseded"), "prior attempts are labeled, not hidden: " + out);
    assertTrue(out.contains("codeandsecurity"), out);
    assertTrue(out.contains("codex"), out);
    assertTrue(out.contains("2 finding"), out);
    assertTrue(out.contains("passed"), out);
  }

  @Test
  void saysLoudlyWhenNoReviewHasRunAndPointsAtTheLiveLog() {
    var out = String.join("\n", AgentReviewCommand.render("auth", "review", List.of()));

    assertTrue(out.contains("No reviews have run"), out);
    assertTrue(
        out.contains("agent log") && out.contains("--review"),
        "must point the FDE at the live negotiation log: " + out);
  }

  @Test
  void rendersFindingsWhenPresent() {
    var finding =
        Map.<String, Object>of(
            "severity", "CRITICAL",
            "category", "SECURITY",
            "file", "Auth.java",
            "line_start", 10,
            "title", "SQL injection");

    var line = AgentReviewCommand.renderFinding(finding);

    assertEquals("CRITICAL SECURITY Auth.java:10 SQL injection", line.strip());
  }
}
