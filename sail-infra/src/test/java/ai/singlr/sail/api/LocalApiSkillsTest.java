/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.SharedProjectFiles;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The socket's skills routes: who may list, read, share and remove a project's skills from inside
 * its container, and which project a run reaches.
 */
class LocalApiSkillsTest {

  private static final String ACME = "name: acme\nagent:\n  type: claude-code\n";
  private static final String JAVA_MD =
      "---\nname: java\ndescription: Java standards.\n---\nApply them.\n";
  private static final String SKILLS = "/v1/projects/acme/skills";
  private static final String SCOPED = "sailbox_scoped_to_acme";
  private static final String PERSON_ONLY =
      "A skill is shared by a person, so an agent sail launched may not %s one.";

  @TempDir Path dir;
  private Sqlite db;
  private FileStore files;
  private ScriptedShellExecutor shell;
  private LocalApiRouter router;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    files = new FileStore(db);
    shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var acme = ProjectDefinition.fromMap(YamlUtil.parseMap(ACME));
    var zeta = ProjectDefinition.fromMap(YamlUtil.parseMap(ACME.replace("acme", "zeta")));
    var skills =
        new SkillOperations(
            new ProjectSkills(
                project ->
                    new SharedProjectFiles(
                        files, dir.resolve("projects"), project, FileLimits.defaults())),
            shell,
            new ProjectLoader(
                shell,
                project ->
                    switch (project) {
                      case "acme" -> Optional.of(acme);
                      case "zeta" -> Optional.of(zeta);
                      default -> Optional.empty();
                    }));
    router =
        new LocalApiRouter(
            new TestOperations() {
              @Override
              public SkillOperations skills() {
                return skills;
              }

              @Override
              public Optional<Actor> boxActorForCredential(String credential) {
                return SCOPED.equals(credential)
                    ? Optional.of(
                        new Actor(TestOperations.BOX_HANDLE, Role.MEMBER, Actor.Lane.CLI)
                            .withScope(ProjectScope.of(List.of("acme"))))
                    : super.boxActorForCredential(credential);
              }
            });
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private static LocalApiRequest request(
      String method, String path, String credential, String form) {
    return new LocalApiRequest(
        method,
        path,
        Map.of(),
        credential == null ? Map.of() : Map.of("authorization", "Bearer " + credential),
        form.getBytes(StandardCharsets.UTF_8));
  }

  private ApiResponse box(String method, String path, String form) {
    return router.handle(request(method, path, TestOperations.BOX_CREDENTIAL, form));
  }

  private static String message(ApiResponse response) {
    return (String) assertInstanceOf(Map.class, response.body().get("error")).get("message");
  }

  private static String remedy(ApiResponse response) {
    var error = assertInstanceOf(Map.class, response.body().get("error"));
    return Remedy.fromMaps((List<?>) error.get("remedy")).plain();
  }

  private void shareJava() {
    shareJava("acme");
  }

  private void shareJava(String project) {
    Acting.system(
        () ->
            files.put(
                project,
                ".sail/skills/java/SKILL.md",
                new ByteArrayInputStream(JAVA_MD.getBytes(StandardCharsets.UTF_8)),
                0644));
  }

  private void containerHolds(String skillMd) {
    shell
        .onOk("incus list", "[{\"name\": \"acme\", \"status\": \"Running\"}]")
        .onOk("-printf", "f " + skillMd.length() + " 644 SKILL.md\0")
        .onOk(
            "base64", Base64.getEncoder().encodeToString(skillMd.getBytes(StandardCharsets.UTF_8)));
  }

  private List<String> shared() {
    return files.list("acme").stream().map(FileStore.FileRow::path).sorted().toList();
  }

  @Test
  void everyCallerListsTheSkillsAnAgentOfItsProjectSees() {
    shareJava();
    shell.onOk("/home/dev/.claude/skills", "lint\n");

    for (var credential :
        List.of(
            TestOperations.BOX_CREDENTIAL,
            TestOperations.RUN_CREDENTIAL,
            TestOperations.ROOM_RUN_CREDENTIAL)) {
      var listed = router.handle(request("GET", SKILLS, credential, ""));

      assertEquals(200, listed.status(), credential);
      assertEquals(
          List.of("java", "sail-portal", "spec-board", "lint"),
          ((List<?>) listed.body().get("skills"))
              .stream().map(row -> ((Map<?, ?>) row).get("name")).toList(),
          credential);
    }
  }

  @Test
  void everyCallerReadsASkillsManifest() {
    shareJava();

    var read =
        router.handle(request("GET", SKILLS + "/java", TestOperations.ROOM_RUN_CREDENTIAL, ""));

    assertEquals(200, read.status());
    assertEquals(Map.of("name", "java", "skill_md", JAVA_MD), withoutSchema(read.body()));
  }

  private static Map<String, Object> withoutSchema(Map<String, Object> body) {
    var copy = new LinkedHashMap<>(body);
    copy.remove("schema_version");
    return copy;
  }

  @Test
  void aPersonsSessionSharesAFolderOfTheContainerForEveryAgent() throws IOException {
    containerHolds(JAVA_MD);

    var shared = box("POST", SKILLS, "folder=%2Fhome%2Fdev%2F.agents%2Fskills%2Fjava");

    assertEquals(200, shared.status(), shared.body().toString());
    assertEquals(
        Map.of(
            "name",
            "java",
            "files",
            1,
            "agents",
            List.of("Claude Code"),
            "installed",
            true,
            "problems",
            List.of()),
        withoutSchema(shared.body()));
    assertEquals(List.of(".sail/skills/java/SKILL.md"), shared());
  }

  @Test
  void aRunOfAnyLaneMayNotShareOrRemoveASkill() {
    shareJava();
    containerHolds(JAVA_MD);

    for (var credential :
        List.of(TestOperations.RUN_CREDENTIAL, TestOperations.ROOM_RUN_CREDENTIAL)) {
      var share =
          router.handle(
              request("POST", SKILLS, credential, "folder=%2Fhome%2Fdev%2Fdrafts%2Flint"));
      var remove = router.handle(request("DELETE", SKILLS + "/java", credential, ""));

      assertEquals(403, share.status(), credential);
      assertEquals(PERSON_ONLY.formatted("share"), message(share));
      assertEquals(
          "Ask them to run 'skill add /home/dev/drafts/lint' in their own session.",
          assertInstanceOf(Map.class, share.body().get("error")).get("action"));
      assertEquals(403, remove.status(), credential);
      assertEquals(PERSON_ONLY.formatted("remove"), message(remove));
      assertEquals(
          "Ask them to run 'skill rm java' in their own session, or 'sail project skills rm java -p"
              + " acme' on the devbox.",
          remedy(remove));
    }
    assertEquals(List.of(".sail/skills/java/SKILL.md"), shared());
    var unnamed = router.handle(request("POST", SKILLS, TestOperations.RUN_CREDENTIAL, ""));
    assertEquals(
        "Ask them to run 'skill add <folder>' in their own session.",
        assertInstanceOf(Map.class, unnamed.body().get("error")).get("action"));
  }

  @Test
  void aPersonsSessionRemovesASharedSkillAndIsToldWhenNoneIsShared() {
    shareJava();

    var removed = box("DELETE", SKILLS + "/java", "");
    var again = box("DELETE", SKILLS + "/java", "");

    assertEquals(200, removed.status());
    assertEquals(Map.of("name", "java", "files", 1), withoutSchema(removed.body()));
    assertEquals(List.of(), shared());
    assertEquals(404, again.status());
    assertEquals("No skill 'java' is shared on acme.", message(again));
  }

  @Test
  void aRunReachesOnlyItsOwnProjectAndAScopedPersonOnlyTheirs() {
    shareJava("zeta");
    var invocations = shell.invocations().size();

    for (var credential : List.of(TestOperations.RUN_CREDENTIAL, SCOPED)) {
      for (var asked :
          List.of(
              request("GET", "/v1/projects/zeta/skills", credential, ""),
              request("GET", "/v1/projects/zeta/skills/java", credential, ""),
              request(
                  "POST", "/v1/projects/zeta/skills", credential, "folder=%2Fhome%2Fdev%2Fjava"),
              request("DELETE", "/v1/projects/zeta/skills/java", credential, ""))) {
        var refused = router.handle(asked);

        assertEquals(404, refused.status(), credential + " " + asked.method());
        assertEquals("Project 'zeta' is not in the catalog.", message(refused));
      }
    }
    assertEquals(invocations, shell.invocations().size(), "nothing was asked of zeta's container");
    assertEquals(200, router.handle(request("GET", SKILLS, SCOPED, "")).status());
    assertEquals(
        List.of(".sail/skills/java/SKILL.md"),
        files.list("zeta").stream().map(FileStore.FileRow::path).toList());
  }

  @Test
  void aRequestWithoutACredentialIsRefused() {
    assertEquals(401, router.handle(request("GET", SKILLS, null, "")).status());
  }

  @Test
  void aFolderThatIsNoSkillIsRefusedWithStageSkillsSentence() {
    containerHolds("---\nname: java\n---\nApply them.\n");

    var refused = box("POST", SKILLS, "folder=%2Fhome%2Fdev%2Fjava");

    assertEquals(400, refused.status());
    assertEquals(
        "Skill 'java' has no description in its front matter: the Agent Skills standard requires"
            + " one.",
        message(refused));
    assertEquals(List.of(), shared());
  }

  @Test
  void aContainerThatCannotBeReadIsAContainerError() {
    shell
        .onOk("incus list", "[{\"name\": \"acme\", \"status\": \"Running\"}]")
        .onThrow("-printf", new IOException("incus went away"));

    var failed = box("POST", SKILLS, "folder=%2Fhome%2Fdev%2Fjava");

    assertEquals(500, failed.status());
    assertEquals("incus went away", message(failed));
  }

  @Test
  void aShareInterruptedWhileTheContainerIsAskedIsAContainerErrorAndKeepsTheInterrupt() {
    var interrupting =
        new ShellExec() {
          @Override
          public Result exec(List<String> command) throws InterruptedException {
            throw new InterruptedException("stopping");
          }

          @Override
          public Result exec(List<String> command, Path workDir, Duration timeout)
              throws InterruptedException {
            throw new InterruptedException("stopping");
          }

          @Override
          public boolean isDryRun() {
            return false;
          }
        };
    var skills =
        new SkillOperations(
            new ProjectSkills(
                project ->
                    new SharedProjectFiles(
                        files, dir.resolve("projects"), project, FileLimits.defaults())),
            interrupting,
            new ProjectLoader(
                new ScriptedShellExecutor()
                    .onOk("incus list", "[{\"name\": \"acme\", \"status\": \"Running\"}]"),
                project -> Optional.of(ProjectDefinition.fromMap(YamlUtil.parseMap(ACME)))));
    var interrupted =
        new LocalApiRouter(
            new TestOperations() {
              @Override
              public SkillOperations skills() {
                return skills;
              }
            });

    var failed =
        interrupted.handle(
            request("POST", SKILLS, TestOperations.BOX_CREDENTIAL, "folder=%2Fhome%2Fdev%2Fjava"));

    assertEquals(500, failed.status());
    assertEquals(true, Thread.interrupted(), "the interrupt is kept for whoever waits on it");
  }

  @Test
  void anUnknownRouteOrMethodIsSaidSo() {
    assertEquals(404, box("GET", "/v1/projects/acme", "").status());
    assertEquals(404, box("GET", "/v1/projects/acme/other", "").status());
    assertEquals(404, box("GET", SKILLS + "/java/more", "").status());
    assertEquals(405, box("PUT", SKILLS, "").status());
    assertEquals(405, box("PUT", SKILLS + "/java", "").status());
    assertEquals(400, box("GET", "/v1/projects/-x/skills", "").status());
  }
}
