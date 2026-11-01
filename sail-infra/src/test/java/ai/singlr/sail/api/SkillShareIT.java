/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.SkillOperations.Kind;
import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.ProjectSkillInstaller;
import ai.singlr.sail.engine.SharedProjectFiles;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A skill made by hand in one agent's folder of a real container is shared with what {@code skill
 * add} runs, installed for every agent at once, taking over the hand-made copy, and removed with
 * what {@code skill rm} runs: the container's own {@code find}, {@code head}, {@code base64},
 * {@code sha256sum}, {@code flock} and {@code mv -T} do what sail's scripts ask of them.
 */
class SkillShareIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-skill-share";
  private static final String SKILL_MD =
      "---\nname: java\ndescription: Java standards for this project.\n---\nApply them.\n";
  private static final String RUN = "#!/bin/sh\necho checked\n";
  private static final String DEFINITION =
      "name: sail-it-skill-share\nagent:\n  type: claude-code\n  install: [claude-code, codex]\n";

  @TempDir Path dir;

  @Test
  void aSkillMadeInOneAgentsFolderIsSharedForEveryAgentAndRemovedFromEvery() throws Exception {
    ensureIncusOrSkip();
    try (var db = Sqlite.open(dir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var files = new FileStore(db);
      var projectSkills =
          new ProjectSkills(
              project ->
                  new SharedProjectFiles(
                      files, dir.resolve("projects"), project, FileLimits.defaults()));
      var definition = SailYaml.fromMap(YamlUtil.parseMap(DEFINITION));
      var skills =
          new SkillOperations(
              projectSkills, shell, new ProjectLoader(shell, project -> Optional.of(definition)));
      var codex = ProjectSkillInstaller.skillsDirOf(Harnesses.of("codex"));
      var claude = ProjectSkillInstaller.skillsDirOf(Harnesses.of("claude-code"));
      launchPrepared(CONTAINER);
      var made =
          exec(
              CONTAINER,
              List.of(
                  "bash",
                  "-c",
                  "userdel -r ubuntu 2>/dev/null || true;"
                      + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev;"
                      + " d=/home/dev/.agents/skills/java; mkdir -p \"$d/scripts\""
                      + " && printf %s \"$1\" > \"$d/SKILL.md\""
                      + " && printf %s \"$2\" > \"$d/scripts/run.sh\""
                      + " && chmod 0644 \"$d/SKILL.md\" && chmod 0755 \"$d/scripts/run.sh\""
                      + " && chown -R dev:dev /home/dev/.agents",
                  "bash",
                  SKILL_MD,
                  RUN));
      assertTrue(made.ok(), "could not make the skill by hand: " + made.stderr());

      var before = skills.rows(CONTAINER).rows().getLast();
      assertEquals(
          new SkillOperations.Row(
              "java",
              Kind.LOCAL,
              0,
              List.of("Codex CLI"),
              "not synced",
              "skill add ~/.agents/skills/java"),
          before);

      var shared =
          Acting.as("uday", () -> skills.shareFromContainer(CONTAINER, codex + "/java", ""));

      assertEquals(
          new SkillOperations.Shared(
              "java", 2, List.of("Claude Code", "Codex CLI"), true, List.of(), Optional.empty()),
          shared);
      var installed = projectSkills.held(CONTAINER).getFirst();
      for (var skillsDir : List.of(codex, claude)) {
        assertEquals(
            ProjectSkillInstaller.fingerprint(installed),
            exec(CONTAINER, List.of("cat", skillsDir + "/java/" + StageSkill.STAMP))
                .stdout()
                .strip(),
            skillsDir);
        assertEquals(
            "checked",
            exec(CONTAINER, List.of("su", "dev", "-c", skillsDir + "/java/scripts/run.sh"))
                .stdout()
                .strip(),
            skillsDir);
      }
      assertEquals(
          List.of("java SHARED", "sail-portal SAIL", "spec-board SAIL"),
          skills.rows(CONTAINER).rows().stream()
              .map(row -> row.name() + " " + row.kind())
              .toList());

      var crowded =
          exec(
              CONTAINER,
              List.of(
                  "su",
                  "dev",
                  "-c",
                  "mkdir -p ~/crowded && for i in $(seq 1 40); do echo x > ~/crowded/f$i.md; done"));
      assertTrue(crowded.ok(), crowded.stderr());
      var refused =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  Acting.as(
                      "uday", () -> skills.shareFromContainer(CONTAINER, "/home/dev/crowded", "")));
      assertEquals(
          "Skill 'crowded' has more than 32 files; the limit is 32.", refused.getMessage());

      assertEquals(2, (int) Acting.as("uday", () -> skills.remove(CONTAINER, "java")));
      for (var harness : List.of(Harnesses.of("codex"), Harnesses.of("claude-code"))) {
        ProjectSkillInstaller.installProject(shell, CONTAINER, harness, projectSkills);
      }
      for (var skillsDir : List.of(codex, claude)) {
        assertEquals(
            false,
            exec(CONTAINER, List.of("test", "-e", skillsDir + "/java")).ok(),
            skillsDir + "/java is gone");
      }
    } finally {
      deleteContainerQuietly(CONTAINER);
    }
  }
}
