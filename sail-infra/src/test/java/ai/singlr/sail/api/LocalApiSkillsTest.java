/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.SharedProjectFiles;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * The skills routes: who may list and read a project's skills from inside its container, and which
 * projects a run reaches; that no credential in a container shares or removes one; and that a
 * person shares a folder of the container, and removes a skill, from outside it over the web API.
 */
class LocalApiSkillsTest {

  private static final String ACME = "name: acme\nagent:\n  type: claude-code\n";
  private static final String JAVA_MD =
      "---\nname: java\ndescription: Java standards.\n---\nApply them.\n";
  private static final String SKILLS = "/v1/projects/acme/skills";
  private static final String SCOPED = "sailbox_scoped_to_acme";
  private static final String ALLOWED = "sailbox_on_a_lane_that_shares";

  @TempDir Path dir;
  private Sqlite db;
  private FileStore files;
  private ScriptedShellExecutor shell;
  private ProjectStore catalog;
  private TestOperations operations;
  private SocketDoor router;

  @BeforeEach
  void setUp() {
    db = Sqlite.open(dir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    files = new FileStore(db);
    shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var acme = TestDefinitions.yaml(ACME);
    var zeta = TestDefinitions.yaml(ACME.replace("acme", "zeta"));
    catalog = new ProjectStore(db);
    Acting.system(
        () -> {
          catalog.upsert(acme);
          catalog.upsert(zeta);
        });
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
                    }),
            catalog);
    operations =
        new TestOperations() {
          @Override
          public SkillOperations skills() {
            return skills;
          }

          @Override
          public Optional<Actor> boxActorForCredential(String credential) {
            if (ALLOWED.equals(credential)) {
              return Optional.of(new Actor(TestOperations.BOX_HANDLE, Role.MEMBER, Actor.Lane.CLI));
            }
            return SCOPED.equals(credential)
                ? Optional.of(
                    new Actor(TestOperations.BOX_HANDLE, Role.MEMBER, Actor.Lane.CONTAINER)
                        .withScope(ProjectScope.of(List.of("acme"))))
                : super.boxActorForCredential(credential);
          }
        };
    router = new SocketDoor(operations);
  }

  /** The web API over the same box, every request made as {@code person}. */
  private SailApiServer web(Actor person) throws IOException {
    var server =
        new SailApiServer(
            "127.0.0.1",
            0,
            operations,
            exchange -> exchange.setAttribute(ApiAuth.ACTOR, person),
            null,
            null,
            null);
    server.start();
    return server;
  }

  private static HttpResponse<String> send(
      SailApiServer server, String method, String path, String json) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  /**
   * A person outside the container shares a folder that lives in it over the web API, as Mast does,
   * and removes the skill; a viewer is read-only there, as everywhere.
   */
  @Test
  void aPersonSharesAFolderOfTheContainerOverTheWebAndAViewerIsReadOnly() throws Exception {
    containerHolds(JAVA_MD);

    try (var member = web(new Actor("uday", Role.MEMBER, Actor.Lane.API));
        var viewer = web(new Actor("vic", Role.VIEWER, Actor.Lane.API))) {
      var folder = "{\"folder\": \"/home/dev/drafts/standards\", \"name\": \"java\"}";
      var refused = send(viewer, "POST", SKILLS, folder);
      assertEquals(403, refused.statusCode(), refused.body());
      assertEquals(
          "Your credential is read-only and cannot share files and skills in projects.",
          ((Map<?, ?>) YamlUtil.parseMap(refused.body()).get("error")).get("message"));
      assertEquals(List.of(), shared());

      var shared = send(member, "POST", SKILLS, folder);
      assertEquals(200, shared.statusCode(), shared.body());
      assertEquals("java", YamlUtil.parseMap(shared.body()).get("name"));
      assertEquals(List.of(".sail/skills/java/SKILL.md"), shared());
      assertEquals(403, send(viewer, "DELETE", SKILLS + "/java", null).statusCode());

      var removed = send(member, "DELETE", SKILLS + "/java", null);
      assertEquals(200, removed.statusCode(), removed.body());
      assertEquals(1, YamlUtil.parseMap(removed.body()).get("files"));
      assertEquals(List.of(), shared());
      var again = send(member, "DELETE", SKILLS + "/java", null);
      assertEquals(404, again.statusCode(), again.body());
      assertEquals(
          "No skill 'java' is shared on acme.",
          ((Map<?, ?>) YamlUtil.parseMap(again.body()).get("error")).get("message"));
    }
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  private ApiResponse box(String method, String path, String form) {
    return router.ask(method, path, TestOperations.BOX_CREDENTIAL, form);
  }

  private static String message(ApiResponse response) {
    return (String) assertInstanceOf(Map.class, response.body().get("error")).get("message");
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
      var listed = router.ask("GET", SKILLS, credential, "");

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

    var read = router.ask("GET", SKILLS + "/java", TestOperations.ROOM_RUN_CREDENTIAL, "");

    assertEquals(200, read.status());
    assertEquals(Map.of("name", "java", "skill_md", JAVA_MD), withoutSchema(read.body()));
  }

  private static Map<String, Object> withoutSchema(Map<String, Object> body) {
    var copy = new LinkedHashMap<>(body);
    copy.remove("schema_version");
    return copy;
  }

  /**
   * The box credential in the container is refused sharing and removing a skill by its lane, as the
   * journal decides the write, naming the doors outside the container that take it.
   */
  @Test
  void theCredentialInTheContainerMayNotShareOrRemoveASkill() {
    shareJava();
    containerHolds(JAVA_MD);

    var asked = shell.invocations().size();
    var share = box("POST", SKILLS, "folder=%2Fhome%2Fdev%2F.agents%2Fskills%2Fjava");
    var remove = box("DELETE", SKILLS + "/java", "");

    for (var refused : List.of(share, remove)) {
      assertEquals(403, refused.status());
      assertEquals(
          "The credential in a project container cannot share files and skills in projects.",
          message(refused));
      assertEquals(
          "Do it from outside the container: 'sail project skills add --container <folder> -p"
              + " acme' on the host, or Mast.",
          assertInstanceOf(Map.class, refused.body().get("error")).get("action"));
    }
    assertEquals(List.of(".sail/skills/java/SKILL.md"), shared());
    assertEquals(
        asked, shell.invocations().size(), "a refusal reads nothing of the container's folder");
  }

  /**
   * The socket's share and remove routes are the one share verb, whatever the caller, decided as
   * the journal records the write: no credential in a container is let share, so a caller on a lane
   * the journal lets share stands in, and gets the skill shared from the folder named and removed.
   */
  @Test
  void theShareRoutesAreTheOneShareVerbTheJournalDecides() {
    containerHolds(JAVA_MD);

    var shared =
        router.ask("POST", SKILLS, ALLOWED, "folder=%2Fhome%2Fdev%2Fdrafts%2Fjava&name=java");
    var removed = router.ask("DELETE", SKILLS + "/java", ALLOWED, "");

    assertEquals(200, shared.status(), shared.body().toString());
    assertEquals("java", shared.body().get("name"));
    assertEquals(Map.of("name", "java", "files", 1), withoutSchema(removed.body()));
    assertEquals(List.of(), shared());
  }

  /**
   * No run shares or removes a skill: the journal refuses a work agent by its lane and a room agent
   * as read-only, in the words every other write of theirs hears.
   */
  @Test
  void aRunOfAnyLaneMayNotShareOrRemoveASkill() {
    shareJava();
    containerHolds(JAVA_MD);
    var refusals =
        Map.of(
            TestOperations.RUN_CREDENTIAL,
            List.of(
                "agent_lane_forbidden",
                "Agent principals cannot share files and skills in projects.",
                "Only an operator lane (CLI or API token) may do this."),
            TestOperations.ROOM_RUN_CREDENTIAL,
            List.of(
                "read_only_credential",
                "Your credential is read-only and cannot share files and skills in projects.",
                "Ask an admin for a member or admin credential."));

    var asked = shell.invocations().size();
    for (var refused : refusals.entrySet()) {
      var credential = refused.getKey();
      var share =
          router.ask("POST", SKILLS, credential, "folder=%2Fhome%2Fdev%2F.agents%2Fskills%2Fjava");
      var remove = router.ask("DELETE", SKILLS + "/java", credential, "");

      for (var answer : List.of(share, remove)) {
        var error = assertInstanceOf(Map.class, answer.body().get("error"));
        assertEquals(403, answer.status(), credential);
        assertEquals(
            refused.getValue(),
            List.of(error.get("code"), error.get("message"), error.get("action")),
            credential);
      }
    }
    assertEquals(List.of(".sail/skills/java/SKILL.md"), shared());
    assertEquals(
        asked, shell.invocations().size(), "a refusal reads nothing of the container's folder");
  }

  @Test
  void aRunReadsItsPersonsOtherProjectsSkillsAndAScopedPersonOnlyTheirs() {
    shareJava("zeta");
    var run = TestOperations.RUN_CREDENTIAL;

    assertEquals(200, router.ask("GET", "/v1/projects/zeta/skills", run, "").status());
    assertEquals(
        Map.of("name", "java", "skill_md", JAVA_MD),
        withoutSchema(router.ask("GET", "/v1/projects/zeta/skills/java", run, "").body()));
    var invocations = shell.invocations().size();
    for (var asked :
        List.of(
            List.of("GET", "/v1/projects/zeta/skills", ""),
            List.of("GET", "/v1/projects/zeta/skills/java", ""),
            List.of("POST", "/v1/projects/zeta/skills", "folder=%2Fhome%2Fdev%2Fjava"),
            List.of("DELETE", "/v1/projects/zeta/skills/java", ""))) {
      var refused = router.ask(asked.get(0), asked.get(1), SCOPED, asked.get(2));

      assertEquals(404, refused.status(), asked.toString());
      assertEquals("Project 'zeta' is not in the catalog.", message(refused));
    }
    assertEquals(invocations, shell.invocations().size(), "nothing was asked of zeta's container");
    assertEquals(200, router.ask("GET", SKILLS, SCOPED, "").status());
    assertEquals(
        List.of(".sail/skills/java/SKILL.md"),
        files.list("zeta").stream().map(FileStore.FileRow::path).toList());
  }

  @Test
  void aRequestWithoutACredentialIsRefused() {
    assertEquals(401, router.ask("GET", SKILLS, null, "").status());
  }

  @Test
  void aFolderThatIsNoSkillIsRefusedWithStageSkillsSentence() {
    containerHolds("---\nname: java\n---\nApply them.\n");

    var refused = router.ask("POST", SKILLS, ALLOWED, "folder=%2Fhome%2Fdev%2Fjava");

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

    var failed = router.ask("POST", SKILLS, ALLOWED, "folder=%2Fhome%2Fdev%2Fjava");

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
                project -> Optional.of(TestDefinitions.yaml(ACME))),
            catalog);
    var interrupted =
        new SocketDoor(
            new TestOperations() {
              @Override
              public SkillOperations skills() {
                return skills;
              }

              @Override
              public Optional<Actor> boxActorForCredential(String credential) {
                return operations.boxActorForCredential(credential);
              }
            });

    var failed = interrupted.ask("POST", SKILLS, ALLOWED, "folder=%2Fhome%2Fdev%2Fjava");

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
