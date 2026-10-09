/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ProjectSkills.Folder;
import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.engine.SharedProjectFiles;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.gen.SpecSkillGenerator;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSkillsTest {

  private static final String SKILL_MD =
      """
      ---
      name: e2e
      description: Runs the e2e suite.
      ---

      Run the suite and report every failure.
      """;

  @TempDir Path dir;
  private Sqlite db;
  private FileStore files;
  private ProjectSkills skills;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    files = new FileStore(db);
    skills =
        new ProjectSkills(
            project ->
                new SharedProjectFiles(
                    files, dir.resolve("projects"), project, FileLimits.defaults()));
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private void share(String path, byte[] content, int mode) {
    Acting.system(() -> files.put("acme", path, new ByteArrayInputStream(content), mode));
  }

  private void share(String path, String content) {
    share(path, content.getBytes(StandardCharsets.UTF_8), 0644);
  }

  private static String hash(String content) {
    return BlobStore.hash(content.getBytes(StandardCharsets.UTF_8));
  }

  private static String read(InputStream stream) throws IOException {
    try (stream) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private Folder.Invalid invalid(String name) {
    return skills.all("acme").stream()
        .filter(folder -> folder.name().equals(name))
        .map(folder -> assertInstanceOf(Folder.Invalid.class, folder))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void aProjectWithNoSkillsHoldsNoFolderAndEveryOtherFileIsLeftAlone() {
    share("README.md", "hello");
    share(".sail/other.md", "not a skill");

    assertEquals(List.of(), skills.all("acme"));
    assertEquals(List.of(), skills.held("acme"));
  }

  @Test
  void everyFolderIsReadWithItsFilesHashesSizesAndModesInNameOrder() {
    share(".sail/skills/release/SKILL.md", "Cut a release.\n");
    share(".sail/skills/e2e/SKILL.md", SKILL_MD);
    share(".sail/skills/e2e/scripts/run.sh", "#!/bin/sh\n".getBytes(StandardCharsets.UTF_8), 0755);
    share(".sail/skills/e2e/reference/cases.md", "Cases.\n");

    var all = skills.all("acme");

    assertEquals(List.of("e2e", "release"), all.stream().map(Folder::name).toList());
    var e2e = assertInstanceOf(Folder.Held.class, all.getFirst()).skill();
    assertEquals("Run the suite and report every failure.", e2e.body());
    assertEquals(
        List.of(
            new StageSkill.File("SKILL.md", hash(SKILL_MD), SKILL_MD.length(), 0644),
            new StageSkill.File("reference/cases.md", hash("Cases.\n"), 7, 0644),
            new StageSkill.File("scripts/run.sh", hash("#!/bin/sh\n"), 10, 0755)),
        e2e.files());
    assertEquals(
        List.of("e2e", "release"), skills.held("acme").stream().map(StageSkill::name).toList());
  }

  @Test
  void aFolderWithoutItsManifestIsInvalidNamingIt() {
    share(".sail/skills/broken/notes.md", "no manifest");
    share(".sail/skills/broken/scripts/x.sh", "x");

    assertEquals(
        new Folder.Invalid(
            "broken", ".sail/skills/broken/SKILL.md is missing or is not a text file.", 2),
        invalid("broken"));
  }

  @Test
  void aManifestThatIsNotTextIsInvalidNamingIt() {
    share(".sail/skills/bin/SKILL.md", new byte[] {0, 1, 2, 3}, 0644);

    assertEquals(
        new Folder.Invalid("bin", ".sail/skills/bin/SKILL.md is missing or is not a text file.", 1),
        invalid("bin"));
  }

  @Test
  void aFolderStageSkillRefusesIsInvalidWithItsOwnMessage() {
    share(".sail/skills/blank/SKILL.md", "---\nname: blank\n---\n");

    assertEquals(
        new Folder.Invalid(
            "blank", "Skill 'blank' has no instructions: its SKILL.md body is blank.", 1),
        invalid("blank"));
  }

  @Test
  void aFolderOverItsBoundsIsInvalidNamingTheLimit() {
    share(".sail/skills/big/SKILL.md", "Body.\n");
    for (var i = 0; i < StageSkill.MAX_FILES; i++) {
      share(".sail/skills/big/f" + i + ".md", "x");
    }

    assertEquals(
        new Folder.Invalid(
            "big", "Skill 'big' has 33 files; the limit is 32.", StageSkill.MAX_FILES + 1),
        invalid("big"));
  }

  @Test
  void aFolderUnderAReservedNameIsInvalidSayingWhichNamesAreSails() {
    share(".sail/skills/spec-board/SKILL.md", "Mine.\n");
    share(".sail/skills/sail-review/SKILL.md", "Mine.\n");
    share(".sail/skills/Bad Name/SKILL.md", "Mine.\n");

    assertEquals(
        "Skill name 'spec-board' is sail's own: spec-board and names starting sail- are reserved."
            + " Give the skill another name.",
        invalid("spec-board").why());
    assertEquals(
        "Skill name 'sail-review' is sail's own: spec-board and names starting sail- are reserved."
            + " Give the skill another name.",
        invalid("sail-review").why());
    assertEquals(
        "Skill name 'Bad Name' must match [a-z0-9][a-z0-9-]{0,63}.", invalid("Bad Name").why());
    assertEquals(List.of(), skills.held("acme"), "none of them is installed");
  }

  @Test
  void aManifestWhoseContentHasNotReachedThisBoxIsInvalidSayingSo() {
    share(".sail/skills/e2e/SKILL.md", SKILL_MD);
    db.execute("DELETE FROM blobs");

    var why = invalid("e2e").why();

    assertTrue(why.contains(hash(SKILL_MD)), why);
  }

  @Test
  void aFileOpensAsTheBytesThatWereRead() throws IOException {
    share(".sail/skills/e2e/SKILL.md", SKILL_MD);
    share(".sail/skills/e2e/scripts/run.sh", "#!/bin/sh\n");
    var e2e = skills.held("acme").getFirst();

    assertEquals("#!/bin/sh\n", read(skills.open("acme", e2e, e2e.files().getLast())));
  }

  @Test
  void aFileThatChangedOrWentSinceItWasReadIsRefused() {
    share(".sail/skills/e2e/SKILL.md", SKILL_MD);
    share(".sail/skills/e2e/scripts/run.sh", "#!/bin/sh\n");
    var e2e = skills.held("acme").getFirst();
    share(".sail/skills/e2e/scripts/run.sh", "#!/bin/bash\n");
    Acting.system(() -> files.delete("acme", ".sail/skills/e2e/SKILL.md"));

    var changed =
        assertThrows(
            IllegalStateException.class, () -> skills.open("acme", e2e, e2e.files().getLast()));
    var gone =
        assertThrows(
            IllegalStateException.class, () -> skills.open("acme", e2e, e2e.files().getFirst()));

    assertEquals(
        "Skill 'e2e' of project 'acme' changed while it was being installed:"
            + " .sail/skills/e2e/scripts/run.sh is no longer the file that was read. Launch again.",
        changed.getMessage());
    assertEquals(
        "Skill 'e2e' of project 'acme' changed while it was being installed:"
            + " .sail/skills/e2e/SKILL.md is no longer the file that was read. Launch again.",
        gone.getMessage());
  }

  @Test
  void aFileWhoseContentIsNotOnThisBoxIsRefusedNamingIt() {
    share(".sail/skills/e2e/SKILL.md", SKILL_MD);
    var e2e = skills.held("acme").getFirst();
    db.execute("DELETE FROM blobs");

    var refused =
        assertThrows(
            IllegalStateException.class, () -> skills.open("acme", e2e, e2e.files().getFirst()));

    assertTrue(
        refused
            .getMessage()
            .startsWith(
                "Skill 'e2e' of project 'acme' cannot be read: the content of"
                    + " .sail/skills/e2e/SKILL.md is not on this box ("),
        refused.getMessage());
  }

  @Test
  void sailsOwnSkillOpensFromSailOnAnyBox() throws IOException {
    var specBoard = SpecSkillGenerator.skill();

    assertEquals(
        SpecSkillGenerator.skillMd(),
        read(ProjectSkills.none().open("acme", specBoard, specBoard.files().getFirst())));
  }

  @Test
  void aBoxWithNoProjectFilesWiredHoldsNoProjectSkill() {
    assertEquals(List.of(), ProjectSkills.none().all("acme"));
  }

  @Test
  void requireOwnNameRefusesSailsNamesAndNonNames() {
    ProjectSkills.requireOwnName("e2e");
    assertEquals(
        "Skill name 'sail-x' is sail's own: spec-board and names starting sail- are reserved. Give"
            + " the skill another name.",
        assertThrows(IllegalArgumentException.class, () -> ProjectSkills.requireOwnName("sail-x"))
            .getMessage());
    assertEquals(
        "Skill name 'spec-board' is sail's own: spec-board and names starting sail- are reserved."
            + " Give the skill another name.",
        assertThrows(
                IllegalArgumentException.class, () -> ProjectSkills.requireOwnName("spec-board"))
            .getMessage());
    assertEquals(
        "Skill name '../x' must match [a-z0-9][a-z0-9-]{0,63}.",
        assertThrows(IllegalArgumentException.class, () -> ProjectSkills.requireOwnName("../x"))
            .getMessage());
  }
}
