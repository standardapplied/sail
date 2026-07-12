/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.EventSubscriber;
import ai.singlr.sail.api.LocalApiSocket;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Exercises the installed stop gate end-to-end in a real container against a live sail-api socket:
 * a dispatched-looking session with a dirty worktree is blocked exactly once (block JSON on
 * stdout), the second stop passes, and the event log shows {@code agent_stop_nudged} followed by
 * {@code agent_session_stopped} — never a stop that did not happen.
 */
class StopGateIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-stop-gate";
  private static final String SPEC_ID = "stop-gate-probe";
  private static final Path CONTAINER_DIR = Path.of("/var/lib/sail/run");

  @Test
  void aDirtySessionIsNudgedOnceAndTheEventLogShowsNudgedThenStopped() throws Exception {
    ensureIncusOrSkip();

    var socketDir = Files.createTempDirectory("sail-it-stop-gate-socket");
    Files.setPosixFilePermissions(socketDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    var dbPath = Files.createTempDirectory("sail-it-stop-gate-db").resolve("sail.db");

    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      var specStore = new SpecStore(db);
      specStore.create(seededSpec());

      var bus = new EventBus();
      var received = new CopyOnWriteArrayList<Event>();
      bus.subscribe(recorder(received));
      var operations =
          new SailOperations(new ShellExecutor(false), "sail.yaml", bus, null, specStore);
      try (var server = new LocalApiSocket(bus, operations, socketDir.resolve("api.sock"))) {
        server.start();

        launch(CONTAINER);
        var dev =
            exec(
                CONTAINER,
                List.of(
                    "bash",
                    "-c",
                    "userdel -r ubuntu 2>/dev/null || true;"
                        + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev"));
        assertTrue(dev.ok(), "the dev user must exist: " + dev.stderr());

        Files.createDirectories(SailPaths.apiSocketHostDir());
        ContainerSailSetup.ensureInstalled(shell, CONTAINER);
        new IncusDeviceManager(shell).ensureEventSocket(CONTAINER, socketDir, CONTAINER_DIR);

        var prepare =
            exec(
                CONTAINER,
                List.of(
                    "bash",
                    "-c",
                    "set -e;"
                        + " for i in $(seq 1 30); do"
                        + " getent hosts archive.ubuntu.com >/dev/null 2>&1 && break; sleep 1; done;"
                        + " apt-get update -qq;"
                        + " apt-get install -y -qq curl git python3"));
        assertTrue(prepare.ok(), "could not ready the container: " + prepare.stderr());

        var seed =
            exec(
                CONTAINER,
                List.of(
                    "su",
                    "-",
                    "dev",
                    "-c",
                    "set -e; mkdir -p ~/workspace/proj; cd ~/workspace/proj;"
                        + " git init -q -b main; echo wip > uncommitted.txt"));
        assertTrue(seed.ok(), "could not seed the dirty workspace repo: " + seed.stderr());

        var first = stopAttempt();
        assertTrue(first.ok(), "the first stop attempt must exit 0: " + first.stderr());
        assertTrue(
            first.stdout().contains("\"decision\": \"block\""),
            "a dirty worktree must block the first stop: " + first.stdout());
        assertTrue(
            first.stdout().contains("proj"),
            "the block reason must name the dirty repo: " + first.stdout());

        var second = stopAttempt();
        assertTrue(second.ok(), "the second stop attempt must exit 0: " + second.stderr());
        assertTrue(
            second.stdout().isBlank(),
            "the second stop always wins, dirty or not: " + second.stdout());

        awaitEvents(received, 2);
        assertEquals(
            List.of(
                Event.WellKnownTypes.AGENT_STOP_NUDGED, Event.WellKnownTypes.AGENT_SESSION_STOPPED),
            received.stream().map(Event::type).toList(),
            "one nudge, then a real stop, in that order — and no stop for the blocked attempt");
        var reason = Objects.toString(received.get(0).data().get("reason"), "");
        assertTrue(reason.contains("proj"), "the nudge event must carry the reason: " + reason);
        assertEquals("run-1", received.get(0).data().get(Event.WellKnownData.RUN_ID));
      }
    } finally {
      deleteContainerQuietly(CONTAINER);
      deleteRecursively(socketDir);
      deleteRecursively(dbPath.getParent());
    }
  }

  private ShellExec.Result stopAttempt() throws Exception {
    return exec(
        CONTAINER,
        List.of(
            "su",
            "-",
            "dev",
            "-c",
            "printf '{}' | SAIL_SPEC_ID="
                + SPEC_ID
                + " SAIL_RUN_ID=run-1 SAIL_AGENT=claude-code "
                + SailStopGate.SCRIPT_PATH));
  }

  private static EventSubscriber recorder(CopyOnWriteArrayList<Event> received) {
    return new EventSubscriber() {
      @Override
      public String name() {
        return "stop-gate-it-recorder";
      }

      @Override
      public Predicate<Event> filter() {
        return EventSubscriber.byType(
            Event.WellKnownTypes.AGENT_STOP_NUDGED, Event.WellKnownTypes.AGENT_SESSION_STOPPED);
      }

      @Override
      public void onEvent(Event event) {
        received.add(event);
      }
    };
  }

  private static void awaitEvents(List<Event> received, int expected) throws InterruptedException {
    for (var i = 0; i < 100 && received.size() < expected; i++) {
      Thread.sleep(100);
    }
    assertEquals(expected, received.size(), "events did not arrive in time: " + received);
  }

  private static SpecStore.SpecRow seededSpec() {
    return new SpecStore.SpecRow(
        SPEC_ID,
        CONTAINER,
        "Stop gate probe",
        SpecStatus.IN_PROGRESS,
        null,
        null,
        null,
        null,
        null,
        0,
        "it",
        null,
        null,
        "it",
        List.of(),
        List.of());
  }
}
