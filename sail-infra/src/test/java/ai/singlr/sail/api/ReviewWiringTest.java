/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.sail.config.SailYaml;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

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
  void controllerFactoryAssemblesAReviewPipelineController() {
    try (var controller = ReviewWiring.controller(null, null, null, p -> null, null)) {
      assertNotNull(controller);
      assertEquals("review-pipeline", controller.name());
    }
  }
}
