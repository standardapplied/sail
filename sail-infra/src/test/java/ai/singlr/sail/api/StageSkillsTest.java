/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.engine.FileMaterializer;
import ai.singlr.sail.engine.SharedProjectFiles;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.gen.BuiltInSkills;
import ai.singlr.sail.harness.Harnesses;
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
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StageSkillsTest {

  private static final String SKILL_MD =
      """
      ---
      name: acme-review
      description: How acme reviews.
      ---

      Judge it acme's way.
      """;

  @TempDir Path dir;
  private Sqlite db;
  private FileStore files;
  private StageSkills skills;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    files = new FileStore(db);
    skills =
        new StageSkills(
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

  private String refusal(String name) {
    return assertThrows(IllegalStateException.class, () -> skills.resolve("acme", name))
        .getMessage();
  }

  @Test
  void aSkillSailShipsIsResolvedWithoutReadingAnyProjectFile() {
    share(".sail/skills/sail-review/SKILL.md", "A project's file under a reserved name.");

    for (var name : List.of(StageSkill.BUILD, StageSkill.REVIEW, StageSkill.FIX)) {
      assertEquals(BuiltInSkills.of(name).orElseThrow(), skills.resolve("acme", name));
      assertEquals(
          BuiltInSkills.of(name).orElseThrow(), StageSkills.builtInOnly().resolve("acme", name));
    }
  }

  @Test
  void aProjectsSkillIsTheFilesUnderItsFolderWithTheirHashesSizesAndModes() {
    var script = "#!/bin/sh\nmvn -q verify\n";
    share(".sail/skills/acme-review/SKILL.md", SKILL_MD);
    share(
        ".sail/skills/acme-review/scripts/check.sh", script.getBytes(StandardCharsets.UTF_8), 0755);
    share(".sail/skills/acme-review/reference/rules.md", "Rules.");
    share(".sail/skills/acme-review-two/SKILL.md", "Another skill's.");
    share(".sail/skills/acme-reviewSKILL.md", "Beside the folder, not in it.");
    share("docs/notes.md", "An ordinary project file.");

    var skill = skills.resolve("acme", "acme-review");

    assertEquals("acme-review", skill.name());
    assertEquals("Judge it acme's way.", skill.body());
    assertEquals(
        List.of(
            new StageSkill.File("SKILL.md", hash(SKILL_MD), SKILL_MD.length(), 0644),
            new StageSkill.File("reference/rules.md", hash("Rules."), 6, 0644),
            new StageSkill.File("scripts/check.sh", hash(script), script.length(), 0755)),
        skill.files());
  }

  @Test
  void aSkillTheProjectDoesNotHoldIsRefusedNamingItsManifest() {
    share(".sail/skills/acme-review/notes.md", "No manifest here.");
    share(".sail/skills/acme-review/docs/SKILL.md", "Not directly under the folder.");

    assertEquals(
        "Skill 'acme-review' of project 'acme' cannot be read: .sail/skills/acme-review/SKILL.md is"
            + " missing or is not a text file. Add it with 'sail project files add <file> --as"
            + " .sail/skills/acme-review/SKILL.md'.",
        refusal("acme-review"));
    assertNull(
        assertThrows(IllegalStateException.class, () -> skills.resolve("acme", "acme-review"))
            .getCause(),
        "the message is said once");
  }

  @Test
  void aManifestThatIsNotTextIsRefusedNamingIt() {
    share(".sail/skills/acme-review/SKILL.md", new byte[] {0, 1, 2, 0, (byte) 0xff}, 0644);

    assertEquals(
        "binary", files.find("acme", ".sail/skills/acme-review/SKILL.md").orElseThrow().kind());
    assertEquals(
        "Skill 'acme-review' of project 'acme' cannot be read: .sail/skills/acme-review/SKILL.md is"
            + " missing or is not a text file. Add it with 'sail project files add <file> --as"
            + " .sail/skills/acme-review/SKILL.md'.",
        refusal("acme-review"));
  }

  @Test
  void aSkillStageSkillRefusesIsRefusedWithItsOwnMessage() {
    share(".sail/skills/unclosed/SKILL.md", "---\nname: unclosed\nBody.\n");
    share(".sail/skills/blank/SKILL.md", "---\nname: blank\n---\n");
    share(".sail/skills/stamped/SKILL.md", "Body.");
    share(".sail/skills/stamped/.sail-skill", "forged");

    assertEquals(
        "Skill 'unclosed' of project 'acme' is refused: Skill 'unclosed' has front matter that is"
            + " never closed: end it with a line that is exactly ---.",
        refusal("unclosed"));
    assertEquals(
        "Skill 'blank' of project 'acme' is refused: Skill 'blank' has no instructions: its"
            + " SKILL.md body is blank.",
        refusal("blank"));
    assertEquals(
        "Skill 'stamped' of project 'acme' is refused: Skill 'stamped' holds the path"
            + " '.sail-skill'; .sail-skill is the name sail stamps an installed skill with.",
        refusal("stamped"));
    assertNull(
        assertThrows(IllegalStateException.class, () -> skills.resolve("acme", "blank"))
            .getCause());
  }

  @Test
  void aSkillOverItsBoundsIsRefusedNamingTheLimit() {
    share(".sail/skills/wordy/SKILL.md", "x".repeat(32_001));
    share(".sail/skills/crowded/SKILL.md", "Body.");
    for (var i = 0; i < StageSkill.MAX_FILES; i++) {
      share(".sail/skills/crowded/ref/" + i + ".md", "r" + i);
    }
    share(".sail/skills/heavy/SKILL.md", "Body.");
    share(".sail/skills/heavy/big.bin", new byte[(int) StageSkill.MAX_BYTES], 0644);
    share(".sail/skills/huge/SKILL.md", ("word ".repeat(1024)).repeat(205));

    assertEquals(
        "Skill 'wordy' of project 'acme' is refused: Skill 'wordy' has a body of 32001 code"
            + " points; the limit is 32000.",
        refusal("wordy"));
    assertEquals(
        "Skill 'crowded' of project 'acme' is refused: Skill 'crowded' has 33 files; the limit is"
            + " 32.",
        refusal("crowded"));
    assertEquals(
        "Skill 'heavy' of project 'acme' is refused: Skill 'heavy' is 1048581 bytes; the limit is"
            + " 1048576.",
        refusal("heavy"));
    assertEquals(
        "Skill 'huge' of project 'acme' is refused: Skill 'huge' has a body of 1048577 code"
            + " points; the limit is 32000.",
        refusal("huge"),
        "a manifest larger than a folder may be is read no further than that");
  }

  @Test
  void aNameThatIsNoSkillNameIsRefusedBeforeAnyFileIsLookedUp() {
    share(".sail/skills/a/b/SKILL.md", "Reached only through a name with a slash.");

    assertEquals(
        "Skill 'a/b' of project 'acme' is refused: Skill name 'a/b' must match"
            + " [a-z0-9][a-z0-9-]{0,63}.",
        refusal("a/b"));
    assertEquals(
        "Skill 'null' of project 'acme' is refused: Skill name 'null' must match"
            + " [a-z0-9][a-z0-9-]{0,63}.",
        refusal(null));
  }

  @Test
  void aBoxWithNoProjectFilesWiredRefusesEverySkillButSailsOwnSayingSo() {
    var builtInOnly = StageSkills.builtInOnly();
    var mine = new StageSkill("acme-review", "Body.", List.of(file("SKILL.md", "Body.")));

    assertEquals(
        "Skill 'acme-review' of project 'acme' cannot be read: this box has no project files"
            + " wired.",
        assertThrows(IllegalStateException.class, () -> builtInOnly.resolve("acme", "acme-review"))
            .getMessage());
    assertEquals(
        "Skill 'acme-review' of project 'acme' cannot be read: this box has no project files"
            + " wired.",
        assertThrows(
                IllegalStateException.class,
                () -> builtInOnly.open("acme", mine, mine.files().getFirst()))
            .getMessage());
  }

  @Test
  void aBuiltInsFileOpensAsItsTextOnAnyBox() throws IOException {
    var skill = BuiltInSkills.of(StageSkill.FIX).orElseThrow();

    assertEquals(
        BuiltInSkills.text(StageSkill.FIX).orElseThrow(),
        read(StageSkills.builtInOnly().open("acme", skill, skill.files().getFirst())));
    assertEquals(
        BuiltInSkills.text(StageSkill.FIX).orElseThrow(),
        read(skills.open("acme", skill, skill.files().getFirst())));
  }

  @Test
  void aProjectsFileOpensAsTheBytesThatWereResolved() throws IOException {
    share(".sail/skills/acme-review/SKILL.md", SKILL_MD);
    share(".sail/skills/acme-review/reference/rules.md", "Rules.");
    var skill = skills.resolve("acme", "acme-review");

    assertEquals(SKILL_MD, read(skills.open("acme", skill, skill.files().getFirst())));
    assertEquals("Rules.", read(skills.open("acme", skill, skill.files().getLast())));
  }

  @Test
  void aFileThatChangedSinceItWasResolvedIsRefused() {
    share(".sail/skills/acme-review/SKILL.md", SKILL_MD);
    share(".sail/skills/acme-review/reference/rules.md", "Rules.");
    var skill = skills.resolve("acme", "acme-review");
    share(".sail/skills/acme-review/reference/rules.md", "Other rules.");

    var refused =
        assertThrows(
            IllegalStateException.class, () -> skills.open("acme", skill, skill.files().getLast()));

    assertEquals(
        "Skill 'acme-review' of project 'acme' changed while it was being installed:"
            + " .sail/skills/acme-review/reference/rules.md is no longer the file that was"
            + " resolved. Launch again.",
        refused.getMessage());
    assertNull(refused.getCause());
  }

  @Test
  void aFileThatWasRemovedSinceItWasResolvedIsRefused() {
    share(".sail/skills/acme-review/SKILL.md", SKILL_MD);
    share(".sail/skills/acme-review/reference/rules.md", "Rules.");
    var skill = skills.resolve("acme", "acme-review");
    Acting.system(() -> files.delete("acme", ".sail/skills/acme-review/reference/rules.md"));

    var refused =
        assertThrows(
            IllegalStateException.class, () -> skills.open("acme", skill, skill.files().getLast()));

    assertEquals(
        "Skill 'acme-review' of project 'acme' changed while it was being installed:"
            + " .sail/skills/acme-review/reference/rules.md is no longer the file that was"
            + " resolved. Launch again.",
        refused.getMessage());
  }

  @Test
  void aManifestThatCannotBeReadIsRefusedSayingWhy() {
    var row =
        new FileStore.FileRow(
            "acme", ".sail/skills/acme-review/SKILL.md", hash("Body."), 5, 0644, "text");
    var unreadable =
        new StageSkills(
            project ->
                new ProjectFiles() {
                  @Override
                  public FileLimits limits() {
                    return FileLimits.defaults();
                  }

                  @Override
                  public List<FileStore.FileRow> list() {
                    return List.of(row);
                  }

                  @Override
                  public Optional<FileStore.FileRow> find(String path) {
                    return Optional.of(row);
                  }

                  @Override
                  public InputStream open(FileStore.FileRow opened) {
                    return new InputStream() {
                      @Override
                      public int read() throws IOException {
                        throw new IOException("blob store is gone");
                      }
                    };
                  }

                  @Override
                  public String put(String path, InputStream bytes, long size, int mode) {
                    return path;
                  }

                  @Override
                  public boolean remove(String path) {
                    return false;
                  }

                  @Override
                  public FileMaterializer.Report materialize() {
                    return null;
                  }
                });

    assertEquals(
        "Skill 'acme-review' of project 'acme' is refused: blob store is gone",
        assertThrows(IllegalStateException.class, () -> unreadable.resolve("acme", "acme-review"))
            .getMessage());
  }

  @Test
  void aBlockNamesTheFolderOfTheHarnessThatRunsThePrompt() {
    var withFiles =
        new StageSkill(
            "acme-review",
            "Judge it acme's way.",
            List.of(file("SKILL.md", "x"), file("scripts/check.sh", "y")));

    assertEquals(
        """
        ## How to do this work (skill: acme-review)

        Judge it acme's way.

        The skill's other files are in ~/.claude/skills/acme-review/.""",
        StageSkills.block(withFiles, Harnesses.of("claude-code")));
    assertEquals(
        """
        ## How to do this work (skill: acme-review)

        Judge it acme's way.

        The skill's other files are in ~/.agents/skills/acme-review/.""",
        StageSkills.block(withFiles, Harnesses.of("codex")));
  }

  private static StageSkill.File file(String path, String content) {
    return new StageSkill.File(path, hash(content), content.length(), 0644);
  }
}
