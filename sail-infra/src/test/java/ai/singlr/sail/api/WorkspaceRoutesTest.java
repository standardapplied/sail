/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
 * The four workspace routes through the real web router over the production operations (R10, the
 * app's contract): a member makes their own, and starts, stops and deletes it; a viewer none; an
 * admin anyone's; a delete of one that holds work is refused with its code until forced.
 */
class WorkspaceRoutesTest {

  private static final HttpClient CLIENT = HttpClient.newHttpClient();

  @AfterAll
  static void closeClient() {
    CLIENT.close();
  }

  private static final ProjectDefinition ACME =
      new ProjectDefinition(
          "acme",
          "",
          null,
          null,
          null,
          null,
          List.of(new ProjectDefinition.Repo("https://github.com/acme/app.git", "app", null)),
          null,
          null,
          null,
          null);

  @TempDir Path tempDir;
  private Sqlite db;
  private HttpServer server;
  private ScriptedShellExecutor incus;
  private String ada;
  private String sam;
  private String obs;
  private String samId;

  /** Workspace {@code try} of acme, sam's by his id, as Incus lists it in {@code state}. */
  private String trying(String state) {
    return """
        {"name": "acme-try", "status": "%s", "type": "container",
         "config": {"user.sail.project": "acme", "user.sail.workspace": "try",
                    "user.sail.ready": "1762941601000", "user.sail.owner": "sam",
                    "user.sail.owner_id": "%s"},
         "expanded_devices": {"root": {"path": "/", "pool": "tank", "type": "disk"}}}"""
        .formatted(state, samId);
  }

  @BeforeEach
  void aBoxWithAnAdminAMemberAViewerAndSamsWorkspace() throws Exception {
    db = Sqlite.open(tempDir.resolve("main.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    HeldProjects.hold(db, "beta");
    Acting.system(
        () -> {
          new ProjectStore(db).upsert(ACME);
          var fdes = new FdeStore(db);
          var tokens = new TokenStore(db);
          ada =
              tokens
                  .create("ada", "admin", fdes.add("ada", "Ada", "ada@x", "admin").id(), null)
                  .token();
          samId = fdes.add("sam", "Sam", "sam@x", "member").id();
          sam = tokens.create("sam", "member", samId, null).token();
          obs =
              tokens
                  .create("obs", "viewer", fdes.add("obs", "Obs", "obs@x", "viewer").id(), null)
                  .token();
        });
    var template = TestWorkspaces.currentTemplate(project -> Optional.of(ACME), "acme");
    var fix =
        trying("Stopped")
            .replace("acme-try", "acme-fix")
            .replace("\"try\"", "\"fix\", \"user.sail.spec\": \"fix\"");
    incus =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("incus storage list --format json", TestWorkspaces.POOL)
            .onOk("incus snapshot list acme--tmpl --format json", TestWorkspaces.V1)
            .onOk("incus list ^acme-try$", "[" + trying("Running") + "]")
            .onOk("incus list ^acme-fix$", "[" + fix + "]")
            .onOk("incus list ^acme-new$", "[]")
            .onOk(
                "incus list --format json",
                "[" + template + "," + trying("Running") + "," + fix + "]")
            .onThrow("incus start acme-fix", new IOException("Error: Failed to start device"))
            .onOk("sail-left", "changes app\n");
    var operations =
        TestControlPlane.on(
            OperationsFactory.create(db, incus, null, SyncScheduler.disabled(), SessionYield.NONE),
            db,
            tempDir,
            new SyncConfig(null, null, null, "box"));
    var auth = TestAuth.tokens(db);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        new ApiRouter(
            operations, auth, new RateLimitGate(), new AgentLogStreamer(auth), Optional.empty()));
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
  }

  private Answer ask(String token, String method, String path, String json) throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
    var response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    return new Answer(response.statusCode(), YamlUtil.parseMap(response.body()));
  }

  /** Scenario 30 through the API: a member makes their own workspace, owned by them. */
  @Test
  void aMemberMakesTheirOwnWorkspaceAndAViewerNone() throws Exception {
    var made = ask(sam, "POST", "/v1/projects/acme/workspaces", "{\"name\": \"new\"}");
    var viewer = ask(obs, "POST", "/v1/projects/acme/workspaces", "{\"name\": \"new\"}");
    var nameless = ask(sam, "POST", "/v1/projects/acme/workspaces", "{\"name\": 5}");
    var untemplated = ask(sam, "POST", "/v1/projects/beta/workspaces", "{\"name\": \"new\"}");

    assertEquals(201, made.status(), made.toString());
    assertEquals(
        List.of("acme", "new", "acme-new", "sam", "v1", false),
        List.of(
            made.body().get("project"),
            made.body().get("workspace"),
            made.body().get("instance"),
            made.body().get("owner"),
            made.body().get("template"),
            made.body().get("stale")));
    assertEquals("read_only_credential", viewer.code());
    assertEquals("invalid_request", nameless.code());
    assertEquals(409, untemplated.status());
    assertEquals("conflict", untemplated.code());
  }

  /**
   * Scenarios 30, 56: start and stop are the owner's or an admin's; Incus failing is said so, and a
   * spec's workspace with no live run of it stops.
   */
  @Test
  void startAndStopAreTheOwnersOrAnAdminsAndIncusFailingIsAContainerError() throws Exception {
    var started = ask(sam, "POST", "/v1/projects/acme/workspaces/try/start", null);
    var stopped = ask(ada, "POST", "/v1/projects/acme/workspaces/try/stop", null);
    var viewer = ask(obs, "POST", "/v1/projects/acme/workspaces/try/start", null);
    var absent = ask(ada, "POST", "/v1/projects/acme/workspaces/new/start", null);
    var failed = ask(ada, "POST", "/v1/projects/acme/workspaces/fix/start", null);
    var specs = ask(ada, "POST", "/v1/projects/acme/workspaces/fix/stop", null);

    assertEquals(200, started.status());
    assertEquals("already_running", started.body().get("change"));
    assertEquals("stopped", stopped.body().get("change"));
    assertEquals("read_only_credential", viewer.code());
    assertEquals("not_found", absent.code());
    assertEquals(
        List.of(500, "container_error"),
        List.of(failed.status(), failed.code()),
        failed.toString());
    assertEquals("already_stopped", specs.body().get("change"), "no run of its spec is live in it");
  }

  /** Ruling 3 through the API: a delete of a workspace that holds work waits for force. */
  @Test
  void aDeleteOfAWorkspaceThatHoldsWorkIsRefusedUntilForced() throws Exception {
    var refused = ask(sam, "DELETE", "/v1/projects/acme/workspaces/try", null);
    var forced = ask(sam, "DELETE", "/v1/projects/acme/workspaces/try?force=true", null);

    assertEquals(409, refused.status());
    assertEquals("workspace_holds_work", refused.code());
    var forcing =
        Map.of(
            "run",
            "sail",
            "verb",
            "project workspace delete",
            "args",
            List.of("acme", "try", "--force"));
    var remedy = ((Map<?, ?>) refused.body().get("error")).get("remedy");
    assertTrue(
        remedy instanceof List<?> parts && parts.contains(forcing),
        "the fix to force names the command, not an HTTP call: " + remedy);
    assertEquals(200, forced.status());
    assertEquals("deleted", forced.body().get("change"));
    assertTrue(incus.invocations().stream().anyMatch(c -> c.startsWith("incus delete")));
  }
}
