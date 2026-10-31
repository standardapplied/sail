/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.api.SkillOperations.Kind;
import ai.singlr.sail.api.SkillOperations.Row;
import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.SharedProjectFiles;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillOperationsTest {

  private static final String ACME =
      "name: acme\nagent:\n  type: claude-code\n  install: [claude-code, codex]\n";
  private static final String JAVA_MD =
      "---\nname: java\ndescription: Java standards for this project.\n---\nApply them.\n";
  private static final String RUNNING = "[{\"name\": \"acme\", \"status\": \"Running\"}]";
  private static final List<String> BOTH = List.of("Claude Code", "Codex CLI");

  @TempDir Path dir;
  private Sqlite db;
  private FileStore files;
  private ScriptedShellExecutor shell;
  private SkillOperations skills;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    files = new FileStore(db);
    shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var definition = SailYaml.fromMap(YamlUtil.parseMap(ACME));
    skills =
        new SkillOperations(
            new ProjectSkills(
                project ->
                    new SharedProjectFiles(
                        files, dir.resolve("projects"), project, FileLimits.defaults())),
            shell,
            new ProjectLoader(
                shell,
                project -> "acme".equals(project) ? Optional.of(definition) : Optional.empty()));
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private void share(String path, String content) {
    Acting.system(
        () ->
            files.put(
                "acme",
                path,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                0644));
  }

  private List<String> shared() {
    return files.list("acme").stream().map(FileStore.FileRow::path).sorted().toList();
  }

  @Test
  void aLocalSkillInEitherAgentsFolderIsOneRowNamingTheAgentsThatSeeItAndHowToShareIt()
      throws Exception {
    share(".sail/skills/e2e/SKILL.md", "---\nname: e2e\ndescription: d\n---\nRun it.\n");
    shell
        .onOk("/home/dev/.claude/skills", "lint\ne2e\n")
        .onOk("/home/dev/.agents/skills", "java\nlint\nspec-board\n");

    var rows = skills.rows("acme");

    assertEquals(
        List.of(
            new Row("e2e", Kind.SHARED, 1, BOTH, "ok", ""),
            new Row("spec-board", Kind.SAIL, 2, BOTH, "ok", ""),
            new Row(
                "java",
                Kind.LOCAL,
                0,
                List.of("Codex CLI"),
                "not synced",
                "skill add ~/.agents/skills/java"),
            new Row("lint", Kind.LOCAL, 0, BOTH, "not synced", "skill add ~/.claude/skills/lint")),
        rows.rows());
    assertEquals(Optional.empty(), rows.note());
  }

  @Test
  void aContainerThatCannotBeListedLeavesItsLocalSkillsOutAndSaysWhy() throws Exception {
    shell.onFail("/home/dev/.claude/skills", "Error: Instance is not running");

    var rows = skills.rows("acme");

    assertEquals(List.of(new Row("spec-board", Kind.SAIL, 2, BOTH, "ok", "")), rows.rows());
    assertEquals(
        Optional.of(
            "Failed to list the skills made by hand in acme: Error: Instance is not running, so"
                + " they are not listed."),
        rows.note());
  }

  @Test
  void aFolderInTheRunningContainerIsSharedForEveryAgentOfTheProject() throws Exception {
    shell
        .onOk("incus list", RUNNING)
        .onOk("-printf", "f " + JAVA_MD.length() + " 644 SKILL.md\0")
        .onOk(
            "base64", Base64.getEncoder().encodeToString(JAVA_MD.getBytes(StandardCharsets.UTF_8)));

    var shared =
        Acting.as(
            "uday", () -> skills.shareFromContainer("acme", "/home/dev/.agents/skills/java", ""));

    assertEquals(
        new SkillOperations.Shared("java", 1, BOTH, true, List.of(), Optional.empty()), shared);
    assertEquals(List.of(".sail/skills/java/SKILL.md"), shared());
    assertEquals(JAVA_MD, skills.manifest("acme", "java"));
    assertEquals(
        List.of("/home/dev/.claude/skills/java", "/home/dev/.agents/skills/java"), placed());
  }

  /** The skill folders each install put in place, in order. */
  private List<String> placed() {
    return shell.arguments().stream()
        .filter(command -> command.contains("flock"))
        .map(command -> command.get(command.indexOf("flock") + 6))
        .filter(folder -> folder.endsWith("/java"))
        .toList();
  }

  @Test
  void aFolderInAnAgentsWayIsNamedWhenTheSkillIsSharedAndTheOtherAgentsGetIt() throws Exception {
    containerHolds(JAVA_MD);
    shell.on(
        "/home/dev/.claude/skills/java",
        new ShellExec.Result(
            1,
            "",
            "/home/dev/.claude/skills/java holds a skill sail did not install (no .sail-skill in it)."
                + " Move it aside, or give the project's skill another name."));

    var shared =
        Acting.as(
            "uday", () -> skills.shareFromContainer("acme", "/home/dev/.agents/skills/java", null));

    assertEquals(
        List.of(
            "Claude Code: Failed to put in place skill 'java' in acme: /home/dev/.claude/skills/java"
                + " holds a skill sail did not install (no .sail-skill in it). Move it aside, or give"
                + " the project's skill another name. Its skills are installed at the next launch or"
                + " apply once that is fixed."),
        shared.problems());
    assertEquals(false, shared.installed());
    assertEquals(
        List.of("/home/dev/.claude/skills/java", "/home/dev/.agents/skills/java"),
        placed(),
        "Codex is installed whatever stood in Claude Code's way");
  }

  @Test
  void anInstallThatFailsAnyWayIsAProblemOfTheShareNotItsFailure() throws Exception {
    containerHolds(JAVA_MD);
    shell.onThrow("flock", new IOException("incus went away"));

    var shared =
        Acting.as(
            "uday", () -> skills.shareFromContainer("acme", "/home/dev/.agents/skills/java", null));

    assertEquals(List.of(".sail/skills/java/SKILL.md"), shared());
    assertEquals(
        List.of(
            "Claude Code: incus went away Its skills are installed at the next launch or apply once"
                + " that is fixed.",
            "Codex CLI: incus went away Its skills are installed at the next launch or apply once"
                + " that is fixed."),
        shared.problems());
  }

  @Test
  void anInstallInterruptedStopsInstallingKeepsTheInterruptAndTheShare() throws Exception {
    containerHolds(JAVA_MD);
    var interrupting =
        new ShellExec() {
          @Override
          public Result exec(List<String> command)
              throws IOException, InterruptedException, TimeoutException {
            if (command.contains("flock")) {
              throw new InterruptedException();
            }
            return shell.exec(command);
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout)
              throws IOException, InterruptedException, TimeoutException {
            return exec(command);
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };
    var interrupted =
        new SkillOperations(
            new ProjectSkills(
                project ->
                    new SharedProjectFiles(
                        files, dir.resolve("projects"), project, FileLimits.defaults())),
            interrupting,
            new ProjectLoader(
                shell, project -> Optional.of(SailYaml.fromMap(YamlUtil.parseMap(ACME)))));

    var shared =
        Acting.as(
            "uday",
            () -> interrupted.shareFromContainer("acme", "/home/dev/.agents/skills/java", null));

    assertEquals(
        List.of(
            "Claude Code: InterruptedException Its skills are installed at the next launch or apply"
                + " once that is fixed."),
        shared.problems());
    assertEquals(true, Thread.interrupted());
    assertEquals(List.of(".sail/skills/java/SKILL.md"), shared());
  }

  private void containerHolds(String skillMd) {
    shell
        .onOk("incus list", RUNNING)
        .onOk("-printf", "f " + skillMd.length() + " 644 SKILL.md\0")
        .onOk(
            "base64", Base64.getEncoder().encodeToString(skillMd.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void aFolderIsSharedUnderTheNameGivenOverItsOwn() throws Exception {
    var md = JAVA_MD.replace("name: java", "name: standards");
    shell
        .onOk("incus list", RUNNING)
        .onOk("-printf", "f " + md.length() + " 644 SKILL.md\0")
        .onOk("base64", Base64.getEncoder().encodeToString(md.getBytes(StandardCharsets.UTF_8)));

    assertEquals(
        "standards",
        Acting.as("uday", () -> skills.shareFromContainer("acme", "/home/dev/java/", "standards"))
            .name());
    assertEquals(List.of(".sail/skills/standards/SKILL.md"), shared());
    containerHolds(JAVA_MD);
    assertEquals(
        "java",
        Acting.as("uday", () -> skills.shareFromContainer("acme", "/home/dev/java/", "")).name());
  }

  @Test
  void aStoppedContainerSharesNothingAndSaysHowToStartIt() {
    shell.onOk("incus list", "[{\"name\": \"acme\", \"status\": \"Stopped\"}]");

    var refused =
        assertThrows(
            ApiException.class,
            () -> skills.shareFromContainer("acme", "/home/dev/.agents/skills/java", null));

    assertEquals("Project 'acme' is stopped.", refused.getMessage());
    assertEquals(List.of(), shared());
  }

  @Test
  void removingASkillNobodySharesIsNotFound() {
    var refused =
        assertThrows(
            ApiException.class, () -> Acting.as("uday", () -> skills.remove("acme", "java")));

    assertEquals(ErrorCode.NOT_FOUND, refused.failure().errorCode());
    assertEquals("No skill 'java' is shared on acme.", refused.getMessage());
  }

  @Test
  void removingASharedSkillRemovesEveryFileOfItAndSaysHowMany() throws Exception {
    share(".sail/skills/java/SKILL.md", JAVA_MD);
    share(".sail/skills/java/references/style.md", "Style.\n");
    share("README.md", "hello");

    assertEquals(2, (int) Acting.as("uday", () -> skills.remove("acme", "java")));
    assertEquals(List.of("README.md"), shared());
  }

  @Test
  void theRowsAndTheSharedSkillReadAsTheWireNamesThem() {
    assertEquals(
        Map.of(
            "name", "java",
            "kind", "local",
            "files", 0,
            "agents", List.of("Codex CLI"),
            "status", "not synced",
            "share", "skill add ~/.agents/skills/java"),
        new Row(
                "java",
                Kind.LOCAL,
                0,
                List.of("Codex CLI"),
                "not synced",
                "skill add ~/.agents/skills/java")
            .toMap());
    var sail = new Row("spec-board", Kind.SAIL, 2, BOTH, "ok", "").toMap();
    assertEquals(null, sail.get("share"));
    var rows = new SkillOperations.Rows(List.of(), Optional.of("why")).toMap();
    assertEquals(Map.of("skills", List.of(), "note", "why"), rows);
    var advised =
        new SkillOperations.Shared("java", 1, BOTH, false, List.of("blocked"), Optional.of("long"))
            .toMap();
    assertEquals(
        Map.of(
            "name",
            "java",
            "files",
            1,
            "agents",
            BOTH,
            "installed",
            false,
            "problems",
            List.of("blocked"),
            "advice",
            "long"),
        advised);
  }
}
