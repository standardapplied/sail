/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  @Test
  void aProjectWithNoDescriptorHasNoneAndOneThatCannotBeReadIsAnErrorNamingTheProject(
      @TempDir Path dir) throws Exception {
    var written = Files.writeString(dir.resolve("sail.yaml"), "agent: [unterminated");

    assertNull(ReviewWiring.descriptor("acme", dir.resolve("absent.yaml")));
    var unreadable =
        assertThrows(IllegalStateException.class, () -> ReviewWiring.descriptor("acme", written));
    assertTrue(unreadable.getMessage().contains("'acme'"), unreadable.getMessage());

    Files.writeString(written, "name: acme\nagent:\n  type: codex\n");
    assertEquals("codex", ReviewWiring.descriptor("acme", written).agent().type());
  }
}
