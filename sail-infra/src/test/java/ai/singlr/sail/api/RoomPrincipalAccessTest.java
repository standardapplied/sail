/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The room credential's authority at the local socket, enforced at the boundary rather than
 * promised by the prompt: a {@code room}-role run reads and posts to its own spec's room, and every
 * spec mutation — status, metadata, content, delete, create, other rooms — returns 403.
 */
class RoomPrincipalAccessTest {

  private static final String HANDLE = "uday";

  private static final String YAML =
      """
      name: acme
      ssh:
        user: dev
      agent:
        type: claude-code
      """;

  @TempDir Path tempDir;
  private Sqlite db;
  private EventBus bus;
  private SpecStore specStore;
  private MessageStore messageStore;
  private LocalApiRouter router;
  private String credential;
  private String runId;
  private String principal;

  @BeforeEach
  void setUp() throws Exception {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, YAML);
    db = Sqlite.open(tempDir.resolve("room.db"));
    new SchemaManager(db).migrate();
    bus = new EventBus();
    specStore = new SpecStore(db);
    messageStore = new MessageStore(db);
    var runStore = new RunStore(db);
    new FdeStore(db).add(HANDLE, null, null, "admin");
    seedSpec("auth");
    seedSpec("other");
    runId = DateTimeUtils.newId().toString();
    principal = "claude/room-" + runId;
    var reservation =
        runStore.reserveDispatch(
            runId,
            "acme",
            "auth",
            HANDLE,
            HANDLE,
            "room",
            List.of(),
            "claude-code",
            null,
            "answer the room",
            "log",
            "sail-agent-" + runId);
    credential = ((RunStore.Reservation.Reserved) reservation).credential();
    var operations =
        new SailOperations(
                NoShell.INSTANCE,
                yaml.toString(),
                (command, logPath) -> 4242L,
                bus,
                null,
                specStore,
                new ReviewStore(db),
                runStore)
            .useMessages(messageStore);
    router = new LocalApiRouter(bus, operations);
  }

  @AfterEach
  void tearDown() {
    if (bus != null) {
      bus.close();
    }
    if (db != null) {
      db.close();
    }
  }

  private void seedSpec(String id) {
    specStore.create(
        new SpecStore.SpecRow(
            id,
            "acme",
            "OAuth flow",
            SpecStatus.DONE,
            HANDLE,
            null,
            null,
            null,
            null,
            0,
            HANDLE,
            "",
            "",
            null,
            List.of(),
            List.of()));
  }

  private ApiResponse call(String method, String path, String body) {
    return router.handle(
        new LocalApiRequest(
            method,
            path,
            Map.of(),
            Map.of("authorization", "Bearer " + credential),
            body.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void whoamiNamesTheRoomLaneAndItsReadOnlyRole() {
    var whoami = call("GET", "/v1/whoami", "");

    assertEquals(200, whoami.status());
    assertEquals(principal, whoami.body().get("handle"));
    assertEquals(HANDLE, whoami.body().get("owner"));
    assertEquals("viewer", whoami.body().get("role"));
    assertEquals("room", whoami.body().get("lane"));
    assertEquals(runId, whoami.body().get("run_id"));
  }

  @Test
  void everySpecMutationReturns403() {
    assertEquals(403, call("PUT", "/v1/specs/auth", "status=in_progress").status());
    assertEquals(403, call("PUT", "/v1/specs/auth", "priority=9").status());
    assertEquals(403, call("PUT", "/v1/specs/auth/content", "body=rewritten").status());
    assertEquals(403, call("DELETE", "/v1/specs/auth", "").status());
    assertEquals(403, call("POST", "/v1/specs", "id=sneaky&title=New&project=acme").status());

    assertEquals(SpecStatus.DONE, specStore.findById("auth").orElseThrow().status());
    assertTrue(specStore.findById("sneaky").isEmpty(), "no spec was created");
  }

  @Test
  void postingToItsOwnSpecsRoomIsTheOneAllowedWrite() {
    var posted = call("POST", "/v1/specs/auth/messages", "body=the answer");

    assertEquals(201, posted.status());
    var messages = messageStore.list("auth", null, 10);
    assertEquals(1, messages.size());
    assertEquals(principal, messages.getFirst().author());
    assertEquals("the answer", messages.getFirst().body());
  }

  @Test
  void postingToAnotherSpecsRoomReturns403EvenForTheSameOwner() {
    var posted = call("POST", "/v1/specs/other/messages", "body=drive-by");

    assertEquals(403, posted.status());
    assertTrue(messageStore.list("other", null, 10).isEmpty());
  }

  @Test
  void readsStayOpenToTheRoomSession() {
    assertEquals(200, call("GET", "/v1/specs/auth", "").status());
    assertEquals(200, call("GET", "/v1/specs/auth/messages", "").status());
    assertEquals(200, call("GET", "/v1/specs", "").status());
  }

  private enum NoShell implements ShellExec {
    INSTANCE;

    @Override
    public ShellExec.Result exec(List<String> command) {
      return new ShellExec.Result(1, "", "no shell in this test");
    }

    @Override
    public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
