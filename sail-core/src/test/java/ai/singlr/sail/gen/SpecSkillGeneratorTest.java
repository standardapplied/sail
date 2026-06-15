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
  void generateFilesReturnsEmptyWhenSpecsDirNull() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, null, BASE);

    assertTrue(files.isEmpty());
  }

  @Test
  void claudeCodeGeneratesSkillMdAndTemplate() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, "specs", BASE);

    assertEquals(2, files.size());
    assertEquals(BASE + ".claude/skills/spec-board/SKILL.md", files.get(0).remotePath());
    assertEquals(BASE + ".claude/skills/spec-board/spec-template.md", files.get(1).remotePath());
  }

  @Test
  void claudeSkillMdHasFrontmatter() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, "specs", BASE);
    var content = files.get(0).content();

    assertTrue(content.startsWith("---\n"));
    assertTrue(content.contains("name: spec-board"));
    assertTrue(content.contains("description:"));
    assertTrue(content.contains("argument-hint:"));
  }

  @Test
  void claudeSkillMdManagesSpecsThroughTheCliNotFiles() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, "my-specs", BASE);
    var content = files.get(0).content();

    assertTrue(content.contains("spec create"));
    assertTrue(content.contains("spec board"));
    assertTrue(content.contains("spec update"));
    assertFalse(content.contains("spec.yaml"), "specs are DB rows, not files");
  }

  @Test
  void claudeSkillMdContainsAllCommands() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, "specs", BASE);
    var content = files.get(0).content();

    assertTrue(content.contains("list"), "Should contain list command");
    assertTrue(content.contains("create"), "Should contain create command");
    assertTrue(content.contains("show"), "Should contain show command");
    assertTrue(content.contains("update"), "Should contain update command");
    assertTrue(content.contains("Bulk creation"), "Should contain bulk creation");
  }

  @Test
  void claudeSkillMdContainsKanbanBoard() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, "specs", BASE);
    var content = files.get(0).content();

    assertTrue(content.contains("Pending"));
    assertTrue(content.contains("In Progress"));
    assertTrue(content.contains("Review"));
    assertTrue(content.contains("Done"));
  }

  @Test
  void claudeSkillMdContainsStatusLifecycle() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, "specs", BASE);
    var content = files.get(0).content();

    assertTrue(content.contains("pending"));
    assertTrue(content.contains("in_progress"));
    assertTrue(content.contains("review"));
    assertTrue(content.contains("done"));
  }

  @Test
  void claudeTemplateFileContainsSpecStructure() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, "specs", BASE);
    var template = files.get(1).content();

    assertTrue(template.contains("## Goal"));
    assertTrue(template.contains("## Requirements"));
    assertTrue(template.contains("## Approach"));
    assertTrue(template.contains("## Edge Cases"));
    assertTrue(template.contains("## Test Strategy"));
  }

  @Test
  void codexReturnsEmptyFiles() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CODEX, "specs", BASE);

    assertTrue(files.isEmpty());
  }

  @Test
  void codexInstructionsContainsSpecManagement() {
    var instructions = SpecSkillGenerator.codexInstructions("specs");

    assertFalse(instructions.isEmpty());
    assertTrue(instructions.contains("Spec Management"));
    assertTrue(instructions.contains("spec create"));
    assertFalse(instructions.contains("spec.yaml"), "specs are DB rows, not files");
  }

  @Test
  void codexInstructionsReturnsEmptyWhenNull() {
    var instructions = SpecSkillGenerator.codexInstructions(null);

    assertTrue(instructions.isEmpty());
  }

  @Test
  void codexInstructionsContainsAllOperations() {
    var instructions = SpecSkillGenerator.codexInstructions("specs");

    assertTrue(instructions.contains("Pending"));
    assertTrue(instructions.contains("In Progress"));
    assertTrue(instructions.contains("create"), "Should contain create instructions");
    assertTrue(instructions.contains("update"), "Should contain update instructions");
    assertTrue(instructions.contains("brainstormed"), "Should contain bulk creation instructions");
  }

  @Test
  void filesAreNotExecutable() {
    var claudeFiles = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, "specs", BASE);

    for (var file : claudeFiles) {
      assertFalse(file.executable());
    }
  }

  @Test
  void specsDirOnlyGatesGenerationItDoesNotLeakIntoContent() {
    var claude = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, "work-items", BASE);
    assertTrue(claude.get(0).content().contains("spec create"));
    assertFalse(claude.get(0).content().contains("work-items"));

    var codex = SpecSkillGenerator.codexInstructions("work-items");
    assertTrue(codex.contains("spec create"));
    assertFalse(codex.contains("work-items"));
  }

  @Test
  void claudeSkillReferencesTemplateFile() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, "specs", BASE);
    var content = files.get(0).content();

    assertTrue(content.contains("spec-template.md"));
  }

  @Test
  void dependencyRulesDocumented() {
    var files = SpecSkillGenerator.generateFiles(AgentCli.CLAUDE_CODE, "specs", BASE);
    var content = files.get(0).content();

    assertTrue(content.contains("depends-on"));
    assertTrue(content.contains("blocked"));
  }
}
