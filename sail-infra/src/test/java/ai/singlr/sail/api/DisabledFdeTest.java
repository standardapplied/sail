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
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.FdeStore;
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

/**
 * A disabled FDE's credential is refused through every door: an API token, a passkey session, the
 * box credential on the socket, the host CLI, the SSH gateway and the terminal. The sync session is
 * {@code SyncServerCommandTest}'s.
 */
class DisabledFdeTest {

  private static final SyncConfig MAIN = new SyncConfig("main", null, "mady", "main-box");

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
  private String runCredential;

  @BeforeEach
  void setUp() throws IOException {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    var mady = new FdeStore(db).add("mady", null, null, "admin");
    token = new TokenStore(db).create("mady", "admin", mady.id(), null).token();
    session = new AuthSessionStore(db).create(mady.id(), Duration.ofHours(1)).token();
    boxCredential = new BoxCredentialStore(db).replace("mady");
    runCredential = reserveRun("mady", "acme", "seed");
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

  @Test
  void everyDoorAdmitsTheFdeWhileItIsActive() throws Exception {
    assertEquals(200, get(token).statusCode());
    assertEquals(200, get(session).statusCode());
    assertEquals(200, socket().status());
    assertEquals(200, socket(runCredential).status());
    assertEquals("mady", CliOperator.of(MAIN, new FdeStore(db)).handle());
    assertInstanceOf(SshGateway.Authorized.class, gateway());
    assertEquals(
        "mady", new HostAccess(db, TestAuth.roles(db, MAIN)).identity(session, null).fde());
  }

  @Test
  void everyDoorRefusesTheFdeOnceItIsDisabled() throws Exception {
    db.execute("UPDATE fdes SET status = 'disabled' WHERE handle = 'mady'");

    assertEquals(403, get(token).statusCode(), "an API token");
    assertEquals(403, get(session).statusCode(), "a passkey session");
    assertEquals(401, socket().status(), "the box credential on the socket");
    var run = socket(runCredential);
    assertEquals(403, run.status(), "a run acting for the FDE");
    assertTrue(run.body().toString().contains("'mady'"), run.body().toString());
    var cli = assertThrows(ApiException.class, () -> CliOperator.of(MAIN, new FdeStore(db)));
    assertEquals(ErrorCode.CONFLICT, cli.failure().errorCode(), "the host CLI");
    assertInstanceOf(SshGateway.Rejected.class, gateway(), "the SSH gateway");
    var terminal =
        assertThrows(
            IOException.class,
            () -> new HostAccess(db, TestAuth.roles(db, MAIN)).identity(session, null));
    assertTrue(terminal.getMessage().contains("disabled"), "the terminal");
  }

  @Test
  void aRunActsWithTheRoleItsFdeHoldsNowCappedByItsLane() throws Exception {
    new FdeStore(db).add("raj", null, null, "admin");
    var credential = reserveRun("raj", "beta", "auth");
    Acting.as(
        "raj",
        () ->
            new SpecStore(db)
                .create(
                    new SpecStore.SpecRow(
                        "auth",
                        "acme",
                        "Auth",
                        SpecStatus.PENDING,
                        "raj",
                        null,
                        null,
                        null,
                        null,
                        0,
                        "raj",
                        null,
                        null,
                        "raj",
                        List.of(),
                        List.of())));

    assertEquals(
        200,
        socket("PUT", "/v1/specs/auth", credential, "priority=3").status(),
        "an admin's agent writes as a member");
    new FdeStore(db).update("raj", null, null, "viewer");

    assertEquals(200, socket(credential).status(), "a demoted FDE's agent still reads");
    assertEquals(
        403,
        socket("PUT", "/v1/specs/auth", credential, "priority=4").status(),
        "and writes no longer");
  }

  private HttpResponse<String> get(String credential) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/specs"))
            .header("Authorization", "Bearer " + credential)
            .GET()
            .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private ApiResponse socket() {
    return socket(boxCredential);
  }

  private ApiResponse socket(String credential) {
    return socket("GET", "/v1/specs", credential, "");
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

  private String reserveRun(String owner, String project, String specId) {
    var id = DateTimeUtils.newId().toString();
    var reservation =
        Acting.system(
            () ->
                new RunStore(db)
                    .reserveDispatch(
                        id,
                        project,
                        specId,
                        "box",
                        owner,
                        "build",
                        List.of(),
                        "claude-code",
                        "feat/x",
                        "do it",
                        "/tmp/" + id + ".log",
                        "sail-agent-" + id));
    return ((RunStore.Reservation.Reserved) reservation).credential();
  }

  private SshGateway.Decision gateway() {
    return SshGateway.authorize(
        "sail spec list",
        "mady",
        new FdeStore(db),
        TestAuth.roles(db, MAIN),
        new AuthSessionStore(db));
  }
}
