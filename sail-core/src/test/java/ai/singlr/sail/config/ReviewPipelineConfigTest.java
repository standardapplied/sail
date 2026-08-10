/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ReviewPipelineConfig.Gate;
import ai.singlr.sail.config.ReviewPipelineConfig.StageType;
import ai.singlr.sail.store.Finding;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewPipelineConfigTest {

  @Test
  void parseMinimalConfig() {
    var config =
        ReviewPipelineConfig.fromMap(
            Map.of("stages", List.of(Map.of("name", "security", "type", "agent"))));

    assertEquals(3, config.maxIterations());
    assertEquals(2, config.maxFindingAge(), "a stuck finding escalates after 2 fix iterations");
    assertEquals(1, config.stages().size());
    assertEquals("security", config.stages().getFirst().name());
    assertEquals(StageType.AGENT, config.stages().getFirst().type());
    assertEquals(Gate.NO_CRITICAL, config.stages().getFirst().gate());
  }

  @Test
  void duplicateStageNamesAreRejected() {
    var stages =
        List.of(
            Map.<String, Object>of("name", "review", "type", "agent"),
            Map.<String, Object>of("name", "review", "type", "agent"));

    assertThrows(
        IllegalArgumentException.class,
        () -> ReviewPipelineConfig.fromMap(Map.of("stages", stages)),
        "carry-forward is keyed by stage name; a duplicate would merge two stages' findings");
  }

  @Test
  void parseMaxFindingAge() {
    var config =
        ReviewPipelineConfig.fromMap(
            Map.of(
                "max_finding_age",
                4,
                "stages",
                List.of(Map.of("name", "security", "type", "agent"))));

    assertEquals(4, config.maxFindingAge());
  }

  @Test
  void parseFullConfig() {
    var config =
        ReviewPipelineConfig.fromMap(
            Map.of(
                "max_iterations",
                2,
                "stages",
                List.of(
                    Map.of(
                        "name",
                        "security",
                        "type",
                        "agent",
                        "agent",
                        "codex",
                        "categories",
                        List.of("security", "injection"),
                        "gate",
                        "no_critical_or_high"),
                    Map.of("name", "human", "type", "human"))));

    assertEquals(2, config.maxIterations());
    assertEquals(2, config.stages().size());
    assertEquals("codex", config.stages().get(0).agent());
    assertEquals(List.of("security", "injection"), config.stages().get(0).categories());
    assertEquals(Gate.NO_CRITICAL_OR_HIGH, config.stages().get(0).gate());
    assertEquals(StageType.HUMAN, config.stages().get(1).type());
  }

  @Test
  void parseMissingStageNameThrows() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ReviewPipelineConfig.fromMap(Map.of("stages", List.of(Map.of("type", "agent")))));
  }

  @Test
  void agentStagesFiltersCorrectly() {
    var config =
        ReviewPipelineConfig.fromMap(
            Map.of(
                "stages",
                List.of(
                    Map.of("name", "sec", "type", "agent"),
                    Map.of("name", "review", "type", "agent"),
                    Map.of("name", "human", "type", "human"))));

    assertEquals(2, config.agentStages().size());
    assertEquals(1, config.humanStages().size());
  }

  @Test
  void gateNoCriticalPassesWithOnlyMediumFindings() {
    var findings =
        List.of(
            Finding.create(
                Finding.Severity.MEDIUM,
                Finding.Category.LOGIC,
                "a.java",
                1,
                1,
                "Issue",
                "",
                "",
                null,
                0.5),
            Finding.create(
                Finding.Severity.LOW,
                Finding.Category.LOGIC,
                "b.java",
                1,
                1,
                "Minor",
                "",
                "",
                null,
                0.3));

    assertTrue(Gate.NO_CRITICAL.passes(findings));
  }

  @Test
  void gateNoCriticalFailsWithCriticalFinding() {
    var findings =
        List.of(
            Finding.create(
                Finding.Severity.CRITICAL,
                Finding.Category.SECURITY,
                "a.java",
                1,
                1,
                "Critical",
                "",
                "",
                null,
                0.9));

    assertFalse(Gate.NO_CRITICAL.passes(findings));
  }

  @Test
  void gateNoCriticalPassesWhenCriticalIsDismissed() {
    var dismissed =
        new Finding(
            "id",
            Finding.Severity.CRITICAL,
            Finding.Category.SECURITY,
            "a.java",
            1,
            1,
            "Dismissed critical",
            "",
            "",
            null,
            0.9,
            Finding.Resolution.DISMISSED,
            null,
            null,
            null);

    assertTrue(Gate.NO_CRITICAL.passes(List.of(dismissed)));
  }

  @Test
  void gateExcludesDisputedFindingsAtEverySeverity() {
    var disputed =
        new Finding(
            "id",
            Finding.Severity.CRITICAL,
            Finding.Category.SECURITY,
            "a.java",
            1,
            1,
            "Disputed critical",
            "",
            "",
            null,
            0.9,
            Finding.Resolution.DISPUTED,
            "the reviewer ruled the argument valid",
            null,
            null);

    assertTrue(Gate.NO_CRITICAL.passes(List.of(disputed)));
    assertTrue(Gate.NO_CRITICAL_OR_HIGH.passes(List.of(disputed)));
    assertTrue(
        Gate.ALL_CLEAR.passes(List.of(disputed)),
        "a disputed finding never counts against any gate — it goes to the human instead");
    assertFalse(Gate.NO_CRITICAL.blocks(disputed));
  }

  @Test
  void gateNoCriticalOrHighFailsWithHighFinding() {
    var findings =
        List.of(
            Finding.create(
                Finding.Severity.HIGH,
                Finding.Category.LOGIC,
                "a.java",
                1,
                1,
                "High issue",
                "",
                "",
                null,
                0.8));

    assertFalse(Gate.NO_CRITICAL_OR_HIGH.passes(findings));
  }

  @Test
  void gateNoCriticalOrHighPassesWithMediumFindings() {
    var findings =
        List.of(
            Finding.create(
                Finding.Severity.MEDIUM,
                Finding.Category.LOGIC,
                "a.java",
                1,
                1,
                "Medium issue",
                "",
                "",
                null,
                0.6));

    assertTrue(Gate.NO_CRITICAL_OR_HIGH.passes(findings));
  }

  @Test
  void gateAllClearFailsWithAnyOpenFinding() {
    var findings =
        List.of(
            Finding.create(
                Finding.Severity.LOW,
                Finding.Category.LOGIC,
                "a.java",
                1,
                1,
                "Tiny",
                "",
                "",
                null,
                0.2));

    assertFalse(Gate.ALL_CLEAR.passes(findings));
  }

  @Test
  void gateAllClearPassesWithEmptyFindings() {
    assertTrue(Gate.ALL_CLEAR.passes(List.of()));
  }

  @Test
  void gateAllClearPassesWhenAllResolved() {
    var fixed =
        new Finding(
            "id",
            Finding.Severity.HIGH,
            Finding.Category.LOGIC,
            "a.java",
            1,
            1,
            "Fixed",
            "",
            "",
            null,
            0.8,
            Finding.Resolution.FIXED,
            "commit abc",
            null,
            null);

    assertTrue(Gate.ALL_CLEAR.passes(List.of(fixed)));
  }

  @Test
  void emptyStagesConfig() {
    var config = ReviewPipelineConfig.fromMap(Map.of());
    assertEquals(3, config.maxIterations());
    assertTrue(config.stages().isEmpty());
  }

  @Test
  void mandatoryDefaultIsOneAgentStageWithNoNamedReviewer() {
    var config = ReviewPipelineConfig.mandatoryDefault();

    assertEquals(3, config.maxIterations());
    assertEquals(1, config.stages().size());
    var stage = config.stages().getFirst();
    assertEquals("review", stage.name());
    assertEquals(ReviewPipelineConfig.StageType.AGENT, stage.type());
    assertNull(stage.agent());
    assertEquals(ReviewPipelineConfig.Gate.NO_CRITICAL, stage.gate());
  }
}
