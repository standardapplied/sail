/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.CodeReview;
import ai.singlr.sail.config.SailYaml;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CodeReviewGeneratorTest {

  @Test
  void reviewScriptContainsReviewerBinary() {
    var script = CodeReviewGenerator.generateReviewScript("codex");

    assertTrue(script.contains("codex --print"));
  }

  @Test
  void reviewScriptContainsBugChecklist() {
    var script = CodeReviewGenerator.generateReviewScript("codex");

    assertTrue(script.contains("Logic errors"));
    assertTrue(script.contains("Edge cases"));
    assertTrue(script.contains("Error handling"));
    assertTrue(script.contains("Resource leaks"));
    assertTrue(script.contains("Concurrency issues"));
    assertTrue(script.contains("Performance pitfalls"));
    assertTrue(script.contains("API contract violations"));
    assertTrue(script.contains("Data integrity"));
  }

  @Test
  void reviewScriptUsesGitDiff() {
    var script = CodeReviewGenerator.generateReviewScript("codex");

    assertTrue(script.contains("git diff main..HEAD"));
  }

  @Test
  void reviewScriptExcludesLockFiles() {
    var script = CodeReviewGenerator.generateReviewScript("codex");

    assertTrue(script.contains("':!*.lock'"));
    assertTrue(script.contains("':!*.min.js'"));
    assertTrue(script.contains("':!*.min.css'"));
  }

  @Test
  void reviewScriptWritesResultFile() {
    var script = CodeReviewGenerator.generateReviewScript("codex");

    assertTrue(script.contains("~/code-review.md"));
  }

  @Test
  void reviewScriptExitsWithCode2OnFailure() {
    var script = CodeReviewGenerator.generateReviewScript("codex");

    assertTrue(script.contains("exit 2"));
  }

  @Test
  void reviewScriptUsesClaudeBinaryWhenConfigured() {
    var script = CodeReviewGenerator.generateReviewScript("claude");

    assertTrue(script.contains("claude --print"));
    assertFalse(script.contains("codex"));
  }

  @Test
  void generateFilesReturnsEmptyWhenNoCodeReview() {
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null);
    var config = minimalConfig(agent);

    var files = CodeReviewGenerator.generateFiles(config, Set.of());

    assertTrue(files.isEmpty());
  }

  @Test
  void generateFilesReturnsEmptyWhenDisabled() {
    var review = new CodeReview(false, "codex");
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, review, null);
    var config = minimalConfig(agent);

    var files = CodeReviewGenerator.generateFiles(config, Set.of());

    assertTrue(files.isEmpty());
  }

  @Test
  void generateFilesReturnsScriptOnly() {
    var review = new CodeReview(true, "codex");
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            List.of("codex"),
            null,
            null,
            null,
            null,
            review,
            null);
    var config = minimalConfig(agent);

    var files = CodeReviewGenerator.generateFiles(config, Set.of());

    assertEquals(1, files.size());
    var script = files.getFirst();
    assertTrue(script.remotePath().endsWith("code-review.sh"));
    assertTrue(script.executable());
    assertTrue(script.content().contains("codex --print"));
  }

  @Test
  void generateFilesAutoResolvesReviewerSkippingExcluded() {
    var review = new CodeReview(true, null);
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            List.of("claude-code", "codex"),
            null,
            null,
            null,
            null,
            review,
            null);
    var config = minimalConfig(agent);

    var files = CodeReviewGenerator.generateFiles(config, Set.of("codex"));

    assertEquals(1, files.size());
    assertTrue(files.getFirst().content().contains("claude --print"));
  }

  @Test
  void generateFilesSelfAuditsWhenAllNonPrimaryExcluded() {
    var review = new CodeReview(true, null);
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            List.of("claude-code", "codex"),
            null,
            null,
            null,
            null,
            review,
            null);
    var config = minimalConfig(agent);

    var files = CodeReviewGenerator.generateFiles(config, Set.of("codex"));

    assertEquals(1, files.size());
    assertTrue(files.getFirst().content().contains("claude --print"));
  }

  @Test
  void generateFilesUsesCorrectSshUser() {
    var review = new CodeReview(true, "codex");
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            List.of("codex"),
            null,
            null,
            null,
            null,
            review,
            null);
    var ssh = new SailYaml.Ssh("alice", null);
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            agent,
            null,
            ssh);

    var files = CodeReviewGenerator.generateFiles(config, Set.of());

    assertTrue(files.getFirst().remotePath().contains("/home/alice/"));
  }

  @Test
  void generateFilesThrowsWhenAuditorIsUnknown() {
    var review = new CodeReview(true, "unknown-agent");
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            List.of("codex"),
            null,
            null,
            null,
            null,
            review,
            null);
    var config = minimalConfig(agent);

    assertThrows(
        IllegalArgumentException.class, () -> CodeReviewGenerator.generateFiles(config, Set.of()));
  }

  private static SailYaml minimalConfig(SailYaml.Agent agent) {
    return new SailYaml(
        "test",
        null,
        new SailYaml.Resources(2, "4GB", "50GB"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        agent,
        null,
        null);
  }
}
