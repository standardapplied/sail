/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.access.Standing;
import ai.singlr.sail.api.HostPty;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.api.SyncScheduler;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.identity.RoleRule.Credential;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncScope;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncTransitionSink;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A10, the door half: a change to main's roster, of a role, a scope or a status, takes effect at
 * main's doors on the next request (the web door, the SSH gateway, a sync session), on a node after
 * its next round (its socket, its host CLI, its copy of what it holds), and at a terminal host
 * within one sweep: an open connection acts as the principal it was admitted as until the sweep
 * severs it, and nothing before. Each round of the node goes as in the field: the gateway
 * authorizes {@code _sync} for the node's FDE, {@code nia}, and main serves the session its token
 * opens. Roles, scopes and statuses are set directly in main's roster, as {@code
 * sail-people-are-managed} adds the commands. A node whose own person main disables is refused its
 * rounds and so never mirrors that disable; {@code sail-people-are-managed} closes it.
 */
class RosterChangeTest {

  private static final SyncConfig MAIN = new SyncConfig(SyncConfig.ROLE_MAIN, null, "ada", "main");
  private static final SyncConfig NODE =
      new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "nia", "node-box");

  @TempDir Path tempDir;
  private SyncDatabase main;
  private Sqlite mainDb;
  private FdeStore roster;
  private GatewayNode node;
  private Sqlite nodeDb;
  private final PrintStream originalErr = System.err;

  @BeforeEach
  void setUp() {
    main = GatewayNode.main(tempDir.resolve("main.db"));
    mainDb = main.db();
    roster = new FdeStore(mainDb);
    roster.add("ada", "Ada", "ada@x.dev", "admin");
    roster.add("nia", "Nia", "nia@x.dev", "member");
    roster.add("obs", "Obs", "obs@x.dev", "viewer");
    Acting.system(
        () -> {
          var projects = new ProjectStore(mainDb);
          projects.upsert(TestDefinitions.named("acme"));
          projects.upsert(TestDefinitions.named("beta"));
        });
    Acting.as(
        "ada",
        () ->
            new RoomStore(mainDb)
                .create(
                    new RoomStore.RoomRow(
                        "lobby", "acme", "lobby", "ada", null, null, "ada", null, null, "ada")));
    node = new GatewayNode(tempDir, main, MAIN, NODE);
    nodeDb = node.db();
  }

  @AfterEach
  void tearDown() {
    System.setErr(originalErr);
    node.close();
    main.close();
  }

  private SshGateway.Decision gateway(String command, String handle) {
    return GatewayNode.gateway(main, MAIN, command, handle);
  }

  private void round() throws Exception {
    node.round();
  }

  /** Whom every door on the node resolves for the box's own person. */
  private Optional<Actor> nodesPerson() {
    return new RoleRule(() -> NODE, new FdeStore(nodeDb)).principal(Credential.box());
  }

  private void onMain(String sql, Object... params) {
    mainDb.execute(sql, params);
  }

  private void write(String id) {
    Acting.as("nia", () -> new SpecStore(nodeDb).create(spec(id)));
  }

  @Test
  void aRoleChangeOnMainReachesTheNodeAfterItsNextRoundAndMainsSyncOnTheNextSession()
      throws Exception {
    round();
    write("before");
    round();
    assertEquals("member", Standing.of(nodesPerson().orElseThrow()).role());
    assertTrue(new SpecStore(mainDb).findById("before").isPresent(), "a member's push lands");

    onMain("UPDATE fdes SET role = 'viewer' WHERE handle = 'nia'");
    write("after");
    assertEquals(
        "member",
        Standing.of(nodesPerson().orElseThrow()).role(),
        "the node knows only what its last round told it");

    round();

    assertEquals("viewer", Standing.of(nodesPerson().orElseThrow()).role());
    assertTrue(
        new SpecStore(mainDb).findById("after").isEmpty(),
        "main's next session decided the push as a viewer's");
  }

  @Test
  void aScopeChangeOnMainReachesTheNodeAfterItsNextRound() throws Exception {
    round();
    assertEquals(ProjectScope.all(), new SyncScope(nodeDb).held());

    roster.restrict("nia");
    roster.grant("nia", "acme");
    assertEquals(ProjectScope.all(), new SyncScope(nodeDb).held());

    round();

    assertEquals(ProjectScope.of(List.of("acme")), new SyncScope(nodeDb).held());
  }

  @Test
  void anotherPersonsDisableReachesTheNodeAfterItsNextRound() throws Exception {
    round();
    var obsToken = Credential.token("obs", "admin");
    var nodeRoles = new RoleRule(() -> NODE, new FdeStore(nodeDb));
    assertTrue(nodeRoles.principal(obsToken).isPresent());

    onMain("UPDATE fdes SET status = 'disabled' WHERE handle = 'obs'");
    assertTrue(nodeRoles.principal(obsToken).isPresent());

    round();

    assertEquals(Optional.empty(), nodeRoles.principal(obsToken));
  }

  @Test
  void aRoleChangeTakesEffectAtTheGatewayOnTheNextCommand() {
    var add = "sail fde add zed --name Zed";
    assertEquals(
        new SshGateway.Rejected("Only an admin may manage people. Ask an admin to do it."),
        gateway(add, "nia"));

    onMain("UPDATE fdes SET role = 'admin' WHERE handle = 'nia'");
    assertInstanceOf(SshGateway.Authorized.class, gateway(add, "nia"));

    onMain("UPDATE fdes SET role = 'viewer' WHERE handle = 'nia'");
    assertEquals(
        new SshGateway.Rejected(
            "Your credential is read-only and cannot manage people. Ask an admin for a member or"
                + " admin credential."),
        gateway(add, "nia"));
  }

  @Test
  void aDisableTakesEffectAtTheGatewayOnTheNextCommandAndAtSyncOnTheNextSession() throws Exception {
    var minted = assertInstanceOf(SshGateway.Authorized.class, gateway("sail _sync", "nia"));

    onMain("UPDATE fdes SET status = 'disabled' WHERE handle = 'nia'");

    for (var command : List.of("sail _sync", "sail spec list")) {
      assertEquals(new SshGateway.Rejected("Unknown or disabled FDE."), gateway(command, "nia"));
    }
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    int exit;
    try {
      exit =
          SyncServerCommand.serve(
              main,
              "main",
              minted.sessionToken(),
              new ByteArrayInputStream(new byte[0]),
              out,
              SyncTransitionSink.NONE,
              () -> MAIN);
    } finally {
      System.setErr(originalErr);
    }
    assertEquals(1, exit);
    assertEquals(0, out.size());
    assertEquals(SyncServerCommand.REFUSED + "\n", err.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aChangeTakesEffectAtMainsWebDoorOnTheNextRequest() throws Exception {
    var token =
        new TokenStore(mainDb)
            .create("nia", "member", roster.byHandle("nia").orElseThrow().id(), null)
            .token();
    try (var web = DoorBox.open(mainDb, tempDir, MAIN)) {
      assertEquals("201", web(web, token, "POST", "/v1/specs", born("s1", "acme")));

      onMain("UPDATE fdes SET role = 'viewer' WHERE handle = 'nia'");
      assertEquals(
          "403 read_only_credential Your credential is read-only and cannot create specs.",
          web(web, token, "POST", "/v1/specs", born("s2", "acme")));

      onMain("UPDATE fdes SET role = 'member' WHERE handle = 'nia'");
      roster.restrict("nia");
      roster.grant("nia", "beta");
      assertEquals(
          "404 spec_not_found Spec 's1' was not found.",
          web(web, token, "GET", "/v1/specs/s1", null));

      onMain("UPDATE fdes SET status = 'disabled' WHERE handle = 'nia'");
      assertEquals(
          "403 invalid_bearer_token Bearer token's FDE 'nia' is disabled.",
          web(web, token, "GET", "/v1/specs", null));
    }
  }

  @Test
  void aRoleChangeOnMainReachesTheNodesSocketAndHostCliAfterItsNextRound() throws Exception {
    round();
    try (var box = DoorBox.open(nodeDb, tempDir, NODE)) {
      assertEquals(201, box.socket("POST", "/v1/specs", "id=n1&title=N&project=acme").status());
      assertEquals(Actor.Lane.CLI, CliOperator.of(NODE, new FdeStore(nodeDb)).lane());

      onMain("UPDATE fdes SET role = 'viewer' WHERE handle = 'nia'");
      assertEquals(
          201,
          box.socket("POST", "/v1/specs", "id=n2&title=N&project=acme").status(),
          "the node knows only what its last round told it");
      assertEquals("member", Standing.of(CliOperator.of(NODE, new FdeStore(nodeDb))).role());

      round();

      var refused = box.socket("POST", "/v1/specs", "id=n3&title=N&project=acme");
      assertEquals(403, refused.status());
      assertEquals(
          "Your credential is read-only and cannot create specs.",
          ((Map<?, ?>) refused.body().get("error")).get("message"));
      assertEquals("viewer", Standing.of(CliOperator.of(NODE, new FdeStore(nodeDb))).role());
    }
  }

  /**
   * The terminal host resolves a connection's principal at its hello and again at each sweep. Until
   * the sweep, an open connection pins rooms and opens sessions as the principal it was admitted
   * as, after its person is demoted, rescoped or disabled alike: the one window A10 leaves the
   * terminal. The sweep severs it once its credential resolves to another principal, or to none,
   * and the reconnect is admitted as what it is now.
   */
  @Test
  void aChangeOfAccessSeversAnOpenTerminalAtTheNextSweepAndNotBefore() throws Exception {
    var nia = roster.byHandle("nia").orElseThrow();
    var session = new AuthSessionStore(mainDb).create(nia.id(), Duration.ofHours(1)).token();
    var socket = tempDir.resolve("pty.sock");
    try (var operations =
            DoorBox.wired(
                OperationsFactory.create(
                    mainDb,
                    new ScriptedShellExecutor(),
                    null,
                    null,
                    SyncScheduler.disabled(),
                    SessionYield.NONE),
                mainDb,
                tempDir,
                MAIN);
        var host = terminalHost(socket, tempDir.resolve("sessions"), operations.pty())) {
      host.start();

      pinnedUntilTheSweep(
          host, socket, session, "UPDATE fdes SET role = 'viewer' WHERE handle = 'nia'");
      assertEquals(
          "Refusing room for session 'again': Your credential is read-only and cannot post in"
              + " rooms. Ask an admin for a member or admin credential.",
          refusedAPin(socket, session));

      onMain("UPDATE fdes SET role = 'member' WHERE handle = 'nia'");
      pinnedUntilTheSweep(
          host, socket, session, "UPDATE fdes SET scope = 'granted' WHERE handle = 'nia'");
      assertEquals(
          "Project 'acme' is not in the catalog. Create it with 'sail project create acme', or"
              + " sync it from main with 'sail sync'.",
          refusedAPin(socket, session));

      roster.unrestrict("nia");
      pinnedUntilTheSweep(
          host, socket, session, "UPDATE fdes SET status = 'disabled' WHERE handle = 'nia'");
      assertEquals(
          "FDE 'nia' is disabled or not in this box's roster. Ask an admin to re-enable it, or run"
              + " 'sail sync' on a node that has not synced its roster.",
          assertThrows(IOException.class, () -> SessionClient.connect(socket, session))
              .getMessage());
    }
  }

  /**
   * Opens a terminal as {@code session}, changes main's roster by {@code change}, pins a session to
   * the lobby before the sweep, which admits it as the principal it opened as, and sweeps: the
   * sweep severs the connection.
   */
  private void pinnedUntilTheSweep(PtySessionHost host, Path socket, String session, String change)
      throws Exception {
    try (var open = SessionClient.connect(socket, session)) {
      onMain(change);
      open.create(
          "pinned-" + System.nanoTime(),
          List.of("sh", "-c", "read a"),
          "/tmp",
          "acme",
          "lobby",
          80,
          24);

      host.sweep(System.nanoTime());

      assertThrows(IOException.class, open::list, "the sweep severs the connection");
    }
  }

  /** The refusal a fresh terminal of {@code session} hears pinning the lobby. */
  private static String refusedAPin(Path socket, String session) throws Exception {
    try (var reconnected = SessionClient.connect(socket, session)) {
      return assertThrows(
              IOException.class,
              () ->
                  reconnected.create(
                      "again", List.of("sh", "-c", "read a"), "/tmp", "acme", "lobby", 80, 24))
          .getMessage();
    }
  }

  /**
   * Main's terminal host: its terminal facet resolves and admits each connection on main's roster.
   */
  private static PtySessionHost terminalHost(Path socket, Path sessions, HostPty pty) {
    return new PtySessionHost(
        socket,
        sessions,
        64 * 1024,
        pty::identity,
        new PtyAdmission() {
          @Override
          public void project(String project, PtyIdentity who) throws IOException {
            pty.admitProject(project, who);
          }

          @Override
          public void room(String room, String project, PtyIdentity who) throws IOException {
            pty.admitRoom(room, project, who);
          }
        },
        PtyEvents.NONE,
        "0.0.0-test");
  }

  /**
   * The status, and a refusal's code and words, of {@code method} on {@code path} at {@code box}.
   */
  private static String web(DoorBox box, String token, String method, String path, String json)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(box.url() + path))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .method(
                method,
                json == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json));
    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() / 100 == 2) {
        return String.valueOf(response.statusCode());
      }
      var error = (Map<?, ?>) YamlUtil.parseMap(response.body()).get("error");
      return response.statusCode() + " " + error.get("code") + " " + error.get("message");
    }
  }

  private static String born(String id, String project) {
    return "{\"id\":\"" + id + "\",\"title\":\"T\",\"project\":\"" + project + "\"}";
  }

  private static SpecStore.SpecRow spec(String id) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        "the " + id + " spec",
        SpecStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        0,
        "nia",
        null,
        null,
        "nia",
        List.of(),
        List.of());
  }
}
