/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.store.BlobStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpecSkillGeneratorTest {

  /**
   * The front matter whole: a description a model acts on, so an interactive session that is asked
   * about specs or the board finds the {@code spec} CLI, and nothing keeping the harness from
   * invoking it.
   */
  @Test
  void theFrontMatterSaysWhenToUseTheSkillAndLetsTheHarnessInvokeIt() {
    var text = SpecSkillGenerator.skillMd();

    assertEquals(
        """
        ---
        name: spec-board
        description: >
          Use when the engineer asks about specs, the board, what to work on next, or to create or
          update a spec. Specs live in the Sail database and are managed with the `spec` CLI.
        argument-hint: "[create|list|show|update] [args...]"
        ---
        """,
        text.substring(0, text.indexOf("---\n", 4) + 4));
    assertFalse(text.contains("disable-model-invocation"), text);
  }

  @Test
  void theSkillIsItsManifestAndTheTemplateAsTheBlobStoreWouldNameThem() throws IOException {
    var skill = SpecSkillGenerator.skill();

    assertEquals("spec-board", skill.name());
    assertTrue(skill.body().startsWith("You are the spec manager for this project."), skill.body());
    assertEquals(
        List.of("SKILL.md", "spec-template.md"),
        skill.files().stream().map(StageSkill.File::path).toList());
    for (var file : skill.files()) {
      var bytes = SpecSkillGenerator.content(file).readAllBytes();
      assertEquals(BlobStore.hash(bytes), file.contentHash(), file.path());
      assertEquals(bytes.length, file.size(), file.path());
      assertEquals(0644, file.mode(), file.path());
    }
    assertEquals(
        SpecSkillGenerator.skillMd(),
        new String(
            SpecSkillGenerator.content(skill.files().getFirst()).readAllBytes(),
            StandardCharsets.UTF_8));
  }

  @Test
  void aFileTheSkillDoesNotHoldIsRefusedNamingIt() {
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> SpecSkillGenerator.content(new StageSkill.File("other.md", "x", 1, 0644)));

    assertEquals("spec-board has no file other.md.", refused.getMessage());
  }

  @Test
  void theManifestManagesSpecsThroughTheCliNotFiles() {
    var content = SpecSkillGenerator.skillMd();

    assertTrue(content.contains("spec create"));
    assertTrue(content.contains("spec board"));
    assertTrue(content.contains("spec update"));
    assertTrue(content.contains("Bulk creation"));
    assertTrue(content.contains("pending` → `in_progress` → `review` → `awaiting_merge` → `done`"));
    assertTrue(content.contains("depends-on"));
    assertTrue(content.contains("blocked"));
    assertTrue(content.contains("[spec-template.md](spec-template.md)"));
    assertFalse(content.contains("spec.yaml"), "specs are DB rows, not files");
  }

  @Test
  void theTemplateHasTheSectionsASpecBodyHas() throws IOException {
    var template = SpecSkillGenerator.skill().files().getLast();
    var content =
        new String(SpecSkillGenerator.content(template).readAllBytes(), StandardCharsets.UTF_8);

    for (var section :
        List.of(
            "## Goal",
            "## Background",
            "## Requirements",
            "## Approach",
            "## Edge Cases",
            "## Test Strategy",
            "## Out of Scope")) {
      assertTrue(content.contains(section), section);
    }
  }
}
