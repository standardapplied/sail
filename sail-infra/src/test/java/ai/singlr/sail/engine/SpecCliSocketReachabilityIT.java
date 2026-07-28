/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.LocalApiSocket;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The end-to-end control-plane path that every FDE and agent depends on: an in-process sail-api
 * server binds a real Unix socket, that socket directory is bind-mounted into a container, and a
 * client <em>inside</em> the container connects to it and reads a seeded spec back over HTTP.
 * Proves the socket is a live IPC endpoint — connectable and serving — not merely a file that
 * exists, which is the guarantee FDE-aware dispatch and the {@code spec} CLI rest on.
 * Self-cleaning.
 */
class SpecCliSocketReachabilityIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-spec-socket";
  private static final String SPEC_ID = "reachability-probe";
  private static final Path CONTAINER_DIR = Path.of("/var/lib/sail/run");

  @Test
  void aClientInsideTheContainerReachesTheBindMountedApiAndReadsASpec() throws Exception {
    ensureIncusOrSkip();

    var socketDir = Files.createTempDirectory("sail-it-spec-socket");
    Files.setPosixFilePermissions(socketDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    var dbPath = Files.createTempDirectory("sail-it-spec-db").resolve("sail.db");

    try (var db = Sqlite.open(dbPath)) {
      new SchemaManager(db).migrate();
      var specStore = new SpecStore(db);
      specStore.create(seededSpec());
      var runStore = new RunStore(db);
      var reservation =
          (RunStore.Reservation.Reserved)
              runStore.reserveDispatch(
                  "01890000-0000-7000-8000-000000000001",
                  CONTAINER,
                  SPEC_ID,
                  "it",
                  "it",
                  "build",
                  List.of(),
                  "claude-code",
                  null,
                  "probe",
                  null,
                  "");
      var credential = reservation.credential();

      var fdeStore = new FdeStore(db);
      fdeStore.add("it", "IT", "it@example.dev", "admin");
      var boxStore = new BoxCredentialStore(db);
      BoxCredentialFile.ensure(boxStore, "it", socketDir);
      var bus = new EventBus();
      var operations =
          new SailOperations(
                  new ShellExecutor(false),
                  "sail.yaml",
                  bus,
                  null,
                  specStore,
                  null,
                  runStore,
                  null,
                  ai.singlr.sail.api.SyncScheduler.disabled(),
                  fdeStore)
              .useMessages(new MessageStore(db))
              .useBoxCredentials(boxStore);
      try (var server = new LocalApiSocket(bus, operations, socketDir.resolve("api.sock"))) {
        server.start();

        launchPrepared(CONTAINER);
        new IncusDeviceManager(shell).ensureEventSocket(CONTAINER, socketDir, CONTAINER_DIR);

        var unauthenticated =
            exec(
                CONTAINER,
                List.of(
                    "curl",
                    "--silent",
                    "--unix-socket",
                    CONTAINER_DIR.resolve("api.sock").toString(),
                    "-o",
                    "/dev/null",
                    "-w",
                    "%{http_code}",
                    "http://sail/v1/specs?project=" + CONTAINER));
        assertTrue(
            unauthenticated.stdout().contains("401"),
            "a request without a run credential must be refused: " + unauthenticated.stdout());

        var response =
            exec(
                CONTAINER,
                List.of(
                    "curl",
                    "--silent",
                    "--show-error",
                    "--fail-with-body",
                    "--unix-socket",
                    CONTAINER_DIR.resolve("api.sock").toString(),
                    "-H",
                    "Authorization: Bearer " + credential,
                    "http://sail/v1/specs?project=" + CONTAINER));
        assertTrue(
            response.ok(),
            "the in-container client could not reach the bind-mounted socket: "
                + response.stderr());
        assertTrue(
            response.stdout().contains(SPEC_ID),
            "the seeded spec must round-trip back through the socket: " + response.stdout());

        var posted =
            exec(
                CONTAINER,
                List.of(
                    "curl",
                    "--silent",
                    "--show-error",
                    "--fail-with-body",
                    "--unix-socket",
                    CONTAINER_DIR.resolve("api.sock").toString(),
                    "-H",
                    "Authorization: Bearer " + credential,
                    "-X",
                    "POST",
                    "--data-urlencode",
                    "body=mid-run progress from inside the container",
                    "http://sail/v1/specs/" + SPEC_ID + "/messages"));
        assertTrue(
            posted.ok(),
            "the in-container agent could not post to the spec's room: "
                + posted.stdout()
                + posted.stderr());
        assertTrue(
            posted.stdout().contains("claude/"),
            "a posted message must be attributed to the run principal: " + posted.stdout());

        var room =
            exec(
                CONTAINER,
                List.of(
                    "curl",
                    "--silent",
                    "--show-error",
                    "--fail-with-body",
                    "--unix-socket",
                    CONTAINER_DIR.resolve("api.sock").toString(),
                    "-H",
                    "Authorization: Bearer " + credential,
                    "http://sail/v1/specs/" + SPEC_ID + "/messages"));
        assertTrue(
            room.stdout().contains("mid-run progress from inside the container"),
            "the posted message must read back through the socket: " + room.stdout());

        var ambient =
            exec(
                CONTAINER,
                List.of(
                    "bash",
                    "-c",
                    "curl --silent --show-error --fail-with-body --unix-socket "
                        + CONTAINER_DIR.resolve("api.sock")
                        + " -H \"Authorization: Bearer $(cat "
                        + CONTAINER_DIR.resolve(BoxCredentialFile.FILE_NAME)
                        + ")\" http://sail/v1/whoami"));
        assertTrue(
            ambient.ok(),
            "a session with no injected credential must authenticate via the ambient file: "
                + ambient.stdout()
                + ambient.stderr());
        assertTrue(
            ambient.stdout().contains("\"it\"") && ambient.stdout().contains("box"),
            "the ambient credential must resolve to the box FDE: " + ambient.stdout());

        var interactive =
            exec(
                CONTAINER,
                List.of(
                    "bash",
                    "-c",
                    "curl --silent --show-error --fail-with-body --unix-socket "
                        + CONTAINER_DIR.resolve("api.sock")
                        + " -H \"Authorization: Bearer $(cat "
                        + CONTAINER_DIR.resolve(BoxCredentialFile.FILE_NAME)
                        + ")\" -X POST --data-urlencode \"body=guidance from the engineer\""
                        + " http://sail/v1/specs/"
                        + SPEC_ID
                        + "/messages"));
        assertTrue(
            interactive.ok(),
            "the box FDE must be able to post to the room: "
                + interactive.stdout()
                + interactive.stderr());
        assertTrue(
            interactive.stdout().contains("\"author\": \"it\""),
            "an interactive post is attributed to the FDE, not a principal: "
                + interactive.stdout());

        var bogus =
            exec(
                CONTAINER,
                List.of(
                    "curl",
                    "--silent",
                    "--unix-socket",
                    CONTAINER_DIR.resolve("api.sock").toString(),
                    "-H",
                    "Authorization: Bearer sailbox_" + "0".repeat(64),
                    "-o",
                    "/dev/null",
                    "-w",
                    "%{http_code}",
                    "http://sail/v1/whoami"));
        assertTrue(
            bogus.stdout().contains("401"),
            "a forged box credential must be refused: " + bogus.stdout());
      }
    } finally {
      deleteContainerQuietly(CONTAINER);
      deleteRecursively(socketDir);
      deleteRecursively(dbPath.getParent());
    }
  }

  private static SpecStore.SpecRow seededSpec() {
    return new SpecStore.SpecRow(
        SPEC_ID,
        CONTAINER,
        "Reachability probe",
        SpecStatus.PENDING,
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
