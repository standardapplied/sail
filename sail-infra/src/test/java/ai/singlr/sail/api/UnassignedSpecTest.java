/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.HostAccess;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * A spec created with no assignee stays unassigned, through every door: HTTP, the CLI, and the
 * socket with a run credential or the box credential. It is its creator's — the acting FDE, never a
 * run's principal — to edit until someone claims it, and any member may claim it.
 */
class UnassignedSpecTest {

  private static final ShellExec SHELL =
      new ShellExec() {
        public Result exec(List<String> command) {
          return new Result(0, "[]", "");
        }

        public Result exec(List<String> command, Path workDir, Duration timeout) {
          return exec(command);
        }

        public boolean isDryRun() {
          return false;
        }
      };

  @TempDir Path tempDir;
  private Sqlite db;
  private SpecStore specs;
  private SailOperations operations;
  private SailApiServer server;

  @BeforeEach
  void setUp() throws IOException {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    specs = new SpecStore(db);
    operations =
        OperationsFactory.create(db, SHELL, null, null, SyncScheduler.disabled(), SessionYield.NONE)
            .useControlPlane(
                db,
                tempDir,
                new SyncOperations(
                    db,
                    "box",
                    tempDir,
                    SyncConfig::unset,
                    target -> {
                      throw new IOException("main unavailable");
                    }));
    var auth = TestAuth.sessions(db);
    server = new SailApiServer("127.0.0.1", 0, operations, auth, new EventBus(), null, null, null);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.close();
    operations.close();
    db.close();
  }

  @Test
  void overHttpItIsItsCreatorsToEditUntilAnotherMemberClaimsIt() throws Exception {
    var uday = token("uday", "member");
    var mady = token("mady", "member");

    var created =
        send(
            "POST",
            "/v1/specs",
            uday,
            "{\"id\": \"auth\", \"project\": \"acme\", \"title\": \"A\"}");
    assertEquals(201, created.statusCode(), created.body());
    assertUnassignedAndCreatedBy("auth", "uday");

    assertEquals(403, send("PUT", "/v1/specs/auth", mady, "{\"title\": \"M\"}").statusCode());
    assertEquals(200, send("PUT", "/v1/specs/auth", uday, "{\"title\": \"U\"}").statusCode());

    var claimed = send("PUT", "/v1/specs/auth", mady, "{\"assignee\": \"mady\"}");
    assertEquals(200, claimed.statusCode(), claimed.body());
    assertEquals("mady", specs.findById("auth").orElseThrow().assignee());
    assertEquals(403, send("PUT", "/v1/specs/auth", uday, "{\"title\": \"X\"}").statusCode());
  }

  @Test
  void onceClaimedItsRoomIsTheNewOwnersAtEveryDoor() throws Exception {
    var uday = token("uday", "member");
    var mady = token("mady", "member");
    send("POST", "/v1/specs", uday, "{\"id\": \"auth\", \"project\": \"acme\", \"title\": \"A\"}");
    assertEquals(200, send("PUT", "/v1/specs/auth", mady, "{\"assignee\": \"mady\"}").statusCode());

    var byOwner = send("POST", "/v1/rooms/auth/messages", mady, "{\"body\": \"mine now\"}");
    var byCreator = send("POST", "/v1/rooms/auth/messages", uday, "{\"body\": \"still?\"}");

    assertEquals(201, byOwner.statusCode(), byOwner.body());
    assertEquals(403, byCreator.statusCode(), byCreator.body());
    assertTrue(byCreator.body().contains("only mady"), byCreator.body());
    var room = roomRunCredential("mady", "auth");
    var router = new LocalApiRouter(operations);
    var answered = router.handle(form("POST", "/v1/specs/auth/messages", room, "body=on it"));
    assertEquals(201, answered.status(), answered.body().toString());
    var terminal = new HostAccess(db, new RoleRule(SyncConfig::unset, new FdeStore(db)));
    terminal.admit("auth", "acme", new PtyIdentity("mady", false));
    var refused =
        assertThrows(
            IOException.class,
            () -> terminal.admit("auth", "acme", new PtyIdentity("uday", false)));
    assertTrue(refused.getMessage().contains("only mady"), refused.getMessage());
    assertEquals(List.of("mady"), new RoomStore(db).owners("auth"), "and main decides the same");
  }

  @Test
  void aSpecBornInSomeoneElsesRoomIsClaimedOnlyByOneWhoMayPostThere() throws Exception {
    var uday = token("uday", "member");
    var carol = token("carol", "member");
    var room = "{\"id\": \"design\", \"project\": \"acme\", \"title\": \"D\"}";
    assertEquals(201, send("POST", "/v1/rooms", uday, room).statusCode());
    var born =
        "{\"id\": \"auth\", \"project\": \"acme\", \"title\": \"A\", \"room_id\": \"design\"}";
    assertEquals(201, send("POST", "/v1/specs", uday, born).statusCode());

    var claim = send("PUT", "/v1/specs/auth", carol, "{\"assignee\": \"carol\"}");

    assertEquals(403, claim.statusCode(), claim.body());
    assertTrue(claim.body().contains("Ask an admin"), claim.body());
    assertEquals(
        403, send("POST", "/v1/rooms/design/messages", carol, "{\"body\": \"hi\"}").statusCode());
    var given = send("PUT", "/v1/specs/auth", uday, "{\"assignee\": \"carol\"}");
    assertEquals(403, given.statusCode(), "handing it on is an admin's act");
    var admin = token("ops", "admin");
    assertEquals(
        200, send("PUT", "/v1/specs/auth", admin, "{\"assignee\": \"carol\"}").statusCode());
    var delegated = send("POST", "/v1/rooms/design/messages", carol, "{\"body\": \"on it\"}");
    assertEquals(201, delegated.statusCode(), delegated.body());
  }

  @Test
  void aSpecIsBornInARoomForSomeoneElseOnlyByAnAdmin() throws Exception {
    var uday = token("uday", "member");
    var room = "{\"id\": \"design\", \"project\": \"acme\", \"title\": \"D\"}";
    assertEquals(201, send("POST", "/v1/rooms", uday, room).statusCode());

    var forCarol =
        send(
            "POST",
            "/v1/specs",
            uday,
            "{\"id\": \"auth\", \"project\": \"acme\", \"title\": \"A\", \"room_id\":"
                + " \"design\", \"assignee\": \"carol\"}");
    var forHimself =
        send(
            "POST",
            "/v1/specs",
            uday,
            "{\"id\": \"login\", \"project\": \"acme\", \"title\": \"L\", \"room_id\":"
                + " \"design\", \"assignee\": \"uday\"}");

    assertEquals(403, forCarol.statusCode(), forCarol.body());
    assertTrue(specs.findById("auth").isEmpty(), "a refused birth leaves nothing behind");
    assertEquals(201, forHimself.statusCode(), forHimself.body());
    assertEquals(
        403,
        send("POST", "/v1/rooms/design/messages", token("carol", "member"), "{\"body\": \"x\"}")
            .statusCode());
  }

  @Test
  void aPostAboutABornInSpecWhoseRoomHasNotArrivedLandsInThatRoom() throws Exception {
    db.execute(
        """
        INSERT INTO specs
            (id, title, project, assignee, created_by, created_at, updated_at, room_id)
        VALUES ('auth', 'A', 'acme', 'uday', 'uday', 'now', 'now', 'design')""");

    var posted =
        send("POST", "/v1/rooms/auth/messages", token("uday", "member"), "{\"body\": \"hi\"}");

    assertEquals(201, posted.statusCode(), posted.body());
    assertEquals(1, new MessageStore(db).list("design", null, 10).size());
    assertTrue(new MessageStore(db).list("auth", null, 10).isEmpty());
  }

  @Test
  void theCliCreatesItUnassigned() throws Exception {
    var uday = token("uday", "member");

    var exit =
        new CommandLine(new Sail())
            .execute(
                "spec",
                "create",
                "--server",
                "http://127.0.0.1:" + server.port(),
                "--token",
                uday,
                "--no-sync",
                "--project",
                "acme",
                "--id",
                "auth",
                "--title",
                "Auth");

    assertEquals(0, exit);
    assertUnassignedAndCreatedBy("auth", "uday");
  }

  @Test
  void aRunCreatesItForTheFdeItActsForAndClaimsItForThatFde() {
    new FdeStore(db).add("uday", null, null, "member");
    var credential = runCredential("uday");
    var router = new LocalApiRouter(operations);

    var created =
        router.handle(form("POST", "/v1/specs", credential, "id=draft&title=Draft&project=acme"));
    assertEquals(201, created.status(), created.body().toString());
    assertUnassignedAndCreatedBy("draft", "uday");

    var principal = new RunStore(db).findByCredential(credential).orElseThrow().principal();
    var asPrincipal =
        router.handle(form("PUT", "/v1/specs/draft", credential, "assignee=" + principal));
    assertEquals(422, asPrincipal.status(), "a run's principal is never an assignee");

    var claimed = router.handle(form("PUT", "/v1/specs/draft", credential, "assignee=uday"));
    assertEquals(200, claimed.status(), claimed.body().toString());
    assertEquals("uday", specs.findById("draft").orElseThrow().assignee());
  }

  @Test
  void theBoxCredentialCreatesItForTheBoxFde() {
    new FdeStore(db).add("uday", null, null, "member");
    var credential = new BoxCredentialStore(db).replace("uday");
    var router = new LocalApiRouter(operations);

    var created =
        router.handle(form("POST", "/v1/specs", credential, "id=draft&title=Draft&project=acme"));

    assertEquals(201, created.status(), created.body().toString());
    assertUnassignedAndCreatedBy("draft", "uday");
  }

  @Test
  void anAssigneeShapedLikeARunsPrincipalIsRefused() throws Exception {
    var uday = token("uday", "admin");

    var created =
        send(
            "POST",
            "/v1/specs",
            uday,
            "{\"id\": \"auth\", \"project\": \"acme\", \"title\": \"A\", \"assignee\": \"claude/r1\"}");
    assertEquals(422, created.statusCode(), created.body());
    assertTrue(created.body().contains("--agent"), created.body());

    var unknown =
        send(
            "POST",
            "/v1/specs",
            uday,
            "{\"id\": \"auth\", \"project\": \"acme\", \"title\": \"A\", \"assignee\": \"newhire\"}");
    assertEquals(201, unknown.statusCode(), "an FDE the roster does not know yet is accepted");
    assertEquals(
        422, send("PUT", "/v1/specs/auth", uday, "{\"assignee\": \"codex/r2\"}").statusCode());
  }

  private void assertUnassignedAndCreatedBy(String id, String creator) {
    var spec = specs.findById(id).orElseThrow();
    assertNull(spec.assignee(), "a spec left blank is unassigned");
    assertEquals(creator, spec.createdBy());
    var room = new RoomStore(db).findById(id).orElseThrow();
    assertNull(room.assignee(), "and so is its identity room");
    assertEquals(creator, room.createdBy());
  }

  private String runCredential(String owner) {
    var id = "019fee00-0000-7000-8000-00000000aa01";
    var reservation =
        Acting.system(
            () ->
                new RunStore(db)
                    .reserveDispatch(
                        id,
                        "acme",
                        "seed",
                        owner,
                        "build",
                        List.of("app"),
                        "claude-code",
                        "feat/x",
                        "do it",
                        "/tmp/" + id + ".log",
                        "sail-agent-" + id));
    return ((RunStore.Reservation.Reserved) reservation).credential();
  }

  private String roomRunCredential(String owner, String specId) {
    var id = "019fee00-0000-7000-8000-00000000aa02";
    var reservation =
        Acting.system(
            () ->
                new RunStore(db)
                    .reserveDispatch(
                        id,
                        "acme",
                        specId,
                        owner,
                        "room",
                        List.of(),
                        "claude-code",
                        null,
                        "answer the room",
                        "/tmp/" + id + ".log",
                        "sail-agent-" + id));
    return ((RunStore.Reservation.Reserved) reservation).credential();
  }

  private String token(String handle, String role) {
    var fde = new FdeStore(db).add(handle, null, null, role);
    return new TokenStore(db).create(handle, role, fde.id(), null).token();
  }

  private HttpResponse<String> send(String method, String path, String token, String body)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private static LocalApiRequest form(String method, String path, String credential, String body) {
    return new LocalApiRequest(
        method,
        path,
        Map.of(),
        Map.of("authorization", "Bearer " + credential),
        body.getBytes(StandardCharsets.UTF_8));
  }
}
