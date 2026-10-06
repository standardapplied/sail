/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.store.BlobStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The skills sail ships: what a build, a reviewer and a fix agent are told when a project names no
 * skill of its own. Each is one {@code SKILL.md}, and each says what sail's prompts said before a
 * stage's instructions could be replaced. {@code disable-model-invocation} keeps a harness from
 * loading one on its own: sail puts the body in the prompt itself.
 */
public final class BuiltInSkills {

  private static final int MODE = 0644;

  private static final Map<String, String> TEXTS =
      Map.of(
          StageSkill.BUILD,
          skillMd(
              StageSkill.BUILD,
              "How sail's build agent does its work. Sail puts this in the build's prompt itself.",
              """
              Execute without waiting for confirmation: plan, implement, test, commit. When \
              complete, run the full local verification the project uses (including any coverage \
              or lint gates).

              Never add AI attribution to the work: no Co-Authored-By trailers and no "Generated \
              with" footers in commit messages or pull request descriptions.

              If the build fails repeatedly on the same error, or three different approaches \
              fail, stop and report rather than retrying.
              """),
          StageSkill.REVIEW,
          skillMd(
              StageSkill.REVIEW,
              "How sail's reviewer judges a change. Sail puts this in the reviewer's prompt itself.",
              """
              Only report genuine issues. Do not flag style preferences or working code.

              Every finding MUST include evidence. If you cannot prove it, do not report it.

              Every finding MUST include a concrete suggestion with before/after code.

              Focus on correctness, security, and reliability — not formatting.
              """),
          StageSkill.FIX,
          skillMd(
              StageSkill.FIX,
              "How sail's fix agent answers review findings. Sail puts this in the fix agent's"
                  + " prompt itself.",
              """
              Fix what is real.

              When every finding is addressed, run the project's verification locally before you \
              commit.
              """));

  private BuiltInSkills() {}

  /** The built-in skill named {@code name}, whose one file is its {@code SKILL.md}. */
  public static Optional<StageSkill> of(String name) {
    return text(name)
        .map(
            text -> {
              var bytes = text.getBytes(StandardCharsets.UTF_8);
              var manifest =
                  new StageSkill.File(
                      StageSkill.MANIFEST, BlobStore.hash(bytes), bytes.length, MODE);
              return StageSkill.of(name, text, List.of(manifest));
            });
  }

  /** The {@code SKILL.md} of the built-in skill named {@code name}. */
  public static Optional<String> text(String name) {
    return Optional.ofNullable(name).map(TEXTS::get);
  }

  private static String skillMd(String name, String description, String body) {
    return """
        ---
        name: %s
        description: %s
        disable-model-invocation: true
        ---

        %s"""
        .formatted(name, description, body);
  }
}
