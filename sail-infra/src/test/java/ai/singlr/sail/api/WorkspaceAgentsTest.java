/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What can start in a workspace, through the real web router over the production operations: its
 * owner is offered each harness with its modes and the login it starts signed in with, or why it
 * does not start, in the words and codes a terminal Create of it is refused with; an admin in
 * another person's workspace is told it is not theirs and sees nothing of their logins; a member is
 * refused the workspace as every workspace door refuses it.
 */
class WorkspaceAgentsTest {

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  @AfterAll
  static void closeClient() {
    CLIENT.close();
  }

  @TempDir Path tempDir;
  private HttpServer server;
  private Sqlite db;
  private ScriptedShellExecutor incus;
  private String ada;
  private String sam;
  private String tom;
  private String obs;
  private String samId;
  private String tomId;

  /** Workspace {@code name} of {@code project} as Incus lists it, {@code state}, labelled. */
  private static String workspace(
      String project, String name, String state, String owner, String id) {
    return """
        {"name": "%s-%s", "status": "%s", "type": "container",
         "config": {"user.sail.project": "%s", "user.sail.workspace": "%s",
                    "user.sail.ready": "1762941601000"%s},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}"""
        .formatted(
            project,
            name,
            state,
            project,
            name,
            owner == null
                ? ""
                : ", \"user.sail.owner\": \"%s\", \"user.sail.owner_id\": \"%s\""
                    .formatted(owner, id));
  }

  @BeforeEach
  void aBoxWhereSamAndTomEachHaveAWorkspace() throws Exception {
    db = Sqlite.open(tempDir.resolve("main.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    HeldProjects.hold(db, "beta");
    var fdes = new FdeStore(db);
    Acting.system(
        () -> {
          var projects = new ProjectStore(db);
          projects.upsert(
              TestDefinitions.yaml(
                  "name: acme\nagent:\n  type: claude-code\n  install: [claude-code]\n"));
          projects.upsert(
              TestDefinitions.yaml(
                  "name: beta\nagent:\n  type: claude-code\n  install: [claude-code, codex]\n"));
          var tokens = new TokenStore(db);
          ada =
              tokens
                  .create("ada", "admin", fdes.add("ada", "Ada", "ada@x", "admin").id(), null)
                  .token();
          samId = fdes.add("sam", "Sam", "sam@x", "member").id();
          sam = tokens.create("sam", "member", samId, null).token();
          tomId = fdes.add("tom", "Tom", "tom@x", "member").id();
          tom = tokens.create("tom", "member", tomId, null).token();
          obs =
              tokens
                  .create("obs", "viewer", fdes.add("obs", "Obs", "obs@x", "viewer").id(), null)
                  .token();
        });
    var claude = Harnesses.of("claude-code");
    var codex = Harnesses.of("codex");
    fdes.logins().store(samId, claude, "work", "api-key", "sk-ant-api03-sam-work");
    fdes.logins().store(samId, codex, "work", "api-key", "sk-proj-sam-work");
    fdes.logins().store(samId, codex, "home", "api-key", "sk-proj-sam-home");
    fdes.logins().remove(samId, codex, "work");
    incus =
        new ScriptedShellExecutor(new ShellExec.Result(0, "[]", ""))
            .onOk(
                "incus list ^acme-try$",
                "[" + workspace("acme", "try", "Running", "sam", samId) + "]")
            .onOk(
                "incus list ^beta-dock$",
                "[" + workspace("beta", "dock", "Stopped", "sam", samId) + "]")
            .onOk(
                "incus list ^beta-tom$",
                "[" + workspace("beta", "tom", "Running", "tom", tomId) + "]")
            .onOk(
                "incus list ^acme-loose$",
                "[" + workspace("acme", "loose", "Running", null, null) + "]")
            .onOk(
                "incus list ^acme-old$",
                "[" + workspace("acme", "old", "Stopped", "zed", "fde_zed") + "]")
            .onOk(
                "incus list ^acme-reused$",
                "[" + workspace("acme", "reused", "Running", "sam", "fde_another_sam") + "]");
    var operations =
        TestControlPlane.on(
            OperationsFactory.create(db, incus, null, SyncScheduler.disabled(), SessionYield.NONE),
            db,
            tempDir,
            new SyncConfig(null, null, null, "box"));
    var auth = TestAuth.tokens(db);
    var router =
        new ApiRouter(
            operations, auth, new RateLimitGate(), new AgentLogStreamer(auth), Optional.empty());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", router);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    db.close();
  }

  private record Answer(int status, Map<String, Object> body) {
    String code() {
      return body.get("error") instanceof Map<?, ?> error ? (String) error.get("code") : null;
    }

    Map<?, ?> offer(String harness) {
      return ((List<?>) body.get("offered"))
          .stream()
              .map(Map.class::cast)
              .filter(offer -> offer.get("harness").equals(harness))
              .findFirst()
              .orElseThrow();
    }
  }

  private Answer ask(String token, String workspace) throws Exception {
    return ask(server, token, workspace);
  }

  private Answer ask(HttpServer on, String token, String workspace) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + on.getAddress().getPort()
                        + "/v1/projects/"
                        + workspace.replace("-", "/workspaces/")
                        + "/agents"))
            .header("Authorization", "Bearer " + token)
            .GET();
    var response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(response.statusCode(), YamlUtil.parseMap(response.body()));
  }

  private static Map<String, Object> refusal(String code, String message, String action) {
    var refusal = new java.util.LinkedHashMap<String, Object>();
    refusal.put("code", code);
    refusal.put("message", message);
    refusal.put("action", action);
    refusal.put("field_errors", List.of());
    return refusal;
  }

  /**
   * The owner is offered Claude Code signed in with their own default login, in its own modes, the
   * first its own start; Codex, which the project does not install, is refused as a Create of it
   * is.
   */
  @Test
  void theOwnerIsOfferedEachHarnessWithTheLoginItStartsWithOrWhyItDoesNotStart() throws Exception {
    var answer = ask(sam, "acme-try");

    assertEquals(200, answer.status(), answer.toString());
    assertEquals(
        List.of("acme", "try", "sam", true),
        List.of(
            answer.body().get("project"),
            answer.body().get("workspace"),
            answer.body().get("owner"),
            answer.body().get("running")));
    assertFalse(answer.body().containsKey("refusal"));
    var claude = answer.offer("claude-code");
    assertEquals(Map.of("name", "work", "kind", "api-key"), claude.get("login"));
    assertEquals(true, claude.get("inside"));
    assertEquals("default", claude.get("mode_default"));
    assertEquals(
        Harnesses.of("claude-code").modes().stream().map(mode -> mode.name()).toList(),
        ((List<?>) claude.get("modes"))
            .stream().map(mode -> ((Map<?, ?>) mode).get("mode")).toList());
    assertEquals(
        Map.of(
            "mode",
            "plan",
            "label",
            "Plan",
            "about",
            "Researches and proposes changes, and edits nothing until you approve the plan."),
        ((List<?>) claude.get("modes")).get(3));
    assertFalse(claude.containsKey("refusal"));
    assertFalse(claude.containsKey("login_refusal"));
    assertFalse(claude.containsKey("note"));
    assertEquals(
        List.of(
            Map.of(
                "kind",
                "api-key",
                "label",
                "API key",
                "how",
                List.of(Map.of("text", "Create an API key in the Claude Console"))),
            Map.of(
                "kind",
                "glm",
                "label",
                "GLM Coding Plan key",
                "how",
                List.of(
                    Map.of(
                        "text",
                        "Create a GLM Coding Plan API key at z.ai/manage-apikey/apikey-list, or a"
                            + " Team Plan's at"
                            + " z.ai/manage-apikey/coding-plan/team/my-plan")))),
        claude.get("kinds"),
        "the kinds in the order a person is shown them, an API key first, each a key whose how is"
            + " words");
    assertEquals(
        Harnesses.of("claude-code").signIn().toMaps(),
        claude.get("sign_in"),
        "a subscription signs in inside the harness's own session, as the harness says it");
    var codex = answer.offer("codex");
    assertEquals(
        refusal(
            "harness_not_installed",
            "Codex CLI is not installed in acme's workspaces; its agent installation lists Claude"
                + " Code.",
            "An admin adds codex to acme's agent install list and builds its template again."),
        codex.get("refusal"));
    assertEquals(false, codex.get("inside"));
    assertFalse(codex.containsKey("sign_in"), "a harness that does not start says no sign-in");
    assertFalse(codex.containsKey("login"));
    assertEquals("full-access", codex.get("mode_default"));
    assertEquals(Harnesses.of("codex").modesNote().orElseThrow(), codex.get("note"));
    assertFalse(answer.toString().contains("sk-"), "no login's value is answered");
  }

  /**
   * A stopped workspace is said to be stopped, never refused: a start starts it. A person with
   * logins of a harness but no default is told which they stored and how to choose one; one with
   * none is told how to store one; each may still start it and sign in inside.
   */
  @Test
  void aLoginNotStoredOrNotTheDefaultIsSaidWithItsRemedyAndAStoppedWorkspaceIsNoRefusal()
      throws Exception {
    var sams = ask(sam, "beta-dock");
    var toms = ask(tom, "beta-tom");

    assertEquals(200, sams.status(), sams.toString());
    assertEquals(false, sams.body().get("running"));
    var noDefault = sams.offer("codex");
    assertEquals(true, noDefault.get("inside"));
    assertFalse(noDefault.containsKey("login"));
    assertEquals(
        List.of(
            "login_not_stored",
            "sam has no default Codex CLI login, so a Codex CLI session cannot start in dock;"
                + " stored: home.",
            "Choose one: agent login use codex home."),
        said(noDefault.get("login_refusal")));
    assertEquals(Map.of("name", "work", "kind", "api-key"), sams.offer("claude-code").get("login"));
    var noneStored = (Map<?, ?>) toms.offer("claude-code").get("login_refusal");
    assertEquals(
        List.of(
            "login_not_stored",
            "No Claude Code login stored for tom, so a Claude Code session cannot start in tom."),
        said(noneStored).subList(0, 2));
    assertEquals(true, toms.offer("claude-code").get("inside"));
    assertTrue(noneStored.containsKey("remedy"), "the remedy names the command that stores one");
    assertTrue(
        ((String) noneStored.get("action")).startsWith("Create an API key in the Claude Console"),
        "the remedy names the kinds in the order the route offers them: " + noneStored);
  }

  private static List<Object> said(Object refusal) {
    var map = (Map<?, ?>) refusal;
    return List.of(map.get("code"), map.get("message"), map.get("action"));
  }

  /**
   * An admin may enter another person's workspace but starts no agent there: told so, with nothing
   * offered and nothing of its person's logins; a workspace no one owns, and one whose person is
   * gone, the same with their own words.
   */
  @Test
  void anAdminInAnotherPersonsWorkspaceIsToldItIsNotTheirsAndSeesNoneOfTheirLogins()
      throws Exception {
    var admin = ask(ada, "acme-try");
    var loose = ask(ada, "acme-loose");
    var old = ask(ada, "acme-old");

    assertEquals(200, admin.status(), admin.toString());
    assertEquals(
        refusal(
            "not_your_workspace",
            "try is sam's workspace, so an agent starts in it only as sam, with sam's login.",
            "Open a shell in it, or start the agent in a workspace of your own."),
        admin.body().get("refusal"));
    assertEquals(List.of(), admin.body().get("offered"));
    assertFalse(admin.toString().contains("api-key"), "nothing of sam's logins");
    assertEquals(
        "loose is no one's workspace, so no agent starts in it.",
        ((Map<?, ?>) loose.body().get("refusal")).get("message"));
    assertEquals(
        List.of(
            "owner_gone",
            "old is zed's workspace, and zed is no longer active here, so no agent starts in it until the box's pass leaves it nobody's.",
            "Delete it meanwhile with: project workspace delete acme old --force."),
        said(old.body().get("refusal")));
    assertEquals(List.of(), old.body().get("offered"));
  }

  /**
   * A member asking of another person's workspace is refused it, a viewer too, a workspace this box
   * does not hold is not found.
   */
  @Test
  void aMemberInAnotherPersonsWorkspaceIsRefusedAndAnAbsentOneIsNotFound() throws Exception {
    var member = ask(tom, "acme-try");
    var viewer = ask(obs, "acme-try");
    var absent = ask(sam, "acme-gone");

    assertEquals(403, member.status(), member.toString());
    assertEquals("forbidden_not_assignee", member.code());
    assertEquals(403, viewer.status(), viewer.toString());
    assertEquals(404, absent.status(), absent.toString());
    assertEquals("not_found", absent.code());
  }

  /**
   * N1 at this route: on sam's node, sam is offered agents with sam's own login in a workspace no
   * one is labelled for, and is refused, as every workspace door refuses, one labelled for another
   * person of the handle sam or for a person gone from the node, offered nothing.
   */
  @Test
  void onANodeItsPersonIsOfferedAgentsOnlyInAWorkspaceLabelledForThemByIdOrForNoOne()
      throws Exception {
    var config = new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "sam", "sam-box");
    var auth = new TokenAuth(new TokenStore(db), TestAuth.roles(db, config));
    var node = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    node.createContext(
        "/",
        new ApiRouter(
            TestControlPlane.on(
                OperationsFactory.create(
                    db, incus, null, SyncScheduler.disabled(), SessionYield.NONE),
                db,
                tempDir,
                config),
            auth,
            new RateLimitGate(),
            new AgentLogStreamer(auth),
            Optional.empty()));
    node.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    node.start();
    try {
      var loose = ask(node, sam, "acme-loose");
      var reused = ask(node, sam, "acme-reused");
      var gone = ask(node, sam, "acme-old");

      assertEquals(200, loose.status(), loose.toString());
      assertEquals("sam", loose.body().get("owner"));
      assertEquals(
          Map.of("name", "work", "kind", "api-key"), loose.offer("claude-code").get("login"));
      assertEquals(403, reused.status(), reused.toString());
      assertEquals("not_your_workspace", reused.code());
      assertEquals(
          "reused is labelled for a person this box does not know as sam, so no agent starts in"
              + " it until the box's pass leaves it nobody's.",
          ((Map<?, ?>) reused.body().get("error")).get("message"));
      assertEquals(409, gone.status(), gone.toString());
      assertEquals("owner_gone", gone.code());
      assertFalse(reused.toString().contains("api-key"), "nothing of a login is offered");
    } finally {
      node.stop(0);
    }
  }
}
