/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Methodology;
import ai.singlr.sail.harness.Harnesses;
import org.junit.jupiter.api.Test;

class MethodologyGeneratorTest {

  @Test
  void generateFiles_nullMethodology_returnsEmpty() {
    var files =
        MethodologyGenerator.generateFiles(
            Harnesses.of("claude-code"), null, "/home/dev/workspace/");

    assertTrue(files.isEmpty());
  }

  @Test
  void generateFiles_specDriven_generatesSpecSkill() {
    var methodology = new Methodology("spec-driven", null, null);

    var files =
        MethodologyGenerator.generateFiles(
            Harnesses.of("claude-code"), methodology, "/home/dev/workspace/");

    assertEquals(1, files.size());
    assertEquals("/home/dev/workspace/.claude/skills/spec/SKILL.md", files.get(0).remotePath());
    assertTrue(files.get(0).content().contains("spec"));
    assertFalse(files.get(0).executable());
  }

  @Test
  void generateFiles_withVerify_generatesVerifySkill() {
    var methodology = new Methodology("free-form", "mvn test", null);

    var files =
        MethodologyGenerator.generateFiles(
            Harnesses.of("claude-code"), methodology, "/home/dev/workspace/");

    assertEquals(1, files.size());
    assertEquals("/home/dev/workspace/.claude/skills/verify/SKILL.md", files.get(0).remotePath());
    assertTrue(files.get(0).content().contains("mvn test"));
  }

  @Test
  void generateFiles_specDrivenWithVerify_generatesBothSkills() {
    var methodology = new Methodology("spec-driven", "mvn test", null);

    var files =
        MethodologyGenerator.generateFiles(
            Harnesses.of("claude-code"), methodology, "/home/dev/workspace/");

    assertEquals(2, files.size());
    assertTrue(files.get(0).remotePath().contains("spec"));
    assertTrue(files.get(1).remotePath().contains("verify"));
  }

  @Test
  void generateFiles_codex_usesAgentsSkillsPath() {
    var methodology = new Methodology("spec-driven", null, null);

    var files =
        MethodologyGenerator.generateFiles(
            Harnesses.of("codex"), methodology, "/home/dev/workspace/");

    assertEquals(1, files.size());
    assertEquals("/home/dev/workspace/.agents/skills/spec/SKILL.md", files.get(0).remotePath());
  }

  @Test
  void generateMethodologyInstructions_null_returnsEmpty() {
    var result = MethodologyGenerator.generateMethodologyInstructions(null);

    assertEquals("", result);
  }

  @Test
  void generateMethodologyInstructions_specDriven_includesSpecWorkflow() {
    var methodology = new Methodology("spec-driven", null, null);

    var result = MethodologyGenerator.generateMethodologyInstructions(methodology);

    assertTrue(result.contains("spec-driven development"));
    assertTrue(result.contains("/spec"));
    assertTrue(result.contains("SPEC.md"));
  }

  @Test
  void generateMethodologyInstructions_tdd_includesTestFirst() {
    var methodology = new Methodology("tdd", null, null);

    var result = MethodologyGenerator.generateMethodologyInstructions(methodology);

    assertTrue(result.contains("test-driven development"));
    assertTrue(result.contains("failing test"));
  }

  @Test
  void generateMethodologyInstructions_freeForm_noApproachSection() {
    var methodology = new Methodology("free-form", null, null);

    var result = MethodologyGenerator.generateMethodologyInstructions(methodology);

    assertTrue(result.contains("## Methodology"));
    assertFalse(result.contains("spec-driven"));
    assertFalse(result.contains("test-driven"));
  }

  @Test
  void generateMethodologyInstructions_withVerify_includesVerificationSection() {
    var methodology = new Methodology("free-form", "mvn test", null);

    var result = MethodologyGenerator.generateMethodologyInstructions(methodology);

    assertTrue(result.contains("### Verification"));
    assertTrue(result.contains("mvn test"));
    assertTrue(result.contains("verification passes"));
  }

  @Test
  void generateMethodologyInstructions_withLint_includesLintSection() {
    var methodology = new Methodology("free-form", null, "mvn spotless:check");

    var result = MethodologyGenerator.generateMethodologyInstructions(methodology);

    assertTrue(result.contains("### Linting"));
    assertTrue(result.contains("mvn spotless:check"));
    assertTrue(result.contains("Fix any issues"));
  }

  @Test
  void generateMethodologyInstructions_fullConfig_includesAllSections() {
    var methodology = new Methodology("tdd", "mvn test", "mvn spotless:check");

    var result = MethodologyGenerator.generateMethodologyInstructions(methodology);

    assertTrue(result.contains("## Methodology"));
    assertTrue(result.contains("test-driven development"));
    assertTrue(result.contains("### Verification"));
    assertTrue(result.contains("mvn test"));
    assertTrue(result.contains("### Linting"));
    assertTrue(result.contains("mvn spotless:check"));
  }
}
