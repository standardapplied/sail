/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.HostAccess;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
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

/**
 * A node's writes speak for its FDE. On Ada's node, Raj — an admin on main — reads through every
 * door and writes through none: his API token, his passkey session, a session his gateway key
 * mints, his box credential, a run acting for him and his terminal. Every door but the terminal,
 * which keeps its own text, answers the spec rule's one refusal — the same kind, code, message and
 * fix — and so does a room run of Ada's own. Ada, the runs acting for her, and a credential naming
 * no FDE write as before.
 */
class NodeWritesTest {

  private static final SyncConfig ADAS_NODE = new SyncConfig("node", "sail@main", "ada", "ada-box");
  private static final String CREATE = "{\"id\":\"%s\",\"project\":\"acme\",\"title\":\"T\"}";

  /** The spec rule's read-only refusal as every door's envelope carries it: one kind, one code. */
  private static final Map<String, Object> READ_ONLY =
      Map.of(
          "code",
          "read_only_credential",
          "message",
          "Your credential is read-only and cannot change specs.",
          "action",
          "Ask an admin for a member or admin credential.",
          "field_errors",
          List.of());

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
  private int created;

  @BeforeEach
  void setUp() throws IOException {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    var fdes = new FdeStore(db);
    fdes.add("ada", null, null, "member");
    fdes.add("raj", null, null, "admin");
    room("rajs", "raj");
    room("adas", "ada");
    operations =
        OperationsFactory.create(db, SHELL, null, null, SyncScheduler.disabled(), SessionYield.NONE)
            .useControlPlane(
                db,
                tempDir,
                new SyncOperations(
                    db,
                    "ada-box",
                    tempDir,
                    () -> ADAS_NODE,
                    target -> {
                      throw new IOException("main unavailable");
                    }));
    server =
        new SailApiServer(
            "127.0.0.1",
            0,
            operations,
            TestAuth.sessions(db, ADAS_NODE),
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

  @Test
  void anotherFdeReadsThroughEveryDoorOfTheNodeAndWritesThroughNone() throws Exception {
    var raj = new FdeStore(db).byHandle("raj").orElseThrow();
    var token = new TokenStore(db).create("raj", "admin", raj.id(), null).token();
    var session = new AuthSessionStore(db).create(raj.id(), Duration.ofHours(1)).token();
    var gateway =
        assertInstanceOf(
            SshGateway.Authorized.class,
            SshGateway.authorize(
                "sail spec create gated --title T --project acme",
                "raj",
                new FdeStore(db),
                TestAuth.roles(db, ADAS_NODE),
                new AuthSessionStore(db)));
    var boxCredential = new BoxCredentialStore(db).replace("raj");

    for (var credential : List.of(token, session, gateway.sessionToken())) {
      assertEquals(200, http("GET", "/v1/specs", credential, "").statusCode());
      var write = http("POST", "/v1/specs", credential, CREATE.formatted(nextId()));
      assertEquals(403, write.statusCode(), write.body());
      assertEquals(READ_ONLY, YamlUtil.parseMap(write.body()).get("error"), "over HTTP");
    }
    for (var credential : List.of(boxCredential, reserveRun("raj", "build"))) {
      assertEquals(200, socket("GET", "/v1/specs", credential, "").status());
      var write = socket("POST", "/v1/specs", credential, form());
      assertEquals(403, write.status(), write.body().toString());
      assertEquals(READ_ONLY, write.body().get("error"), "the same refusal on the socket");
    }
    var roomRun = socket("POST", "/v1/specs", reserveRun("ada", "room"), form());
    assertEquals(403, roomRun.status(), roomRun.body().toString());
    assertEquals(READ_ONLY, roomRun.body().get("error"), "and for a room run of the box's own FDE");
    var access = new HostAccess(db, TestAuth.roles(db, ADAS_NODE));
    var identity = access.identity(session, null);
    assertFalse(identity.admin(), "the terminal names no admin");
    var terminal = assertThrows(IOException.class, () -> access.admit("rajs", "acme", identity));
    assertTrue(terminal.getMessage().contains("read-only"), terminal.getMessage());
    assertEquals(0, count(), "nothing was written");
  }

  @Test
  void theBoxsFdeItsRunsAndACredentialNamingNoFdeWriteAsBefore() throws Exception {
    var ada = new FdeStore(db).byHandle("ada").orElseThrow();
    var token = new TokenStore(db).create("ada", "member", ada.id(), null).token();
    var session = new AuthSessionStore(db).create(ada.id(), Duration.ofHours(1)).token();
    var machine = new TokenStore(db).create("ci", "member", null, null).token();

    for (var credential : List.of(token, session, machine)) {
      var write = http("POST", "/v1/specs", credential, CREATE.formatted(nextId()));
      assertEquals(201, write.statusCode(), write.body());
    }
    assertEquals(
        201,
        socket("POST", "/v1/specs", new BoxCredentialStore(db).replace("ada"), form()).status());
    assertEquals(201, socket("POST", "/v1/specs", reserveRun("ada", "build"), form()).status());
    var access = new HostAccess(db, TestAuth.roles(db, ADAS_NODE));
    access.admit("adas", "acme", access.identity(null, "ada"));
    assertEquals(5, count());
  }

  private void room(String id, String owner) {
    Acting.as(
        owner,
        () ->
            new RoomStore(db)
                .create(
                    new RoomStore.RoomRow(
                        id, "acme", id, owner, null, null, null, null, null, null)));
  }

  private String nextId() {
    return "spec-" + ++created;
  }

  private String form() {
    return "id=" + nextId() + "&project=acme&title=T";
  }

  private long count() {
    return db.queryOne("SELECT count(*) FROM specs", row -> row.integer(0)).orElseThrow();
  }

  private HttpResponse<String> http(String method, String path, String credential, String json)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Authorization", "Bearer " + credential)
            .header("Content-Type", "application/json")
            .method(
                method,
                json.isEmpty()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private ApiResponse socket(String method, String path, String credential, String form) {
    return new LocalApiRouter(operations)
        .handle(
            new LocalApiRequest(
                method,
                path,
                Map.of(),
                Map.of("authorization", "Bearer " + credential),
                form.getBytes(StandardCharsets.UTF_8)));
  }

  private String reserveRun(String boxHandle, String role) {
    var id = DateTimeUtils.newId().toString();
    var reservation =
        Acting.system(
            () ->
                new RunStore(db)
                    .reserveDispatch(
                        id,
                        "acme",
                        "seed-" + id,
                        boxHandle,
                        role,
                        List.of(),
                        "claude-code",
                        "feat/x",
                        "do it",
                        "/tmp/" + id + ".log",
                        "sail-agent-" + id));
    return ((RunStore.Reservation.Reserved) reservation).credential();
  }
}
