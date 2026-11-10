/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.access.Principal;
import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.PipedSyncChannel;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.identity.RoleRule.Credential;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.BoxDatabase;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncScope;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncTransitionSink;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A10, the door half for sync and the SSH gateway: a change to main's roster takes effect at the
 * gateway on the next command, at main's sync server on the next session, and on a node after its
 * next round. Each round of the node goes as in the field: the gateway authorizes {@code _sync} for
 * the node's FDE, {@code nia}, and main serves the session its token opens. Roles, scopes and
 * statuses are set directly in main's roster, as {@code sail-people-are-managed} adds the commands.
 */
class SyncGatewayRosterChangeTest {

  private static final SyncConfig MAIN = new SyncConfig(SyncConfig.ROLE_MAIN, null, "ada", "main");
  private static final SyncConfig NODE =
      new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "nia", "node-box");

  @TempDir Path tempDir;
  private SyncDatabase main;
  private Sqlite mainDb;
  private FdeStore roster;
  private Sqlite nodeDb;
  private SyncOperations node;
  private final PrintStream originalErr = System.err;

  @BeforeEach
  void setUp() {
    var mainPath = tempDir.resolve("main.db");
    try (var db = Sqlite.open(mainPath)) {
      BoxDatabase.prepare(db);
    }
    main = SyncDatabase.converge(mainPath, "main");
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
    nodeDb = Sqlite.open(tempDir.resolve("node.db"));
    new SchemaManager(nodeDb).migrate();
    node =
        new SyncOperations(
            nodeDb,
            "node",
            tempDir.resolve("projects"),
            () -> NODE,
            target -> PipedSyncChannel.to(this::throughTheGateway),
            event -> {},
            remedy -> {},
            new NoContainers());
    node.prepare();
  }

  @AfterEach
  void tearDown() {
    System.setErr(originalErr);
    nodeDb.close();
    main.close();
  }

  /** Main's side of one node session: the gateway's {@code _sync}, then the server it execs. */
  private void throughTheGateway(InputStream in, OutputStream out) throws Exception {
    var authorized = assertInstanceOf(SshGateway.Authorized.class, gateway("sail _sync", "nia"));
    SyncServerCommand.serve(
        main, "main", authorized.sessionToken(), in, out, SyncTransitionSink.NONE, () -> MAIN);
  }

  private SshGateway.Decision gateway(String command, String handle) {
    return SshGateway.authorize(
        command, handle, roster, new RoleRule(() -> MAIN, roster), new AuthSessionStore(mainDb));
  }

  /** One round of the node, as {@code sail sync} runs it. */
  private void round() throws Exception {
    System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    try {
      node.sync(new SyncRequest(null));
    } finally {
      System.setErr(originalErr);
    }
  }

  /** Whom every door on the node resolves for the box's own person. */
  private Optional<Actor> nodesPerson() {
    return new RoleRule(() -> NODE, new FdeStore(nodeDb)).principal(Credential.box(Actor.Lane.CLI));
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
    assertEquals("member", Principal.of(nodesPerson().orElseThrow()).role());
    assertTrue(new SpecStore(mainDb).findById("before").isPresent(), "a member's push lands");

    onMain("UPDATE fdes SET role = 'viewer' WHERE handle = 'nia'");
    write("after");
    assertEquals(
        "member",
        Principal.of(nodesPerson().orElseThrow()).role(),
        "the node knows only what its last round told it");

    round();

    assertEquals("viewer", Principal.of(nodesPerson().orElseThrow()).role());
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

  private static final class NoContainers implements ShellExec {
    @Override
    public Result exec(List<String> command) {
      return new Result(0, "[]", "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
