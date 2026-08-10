/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.SailRoomRelay;
import ai.singlr.sail.engine.SailStopGate;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole delivery loop against the production local API: a message posted mid-run reaches the
 * real relay script over the real Unix socket as {@code additionalContext}, the watermark advances
 * so the real stop gate passes without a nudge, and a dead socket leaves the relay silent — all in
 * the fix lane's environment, which exports the run identity but never {@code SAIL_SPEC_ID}.
 */
class RoomRelayDeliveryIT {

  @TempDir Path root;
  private Sqlite db;
  private EventBus bus;
  private RunStore runStore;
  private MessageStore messages;
  private LocalApiSocket listener;
  private Path home;
  private String runId;
  private String credential;

  @BeforeEach
  void bootFakeRunAgainstTheProductionApi() throws Exception {
    home = root.resolve("home");
    Files.createDirectories(home);
    db = Sqlite.open(root.resolve("delivery.db"));
    new SchemaManager(db).migrate();
    db.execute(
        """
        INSERT INTO specs
            (id, title, project, assignee, created_by, created_at, updated_at)
        VALUES ('room', 'Room', 'acme', 'ada', 'ada', 'now', 'now')""");
    bus = new EventBus();
    runStore = new RunStore(db);
    messages = new MessageStore(db);
    var operations =
        new SailOperations(
                new ShellExecutor(false),
                "sail.yaml",
                bus,
                null,
                new SpecStore(db),
                new ReviewStore(db),
                runStore,
                new ProjectStore(db),
                SyncScheduler.disabled(),
                null)
            .useMessages(messages);
    runId = DateTimeUtils.newId().toString();
    var reservation =
        (RunStore.Reservation.Reserved)
            runStore.reserveDispatch(
                runId,
                "acme",
                "room",
                "node-a",
                "ada",
                "build",
                java.util.List.of(),
                "claude-code",
                "b",
                "t",
                "l",
                "u");
    credential = reservation.credential();
    listener = new LocalApiSocket(bus, operations, root.resolve("api.sock"));
    listener.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (listener != null) {
      listener.close();
    }
    bus.close();
    db.close();
  }

  @Test
  void aMidRunMessageIsInjectedOnceAndTheStopGatePassesAsAlreadyDelivered() throws Exception {
    var posted = messages.append("room", "ada", "also update the docs, please", null);

    var relay = runScript(relayScript(), "");
    assertEquals(0, relay.exitCode());
    var output = YamlUtil.parseMap(relay.stdout());
    @SuppressWarnings("unchecked")
    var hookOutput = (Map<String, Object>) output.get("hookSpecificOutput");
    assertEquals("PostToolUse", hookOutput.get("hookEventName"));
    assertTrue(
        hookOutput
            .get("additionalContext")
            .toString()
            .contains(
                "[Room message from ada, arrived while you were working]: also update the docs,"
                    + " please"),
        relay.stdout());
    assertTrue(
        runStore.deliveredMessageIds(runId).contains(posted.id()),
        "the relay acknowledged what it delivered");

    var gate = runScript(gateScript(), "{\"stop_hook_active\": false}");
    assertEquals(0, gate.exitCode());
    assertEquals(
        "", gate.stdout(), "already delivered mid-run: the stop gate has nothing left to block on");
  }

  @Test
  void aDeadSocketLeavesTheRelaySilent() throws Exception {
    messages.append("room", "ada", "anyone there?", null);
    listener.close();
    Files.deleteIfExists(root.resolve("api.sock"));

    var relay = runScript(relayScript(), "");

    assertEquals(0, relay.exitCode(), "a down API must never break a build");
    assertEquals("", relay.stdout());
    assertTrue(runStore.deliveredMessageIds(runId).isEmpty(), "nothing was delivered");
  }

  private Path relayScript() throws IOException {
    return writeScript("sail-room-relay", SailRoomRelay.scriptContent());
  }

  private Path gateScript() throws IOException {
    return writeScript("sail-stop-gate", SailStopGate.scriptContent());
  }

  private Path writeScript(String name, String content) throws IOException {
    var path = home.resolve(name);
    Files.writeString(
        path,
        content.replace(
            SailPaths.apiSocketContainerPath().toString(), root.resolve("api.sock").toString()));
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
    return path;
  }

  private record ScriptResult(int exitCode, String stdout, String stderr) {}

  private ScriptResult runScript(Path script, String stdin) throws Exception {
    var pb = new ProcessBuilder("/bin/sh", script.toString());
    var env = pb.environment();
    env.put("HOME", home.toString());
    env.remove("SAIL_SPEC_ID");
    env.put("SAIL_RUN_ID", runId);
    env.put("SAIL_RUN_CREDENTIAL", credential);
    var process = pb.start();
    try (var in = process.getOutputStream()) {
      in.write(stdin.getBytes(StandardCharsets.UTF_8));
    }
    var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "scripts must finish inside their timeouts");
    return new ScriptResult(process.exitValue(), stdout, stderr);
  }
}
