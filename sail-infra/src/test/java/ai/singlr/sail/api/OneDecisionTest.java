/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.HostAccess;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.sync.FdeRoster;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncRpcServer;
import ai.singlr.sail.sync.SyncSession;
import ai.singlr.sail.sync.SyncTransitionSink;
import ai.singlr.sail.sync.SyncWire;
import ai.singlr.sail.sync.SyncedEntities;
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

/**
 * One decision at the door and on main: Ada editing Bob's spec, or posting in Bob's room, is
 * refused with the same kind and the same words through HTTP with a token and with a passkey
 * session, the host CLI, the socket with her box credential, her agent's run and her room run, the
 * terminal door, and main's commit of the same edit pushed from her box — because every one of them
 * asks the one rule.
 */
class OneDecisionTest {

  private static final SyncConfig MAIN = new SyncConfig("main", null, "root", "main-box");
  private static final SyncConfig ADAS_NODE = new SyncConfig("node", "sail@main", "ada", "ada");
  private static final String NOT_ADAS_SPEC = "Spec 'theirs' is assigned to 'bob', not you.";
  private static final String NOT_ADAS_ROOM =
      "'den' belongs to bob: only bob or an admin may post there, not you.";

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
  private SailOperations operations;
  private SailApiServer server;
  private String token;
  private String session;
  private String boxCredential;
  private String agentRun;
  private String roomRun;

  @BeforeEach
  void setUp() throws IOException {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    var fdes = new FdeStore(db);
    fdes.add("root", null, null, "admin");
    var ada = fdes.add("ada", null, null, "member");
    fdes.add("bob", null, null, "member");
    token = new TokenStore(db).create("ada", "member", ada.id(), null).token();
    session = new AuthSessionStore(db).create(ada.id(), Duration.ofHours(1)).token();
    boxCredential = new BoxCredentialStore(db).replace("ada");
    Acting.as(
        "bob",
        () -> {
          new SpecStore(db).create(spec("theirs", "bob"));
          new RoomStore(db).create(room("theirs", "bob"));
          new RoomStore(db).create(room("den", "bob"));
        });
    Acting.as("ada", () -> new SpecStore(db).create(spec("mine", "ada")));
    agentRun = reserve("mine", null, "build");
    roomRun = reserve(null, "den", DispatchGate.ROOM_ROLE);
    operations =
        OperationsFactory.create(
                db, SHELL, "sail.yaml", null, null, SyncScheduler.disabled(), SessionYield.NONE)
            .useControlPlane(
                db,
                tempDir,
                new SyncOperations(
                    db,
                    "box",
                    tempDir,
                    () -> MAIN,
                    target -> {
                      throw new IOException("main unavailable");
                    }));
    server =
        new SailApiServer(
            "127.0.0.1",
            0,
            operations,
            TestAuth.sessions(db, MAIN),
            new EventBus(),
            null,
            null,
            null);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.close();
    operations.close();
    db.close();
  }

  private static RoomStore.RoomRow room(String id, String owner) {
    return new RoomStore.RoomRow(id, "acme", id, owner, null, null, null, null, null, null);
  }

  private static SpecStore.SpecRow spec(String id, String assignee) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        id,
        SpecStatus.PENDING,
        assignee,
        null,
        null,
        null,
        null,
        0,
        null,
        "",
        "",
        null,
        List.of(),
        List.of());
  }

  @Test
  void editingAnotherMembersSpecIsRefusedAlikeThroughEveryDoorAndOnMain() throws Exception {
    assertRefusedOverHttp(
        http("PUT", "/v1/specs/theirs", token, "{\"title\":\"x\"}"), NOT_ADAS_SPEC);
    assertRefusedOverHttp(
        http("PUT", "/v1/specs/theirs", session, "{\"title\":\"x\"}"), NOT_ADAS_SPEC);
    var cli =
        Actor.call(
            CliOperator.of(ADAS_NODE, new FdeStore(db)),
            () -> operations.updateGlobalSpec("theirs", retitle()));
    var failure = assertInstanceOf(Result.Failure.class, cli, "the host CLI");
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, failure.errorCode());
    assertEquals(NOT_ADAS_SPEC, failure.errorMessage());
    for (var credential : List.of(boxCredential, agentRun)) {
      assertRefusedOnSocket(
          socket("PUT", "/v1/specs/theirs", credential, "title=x"),
          "forbidden_not_assignee",
          NOT_ADAS_SPEC);
    }
    assertRefusedOnSocket(
        socket("PUT", "/v1/specs/theirs", roomRun, "title=x"),
        "read_only_credential",
        "Your credential is read-only and cannot change specs.");

    try (var node = new SyncBox("ada")) {
      pull(node);
      var row = node.specs.findById("theirs").orElseThrow();
      Acting.as(
          "ada",
          () ->
              node.specs.update(
                  new SpecStore.SpecRow(
                      row.id(),
                      row.project(),
                      "x",
                      row.status(),
                      row.assignee(),
                      null,
                      null,
                      null,
                      null,
                      0,
                      row.createdBy(),
                      row.createdAt(),
                      row.updatedAt(),
                      null,
                      List.of(),
                      List.of(),
                      row.roomId())));

      assertEquals(List.of(NOT_ADAS_SPEC), reasons(push(node, "spec")), "main's commit");
    }
    assertEquals("theirs", new SpecStore(db).findById("theirs").orElseThrow().title());
  }

  @Test
  void postingInAnotherMembersRoomIsRefusedAlikeThroughEveryDoorAndOnMain() throws Exception {
    assertRefusedOverHttp(
        http("POST", "/v1/rooms/den/messages", token, "{\"body\":\"hi\"}"), NOT_ADAS_ROOM);
    assertRefusedOverHttp(
        http("POST", "/v1/rooms/den/messages", session, "{\"body\":\"hi\"}"), NOT_ADAS_ROOM);
    var cli =
        Actor.call(
            CliOperator.of(ADAS_NODE, new FdeStore(db)),
            () ->
                operations.postRoomMessage(
                    "den", new SpecMessageRequest("hi", null, false), "ada"));
    var failure = assertInstanceOf(Result.Failure.class, cli, "the host CLI");
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, failure.errorCode());
    assertEquals(NOT_ADAS_ROOM, failure.errorMessage());
    for (var credential : List.of(boxCredential, agentRun, roomRun)) {
      assertRefusedOnSocket(
          socket("POST", "/v1/specs/den/messages", credential, "body=hi"),
          "forbidden_not_assignee",
          NOT_ADAS_ROOM);
    }
    var access = new HostAccess(db, TestAuth.roles(db, MAIN));
    var terminal =
        assertThrows(
            IOException.class, () -> access.admit("den", "acme", access.identity(session, null)));
    assertTrue(terminal.getMessage().startsWith(NOT_ADAS_ROOM), terminal.getMessage());

    try (var node = new SyncBox("ada")) {
      pull(node);
      Acting.as("ada", () -> new MessageStore(node.db).append("den", "ada", "hi", null));

      assertEquals(List.of(NOT_ADAS_ROOM), reasons(push(node, "message")), "main's commit");
    }
    assertEquals(List.of(), new MessageStore(db).list("den", null, 10));
  }

  private static SpecUpdateRequest retitle() {
    return new SpecUpdateRequest(
        null, "x", null, null, null, null, null, null, null, null, null, null, false);
  }

  private void pull(SyncBox node) throws IOException {
    for (var entity : SyncedEntities.all()) {
      push(node, entity.type());
    }
  }

  private SyncSession.TypeReport push(SyncBox node, String type) throws IOException {
    var server =
        SyncRpcServer.over(
            db,
            "main",
            null,
            Actor.sync("ada", Role.MEMBER),
            FdeRoster.EMPTY,
            SyncTransitionSink.NONE,
            SyncWire.UPGRADE_FLOOR);
    try (var link = SyncBox.connect(server, node)) {
      return link.reconcile(type, SyncedEntities.replicas(node.db, node.id, node.id).get(type));
    }
  }

  private static List<String> reasons(SyncSession.TypeReport report) {
    return report.denials().stream().map(SyncSession.Denial::reason).toList();
  }

  private static void assertRefusedOnSocket(ApiResponse response, String code, String message) {
    assertEquals(403, response.status(), response.body().toString());
    var error = assertInstanceOf(Map.class, response.body().get("error"));
    assertEquals(code, error.get("code"));
    assertEquals(message, error.get("message"));
  }

  private static void assertRefusedOverHttp(HttpResponse<String> response, String message) {
    assertEquals(403, response.statusCode(), response.body());
    assertTrue(response.body().contains("\"forbidden_not_assignee\""), response.body());
    assertTrue(response.body().contains(message), response.body());
  }

  private HttpResponse<String> http(String method, String path, String credential, String json)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Authorization", "Bearer " + credential)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(json))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private ApiResponse socket(String method, String path, String credential, String form) {
    return new LocalApiRouter(new EventBus(), operations)
        .handle(
            new LocalApiRequest(
                method,
                path,
                Map.of(),
                Map.of("authorization", "Bearer " + credential),
                form.getBytes(StandardCharsets.UTF_8)));
  }

  private String reserve(String specId, String roomId, String role) {
    var id = DateTimeUtils.newId().toString();
    var reservation =
        Acting.system(
            () ->
                new RunStore(db)
                    .reserveDispatch(
                        id,
                        "acme",
                        specId,
                        roomId,
                        "ada",
                        role,
                        List.of(),
                        "claude-code",
                        "feat/x",
                        "do it",
                        "/tmp/" + id + ".log",
                        "sail-agent-" + id,
                        Duration.ofHours(1)));
    return ((RunStore.Reservation.Reserved) reservation).credential();
  }
}
