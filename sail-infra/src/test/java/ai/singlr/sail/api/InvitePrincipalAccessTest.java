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
 * The two invite credentials at the local socket, enforced at the boundary: a read-only {@code
 * invite} run carries the room contract verbatim (viewer role, chat is the one write), and an
 * {@code invite-full} run carries exactly the member-tier agent principal a dispatched agent holds
 * — it can post, draft the spec body, and create sibling specs that are born draft and attributed
 * to its principal.
 */
class InvitePrincipalAccessTest {

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
  private String readOnlyCredential;
  private String readOnlyRunId;
  private String fullCredential;
  private String fullRunId;

  @BeforeEach
  void setUp() throws Exception {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, YAML);
    db = Sqlite.open(tempDir.resolve("invite-access.db"));
    new SchemaManager(db).migrate();
    bus = new EventBus();
    specStore = new SpecStore(db);
    messageStore = new MessageStore(db);
    var runStore = new RunStore(db);
    new FdeStore(db).add(HANDLE, null, null, "admin");
    seedSpec("auth");
    seedSpec("other");
    readOnlyRunId = DateTimeUtils.newId().toString();
    readOnlyCredential = reserve(runStore, readOnlyRunId, "invite", "claude-code");
    fullRunId = DateTimeUtils.newId().toString();
    fullCredential = reserve(runStore, fullRunId, "invite-full", "codex");
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

  private static String reserve(RunStore runStore, String runId, String role, String agent) {
    var reservation =
        runStore.reserveDispatch(
            runId,
            "acme",
            "auth",
            HANDLE,
            HANDLE,
            role,
            List.of(),
            agent,
            null,
            "help in the room",
            "log",
            "sail-agent-" + runId);
    return ((RunStore.Reservation.Reserved) reservation).credential();
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

  private ApiResponse call(String credential, String method, String path, String body) {
    return router.handle(
        new LocalApiRequest(
            method,
            path,
            Map.of(),
            Map.of("authorization", "Bearer " + credential),
            body.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void aReadOnlyInviteWhoamiNamesTheRoomLaneAndItsViewerRole() {
    var whoami = call(readOnlyCredential, "GET", "/v1/whoami", "");

    assertEquals(200, whoami.status());
    assertEquals("claude/invite-" + readOnlyRunId, whoami.body().get("handle"));
    assertEquals(HANDLE, whoami.body().get("owner"));
    assertEquals("viewer", whoami.body().get("role"));
    assertEquals("room", whoami.body().get("lane"));
  }

  @Test
  void aReadOnlyInviteIsRefusedEverySpecMutation() {
    assertEquals(403, call(readOnlyCredential, "PUT", "/v1/specs/auth", "status=pending").status());
    assertEquals(
        403, call(readOnlyCredential, "PUT", "/v1/specs/auth/content", "body=rewritten").status());
    assertEquals(
        403,
        call(readOnlyCredential, "POST", "/v1/specs", "id=sneaky&title=New&project=acme").status());
    assertTrue(specStore.findById("sneaky").isEmpty());
  }

  @Test
  void aReadOnlyInvitePostsOnlyToItsOwnSpecsRoom() {
    assertEquals(
        201, call(readOnlyCredential, "POST", "/v1/specs/auth/messages", "body=critique").status());
    assertEquals(
        403,
        call(readOnlyCredential, "POST", "/v1/specs/other/messages", "body=drive-by").status());

    var messages = messageStore.list("auth", null, 10);
    assertEquals(1, messages.size());
    assertEquals("claude/invite-" + readOnlyRunId, messages.getFirst().author());
  }

  @Test
  void aFullInviteWhoamiNamesTheAgentLaneAndItsMemberRole() {
    var whoami = call(fullCredential, "GET", "/v1/whoami", "");

    assertEquals(200, whoami.status());
    assertEquals("codex/invite-" + fullRunId, whoami.body().get("handle"));
    assertEquals(HANDLE, whoami.body().get("owner"));
    assertEquals("member", whoami.body().get("role"));
    assertEquals("agent", whoami.body().get("lane"));
  }

  @Test
  void aFullInviteCreatesASpecBornDraftAttributedToItsPrincipal() {
    var created =
        call(
            fullCredential,
            "POST",
            "/v1/specs",
            "id=oauth-hardening&title=Harden the flow&project=acme");

    assertEquals(201, created.status());
    var spec = specStore.findById("oauth-hardening").orElseThrow();
    assertEquals(SpecStatus.DRAFT, spec.status(), "new specs from a room conversation are drafts");
    assertEquals("codex/invite-" + fullRunId, spec.createdBy());
  }

  @Test
  void aFullInvitePostsToTheRoomUnderItsOwnPrincipal() {
    var posted = call(fullCredential, "POST", "/v1/specs/auth/messages", "body=shipping a fix");

    assertEquals(201, posted.status());
    assertEquals(
        "codex/invite-" + fullRunId, messageStore.list("auth", null, 10).getFirst().author());
  }

  @Test
  void aFullInviteRevisesTheSpecBodyTheBrainstormLane() {
    var revised = call(fullCredential, "PUT", "/v1/specs/auth/content", "body=A sharper spec body");

    assertEquals(200, revised.status());
    assertEquals(
        "A sharper spec body",
        specStore.getContent("auth").orElseThrow().body(),
        "drafting the spec body via the CLI is the brainstorm flow's whole point");
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
