/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AgentCli;
import org.junit.jupiter.api.Test;

class SpecSkillGeneratorTest {

  private static final String BASE = "/home/dev/workspace/";

  @Test
  void claudeCodeGeneratesSkillMdAndTemplate() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, BASE);

    assertEquals(2, files.size());
    assertEquals(BASE + ".claude/skills/spec-board/SKILL.md", files.get(0).remotePath());
    assertEquals(BASE + ".claude/skills/spec-board/spec-template.md", files.get(1).remotePath());
  }

  @Test
  void claudeSkillMdHasFrontmatter() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, BASE);
    var content = files.get(0).content();

    assertTrue(content.startsWith("---\n"));
    assertTrue(content.contains("name: spec-board"));
    assertTrue(content.contains("description:"));
    assertTrue(content.contains("argument-hint:"));
  }

  @Test
  void claudeSkillMdManagesSpecsThroughTheCliNotFiles() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, BASE);
    var content = files.get(0).content();

    assertTrue(content.contains("spec create"));
    assertTrue(content.contains("spec board"));
    assertTrue(content.contains("spec update"));
    assertFalse(content.contains("spec.yaml"), "specs are DB rows, not files");
  }

  @Test
  void claudeSkillMdContainsAllCommands() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, BASE);
    var content = files.get(0).content();

    assertTrue(content.contains("list"), "Should contain list command");
    assertTrue(content.contains("create"), "Should contain create command");
    assertTrue(content.contains("show"), "Should contain show command");
    assertTrue(content.contains("update"), "Should contain update command");
    assertTrue(content.contains("Bulk creation"), "Should contain bulk creation");
  }

  @Test
  void claudeSkillMdContainsKanbanBoard() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, BASE);
    var content = files.get(0).content();

    assertTrue(content.contains("Pending"));
    assertTrue(content.contains("In Progress"));
    assertTrue(content.contains("Review"));
    assertTrue(content.contains("Done"));
  }

  @Test
  void claudeSkillMdContainsStatusLifecycle() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, BASE);
    var content = files.get(0).content();

    assertTrue(content.contains("pending"));
    assertTrue(content.contains("in_progress"));
    assertTrue(content.contains("review"));
    assertTrue(content.contains("done"));
  }

  @Test
  void claudeTemplateFileContainsSpecStructure() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, BASE);
    var template = files.get(1).content();

    assertTrue(template.contains("## Goal"));
    assertTrue(template.contains("## Requirements"));
    assertTrue(template.contains("## Approach"));
    assertTrue(template.contains("## Edge Cases"));
    assertTrue(template.contains("## Test Strategy"));
  }

  @Test
  void codexGetsARealSkillFileNotInlineInstructions() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CODEX, BASE);

    assertEquals(2, files.size());
    assertEquals(BASE + ".agents/skills/spec-board/SKILL.md", files.get(0).remotePath());
    assertEquals(BASE + ".agents/skills/spec-board/spec-template.md", files.get(1).remotePath());
    var content = files.get(0).content();
    assertTrue(content.contains("name: spec-board"));
    assertTrue(content.contains("spec create"));
    assertFalse(content.contains("spec.yaml"), "specs are DB rows, not files");
  }

  @Test
  void filesAreNotExecutable() {
    var claudeFiles = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, BASE);

    for (var file : claudeFiles) {
      assertFalse(file.executable());
    }
  }

  @Test
  void claudeSkillReferencesTemplateFile() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, BASE);
    var content = files.get(0).content();

    assertTrue(content.contains("spec-template.md"));
  }

  @Test
  void dependencyRulesDocumented() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, BASE);
    var content = files.get(0).content();

    assertTrue(content.contains("depends-on"));
    assertTrue(content.contains("blocked"));
  }
}
