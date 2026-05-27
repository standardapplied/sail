/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.*;

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
    assertEquals(1, config.stages().size());
    assertEquals("security", config.stages().getFirst().name());
    assertEquals(StageType.AGENT, config.stages().getFirst().type());
    assertEquals(Gate.NO_CRITICAL, config.stages().getFirst().gate());
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
            Finding.Resolution.DISMISSED);

    assertTrue(Gate.NO_CRITICAL.passes(List.of(dismissed)));
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
            Finding.Resolution.FIXED);

    assertTrue(Gate.ALL_CLEAR.passes(List.of(fixed)));
  }

  @Test
  void emptyStagesConfig() {
    var config = ReviewPipelineConfig.fromMap(Map.of());
    assertEquals(3, config.maxIterations());
    assertTrue(config.stages().isEmpty());
  }
}
