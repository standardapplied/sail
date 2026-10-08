/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.authority.Refusal;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.sync.ConflictMerge;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A1 and A2 through the real doors of main: the cases the audit of 2026-09-29 found a door
 * answering with its own code, or writing with no rule asked. Each is now the type's rule, decided
 * where the revision is journaled, and answered with the rule's one code.
 */
class AuthorityAuditTest {

  private static final SyncConfig MAIN = new SyncConfig("main", null, "root", "main-box");
  private static final SyncConfig ADAS_NODE = new SyncConfig("node", "sail@main", "ada", "ada");

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
  private String adaToken;
  private String bobToken;
  private String vicToken;
  private String vicSession;

  @BeforeEach
  void setUp() throws IOException {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    var fdes = new FdeStore(db);
    fdes.add("root", null, null, "admin");
    var ada = fdes.add("ada", null, null, "member");
    var bob = fdes.add("bob", null, null, "member");
    var vic = fdes.add("vic", null, null, "viewer");
    var tokens = new TokenStore(db);
    adaToken = tokens.create("ada", "member", ada.id(), null).token();
    bobToken = tokens.create("bob", "member", bob.id(), null).token();
    vicToken = tokens.create("vic", "viewer", vic.id(), null).token();
    vicSession = new AuthSessionStore(db).create(vic.id(), Duration.ofHours(1)).token();
    Acting.as(
        "bob",
        () -> {
          new SpecStore(db).create(spec("theirs", "bob"));
          new RoomStore(db).create(room("theirs", "bob"));
          new RoomStore(db).create(room("den", "bob"));
        });
    Acting.as("ada", () -> new SpecStore(db).create(spec("mine", "ada")));
    operations = operations(db, MAIN, "box");
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

  /** A1: HTTP's write tier answered {@code forbidden} where the socket and the CLI answer this. */
  @Test
  void aViewersEditIsRefusedAsReadOnlyOverHttpAsOnTheSocketAndCli() throws Exception {
    var readOnly =
        Map.of(
            "code",
            "read_only_credential",
            "message",
            "Your credential is read-only and cannot change specs.",
            "action",
            "Ask an admin for a member or admin credential.",
            "field_errors",
            List.of());

    for (var credential : List.of(vicToken, vicSession)) {
      var response = http("PUT", "/v1/specs/mine", credential, "{\"title\":\"x\"}");
      assertEquals(403, response.statusCode(), response.body());
      assertEquals(readOnly, YamlUtil.parseMap(response.body()).get("error"));
    }
    var cli =
        Actor.call(
            new Actor("vic", Role.VIEWER, Actor.Lane.CLI),
            () ->
                operations.updateGlobalSpec(
                    "mine", SpecUpdateRequest.fromMap(Map.of("title", "x"))));
    var failure = assertInstanceOf(Result.Failure.class, cli);
    assertEquals(ErrorCode.READ_ONLY_CREDENTIAL, failure.errorCode());
    assertEquals(readOnly.get("message"), failure.errorMessage());
    assertEquals("mine", new SpecStore(db).findById("mine").orElseThrow().title());
  }

  /**
   * A1: a write no row's rule decides before it starts — bytes kept for a shared file, a snapshot
   * removed — is refused a read-only credential with the rule's own refusal, never a route tier's.
   */
  @Test
  void aViewersFileAndSnapshotAreRefusedAsReadOnlyToo() throws Exception {
    var file = http("PUT", "/v1/projects/acme/files/notes.md", vicToken, "hello");
    var snapshot = http("DELETE", "/v1/projects/acme/snapshots/snap-1", vicToken, "");
    var restore = http("POST", "/v1/projects/acme/snapshots/snap-1/restore", vicToken, "");

    assertEquals(403, file.statusCode(), file.body());
    assertEquals(
        Map.of(
            "code",
            "read_only_credential",
            "message",
            "Your credential is read-only and cannot change files.",
            "action",
            "Ask an admin for a member or admin credential.",
            "field_errors",
            List.of()),
        YamlUtil.parseMap(file.body()).get("error"));
    assertEquals(403, snapshot.statusCode(), snapshot.body());
    assertTrue(snapshot.body().contains("read-only and cannot delete snapshots"), snapshot.body());
    assertTrue(snapshot.body().contains("\"read_only_credential\""), snapshot.body());
    assertEquals(403, restore.statusCode(), restore.body());
    assertTrue(restore.body().contains("read-only and cannot restore snapshots"), restore.body());
  }

  /**
   * A1: Ada managing root's spec-less room on main. Deleting it and dismissing its member ask the
   * same room rule; the launch admission used to re-map the second to {@code not_your_spec}.
   */
  @Test
  void theRoomRulesRefusalReadsTheSameForEveryRoomWrite() throws Exception {
    Acting.as("root", () -> new RoomStore(db).create(room("lounge", "root")));

    var delete = http("DELETE", "/v1/rooms/lounge", adaToken, "");
    var dismiss =
        Actor.call(
            new Actor("ada", Role.MEMBER, Actor.Lane.API),
            () -> operations.removeRoomMember("lounge", "root"));

    var deleted = assertInstanceOf(Map.class, YamlUtil.parseMap(delete.body()).get("error"));
    var dismissed = assertInstanceOf(Result.Failure.class, dismiss);
    assertEquals("forbidden_not_assignee", deleted.get("code"));
    assertEquals(ErrorCode.FORBIDDEN_NOT_ASSIGNEE, dismissed.errorCode());
    assertEquals("Room 'lounge' belongs to 'root', not you.", deleted.get("message"));
    assertEquals(deleted.get("message"), dismissed.errorMessage(), "same rule, same words");
    assertTrue(new RoomStore(db).findById("lounge").isPresent());
  }

  /**
   * A1: main's commit takes Bob's spec born over his own room (the spec rule: the room is already
   * its owner's); the HTTP door used to refuse it 409. Anyone else's is the rule's refusal.
   */
  @Test
  void aSpecBornOverItsOwnersRoomIsAcceptedAtTheDoorAsMainAcceptsIt() throws Exception {
    var body = "{\"id\":\"den\",\"project\":\"acme\",\"title\":\"den work\"}";

    var taken = http("POST", "/v1/specs", adaToken, body);
    var born = http("POST", "/v1/specs", bobToken, body);

    assertEquals(403, taken.statusCode(), taken.body());
    assertTrue(taken.body().contains("\"forbidden_not_assignee\""), taken.body());
    assertEquals(201, born.statusCode(), born.body());
    assertEquals("den", new SpecStore(db).findById("den").orElseThrow().roomIdOrIdentity());
    assertEquals(
        "den", new RoomStore(db).findById("den").orElseThrow().title(), "adopted as it is");
  }

  /** Evidence for the test above: main's commit accepts the same birth pushed from Bob's box. */
  @Test
  void mainAcceptsASpecBornOverItsOwnersRoom() {
    try (var node = new SyncBox("bob")) {
      for (var entity : SyncedEntities.all()) {
        SyncBox.round(db, node.db, "bob", entity.type());
      }
      Acting.as("bob", () -> node.specs.create(spec("den", null)));

      SyncBox.round(db, node.db, "bob", "spec");

      assertTrue(new SpecStore(db).findById("den").isPresent(), "main takes it");
    }
  }

  /**
   * A2: on Ada's node, a conflict resolved by merge wrote whatever the merged record said. A
   * reassignment of her spec is the spec rule's admin-only, for the host CLI's operator too.
   */
  @Test
  void aMergedResolutionIsDecidedByTheSpecRule() throws Exception {
    try (var node = new SyncBox("ada");
        var nodeOps = operations(node.db, ADAS_NODE, "ada")) {
      new FdeStore(node.db).add("ada", null, null, "member");
      Acting.as("ada", () -> node.specs.create(spec("auth", "ada")));
      var local = node.specs.comparableSnapshot("auth");
      var remote = new LinkedHashMap<>(local);
      remote.put("title", "main title");
      node.conflicts.record(
          "spec",
          "auth",
          YamlUtil.dumpJson(local),
          YamlUtil.dumpJson(local),
          YamlUtil.dumpJson(remote),
          "9-main",
          "main",
          List.of("title"));
      var merged =
          new LinkedHashMap<>(
              ConflictMerge.parseTemplate(nodeOps.conflictMergeTemplate("spec", "auth")));
      merged.put("title", "merged");
      merged.put("assignee", "carol");
      var resolution = new Resolution(Resolution.Strategy.MERGE, YamlUtil.dumpToString(merged));
      var operator = CliOperator.of(ADAS_NODE, new FdeStore(node.db));

      var refused =
          assertThrows(
              WriteRefused.class,
              () ->
                  Actor.call(operator, () -> nodeOps.resolveConflict("spec", "auth", resolution)));

      assertEquals(Refusal.Kind.ADMIN_ONLY, refused.refusal().kind());
      assertEquals("ada", node.specs.findById("auth").orElseThrow().assignee());
      assertEquals("auth", node.specs.findById("auth").orElseThrow().title(), "nothing adopted");
      assertTrue(node.conflicts.pendingFor("spec", "auth").isPresent(), "and it stays parked");
    }
  }

  private SailOperations operations(Sqlite box, SyncConfig config, String id) {
    return OperationsFactory.create(
            box, SHELL, null, null, SyncScheduler.disabled(), SessionYield.NONE)
        .useControlPlane(
            box,
            tempDir,
            new SyncOperations(
                box,
                id,
                tempDir,
                () -> config,
                target -> {
                  throw new IOException("main unavailable");
                }));
  }

  private HttpResponse<String> http(String method, String path, String credential, String json)
      throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
            .header("Authorization", "Bearer " + credential)
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(json))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
  }

  private static RoomStore.RoomRow room(String id, String owner) {
    return new RoomStore.RoomRow(id, "acme", id, owner, null, null, null, null, null, null);
  }

  private static SpecStore.SpecRow spec(String id, String assignee) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        id,
        SpecStatus.PENDING,
        assignee,
        null,
        null,
        null,
        null,
        0,
        null,
        "",
        "",
        null,
        List.of(),
        List.of());
  }
}
