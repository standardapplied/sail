/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectSkills;
import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SharedProjectFiles;
import ai.singlr.sail.engine.WorkspaceFiles;
import ai.singlr.sail.gen.SpecSkillGenerator;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class ProjectSkillsCommandTest {

  private static final String ACME = "name: acme\nagent:\n  type: claude-code\n";

  private static final String E2E =
      """
      ---
      name: e2e
      description: Runs the e2e suite.
      ---

      Run the suite.
      """;

  @TempDir Path dir;
  private Path database;
  private Sqlite db;

  @BeforeEach
  void setUp() {
    database = dir.resolve("sail.db");
    db = Sqlite.open(database);
    new SchemaManager(db).migrate();
    Acting.system(() -> new ProjectStore(db).upsert("acme", ACME));
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private HostOperations operations() {
    return OperationsFactory.open(database);
  }

  private void share(String path, String content) {
    Acting.system(
        () ->
            new FileStore(db)
                .put(
                    "acme",
                    path,
                    new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                    0644));
  }

  private List<String> shared() {
    return new FileStore(db).list("acme").stream().map(FileStore.FileRow::path).sorted().toList();
  }

  private Path localSkill(String name, Map<String, String> files) throws Exception {
    var folder = dir.resolve("local").resolve(name);
    for (var entry : files.entrySet()) {
      var file = folder.resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, entry.getValue());
      WorkspaceFiles.mode(file, entry.getKey().endsWith(".sh") ? 0755 : 0644);
    }
    return folder;
  }

  private record Ran(int exit, String out, String err, Exception escaped) {}

  private Ran ls(String... args) {
    return run(new ProjectSkillsCommand.Ls(this::operations), args);
  }

  private Ran add(String... args) {
    return run(new ProjectSkillsCommand.Add(this::operations), args);
  }

  private Ran rm(String... args) {
    return run(new ProjectSkillsCommand.Rm(this::operations), args);
  }

  private Ran show(String... args) {
    return run(new ProjectSkillsCommand.Show(this::operations), args);
  }

  private static Ran run(Object command, String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    var originalOut = System.out;
    var originalErr = System.err;
    var escaped = new Exception[1];
    try (var outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        var errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      System.setOut(outStream);
      System.setErr(errStream);
      var exit =
          new CommandLine(command)
              .setExecutionExceptionHandler(
                  (thrown, commandLine, parsed) -> {
                    escaped[0] = thrown;
                    return 1;
                  })
              .execute(args);
      return new Ran(
          exit,
          out.toString(StandardCharsets.UTF_8),
          err.toString(StandardCharsets.UTF_8),
          escaped[0]);
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }

  @Test
  void theCommandIsAVerbOfProjectWithLsAddRmAndShowBeneathIt() {
    var subcommands = new CommandLine(new ProjectCommand()).getSubcommands();

    assertTrue(subcommands.containsKey("skills"));
    assertEquals(
        List.of("ls", "add", "rm", "show"),
        List.copyOf(subcommands.get("skills").getSubcommands().keySet()));
  }

  @Test
  void aProjectWithNoSkillsSaysSo() {
    var ran = ls("-p", "acme");

    assertEquals(0, ran.exit(), ran.err());
    assertEquals(
        "  No skills: share one with 'sail project skills add <folder>'.\n",
        CommandLine.Help.Ansi.OFF.string(ran.out()));
  }

  @Test
  void lsShowsEachFolderWithItsFilesAndWhetherItIsASkill() {
    share(".sail/skills/e2e/SKILL.md", E2E);
    share(".sail/skills/e2e/scripts/run.sh", "#!/bin/sh\n");
    share(".sail/skills/broken/notes.md", "no manifest");

    var ran = ls("-p", "acme");

    assertEquals(0, ran.exit(), ran.err());
    assertEquals(
        """
          Skills: acme
          SKILL   FILES  STATUS
          broken      1  invalid: .sail/skills/broken/SKILL.md is missing or is not a text file.
          e2e         2  ok
        """,
        CommandLine.Help.Ansi.OFF.string(ran.out()));
  }

  @Test
  void jsonIsTheSameRows() {
    share(".sail/skills/e2e/SKILL.md", E2E);

    var ran = ls("-p", "acme", "--json");

    assertEquals(0, ran.exit(), ran.err());
    assertEquals(
        List.of(Map.of("name", "e2e", "files", 1, "status", "ok")), YamlUtil.parseList(ran.out()));
  }

  @Test
  void addSharesEveryFileUnderTheFolderUnderTheSkillsNameWithItsMode() throws Exception {
    var folder =
        localSkill(
            "e2e",
            Map.of("SKILL.md", E2E, "scripts/run.sh", "#!/bin/sh\n", "reference/cases.md", "x"));

    var ran = add("-p", "acme", folder.toString());

    assertEquals(0, ran.exit(), ran.err() + ran.escaped());
    assertEquals(
        List.of(
            ".sail/skills/e2e/SKILL.md",
            ".sail/skills/e2e/reference/cases.md",
            ".sail/skills/e2e/scripts/run.sh"),
        shared());
    assertEquals(
        0755,
        new FileStore(db).find("acme", ".sail/skills/e2e/scripts/run.sh").orElseThrow().mode());
    assertTrue(ran.out().contains("Shared skill e2e (3 files) on acme"), ran.out());
    assertEquals("ok", ProjectSkillsCommand.rows("acme", skills()).getFirst().status());
  }

  private ProjectSkills skills() {
    return new ProjectSkills(
        project ->
            new SharedProjectFiles(
                new FileStore(db), dir.resolve("projects"), project, FileLimits.defaults()));
  }

  @Test
  void addTakesTheNameGivenOverTheFoldersOwn() throws Exception {
    var folder = localSkill("my-e2e-draft", Map.of("SKILL.md", E2E));

    var ran = add("-p", "acme", folder.toString(), "--name", "e2e");

    assertEquals(0, ran.exit(), ran.err());
    assertEquals(List.of(".sail/skills/e2e/SKILL.md"), shared());
  }

  @Test
  void addRefusesAReservedNameBeforeSharingAnything() throws Exception {
    var sailX = localSkill("sail-x", Map.of("SKILL.md", E2E));
    var specBoard = localSkill("spec-board", Map.of("SKILL.md", E2E));
    var named = localSkill("mine", Map.of("SKILL.md", E2E));

    for (var ran :
        List.of(
            add("-p", "acme", sailX.toString()),
            add("-p", "acme", specBoard.toString()),
            add("-p", "acme", named.toString(), "--name", "sail-mine"))) {
      assertEquals(1, ran.exit());
      assertTrue(
          ran.escaped().getMessage().contains("is sail's own: spec-board and names starting sail-"),
          ran.escaped().getMessage());
    }
    assertEquals(List.of(), shared());
  }

  @Test
  void addRefusesAFolderThatIsNoSkillBeforeSharingAnything() throws Exception {
    var noManifest = localSkill("notes", Map.of("notes.md", "x"));
    var blank = localSkill("blank", Map.of("SKILL.md", "---\nname: blank\n---\n"));

    var ranNoManifest = add("-p", "acme", noManifest.toString());
    var ranBlank = add("-p", "acme", blank.toString());

    assertEquals("Skill 'notes' has no SKILL.md.", ranNoManifest.escaped().getMessage());
    assertEquals(
        "Skill 'blank' has no instructions: its SKILL.md body is blank.",
        ranBlank.escaped().getMessage());
    assertEquals(List.of(), shared());
  }

  @Test
  void addRefusesWhatIsNotAFolder() throws Exception {
    var file = dir.resolve("SKILL.md");
    Files.writeString(file, E2E);

    var ran = add("-p", "acme", file.toString());

    assertEquals(1, ran.exit());
    assertTrue(ran.err().contains("Not a folder: " + file), ran.err());
  }

  @Test
  void rmStopsSharingEveryFileOfTheSkillAndNothingElse() {
    share(".sail/skills/e2e/SKILL.md", E2E);
    share(".sail/skills/e2e/scripts/run.sh", "#!/bin/sh\n");
    share(".sail/skills/release/SKILL.md", "Cut it.\n");
    share("README.md", "hello");

    var ran = rm("-p", "acme", "e2e");

    assertEquals(0, ran.exit(), ran.err());
    assertEquals(List.of(".sail/skills/release/SKILL.md", "README.md"), shared());
    assertTrue(ran.out().contains("Stopped sharing skill e2e (2 files)"), ran.out());
  }

  @Test
  void rmOfASkillNobodyHoldsExitsNonZeroSayingSo() {
    var ran = rm("-p", "acme", "e2e");

    assertEquals(1, ran.exit());
    assertTrue(ran.err().contains("No skill 'e2e' on acme."), ran.err());
  }

  @Test
  void showPrintsAProjectsSkillMdAsItIsStored() {
    share(".sail/skills/e2e/SKILL.md", E2E);

    var ran = show("-p", "acme", "e2e");

    assertEquals(0, ran.exit(), ran.err());
    assertEquals(E2E, ran.out());
  }

  @Test
  void showPrintsSailsOwnSkillMdWithoutNeedingAProject() {
    var ran = show(SpecSkillGenerator.NAME);

    assertEquals(0, ran.exit(), ran.err());
    assertEquals(SpecSkillGenerator.skillMd(), ran.out());
  }

  @Test
  void showOfASkillNobodyHoldsExitsNonZeroNamingItsManifest() {
    var ran = show("-p", "acme", "e2e");

    assertEquals(1, ran.exit());
    assertEquals(
        "No skill 'e2e' on acme: it has no .sail/skills/e2e/SKILL.md.", ran.escaped().getMessage());
    assertNull(ran.escaped().getCause());
  }

  @Test
  void aProjectNotInTheCatalogExitsNonZeroSayingSo() {
    var ran = ls("-p", "other");

    assertEquals(1, ran.exit());
    assertTrue(
        ran.escaped().getMessage().contains("not in the catalog"), ran.escaped().getMessage());
  }
}
