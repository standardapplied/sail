/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.AgentCli;
import java.util.List;
import org.junit.jupiter.api.Test;

class LanguageRulesGeneratorTest {

  private static final String HOME = "/home/dev/";

  private static SailYaml.AgentRule java() {
    return new SailYaml.AgentRule(
        "java", List.of("**/*.java"), "- Records for value types.\n- Virtual threads for I/O.");
  }

  private static SailYaml.AgentRule typescript() {
    return new SailYaml.AgentRule(
        "typescript", List.of("**/*.ts", "**/*.tsx"), "- Functional components with hooks.");
  }

  @Test
  void nullRulesGenerateNothing() {
    assertTrue(LanguageRulesGenerator.generateFiles(AgentCli.CLAUDE_CODE, null, HOME).isEmpty());
  }

  @Test
  void emptyRulesGenerateNothing() {
    assertTrue(
        LanguageRulesGenerator.generateFiles(AgentCli.CLAUDE_CODE, List.of(), HOME).isEmpty());
  }

  @Test
  void claudeRuleIsAPathScopedRuleFile() {
    var files = LanguageRulesGenerator.generateFiles(AgentCli.CLAUDE_CODE, List.of(java()), HOME);

    assertEquals(1, files.size());
    var rule = files.getFirst();
    assertEquals(HOME + ".claude/rules/java.md", rule.remotePath());
    assertFalse(rule.executable());
    assertTrue(
        rule.content().startsWith("---\npaths:\n"), "the rule is path-scoped via frontmatter");
    assertTrue(rule.content().contains("\"**/*.java\""), "the glob is rendered");
    assertTrue(rule.content().contains("- Records for value types."), "the org body is carried");
  }

  @Test
  void claudeRuleRendersEveryGlob() {
    var files =
        LanguageRulesGenerator.generateFiles(AgentCli.CLAUDE_CODE, List.of(typescript()), HOME);

    var content = files.getFirst().content();
    assertTrue(content.contains("\"**/*.ts\""));
    assertTrue(content.contains("\"**/*.tsx\""));
  }

  @Test
  void aRuleWithoutPathsIsAlwaysOnForClaude() {
    var rule = new SailYaml.AgentRule("house-style", List.of(), "- Always prefer composition.");

    var content =
        LanguageRulesGenerator.generateFiles(AgentCli.CLAUDE_CODE, List.of(rule), HOME)
            .getFirst()
            .content();

    assertFalse(content.contains("paths:"), "no paths means a session-start rule, not path-scoped");
    assertTrue(content.contains("- Always prefer composition."));
  }

  @Test
  void codexRuleIsADescriptionLoadedSkill() {
    var files = LanguageRulesGenerator.generateFiles(AgentCli.CODEX, List.of(java()), HOME);

    assertEquals(1, files.size());
    var skill = files.getFirst();
    assertEquals(HOME + ".agents/skills/java/SKILL.md", skill.remotePath());
    assertFalse(skill.executable());
    assertTrue(skill.content().startsWith("---\n"));
    assertTrue(skill.content().contains("name: java"));
    assertTrue(skill.content().contains("description:"));
    assertTrue(
        skill.content().contains("**/*.java"), "the description names the files it applies to");
    assertTrue(skill.content().contains("- Records for value types."), "the org body is carried");
  }

  @Test
  void everyConfiguredRuleBecomesAFile() {
    var files =
        LanguageRulesGenerator.generateFiles(
            AgentCli.CLAUDE_CODE, List.of(java(), typescript()), HOME);

    assertEquals(2, files.size());
    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("/rules/java.md")));
    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("/rules/typescript.md")));
  }

  @Test
  void generatedRulesAreNotExecutable() {
    var files =
        LanguageRulesGenerator.generateFiles(AgentCli.CODEX, List.of(java(), typescript()), HOME);

    assertTrue(files.stream().noneMatch(GeneratedFile::executable));
  }
}
