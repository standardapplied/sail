/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectReader;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.gen.BuiltInSkills;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import picocli.CommandLine;

/**
 * {@code sail project skills} over a real control-plane database: the catalog's definition and the
 * project's shared files, read as a launch reads them.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ProjectSkillsCommandTest {

  private static final String DEFAULTS = "name: acme\nagent:\n  type: claude-code\n";

  private static final String NAMED =
      """
      name: acme
      agent:
        type: claude-code
        build_skill: acme-build
        review_pipeline:
          fix_skill: acme-fix
          stages:
            - name: security
              skill: acme-security
            - name: style
            - name: sign-off
              type: human
      """;

  private static final String ACME_BUILD =
      """
      ---
      name: acme-build
      description: How acme builds.
      ---

      Build it acme's way.
      """;

  @TempDir Path dir;
  private Path database;
  private Sqlite db;

  @BeforeEach
  void setUp() {
    database = dir.resolve("sail.db");
    db = Sqlite.open(database);
    new SchemaManager(db).migrate();
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private HostOperations operations() {
    return OperationsFactory.open(database);
  }

  private void define(String project, String yaml) {
    Acting.system(() -> new ProjectStore(db).upsert(project, yaml));
  }

  private void share(String path, String content) {
    share(path, content.getBytes(StandardCharsets.UTF_8));
  }

  private void share(String path, byte[] content) {
    Acting.system(
        () -> new FileStore(db).put("acme", path, new ByteArrayInputStream(content), 0644));
  }

  private record Ran(int exit, String out, String err, Exception escaped) {}

  private Ran list(String... args) {
    return run(new ProjectSkillsCommand(this::operations), args);
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
  void theCommandIsAVerbOfProjectWithShowBeneathIt() {
    var project = new CommandLine(new ProjectCommand());

    assertTrue(project.getSubcommands().containsKey("skills"));
    assertEquals(
        List.of("show"),
        List.copyOf(project.getSubcommands().get("skills").getSubcommands().keySet()));
    assertTrue(
        new CommandLine(new ProjectSkillsCommand()).getUsageMessage().contains("--json"),
        "and its production constructor is the one picocli builds");
    assertTrue(
        new CommandLine(new ProjectSkillsCommand.Show()).getUsageMessage().contains("<name>"));
  }

  @Test
  void aProjectThatNamesNoSkillRunsEveryStageUnderSailsOwn() {
    define("acme", DEFAULTS);

    var listed = list("-p", "acme");

    assertEquals(0, listed.exit(), String.valueOf(listed.escaped()));
    assertEquals(
        """
          Stage skills: acme
          STAGE   SKILL        SOURCE
          build   sail-build   built-in
          review  sail-review  built-in
          fix     sail-fix     built-in
        """,
        listed.out());
    assertEquals("", listed.err());
  }

  @Test
  void eachStageIsListedInTheOrderItRunsWithHowItsSkillStands() {
    define("acme", NAMED);
    share(".sail/skills/acme-build/SKILL.md", ACME_BUILD);
    share(".sail/skills/acme-build/scripts/check.sh", "true\n");
    share(".sail/skills/acme-build/reference/rules.md", "Rules.\n");
    share(".sail/skills/acme-fix/SKILL.md", "---\nname: acme-fix\n---\n");
    share(".sail/skills/acme-fix/notes.md", "Notes.\n");

    var listed = list("--project", "acme");

    assertEquals(0, listed.exit(), String.valueOf(listed.escaped()));
    assertEquals(
        """
          Stage skills: acme
          STAGE     SKILL          SOURCE
          build     acme-build     project (3 files)
          security  acme-security  missing
          style     sail-review    built-in
          fix       acme-fix       invalid: Skill 'acme-fix' has no instructions: its SKILL.md body is blank.
        """,
        listed.out());
    assertEquals("", listed.err());
  }

  @Test
  void jsonIsTheSameRowsWithTheFileCountOfEach() {
    define("acme", NAMED);
    share(".sail/skills/acme-build/SKILL.md", ACME_BUILD);
    share(".sail/skills/acme-build/scripts/check.sh", "true\n");
    share(".sail/skills/acme-fix/SKILL.md", "---\nname: acme-fix\n---\n");
    share(".sail/skills/acme-fix/notes.md", "Notes.\n");
    share(".sail/skills/acme-security/notes.md", "No manifest.\n");

    var listed = list("-p", "acme", "--json");

    assertEquals(0, listed.exit(), String.valueOf(listed.escaped()));
    assertEquals(
        List.of(
            Map.of("stage", "build", "skill", "acme-build", "source", "project", "files", 2),
            Map.of("stage", "security", "skill", "acme-security", "source", "missing", "files", 0),
            Map.of("stage", "style", "skill", "sail-review", "source", "built-in", "files", 1),
            Map.of(
                "stage",
                "fix",
                "skill",
                "acme-fix",
                "source",
                "invalid: Skill 'acme-fix' has no instructions: its SKILL.md body is blank.",
                "files",
                2)),
        YamlUtil.parseList(listed.out()));
    assertTrue(listed.out().endsWith("\n"));
  }

  @Test
  void aManifestThatIsNotTextIsAMissingSkill() {
    define("acme", NAMED);
    share(".sail/skills/acme-build/SKILL.md", new byte[] {0, 1, 2, 0, (byte) 0xff});

    var listed = list("-p", "acme", "--json");

    assertEquals(
        Map.of("stage", "build", "skill", "acme-build", "source", "missing", "files", 0),
        YamlUtil.parseList(listed.out()).getFirst());
  }

  @Test
  void aColumnIsNeverNarrowerThanItsHeader() {
    define(
        "acme",
        """
        name: acme
        agent:
          type: claude-code
          build_skill: b
          review_pipeline:
            fix_skill: f
            stages:
              - name: s
                skill: r
        """);

    var listed = list("-p", "acme");

    assertEquals(0, listed.exit());
    assertEquals(
        """
          Stage skills: acme
          STAGE  SKILL  SOURCE
          build  b      missing
          s      r      missing
          fix    f      missing
        """,
        listed.out());
  }

  @Test
  void aPipelineBlockWithOnlyAFixSkillListsTheDefaultStageAndThatSkill() {
    define("acme", DEFAULTS + "  review_pipeline:\n    fix_skill: acme-fix\n");
    share(".sail/skills/acme-fix/SKILL.md", "Fix it acme's way.\n");

    var listed = list("-p", "acme");

    assertEquals(
        """
          Stage skills: acme
          STAGE   SKILL        SOURCE
          build   sail-build   built-in
          review  sail-review  built-in
          fix     acme-fix     project (1 files)
        """,
        listed.out());
  }

  @Test
  void aProjectWithNoAgentBlockStillListsTheLoopSailWouldRun() {
    define("acme", "name: acme\n");

    var listed = list("-p", "acme", "--json");

    assertEquals(
        List.of("build:sail-build", "review:sail-review", "fix:sail-fix"),
        YamlUtil.parseList(listed.out()).stream()
            .map(row -> row.get("stage") + ":" + row.get("skill"))
            .toList());
  }

  @Test
  void filesUnderAReservedNameAreReportedOnStderrOncePerFolder() {
    define("acme", DEFAULTS);
    share(".sail/skills/sail-review/SKILL.md", "Mine.\n");
    share(".sail/skills/sail-review/notes.md", "Notes.\n");
    share(".sail/skills/sail-mine/scripts/x.sh", "true\n");
    share(".sail/skills/sail-notes.md", "A file, not a folder.\n");
    share(".sail/skills/sailing/SKILL.md", "Not reserved.\n");

    var listed = list("-p", "acme");

    assertEquals(0, listed.exit());
    assertEquals(
        List.of(
            "  ⚠ .sail/skills/sail-mine/ is reserved and unused: skill names starting sail- are"
                + " sail's own, so no stage can name it. Move its files under another name.",
            "  ⚠ .sail/skills/sail-review/ is reserved and unused: skill names starting sail- are"
                + " sail's own, so no stage can name it. Move its files under another name."),
        listed.err().lines().toList());
    assertTrue(listed.out().contains("review  sail-review  built-in"), listed.out());
  }

  @Test
  void aProjectNotInTheCatalogExitsNonZeroSayingSo() {
    var listed = list("-p", "ghost");

    assertEquals(1, listed.exit());
    assertEquals(
        "Project 'ghost' is not in the catalog.",
        assertInstanceOf(ApiException.class, listed.escaped()).getMessage());
    assertEquals("", listed.out());
  }

  @Test
  void aRowThatCannotBeReadExitsNonZeroSayingWhy() {
    define("acme", DEFAULTS);
    db.execute(
        "UPDATE projects SET definition = ? WHERE name = ?",
        DEFAULTS + "  build_skill: sail-x\n",
        "acme");

    var listed = list("-p", "acme");

    assertEquals(1, listed.exit());
    assertTrue(
        assertInstanceOf(ProjectReader.Unreadable.class, listed.escaped())
            .getMessage()
            .startsWith(
                "The definition of project 'acme' in the catalog could not be read:"
                    + " agent.build_skill 'sail-x' is not a skill a project can name"),
        listed.escaped().getMessage());
    assertEquals("", listed.out());
  }

  @Test
  void anInvalidProjectNameIsRefusedBeforeAnythingIsOpened() {
    var listed = list("-p", "../etc");

    assertEquals(1, listed.exit());
    assertInstanceOf(IllegalArgumentException.class, listed.escaped());
  }

  @Test
  void showPrintsADefaultsSkillMdWholeWithoutNeedingAProject() {
    for (var name : List.of("sail-build", "sail-review", "sail-fix")) {
      var shown = show(name);

      assertEquals(0, shown.exit(), String.valueOf(shown.escaped()));
      assertEquals(BuiltInSkills.text(name).orElseThrow(), shown.out());
      assertNull(shown.escaped());
    }
  }

  @Test
  void showPrintsAProjectsSkillMdAsItIsStored() {
    define("acme", NAMED);
    share(".sail/skills/acme-build/SKILL.md", ACME_BUILD);
    share(".sail/skills/acme-build/scripts/check.sh", "true\n");

    var shown = show("acme-build", "-p", "acme");

    assertEquals(0, shown.exit(), String.valueOf(shown.escaped()));
    assertEquals(ACME_BUILD, shown.out());
  }

  @Test
  void showOfASkillNobodyHoldsExitsNonZeroWithTheResolversMessage() {
    define("acme", DEFAULTS);
    share(".sail/skills/blank/SKILL.md", "---\nname: blank\n---\n");

    var unknown = show("acme-review", "-p", "acme");
    var invalid = show("blank", "-p", "acme");
    var noName = show("Bad Name", "-p", "acme");

    assertEquals(1, unknown.exit());
    assertEquals(
        "Skill 'acme-review' of project 'acme' cannot be read: .sail/skills/acme-review/SKILL.md is"
            + " missing or is not a text file. Add it with 'sail project files add <file> --as"
            + " .sail/skills/acme-review/SKILL.md'.",
        assertInstanceOf(IllegalStateException.class, unknown.escaped()).getMessage());
    assertEquals("", unknown.out());
    assertEquals(1, invalid.exit());
    assertEquals(
        "Skill 'blank' of project 'acme' is refused: Skill 'blank' has no instructions: its"
            + " SKILL.md body is blank.",
        invalid.escaped().getMessage());
    assertEquals(1, noName.exit());
    assertEquals(
        "Skill 'Bad Name' of project 'acme' is refused: Skill name 'Bad Name' must match"
            + " [a-z0-9][a-z0-9-]{0,63}.",
        noName.escaped().getMessage());
  }
}
