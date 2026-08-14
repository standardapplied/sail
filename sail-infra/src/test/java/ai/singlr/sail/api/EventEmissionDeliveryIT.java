/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.engine.SailEventHelper;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the emission gate behaviorally: the generated {@code sail-event.sh} runs against the real
 * {@link LocalApiSocket} and either delivers or stays silent. The credential — not {@code
 * SAIL_SPEC_ID} — is what lets a run emit and what scopes the event, which is exactly why a
 * reviewer or fix agent (credentialed, but with no spec id and no run id) shows presence.
 */
class EventEmissionDeliveryIT {

  @TempDir Path root;
  private Sqlite db;
  private EventBus bus;
  private RunStore runStore;
  private LocalApiSocket listener;
  private Path home;
  private String runId;
  private String credential;

  @BeforeEach
  void bootFakeRunAgainstTheProductionApi() throws Exception {
    home = root.resolve("home");
    Files.createDirectories(home);
    db = Sqlite.open(root.resolve("session.db"));
    new SchemaManager(db).migrate();
    db.execute(
        """
        INSERT INTO specs
            (id, title, project, assignee, created_by, created_at, updated_at)
        VALUES ('auth', 'Auth', 'acme', 'ada', 'ada', 'now', 'now')""");
    bus = new EventBus();
    runStore = new RunStore(db);
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
            .useMessages(new MessageStore(db));
    runId = DateTimeUtils.newId().toString();
    var reservation =
        (RunStore.Reservation.Reserved)
            runStore.reserveDispatch(
                runId,
                "acme",
                "auth",
                "node-a",
                "ada",
                "build",
                List.of(),
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

  private CountDownLatch captureToolStarts(CopyOnWriteArrayList<Event> sink) {
    var latch = new CountDownLatch(1);
    bus.subscribe(
        new EventSubscriber() {
          @Override
          public String name() {
            return "capture";
          }

          @Override
          public Predicate<Event> filter() {
            return e -> Event.WellKnownTypes.AGENT_TOOL_STARTED.equals(e.type());
          }

          @Override
          public void onEvent(Event event) {
            sink.add(event);
            latch.countDown();
          }
        });
    return latch;
  }

  @Test
  void aCredentialedRunEmitsAndTheServerScopesItToTheRunSpec() throws Exception {
    var captured = new CopyOnWriteArrayList<Event>();
    var latch = captureToolStarts(captured);

    var result = runEvent("agent_tool_started", true);

    assertEquals(0, result, "a best-effort emit never fails the hook");
    assertTrue(latch.await(30, TimeUnit.SECONDS), "the credentialed tool-start must be delivered");
    var event = captured.get(0);
    assertEquals(
        "auth",
        event.spec(),
        "the server scopes the event to the run's spec, not the client's blank SAIL_SPEC_ID");
    assertEquals(runId, event.data().get(Event.WellKnownData.RUN_ID));
  }

  @Test
  void anUncredentialedSessionEmitsNothing() throws Exception {
    var captured = new CopyOnWriteArrayList<Event>();
    var latch = captureToolStarts(captured);

    runEvent("agent_tool_started", false);
    runEvent("agent_tool_started", true);

    assertTrue(latch.await(30, TimeUnit.SECONDS), "the credentialed control must land");
    assertEquals(
        1,
        captured.size(),
        "the un-credentialed call emitted nothing; only the credentialed control landed");
  }

  private int runEvent(String eventType, boolean withCredential) throws Exception {
    var script = writeScript();
    var pb = new ProcessBuilder("/bin/sh", script.toString(), eventType);
    var env = pb.environment();
    env.put("HOME", home.toString());
    env.remove("SAIL_SPEC_ID");
    env.remove("SAIL_RUN_ID");
    if (withCredential) {
      env.put("SAIL_RUN_CREDENTIAL", credential);
    } else {
      env.remove("SAIL_RUN_CREDENTIAL");
    }
    var process = pb.start();
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the emit must finish inside its timeout");
    return process.exitValue();
  }

  private Path writeScript() throws IOException {
    var path = home.resolve("sail-event.sh");
    Files.writeString(
        path,
        SailEventHelper.scriptContent()
            .replace(
                SailPaths.apiSocketContainerPath().toString(),
                root.resolve("api.sock").toString()));
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
    return path;
  }
}
