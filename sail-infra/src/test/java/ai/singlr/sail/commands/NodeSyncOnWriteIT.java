/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MigrationRunner;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.sync.SyncDatabase;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The sync-on-write bug reproduced end-to-end and proven fixed: a spec created through a node's
 * HTTP API becomes visible in main's database with no manual {@code sail sync} anywhere. The node
 * is a real control-plane server in a child JVM whose {@code host.yaml} declares a node role; its
 * write-triggered reconcile opens the real {@code ssh sail@mainbox sail _sync} lane, intercepted by
 * an {@code ssh} shim on {@code PATH} that runs the genuine {@code _sync} RPC server against main's
 * home directory. Runs under the {@code integration} profile; it needs a Unix JVM, not incus.
 * Deterministic synchronization: the server's "listening" line and bounded {@code waitFor} polls —
 * no bare sleeps.
 */
class NodeSyncOnWriteIT {

  private static final String MAIN_CLASS = "ai.singlr.sail.Main";
  private static final String SPEC_ID = "sync-e2e";
  private static final Duration PROPAGATION_DEADLINE = Duration.ofSeconds(60);

  @TempDir Path root;

  @Test
  void aSpecWriteOnANodeReachesMainWithoutAManualSync() throws Exception {
    var mainHome = Files.createDirectories(root.resolve("main-home"));
    var nodeHome = Files.createDirectories(root.resolve("node-home"));
    var mainDb = Files.createDirectories(mainHome.resolve(".sail")).resolve("sail.db");
    var nodeSailDir = Files.createDirectories(nodeHome.resolve(".sail"));

    var syncToken = seedMainWithMemberSession(mainDb);
    var shim = writeSshShim(mainHome, syncToken);
    Files.writeString(
        nodeSailDir.resolve("host.yaml"),
        """
        sync:
          role: node
          main: sail@mainbox
          handle: mady
        """);

    var port = freePort();
    var server = startNodeServer(nodeHome, shim.getParent(), port);
    var lines = new LinkedBlockingQueue<String>();
    var reader = new Thread(() -> drainInto(server, lines), "node-server-output");
    reader.setDaemon(true);
    reader.start();
    try {
      awaitListening(server, lines);
      var nodeToken = readNodeToken(nodeSailDir.resolve("config.yaml"));
      createSpecViaNodeApi(port, nodeToken);
      awaitSpecOnMain(server, mainDb);
    } finally {
      server.destroy();
      server.waitFor(10, TimeUnit.SECONDS);
    }
  }

  /**
   * Seeds main's database the way {@code sail join} would have: a member FDE and a live session
   * token, which the SSH gateway hands to {@code _sync} so the node's pushes are authorized.
   */
  private static String seedMainWithMemberSession(Path mainDb) {
    try (var db = Sqlite.open(mainDb)) {
      MigrationRunner.applyAll(db, MigrateCommand.REGISTRY, DataMigration.Prompter.NON_INTERACTIVE);
    }
    try (var converged = SyncDatabase.converge(mainDb, "mainbox")) {
      var fde = new FdeStore(converged.db()).add("mady", "Mady", "mady@example.dev", "member");
      return new AuthSessionStore(converged.db()).create(fde.id(), Duration.ofHours(1)).token();
    }
  }

  /**
   * An {@code ssh} that never leaves the machine: whatever host and options the node's sync lane
   * asks for, it execs the real {@code sail _sync} RPC server against main's home over stdio —
   * exactly what the gateway does after authorizing the key, minus the network.
   */
  private Path writeSshShim(Path mainHome, String syncToken) throws Exception {
    var shimDir = Files.createDirectories(root.resolve("bin"));
    var shim = shimDir.resolve("ssh");
    Files.writeString(
        shim,
        """
        #!/bin/sh
        export SAIL_TOKEN='%s'
        exec '%s' '-Duser.home=%s' --enable-native-access=ALL-UNNAMED -cp '%s' %s _sync
        """
            .formatted(syncToken, javaBinary(), mainHome, classpath(), MAIN_CLASS));
    Files.setPosixFilePermissions(shim, PosixFilePermissions.fromString("rwxr-xr-x"));
    return shim;
  }

  private static Process startNodeServer(Path nodeHome, Path shimDir, int port) throws Exception {
    var builder =
        new ProcessBuilder(
                javaBinary(),
                "-Duser.home=" + nodeHome,
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                classpath(),
                MAIN_CLASS,
                "server",
                "start",
                "--host",
                "127.0.0.1",
                "--port",
                String.valueOf(port))
            .redirectErrorStream(true);
    var env = builder.environment();
    env.put("PATH", shimDir + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
    env.put(NodeSync.DEBOUNCE_ENV, "50");
    env.remove("SAIL_TOKEN");
    env.remove("SAIL_DATA_DIR");
    env.remove("SAIL_NO_SYNC");
    var process = builder.start();
    process.getOutputStream().close();
    return process;
  }

  private static void awaitListening(Process server, LinkedBlockingQueue<String> lines)
      throws Exception {
    var deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      var line = lines.poll(500, TimeUnit.MILLISECONDS);
      if (line != null && line.contains("listening on")) {
        return;
      }
      if (!server.isAlive()) {
        fail("node server exited before listening: " + String.join("\n", lines));
      }
    }
    fail("node server never reported listening: " + String.join("\n", lines));
  }

  private static String readNodeToken(Path configYaml) throws Exception {
    var token = (String) YamlUtil.parseFile(configYaml).get("token");
    assertNotNull(token, "server start should have saved an admin token to " + configYaml);
    return token;
  }

  private static void createSpecViaNodeApi(int port, String token) throws Exception {
    try (var client = new SailApiClient("http://127.0.0.1:" + port, token)) {
      var body = new LinkedHashMap<String, Object>();
      body.put("id", SPEC_ID);
      body.put("project", "acme");
      body.put("title", "Propagates without manual sync");
      body.put("status", "pending");
      var created = client.post("/v1/specs", body);
      assertNotNull(created.get("spec"), "node API should accept the spec write locally");
    }
  }

  private static void awaitSpecOnMain(Process server, Path mainDb) throws Exception {
    var deadline = System.nanoTime() + PROPAGATION_DEADLINE.toNanos();
    while (System.nanoTime() < deadline) {
      if (mainHasSpec(mainDb)) {
        return;
      }
      if (server.waitFor(200, TimeUnit.MILLISECONDS)) {
        fail("node server exited before the spec propagated");
      }
    }
    fail("spec '" + SPEC_ID + "' never appeared on main within " + PROPAGATION_DEADLINE);
  }

  private static boolean mainHasSpec(Path mainDb) {
    try (var db = Sqlite.open(mainDb)) {
      var spec = new SpecStore(db).findById(SPEC_ID);
      spec.ifPresent(row -> assertEquals("acme", row.project()));
      return spec.isPresent();
    }
  }

  private static void drainInto(Process process, LinkedBlockingQueue<String> lines) {
    try (var reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    } catch (Exception ignored) {
    }
  }

  private static String javaBinary() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static String classpath() {
    return System.getProperty("java.class.path");
  }

  private static int freePort() throws Exception {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
