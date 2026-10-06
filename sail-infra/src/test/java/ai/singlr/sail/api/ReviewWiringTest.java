/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewWiringTest {

  private static SailYaml yaml(Map<String, Object> agent) {
    var map = new HashMap<String, Object>();
    map.put("name", "acme");
    if (agent != null) {
      map.put("agent", agent);
    }
    return SailYaml.fromMap(map);
  }

  @Test
  void theLoopReadsAProjectAsItsRowNullForNoneAndAnUnreadableRowAsItIs() {
    var sail = yaml(null);
    var unreadable = new ProjectReader.Unreadable("acme", new IllegalArgumentException("bad"));

    assertNull(ReviewWiring.definitions(project -> Optional.empty()).apply("acme"));
    assertSame(sail, ReviewWiring.definitions(project -> Optional.of(sail)).apply("acme"));
    assertSame(
        unreadable,
        assertThrows(
            ProjectReader.Unreadable.class,
            () ->
                ReviewWiring.definitions(
                        project -> {
                          throw unreadable;
                        })
                    .apply("acme")));
  }

  @Test
  void configResolverUsesTheConfiguredPipelineWhenPresent() {
    var sail =
        yaml(
            Map.of(
                "type",
                "claude-code",
                "review_pipeline",
                Map.of(
                    "stages",
                    List.of(Map.of("name", "sec", "type", "agent", "gate", "no_critical")))));

    var config = ReviewWiring.configResolver(p -> sail).apply("acme");

    assertEquals(1, config.stages().size());
    assertEquals("sec", config.stages().getFirst().name());
  }

  @Test
  void configResolverFallsBackToTheMandatoryDefaultWhenUnconfigured() {
    var config =
        ReviewWiring.configResolver(p -> yaml(Map.of("type", "claude-code"))).apply("acme");

    assertEquals("review", config.stages().getFirst().name());
  }

  @Test
  void aBlockWithNoStagesRunsTheDefaultStagesAndKeepsOnlyItsFixSkill() {
    var sail =
        yaml(
            Map.of(
                "type",
                "claude-code",
                "review_pipeline",
                Map.of(
                    "fix_skill",
                    "acme-fix",
                    "max_iterations",
                    9,
                    "max_finding_age",
                    7,
                    "guardrails",
                    Map.of("max_duration", "1h"))));
    var fallback = ReviewPipelineConfig.mandatoryDefault();

    var config = ReviewWiring.configResolver(p -> sail).apply("acme");

    assertEquals("acme-fix", config.fixSkill());
    assertEquals(fallback.stages(), config.stages());
    assertEquals(fallback.maxIterations(), config.maxIterations());
    assertEquals(fallback.maxFindingAge(), config.maxFindingAge());
    assertEquals(fallback.guardrails(), config.guardrails());
    assertEquals("sail-review", config.stages().getFirst().skill());
  }

  @Test
  void aBlockWithStagesIsThePipelineWholeFixSkillIncluded() {
    var sail =
        yaml(
            Map.of(
                "type",
                "claude-code",
                "review_pipeline",
                Map.of(
                    "fix_skill",
                    "acme-fix",
                    "max_iterations",
                    9,
                    "stages",
                    List.of(Map.of("name", "sec", "skill", "acme-security")))));

    var config = ReviewWiring.configResolver(p -> sail).apply("acme");

    assertSame(sail.agent().reviewPipeline(), config);
    assertEquals("acme-fix", config.fixSkill());
    assertEquals("acme-security", config.stages().getFirst().skill());
    assertEquals(9, config.maxIterations());
  }

  @Test
  void configResolverDefaultsWhenNoAgentOrNoYaml() {
    assertEquals(
        "review",
        ReviewWiring.configResolver(p -> yaml(null)).apply("acme").stages().getFirst().name());
    assertEquals(
        "review", ReviewWiring.configResolver(p -> null).apply("acme").stages().getFirst().name());
  }

  @Test
  void reviewerResolverPicksTheOtherInstalledAgent() {
    var sail = yaml(Map.of("type", "claude-code", "install", List.of("claude-code", "codex")));

    assertEquals("codex", ReviewWiring.reviewerResolver(p -> sail).apply("acme"));
  }

  @Test
  void reviewerResolverFallsBackToSelfReview() {
    var sail = yaml(Map.of("type", "claude-code", "install", List.of("claude-code")));

    assertEquals("claude-code", ReviewWiring.reviewerResolver(p -> sail).apply("acme"));
  }

  @Test
  void reviewerResolverIsNullWhenNoAgentConfigured() {
    assertNull(ReviewWiring.reviewerResolver(p -> yaml(null)).apply("acme"));
    assertNull(ReviewWiring.reviewerResolver(p -> null).apply("acme"));
  }

  @Test
  void aBlockThatNamesNoStagesGetsTheDefaultReview() {
    var sail =
        SailYaml.fromMap(
            Map.of(
                "name",
                "acme",
                "agent",
                Map.of(
                    "type",
                    "claude-code",
                    "review_pipeline",
                    Map.of("guardrails", Map.of("max_duration", "90m")))));

    var resolved = ReviewWiring.configResolver(p -> sail).apply("acme");

    assertEquals("review", resolved.stages().getFirst().name());
  }

  @Test
  void controllerFactoryAssemblesAReviewPipelineController(@TempDir Path dir) {
    try (var db = Sqlite.open(dir.resolve("wiring.db"))) {
      new SchemaManager(db).migrate();

      var controller =
          ReviewWiring.controller(
              new SpecStore(db),
              new ReviewStore(db),
              new RunStore(db),
              null,
              p -> null,
              new NoReviewLanes(),
              () -> {},
              () -> "node-a");

      assertEquals("review-pipeline", controller.name());
    }
  }
}
