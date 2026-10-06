/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.store.BlobStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuiltInSkillsTest {

  @Test
  void theBuildSkillIsThisFileWhole() {
    assertEquals(
        """
        ---
        name: sail-build
        description: How sail's build agent does its work. Sail puts this in the build's prompt itself.
        disable-model-invocation: true
        ---

        Execute without waiting for confirmation: plan, implement, test, commit. When complete, run the full local verification the project uses (including any coverage or lint gates).

        Never add AI attribution to the work: no Co-Authored-By trailers and no "Generated with" footers in commit messages or pull request descriptions.

        If the build fails repeatedly on the same error, or three different approaches fail, stop and report rather than retrying.
        """,
        BuiltInSkills.text("sail-build").orElseThrow());
  }

  @Test
  void theReviewSkillIsThisFileWhole() {
    assertEquals(
        """
        ---
        name: sail-review
        description: How sail's reviewer judges a change. Sail puts this in the reviewer's prompt itself.
        disable-model-invocation: true
        ---

        Only report genuine issues. Do not flag style preferences or working code.

        Every finding MUST include evidence. If you cannot prove it, do not report it.

        Every finding MUST include a concrete suggestion with before/after code.

        Focus on correctness, security, and reliability — not formatting.
        """,
        BuiltInSkills.text("sail-review").orElseThrow());
  }

  @Test
  void theFixSkillIsThisFileWhole() {
    assertEquals(
        """
        ---
        name: sail-fix
        description: How sail's fix agent answers review findings. Sail puts this in the fix agent's prompt itself.
        disable-model-invocation: true
        ---

        Fix what is real.

        When every finding is addressed, run the project's verification locally before you commit.
        """,
        BuiltInSkills.text("sail-fix").orElseThrow());
  }

  @Test
  void aBuiltInIsItsOneFileAsTheBlobStoreWouldNameIt() {
    for (var name : List.of(StageSkill.BUILD, StageSkill.REVIEW, StageSkill.FIX)) {
      var text = BuiltInSkills.text(name).orElseThrow();
      var bytes = text.getBytes(StandardCharsets.UTF_8);
      var skill = BuiltInSkills.of(name).orElseThrow();

      assertEquals(name, skill.name());
      assertEquals(
          List.of(new StageSkill.File("SKILL.md", BlobStore.hash(bytes), bytes.length, 0644)),
          skill.files());
      assertEquals(text.substring(text.indexOf("---\n\n") + 5).strip(), skill.body());
    }
  }

  @Test
  void theFrontMatterOfABuiltInNamesItAndKeepsTheHarnessFromLoadingIt() {
    for (var name : List.of(StageSkill.BUILD, StageSkill.REVIEW, StageSkill.FIX)) {
      var text = BuiltInSkills.text(name).orElseThrow();
      var frontMatter = YamlUtil.parseMap(text.substring(4, text.indexOf("\n---\n", 4)));

      assertEquals(name, frontMatter.get("name"));
      assertEquals(Boolean.TRUE, frontMatter.get("disable-model-invocation"));
      assertTrue(((String) frontMatter.get("description")).endsWith("prompt itself."), name);
      assertEquals(3, ((Map<?, ?>) frontMatter).size());
    }
  }

  @Test
  void anyOtherNameIsNoBuiltIn() {
    assertTrue(BuiltInSkills.of("mine").isEmpty());
    assertTrue(BuiltInSkills.text("sail-").isEmpty());
    assertTrue(BuiltInSkills.of(null).isEmpty());
  }
}
