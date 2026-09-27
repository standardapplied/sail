/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ConflictOperations;
import ai.singlr.sail.engine.FileMaterializer;
import ai.singlr.sail.engine.SharedProjectFiles;
import ai.singlr.sail.engine.WorkspaceFiles;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.sync.ConflictMerge;
import ai.singlr.sail.sync.SyncBox;
import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ApiRouterTest {

  @Test
  void syncAndConflictsAreAvailableThroughTheOperationsRoutes() throws Exception {
    var operations = new SeamOperations();
    try (var server = serverWith(operations, true)) {
      var status = get(server, "/v1/sync", "token");
      assertEquals(200, status.statusCode());
      assertTrue(status.body().contains("main@host"));
      assertEquals(
          List.of(Map.of("type", "spec", "id", "auth", "reason", "your role is read-only")),
          YamlUtil.parseMap(status.body()).get("denials"),
          "GET /v1/sync lists the last round's denials");
      assertEquals(422, post(server, "/v1/sync", "token", "{\"main\":3}").statusCode());
      var round = post(server, "/v1/sync", "token", "{\"main\":\"alternate\"}");
      assertEquals(200, round.statusCode());
      assertEquals("alternate", operations.request.main());
      assertTrue(round.body().contains("\"pushed\": 2"));
      assertEquals(
          List.of(Map.of("type", "spec", "id", "auth", "reason", "your role is read-only")),
          YamlUtil.parseMap(round.body()).get("denials"));
      var conflicts = get(server, "/v1/conflicts", "token");
      assertEquals(200, conflicts.statusCode());
      assertTrue(conflicts.body().contains("acme/config"));
      assertEquals(200, get(server, "/v1/conflicts/acme/config", "token").statusCode());
      assertEquals(404, get(server, "/v1/conflicts/missing", "token").statusCode());
      assertEquals(
          200,
          post(server, "/v1/conflicts/acme/config/resolve", "token", "{\"strategy\":\"theirs\"}")
              .statusCode());
      assertEquals(Resolution.Strategy.THEIRS, operations.resolution.strategy());
      assertEquals(
          422,
          post(server, "/v1/conflicts/acme/config/resolve", "token", "{\"strategy\":\"invalid\"}")
              .statusCode());
      assertEquals(
          422, post(server, "/v1/conflicts/acme/config/resolve", "token", "{}").statusCode());
      assertEquals(413, post(server, "/v1/sync", "token", "x".repeat(66 * 1024 + 1)).statusCode());
      assertEquals(
          413,
          post(server, "/v1/conflicts/acme/config/resolve", "token", "x".repeat(66 * 1024 + 1))
              .statusCode());
      assertEquals(405, delete(server, "/v1/sync", "token").statusCode());
      assertEquals(405, post(server, "/v1/conflicts", "token", "{}").statusCode());
      assertEquals(405, put(server, "/v1/conflicts/acme/config", "token", "{}").statusCode());
    }
  }

  @Test
  void aConflictIsAddressedByTypeAndAStaleOrAmbiguousResolveIsRefused() throws Exception {
    try (var box = new SyncBox("node")) {
      var rooms = new RoomStore(box.db);
      Acting.system(() -> box.specs.create(SyncBox.spec("auth", "local", "pending")));
      Acting.system(() -> rooms.ensureFor("auth", "proj", "Auth", "uday", "mention"));
      park(box, "spec", box.specs.comparableSnapshot("auth"), "title", "remote");
      park(box, "room", rooms.comparableSnapshot("auth"), "wake", "off");
      var operations =
          new TestOperations() {
            @Override
            public SyncConflicts.Conflict conflict(String type, String id) {
              return new ConflictOperations(box.db).find(type, id);
            }

            @Override
            public SyncConflicts.Conflict resolveConflict(
                String type, String id, Resolution resolution) {
              return new ConflictOperations(box.db).resolve(type, id, resolution);
            }
          };
      try (var server = serverWith(operations, true)) {
        var ambiguous = get(server, "/v1/conflicts/auth", "token");
        assertEquals(400, ambiguous.statusCode(), ambiguous.body());
        assertTrue(ambiguous.body().contains("open conflicts as spec and room"), ambiguous.body());
        var blind = post(server, "/v1/conflicts/auth/resolve", "token", "{\"strategy\":\"mine\"}");
        assertEquals(400, blind.statusCode(), blind.body());
        assertTrue(blind.body().contains("open conflicts as spec and room"), blind.body());
        var room = get(server, "/v1/conflicts/auth?type=room", "token");
        assertEquals(200, room.statusCode(), room.body());
        assertEquals("room", YamlUtil.parseMap(room.body()).get("entity_type"));
        assertEquals(404, get(server, "/v1/conflicts/auth?type=file", "token").statusCode());

        Acting.system(
            () -> box.specs.setContent("auth", "written after the conflict was recorded", ""));
        var stale =
            post(
                server, "/v1/conflicts/auth/resolve?type=spec", "token", "{\"strategy\":\"mine\"}");
        assertEquals(409, stale.statusCode(), stale.body());
        assertTrue(
            stale.body().contains("changed on this box after this conflict was recorded (body)"),
            stale.body());
        assertTrue(stale.body().contains("Run 'sail sync' to refresh it"), stale.body());
        assertEquals(2, box.conflicts.pending().size());

        var resolved =
            post(
                server,
                "/v1/conflicts/auth/resolve?type=room",
                "token",
                "{\"strategy\":\"theirs\"}");
        assertEquals(200, resolved.statusCode(), resolved.body());
        assertEquals("off", rooms.findById("auth").orElseThrow().wake());
        var remaining = get(server, "/v1/conflicts/auth", "token");
        assertEquals(200, remaining.statusCode(), remaining.body());
        assertEquals("spec", YamlUtil.parseMap(remaining.body()).get("entity_type"));
      }
    }
  }

  private static void park(
      SyncBox box, String type, Map<String, Object> local, String field, String theirs) {
    var remote = new LinkedHashMap<>(local);
    remote.put(field, theirs);
    box.conflicts.record(
        type,
        "auth",
        YamlUtil.dumpJson(local),
        YamlUtil.dumpJson(local),
        YamlUtil.dumpJson(remote),
        List.of(field));
  }

  @Test
  void aMergeSettlesOnlyTheVersionOfTheConflictItWasMadeFrom() throws Exception {
    try (var box = new SyncBox("node")) {
      Acting.system(() -> box.specs.create(SyncBox.spec("auth", "local", "pending")));
      var local = box.specs.comparableSnapshot("auth");
      park(box, "spec", local, "title", "remote");
      var conflicts = new ConflictOperations(box.db);
      var rev = box.specs.revOf("auth");
      var operations =
          new TestOperations() {
            @Override
            public String conflictMergeTemplate(String type, String id) {
              return conflicts.mergeTemplate(type, id);
            }

            @Override
            public SyncConflicts.Conflict resolveConflict(
                String type, String id, Resolution resolution) {
              return conflicts.resolve(type, id, resolution);
            }
          };
      try (var server = serverWith(operations, true)) {
        var started = template(server);
        var path = "/v1/conflicts/auth/resolve?type=spec";
        var unnamed =
            post(
                server,
                path,
                "token",
                mergeBody(started.replaceAll("(?m)^" + ConflictMerge.CONFLICT + ": .*\n", "")));
        assertEquals(400, unnamed.statusCode(), unnamed.body());
        assertTrue(
            unnamed.body().contains("must start from 'sail conflicts show auth --template'"),
            unnamed.body());

        park(box, "spec", local, "title", "remote, revised");
        var parked = box.conflicts.pendingFor("spec", "auth").orElseThrow();
        var moved = post(server, path, "token", mergeBody(started));
        assertEquals(409, moved.statusCode(), moved.body());
        assertTrue(
            moved.body().contains("'auth' was re-recorded after this merge was started"),
            moved.body());
        assertEquals(parked, box.conflicts.pendingFor("spec", "auth").orElseThrow());
        assertEquals(rev, box.specs.revOf("auth"));
        assertEquals(local, box.specs.comparableSnapshot("auth"));

        var fresh = post(server, path, "token", mergeBody(template(server)));
        assertEquals(200, fresh.statusCode(), fresh.body());
        assertEquals("merged", box.specs.findById("auth").orElseThrow().title());
        assertTrue(box.conflicts.pending().isEmpty());
        var gone = get(server, "/v1/conflicts/auth?type=spec&template=true", "token");
        assertEquals(404, gone.statusCode(), gone.body());
        assertTrue(gone.body().contains("No open conflict for 'auth'."), gone.body());
        var settled = post(server, path, "token", mergeBody(started));
        assertEquals(404, settled.statusCode(), settled.body());
      }
    }
  }

  private static String template(SailApiServer server) throws Exception {
    var response = get(server, "/v1/conflicts/auth?type=spec&template=true", "token");
    assertEquals(200, response.statusCode(), response.body());
    return (String) YamlUtil.parseMap(response.body()).get("template");
  }

  private static String mergeBody(String template) {
    assertTrue(template.contains("\ntitle: local\n"), template);
    return YamlUtil.dumpJson(
        Map.of(
            "strategy",
            "merge",
            "merged",
            template.replace("\ntitle: local\n", "\ntitle: merged\n")));
  }

  @Test
  void fileListingsUseMetadataAndConditionalDownloadsNeverOpenTheBlob() throws Exception {
    var operations = new SeamOperations();
    Acting.system(() -> operations.files.put("data", new byte[] {1, 2, 3}));
    try (var server = serverWith(operations, true)) {
      var listed = get(server, "/v1/projects/acme/files", "token");
      assertTrue(listed.body().contains("content_hash"));
      assertTrue(listed.body().contains("mode"));
      assertTrue(listed.body().contains("kind"));
      assertEquals(0, operations.openedFiles);
      var download = get(server, "/v1/projects/acme/files/data", "token");
      assertEquals("3", download.headers().firstValue("content-length").orElseThrow());
      var etag = download.headers().firstValue("etag").orElseThrow();
      assertEquals("\"" + ai.singlr.sail.store.BlobStore.hash(new byte[] {1, 2, 3}) + "\"", etag);
      var request =
          java.net.http.HttpRequest.newBuilder(
                  java.net.URI.create(
                      "http://127.0.0.1:" + server.port() + "/v1/projects/acme/files/data"))
              .header("Authorization", "Bearer token")
              .header("If-None-Match", etag)
              .GET()
              .build();
      try (var client = java.net.http.HttpClient.newHttpClient()) {
        var cached = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(304, cached.statusCode());
        assertEquals("", cached.body());
      }
      assertEquals(1, operations.openedFiles);
    }
  }

  @Test
  void fileRoutesCarryRawBytesAndEnforceTheHostCap() throws Exception {
    var operations = new SeamOperations();
    try (var server = serverWith(operations, true)) {
      assertEquals(200, get(server, "/v1/projects/acme/files", "token").statusCode());
      assertEquals(404, get(server, "/v1/projects/acme/files/config", "token").statusCode());
      var content = "raw\u0000file\n";
      assertEquals(
          200, put(server, "/v1/projects/acme/files/dir/config", "token", content).statusCode());
      var file = get(server, "/v1/projects/acme/files/dir/config", "token");
      assertEquals(200, file.statusCode());
      assertEquals(content, file.body());
      assertEquals(
          "application/octet-stream", file.headers().firstValue("content-type").orElseThrow());
      assertEquals(200, put(server, "/v1/projects/acme/files/empty", "token", "").statusCode());
      assertEquals("", get(server, "/v1/projects/acme/files/empty", "token").body());
      assertEquals(
          200, put(server, "/v1/projects/acme/files/cap", "token", "x".repeat(1024)).statusCode());
      var large = put(server, "/v1/projects/acme/files/large", "token", "x".repeat(1024 + 1));
      assertEquals(413, large.statusCode());
      assertTrue(large.body().contains("raise limits.file_max in host.yaml"), large.body());
      assertEquals(4, operations.capConsulted, "one cap read per PUT");
      assertTrue(get(server, "/v1/projects/acme/files", "token").body().contains("dir/config"));
      assertEquals(
          200,
          put(server, "/v1/projects/acme/files/dir/%20/config", "token", content).statusCode());
      assertTrue(operations.files.containsKey("dir/ /config"));
      assertEquals(content, get(server, "/v1/projects/acme/files/dir/%20/config", "token").body());
      assertEquals(422, get(server, "/v1//projects/acme/files/config", "token").statusCode());
      assertFalse(operations.files.containsKey("large"));
      assertEquals(200, delete(server, "/v1/projects/acme/files/dir/config", "token").statusCode());
      assertEquals(404, delete(server, "/v1/projects/acme/files/dir/config", "token").statusCode());
      assertEquals(405, put(server, "/v1/projects/acme/files", "token", content).statusCode());
      assertEquals(405, post(server, "/v1/projects/acme/files/config", "token", "{}").statusCode());
      assertEquals(
          422, put(server, "/v1/projects/acme/files/../outside", "token", content).statusCode());
    }
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(ints = {0600, 0750})
  void filePutsPreserveExistingPermissionsAndDefaultNewFilesAcrossSync(
      Integer existingMode, @TempDir Path directory) throws Exception {
    try (var main = new SyncBox(directory, "main");
        var node = new SyncBox(directory, "node")) {
      var mainFiles =
          new SharedProjectFiles(
              new FileStore(main.db),
              directory.resolve("main-projects"),
              "acme",
              ai.singlr.sail.config.FileLimits.defaults());
      var nodeFiles =
          new SharedProjectFiles(
              new FileStore(node.db),
              directory.resolve("node-projects"),
              "acme",
              ai.singlr.sail.config.FileLimits.defaults());
      var path = "dir/config";
      if (existingMode != null) {
        var original = "original\n".getBytes(StandardCharsets.UTF_8);
        Acting.system(
            () ->
                mainFiles.put(
                    path, new ByteArrayInputStream(original), original.length, existingMode));
        mainFiles.materialize();
        SyncBox.round(main.db, node.db, "file");
        nodeFiles.materialize();
      }
      var operations =
          new TestOperations() {
            @Override
            public ProjectFiles projectFiles(String project) {
              return mainFiles;
            }
          };
      var updated = "updated\u0000bytes\n";
      try (var server = serverWith(operations, true)) {
        var response = put(server, "/v1/projects/acme/files/" + path, "token", updated);
        assertEquals(200, response.statusCode(), response.body());
      }
      SyncBox.round(main.db, node.db, "file");
      assertEquals(new FileMaterializer.Report(1, 0, List.of()), nodeFiles.materialize());

      var expectedMode = existingMode == null ? 0644 : existingMode;
      var updatedHash = BlobStore.hash(updated.getBytes(StandardCharsets.UTF_8));
      for (var files : List.of(mainFiles, nodeFiles)) {
        var row = files.find(path).orElseThrow();
        var materialized =
            Acting.system(() -> files.projectsDir().resolve("acme/files").resolve(path));
        assertAll(
            () -> assertEquals(expectedMode, row.mode()),
            () -> assertEquals(updatedHash, row.contentHash()),
            () -> assertEquals(expectedMode, WorkspaceFiles.mode(materialized)),
            () -> assertEquals(updated, Files.readString(materialized)));
      }
    }
  }

  @Test
  void aSharedFileNeedsADeclaredLengthAndAnOversizedHeaderIsRejectedWithoutABody()
      throws Exception {
    var operations = new SeamOperations();
    try (var server = serverWith(operations, true)) {
      var request =
          HttpRequest.newBuilder(uri(server, "/v1/projects/acme/files/data"))
              .header("Authorization", "Bearer token")
              .PUT(
                  HttpRequest.BodyPublishers.ofInputStream(
                      () -> new java.io.ByteArrayInputStream(new byte[] {1})))
              .build();
      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("Content-Length is required"));
      try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
        socket.setSoTimeout(5000);
        socket
            .getOutputStream()
            .write(
                ("PUT /v1/projects/acme/files/large HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer token\r\nContent-Length: 1025\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var line =
            new java.io.BufferedReader(
                    new java.io.InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII))
                .readLine();
        assertTrue(line.contains("413"), line);
      }
      assertTrue(operations.files.isEmpty());
    }
  }

  @Test
  void viewerCanReadSyncButCannotResolveOrWriteFiles() throws Exception {
    var operations = new SeamOperations();
    ApiAuth viewer = exchange -> exchange.setAttribute("token.role", "viewer");
    try (var server = serverWith(operations, true, viewer)) {
      assertEquals(200, get(server, "/v1/sync", "token").statusCode());
      assertEquals(200, get(server, "/v1/conflicts", "token").statusCode());
      assertEquals(
          403,
          post(server, "/v1/conflicts/acme/config/resolve", "token", "{\"strategy\":\"mine\"}")
              .statusCode());
      assertNull(operations.resolution);
      assertEquals(403, post(server, "/v1/sync", "token", "{}").statusCode());
      assertEquals(
          403, put(server, "/v1/projects/acme/files/config", "token", "data").statusCode());
      assertTrue(operations.files.isEmpty());
    }
  }

  private static final SyncSession.Denial DENIAL =
      new SyncSession.Denial("spec", "auth", "your role is read-only");

  private static final class SeamOperations extends TestOperations {
    private int openedFiles;
    private int capConsulted;
    private SyncRequest request;
    private Resolution resolution;
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private final SyncConflicts.Conflict conflict =
        new SyncConflicts.Conflict(
            1, "file", "acme/config", null, "{}", "{}", List.of("content"), "now", "pending", null);

    @Override
    public SyncStatus syncStatus() {
      return new SyncStatus(
          "node",
          "main@host",
          SyncEngine.Report.NONE,
          "in_sync",
          null,
          null,
          0,
          null,
          null,
          null,
          0,
          0,
          0,
          List.of(DENIAL));
    }

    @Override
    public SyncReport sync(SyncRequest request) {
      this.request = request;
      return new SyncReport(
          new SyncEngine.Report(1, 2, 3, 4),
          null,
          List.of(
              new SyncSession.TypeReport(
                  "spec", SyncEngine.Report.NONE, 0, 0, false, null, 0, 0, 0, List.of(DENIAL))));
    }

    @Override
    public List<SyncConflicts.Conflict> conflicts() {
      return List.of(conflict);
    }

    @Override
    public SyncConflicts.Conflict conflict(String type, String id) {
      return id.equals(conflict.entityId()) ? conflict : null;
    }

    @Override
    public SyncConflicts.Conflict resolveConflict(String type, String id, Resolution resolution) {
      this.resolution = resolution;
      return conflict;
    }

    @Override
    public ProjectFiles projectFiles(String project) {
      return new ProjectFiles() {
        public List<FileStore.FileRow> list() {
          return files.entrySet().stream()
              .map(
                  entry ->
                      new FileStore.FileRow(
                          project,
                          entry.getKey(),
                          ai.singlr.sail.store.BlobStore.hash(entry.getValue()),
                          entry.getValue().length,
                          0644,
                          "binary"))
              .toList();
        }

        public ai.singlr.sail.config.FileLimits limits() {
          capConsulted++;
          return new ai.singlr.sail.config.FileLimits(1024);
        }

        public Optional<FileStore.FileRow> find(String path) {
          return list().stream().filter(row -> row.path().equals(path)).findFirst();
        }

        public java.io.InputStream open(FileStore.FileRow row) {
          openedFiles++;
          return new java.io.ByteArrayInputStream(files.get(row.path()));
        }

        public String put(String path, java.io.InputStream bytes, long size, int mode) {
          try {
            Acting.system(() -> files.put(path, bytes.readAllBytes()));
          } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
          }
          return path;
        }

        public boolean remove(String path) {
          return files.remove(path) != null;
        }

        public FileMaterializer.Report materialize() {
          return new FileMaterializer.Report(0, 0, List.of());
        }
      };
    }
  }

  @Test
  void healthDoesNotRequireAuth() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/health", null);

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"status\": \"ok\""));
      assertTrue(response.body().contains("\"schema_version\": 1"));
    }
  }

  @Test
  void fdesListsTheRosterToAnAuthenticatedCaller() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/fdes", "token");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"handle\": \"ada\""));
      assertTrue(response.body().contains("\"display_name\": \"Ada Lovelace\""));
      assertTrue(response.body().contains("\"email\": \"ada@x.dev\""));
      assertTrue(response.body().contains("\"role\": \"admin\""));
      assertTrue(response.body().contains("\"handle\": \"bob\""));
      assertTrue(response.body().contains("\"schema_version\": 1"));
      assertTrue(
          response.body().indexOf("\"ada\"") < response.body().indexOf("\"bob\""),
          "the roster is served in the handle order the store returns");
    }
  }

  @Test
  void fdesRequiresABearerToken() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/fdes", null);

      assertEquals(401, response.statusCode());
      assertTrue(response.body().contains("missing_bearer_token"));
    }
  }

  @Test
  void protectedRoutesRequireBearerToken() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent", null);

      assertEquals(401, response.statusCode());
      assertTrue(response.body().contains("missing_bearer_token"));
    }
  }

  @Test
  void malformedBearerHeaderIsRejected() throws Exception {
    try (var server = server()) {
      var request =
          HttpRequest.newBuilder(uri(server, "/v1/projects/acme/agent"))
              .header("Authorization", "Token token")
              .GET()
              .build();

      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(401, response.statusCode());
      assertTrue(response.body().contains("missing_bearer_token"));
    }
  }

  @Test
  void duplicateBearerHeadersAreRejected() throws Exception {
    try (var server = server()) {
      var request =
          HttpRequest.newBuilder(uri(server, "/v1/projects/acme/agent"))
              .header("Authorization", "Bearer token")
              .header("Authorization", "Bearer token")
              .GET()
              .build();

      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(403, response.statusCode());
      assertTrue(response.body().contains("invalid_bearer_token"));
    }
  }

  @Test
  void protectedRoutesRejectWrongBearerToken() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent", "wrong");

      assertEquals(403, response.statusCode());
      assertTrue(response.body().contains("invalid_bearer_token"));
    }
  }

  @Test
  void dispatchParsesJsonBody() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/projects/acme/dispatch", "token", "{\"spec_id\": \"auth\"}");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"id\": \"auth\""));
      assertTrue(response.body().contains("\"name\": \"acme\""));
    }
  }

  @Test
  void dispatchParsesRestartFlag() throws Exception {
    try (var server = server()) {
      var response =
          post(
              server,
              "/v1/projects/acme/dispatch",
              "token",
              "{\"spec_id\": \"auth\", \"restart\": true}");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"restarted\": true"));
    }
  }

  @Test
  void dispatchDefaultsRestartToFalse() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/projects/acme/dispatch", "token", "{\"spec_id\": \"auth\"}");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"restarted\": false"));
    }
  }

  @Test
  void dispatchParsesSingleRepoTarget() throws Exception {
    try (var server = server()) {
      var response =
          post(
              server,
              "/v1/projects/acme/dispatch",
              "token",
              "{\"spec_id\": \"auth\", \"repo\": \"chorus\"}");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"repos\": [\"chorus\"]"));
    }
  }

  @Test
  void dispatchParsesMultipleRepoTargets() throws Exception {
    try (var server = server()) {
      var response =
          post(
              server,
              "/v1/projects/acme/dispatch",
              "token",
              "{\"spec_id\": \"auth\", \"repos\": [\"sing\", \"chorus\"]}");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"repos\": [\"sing\", \"chorus\"]"));
    }
  }

  @Test
  void dispatchParsesScalarReposTarget() throws Exception {
    try (var server = server()) {
      var response =
          post(
              server,
              "/v1/projects/acme/dispatch",
              "token",
              "{\"spec_id\": \"auth\", \"repos\": \"chorus\"}");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"repos\": [\"chorus\"]"));
    }
  }

  @Test
  void dispatchRejectsRepoAndReposTogether() throws Exception {
    try (var server = server()) {
      var response =
          post(
              server,
              "/v1/projects/acme/dispatch",
              "token",
              "{\"spec_id\": \"auth\", \"repo\": \"sing\", \"repos\": [\"chorus\"]}");

      assertEquals(400, response.statusCode());
      assertTrue(response.body().contains("invalid_json"));
    }
  }

  @Test
  void methodMismatchReturnsMethodNotAllowed() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/dispatch", "token");

      assertEquals(405, response.statusCode());
      assertTrue(response.body().contains("method_not_allowed"));
    }
  }

  @Test
  void unknownRouteReturnsNotFound() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/unknown", "token");

      assertEquals(404, response.statusCode());
      assertTrue(response.body().contains("not_found"));
    }
  }

  @Test
  void invalidProjectNameReturnsUnprocessableEntity() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/Bad_Name/agent", "token");

      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("invalid_request"));
    }
  }

  @Test
  void invalidSpecIdReturnsUnprocessableEntity() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/specs/Bad_Name", "token");

      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("invalid_request"));
    }
  }

  @Test
  void invalidJsonReturnsBadRequest() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/projects/acme/dispatch", "token", "{");

      assertEquals(400, response.statusCode());
      assertTrue(response.body().contains("invalid_json"));
    }
  }

  @Test
  void emptyJsonBodyDefaultsToEmptyRequest() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/projects/acme/dispatch", "token", "");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("schema_version"));
    }
  }

  @Test
  void unsupportedContentTypeReturnsUnsupportedMediaType() throws Exception {
    try (var server = server()) {
      var request =
          HttpRequest.newBuilder(uri(server, "/v1/projects/acme/dispatch"))
              .header("Authorization", "Bearer token")
              .header("Content-Type", "text/plain")
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();

      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(415, response.statusCode());
      assertTrue(response.body().contains("unsupported_media_type"));
    }
  }

  @Test
  void oversizedBodyReturnsPayloadTooLarge() throws Exception {
    try (var server = server()) {
      var body = "{\"value\":\"" + "x".repeat(70_000) + "\"}";
      var response = post(server, "/v1/projects/acme/dispatch", "token", body);

      assertEquals(413, response.statusCode());
      assertTrue(response.body().contains("request_too_large"));
    }
  }

  @Test
  void invalidTailReturnsUnprocessableEntity() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/runs/r1/log?tail=0", "token");

      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("invalid_tail"));
    }
  }

  @Test
  void validTailIsPassedToOperations() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/runs/r1/log?tail=42", "token");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("tail=42"));
    }
  }

  @Test
  void invalidTailTextReturnsUnprocessableEntity() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/runs/r1/log?tail=abc", "token");

      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("invalid_tail"));
    }
  }

  @Test
  void missingTailValueReturnsUnprocessableEntity() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/runs/r1/log?tail", "token");

      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("invalid_tail"));
    }
  }

  @Test
  void urlDecodedTailIsPassedToOperations() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/runs/r1/log?tail=4%32", "token");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("tail=42"));
    }
  }

  @Test
  void missingTailQueryValueUsesDefault() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/runs/r1/log?foo=bar", "token");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("tail=200"));
    }
  }

  @Test
  void runListAndDetailAndStopAreRouted() throws Exception {
    try (var server = server()) {
      assertEquals(200, get(server, "/v1/runs?project=acme", "token").statusCode());
      var detail = get(server, "/v1/runs/r1", "token");
      assertEquals(200, detail.statusCode());
      assertTrue(detail.body().contains("\"node\""));
      assertEquals(200, post(server, "/v1/runs/r1/stop", "token", "{}").statusCode());
    }
  }

  @Test
  void snapshotRoutesListDeleteAndRestore() throws Exception {
    try (var server = server()) {
      var list = get(server, "/v1/projects/acme/snapshots", "token");
      assertEquals(200, list.statusCode());
      assertTrue(list.body().contains("\"invite-run-7\""));
      assertTrue(list.body().contains("\"source\": \"invite\""));

      var deleted = delete(server, "/v1/projects/acme/snapshots/invite-run-7", "token");
      assertEquals(202, deleted.statusCode());
      assertTrue(deleted.body().contains("\"status\": \"accepted\""));
      assertTrue(deleted.body().contains("\"action\": \"delete\""));

      var restored =
          post(server, "/v1/projects/acme/snapshots/invite-run-7/restore", "token", "{}");
      assertEquals(202, restored.statusCode());
      assertTrue(restored.body().contains("\"action\": \"restore\""));
    }
  }

  @Test
  void snapshotRoutesRefuseWrongMethodsAndUnknownSubResources() throws Exception {
    try (var server = server()) {
      assertEquals(405, post(server, "/v1/projects/acme/snapshots", "token", "{}").statusCode());
      assertEquals(405, get(server, "/v1/projects/acme/snapshots/snap-1", "token").statusCode());
      assertEquals(
          405, get(server, "/v1/projects/acme/snapshots/snap-1/restore", "token").statusCode());
      assertEquals(
          404,
          post(server, "/v1/projects/acme/snapshots/snap-1/bogus", "token", "{}").statusCode());
      assertEquals(
          404,
          post(server, "/v1/projects/acme/snapshots/snap-1/restore/extra", "token", "{}")
              .statusCode());
    }
  }

  @Test
  void aRefusedSnapshotRestoreRendersTheRefusalVerbatim() throws Exception {
    try (var server = serverWith(new SnapshotRefusingOperations())) {
      server.start();

      var response = post(server, "/v1/projects/acme/snapshots/snap-1/restore", "token", "{}");
      assertEquals(409, response.statusCode());
      assertTrue(response.body().contains("\"agent_already_running\""));
      assertTrue(response.body().contains("would discard its live work"));
    }
  }

  @Test
  void unknownRunSubResourceReturnsNotFound() throws Exception {
    try (var server = server()) {
      assertEquals(404, get(server, "/v1/runs/r1/bogus", "token").statusCode());
      assertEquals(404, get(server, "/v1/runs/r1/log/extra", "token").statusCode());
      assertEquals(405, post(server, "/v1/runs/r1/log", "token", "{}").statusCode());
      assertEquals(405, get(server, "/v1/runs/r1/stop", "token").statusCode());
    }
  }

  @Test
  void shortUnknownRoutesReturnNotFound() throws Exception {
    try (var server = server()) {
      assertEquals(404, get(server, "/", "token").statusCode());
      assertEquals(404, get(server, "/v1", "token").statusCode());
      assertEquals(404, get(server, "/bad/projects/acme", "token").statusCode());
      assertEquals(404, get(server, "/v1/project/acme", "token").statusCode());
    }
  }

  @Test
  void knownResourcesWithWrongMethodsReturnMethodNotAllowed() throws Exception {
    try (var server = server()) {
      assertEquals(405, post(server, "/v1/projects/acme", "token", "{}").statusCode());
      assertEquals(405, post(server, "/v1/projects/acme/specs", "token", "{}").statusCode());
      assertEquals(405, get(server, "/v1/projects/acme/dispatch", "token").statusCode());
      assertEquals(405, post(server, "/v1/projects/acme/agent", "token", "{}").statusCode());
      assertEquals(405, get(server, "/v1/projects/acme/agent/report", "token").statusCode());
      assertEquals(405, post(server, "/v1/runs/r1", "token", "{}").statusCode());
    }
  }

  @Test
  void malformedKnownResourcesReturnMethodNotAllowed() throws Exception {
    try (var server = server()) {
      assertEquals(405, get(server, "/v1/projects/acme/specs/auth/extra", "token").statusCode());
      assertEquals(405, get(server, "/v1/projects/acme/dispatch/extra", "token").statusCode());
      assertEquals(405, get(server, "/v1/projects/acme/agent/log/extra", "token").statusCode());
    }
  }

  @Test
  void unknownAgentSubResourcesReturnNotFound() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/agent/unknown", "token");

      assertEquals(404, response.statusCode());
      assertTrue(response.body().contains("not_found"));
    }
  }

  @Test
  void projectsCollectionListsProjects() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects", "token");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"name\": \"acme\""));
      assertTrue(response.body().contains("\"container_status\": \"not_created\""));
      assertTrue(response.body().contains("\"total\": 2"));
    }
  }

  @Test
  void projectsCollectionRequiresAuth() throws Exception {
    try (var server = server()) {
      assertEquals(401, get(server, "/v1/projects", null).statusCode());
    }
  }

  @Test
  void projectsCollectionRejectsWrites() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/projects", "token", "{}");

      assertEquals(405, response.statusCode());
      assertTrue(response.body().contains("method_not_allowed"));
    }
  }

  @Test
  void connectReturnsTheStructuredSshTarget() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/projects/acme/connect", "token");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"server_ip\": \"203.0.113.7\""));
      assertTrue(response.body().contains("\"container_ip\": \"10.171.87.10\""));
      assertTrue(response.body().contains("\"container_user\": \"dev\""));
      assertTrue(response.body().contains("\"workstation_key_set\": true"));
    }
  }

  @Test
  void connectRejectsWrites() throws Exception {
    try (var server = server()) {
      assertEquals(405, post(server, "/v1/projects/acme/connect", "token", "{}").statusCode());
    }
  }

  @Test
  void connectSubResourcesReturnNotFound() throws Exception {
    try (var server = server()) {
      assertEquals(404, get(server, "/v1/projects/acme/connect/extra", "token").statusCode());
    }
  }

  @Test
  void routesAgentAndSpecEndpoints() throws Exception {
    try (var server = server()) {
      assertEquals(200, get(server, "/v1/projects/acme", "token").statusCode());
      assertEquals(200, get(server, "/v1/projects/acme/specs", "token").statusCode());
      assertEquals(200, get(server, "/v1/projects/acme/specs/auth", "token").statusCode());
      assertEquals(200, get(server, "/v1/projects/acme/agent", "token").statusCode());
      assertEquals(200, post(server, "/v1/projects/acme/agent/report", "token", "{}").statusCode());
      assertEquals(200, get(server, "/v1/runs/r1/log", "token").statusCode());
      assertEquals(200, post(server, "/v1/runs/r1/stop", "token", "{}").statusCode());
    }
  }

  @Test
  void operationExceptionsBecomeStructuredErrors() throws Exception {
    try (var server = serverWith(new FailingOperations())) {
      server.start();

      var response = get(server, "/v1/projects/acme/agent", "token");

      assertEquals(409, response.statusCode());
      assertTrue(response.body().contains("conflict"));
    }
  }

  @Test
  void unexpectedOperationExceptionsBecomeInternalErrors() throws Exception {
    try (var server = serverWith(new ExplodingOperations())) {
      server.start();

      var response = get(server, "/v1/projects/acme/agent", "token");

      assertEquals(500, response.statusCode());
      assertTrue(response.body().contains("internal"));
    }
  }

  @Test
  void responseHelpersAddSchema() {
    assertEquals(201, ApiResponse.created(Map.of("created", true)).status());
    assertFalse(
        ApiJson.withSchema(new ApiError("code", "message", "")).toString().contains("action"));
    assertTrue(
        ApiJson.withSchema(new ApiError("code", "message", "fix")).toString().contains("action"));
    assertThrows(NullPointerException.class, () -> new TokenAuth(null));
  }

  @Test
  void publishEventReturnsStampedId() throws Exception {
    try (var server = server()) {
      var body =
          "{\"v\":1,\"ts\":\"2026-05-21T12:34:56Z\",\"project\":\"light-grid\","
              + "\"type\":\"spec_dispatched\",\"agent\":\"sail\",\"host\":\"host-01\"}";
      var response = post(server, "/v1/events", "token", body);
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"id\": 1"));
      assertTrue(response.body().contains("\"event\""));
    }
  }

  @Test
  void publishEventRejectsBadJsonBody() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/events", "token", "{not even json}");
      assertEquals(400, response.statusCode());
    }
  }

  @Test
  void publishEventRejectsMissingRequiredField() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/events", "token", "{\"v\":1}");
      assertEquals(400, response.statusCode());
    }
  }

  @Test
  void eventsRejectsUnsupportedMethod() throws Exception {
    try (var server = server()) {
      var request =
          HttpRequest.newBuilder(uri(server, "/v1/events"))
              .header("Authorization", "Bearer token")
              .method("DELETE", HttpRequest.BodyPublishers.noBody())
              .build();
      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void specEventsRequiresSpecQueryParameter() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events", "token");
      assertEquals(422, response.statusCode());
      assertTrue(response.body().contains("spec query parameter is required"));
    }
  }

  @Test
  void specEventsReturnsScopedHistory() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events?spec=auth", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"spec\": \"auth\""));
      assertTrue(response.body().contains("\"limit\": 100"));
      assertTrue(response.body().contains("\"events\""));
      assertFalse(response.body().contains("\"since\""));
    }
  }

  @Test
  void specEventsPassesSinceCursorThrough() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events?spec=auth&since=42", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"since\": 42"));
    }
  }

  @Test
  void specEventsClampsLimitInsteadOfRejecting() throws Exception {
    try (var server = server()) {
      assertTrue(
          get(server, "/v1/events?spec=auth&limit=99999", "token")
              .body()
              .contains("\"limit\": 1000"));
      assertTrue(
          get(server, "/v1/events?spec=auth&limit=0", "token").body().contains("\"limit\": 1"));
    }
  }

  @Test
  void specEventsRejectsNonNumericLimit() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events?spec=auth&limit=abc", "token");
      assertEquals(400, response.statusCode());
    }
  }

  @Test
  void specEventsRejectsInvalidSince() throws Exception {
    try (var server = server()) {
      assertEquals(422, get(server, "/v1/events?spec=auth&since=abc", "token").statusCode());
      assertEquals(422, get(server, "/v1/events?spec=auth&since=-1", "token").statusCode());
    }
  }

  @Test
  void specEventsRejectsInvalidSpecId() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events?spec=Bad%20Spec!", "token");
      assertEquals(422, response.statusCode());
    }
  }

  @Test
  void specEventsRequiresAuth() throws Exception {
    try (var server = server()) {
      assertEquals(401, get(server, "/v1/events?spec=auth", null).statusCode());
    }
  }

  @Test
  void recentEventsReturnsArray() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/recent", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"events\""));
      assertTrue(response.body().contains("\"limit\": 100"));
    }
  }

  @Test
  void recentEventsHonorsLimitQueryParam() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/recent?limit=42", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"limit\": 42"));
    }
  }

  @Test
  void recentEventsRejectsInvalidLimit() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/recent?limit=99999", "token");
      assertEquals(422, response.statusCode());
    }
  }

  @Test
  void recentEventsRejectsNonNumericLimit() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/recent?limit=abc", "token");
      assertEquals(422, response.statusCode());
    }
  }

  @Test
  void eventBusStatsReturnsCounts() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/stats", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"published\""));
      assertTrue(response.body().contains("\"subscribers\""));
    }
  }

  @Test
  void unknownEventsPathReturns404() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/bogus", "token");
      assertEquals(404, response.statusCode());
    }
  }

  @Test
  void deeplyNestedEventsPathReturns404() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/events/recent/extra", "token");
      assertEquals(404, response.statusCode());
    }
  }

  @Test
  void recentEventsRejectsPost() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/events/recent", "token", "{}");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void eventBusStatsRejectsPost() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/events/stats", "token", "{}");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void eventsEndpointsRequireAuth() throws Exception {
    try (var server = server()) {
      assertEquals(401, get(server, "/v1/events/recent", null).statusCode());
      assertEquals(401, get(server, "/v1/events/stats", null).statusCode());
    }
  }

  @Test
  void eventModelsCoverConstruction() {
    var pub = new EventPublishResponse(7L, Map.of("k", "v"));
    var recent = new RecentEventsResponse(10, 2, List.of(Map.of("a", 1)));
    var stat = new SubscriberStatsView("audit", 1024, 0, 0L);
    var stats = new EventBusStatsResponse(5L, 1L, List.of(stat));
    assertEquals(7L, pub.id());
    assertEquals(2, recent.returned());
    assertEquals(1L, stats.rejectedSubscribers());
    assertEquals("audit", stats.subscribers().getFirst().name());
    var scoped = new SpecEventsResponse("auth", 3L, 100, 1, List.of(Map.of("id", 4)));
    assertEquals("auth", scoped.spec());
    assertEquals(3L, scoped.toMap().get("since"));
    assertEquals(1, scoped.toMap().get("returned"));
    assertFalse(
        new SpecEventsResponse("auth", null, 100, 0, List.of()).toMap().containsKey("since"));
  }

  @Test
  void reviewModelsCoverConstruction() {
    var stageRow =
        new ReviewStore.StageRow(
            "s1", "r1", "security", "agent", "passed", "codex", "t1", "t2", null);
    var stageView = StageView.from(stageRow, 3);
    assertEquals("security", stageView.name());
    assertEquals(3, stageView.findingCount());
    var map = stageView.toMap();
    assertEquals("codex", map.get("reviewer"));

    var reviewRow =
        new ReviewStore.ReviewRow("r1", "auth", 1, "passed", "t0", "t1", null, null, null);
    var reviewView = ReviewView.from(reviewRow, List.of(stageView));
    assertEquals(1, reviewView.iteration());
    var rmap = reviewView.toMap();
    assertEquals("r1", rmap.get("id"));

    var list = new ReviewListResponse("auth", List.of(reviewView));
    assertEquals("auth", list.toMap().get("spec_id"));

    var detail = new ReviewDetailResponse(reviewView, List.of());
    assertNotNull(detail.toMap().get("review"));

    var approve = new ReviewApproveResponse("r1", true);
    assertEquals(true, approve.toMap().get("approved"));

    var dismiss = new FindingDismissResponse("f1", true);
    assertEquals(true, dismiss.toMap().get("dismissed"));
  }

  @Test
  void globalSpecsListReturnsEmptyArray() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"specs\": []"));
      assertTrue(response.body().contains("\"total\": 0"));
    }
  }

  @Test
  void globalSpecsListAcceptsFilterParams() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs?status=pending&assignee=uday&q=auth", "token");
      assertEquals(200, response.statusCode());
    }
  }

  @Test
  void globalSpecCreateReturns201() throws Exception {
    try (var server = server()) {
      var response =
          post(
              server,
              "/v1/specs",
              "token",
              "{\"id\": \"auth\", \"title\": \"OAuth flow\", \"status\": \"draft\"}");
      assertEquals(201, response.statusCode());
      assertTrue(response.body().contains("\"id\": \"auth\""));
      assertTrue(response.body().contains("\"title\": \"OAuth flow\""));
    }
  }

  @Test
  void globalSpecGetReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"id\": \"auth-flow\""));
    }
  }

  @Test
  void globalSpecUpdateReturns200() throws Exception {
    try (var server = server()) {
      var response = put(server, "/v1/specs/auth-flow", "token", "{\"title\": \"Updated\"}");
      assertEquals(200, response.statusCode());
    }
  }

  @Test
  void globalSpecDeleteReturns200() throws Exception {
    try (var server = server()) {
      var response = delete(server, "/v1/specs/auth-flow", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"deleted\": true"));
    }
  }

  @Test
  void globalSpecContentGetReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/content", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"spec_id\": \"auth-flow\""));
    }
  }

  @Test
  void globalSpecContentSetReturns200() throws Exception {
    try (var server = server()) {
      var response =
          put(
              server,
              "/v1/specs/auth-flow/content",
              "token",
              "{\"body\": \"# Spec\", \"plan\": \"## Plan\"}");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"body\": \"# Spec\""));
    }
  }

  @Test
  void retiredSpecConversationDoorsAnswer404() throws Exception {
    var ops = new FakeOperations();
    try (var server = serverWithOwnedToken(ops, true)) {
      assertEquals(404, get(server, "/v1/specs/auth-flow/messages", "token").statusCode());
      assertEquals(
          404,
          post(server, "/v1/specs/auth-flow/messages", "token", "{\"body\":\"x\"}").statusCode());
      assertEquals(
          404,
          post(server, "/v1/specs/auth-flow/engage", "token", "{\"agent\":\"c\"}").statusCode());
      assertEquals(404, post(server, "/v1/specs/auth-flow/disengage", "token", "{}").statusCode());
      assertEquals(
          404,
          post(server, "/v1/specs/auth-flow/invite", "token", "{\"agent\":\"c\"}").statusCode());
    }
  }

  @Test
  void specMessageAcceptsEscapedJsonBodyBelowTheMessageLimit() throws Exception {
    var ops = new MessageActorProbe();
    var body = "\"".repeat(34_000);
    var json = "{\"body\":\"" + "\\\"".repeat(34_000) + "\"}";
    try (var server = serverWithOwnedToken(ops, true)) {
      var response = post(server, "/v1/rooms/auth-flow/messages", "token", json);

      assertEquals(201, response.statusCode());
      assertEquals(body, ops.body);
    }
  }

  @Test
  void globalSpecBoardReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/board", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"pending\": 0"));
    }
  }

  @Test
  void globalSpecHistoryGetReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/history", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"spec_id\": \"auth-flow\""));
      assertTrue(response.body().contains("\"rev\": \"1-abc\""));
      assertTrue(response.body().contains("\"total\": 1"));
    }
  }

  @Test
  void globalSpecHistoryRejectsNonGet() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/specs/auth-flow/history", "token", "{}");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void globalSpecRestorePostReturns200() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/specs/auth-flow/restore", "token", "{\"rev\": \"2-abc\"}");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"from_rev\": \"2-abc\""));
    }
  }

  @Test
  void pruneIsACollectionVerbThatReportsByDefault() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/specs:prune", "token", "{\"ids\": [\"auth-flow\"]}");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"dry_run\": true"), response.body());
      var applied =
          post(
              server, "/v1/specs:prune", "token", "{\"ids\": [\"auth-flow\"], \"dry_run\": false}");
      assertTrue(applied.body().contains("\"dry_run\": false"), applied.body());
    }
  }

  @Test
  void pruneTakesOnlyAPostWithASelector() throws Exception {
    try (var server = server()) {
      assertEquals(405, get(server, "/v1/specs:prune", "token").statusCode());
      var unselected = post(server, "/v1/specs:prune", "token", "{}");
      assertEquals(422, unselected.statusCode());
      assertTrue(unselected.body().contains("Name the specs to prune"), unselected.body());
    }
  }

  @Test
  void globalSpecRestoreRejectsNonPost() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/restore", "token");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void agentsListsTheMemberModeSupportToAnAuthenticatedCaller() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/agents", "token");

      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"name\": \"claude-code\""));
      assertTrue(response.body().contains("\"display_name\": \"Claude Code\""));
      assertTrue(response.body().contains("\"mode\": \"read_only\""));
      assertTrue(response.body().contains("\"supported\": true"));
      assertTrue(response.body().contains("\"supported\": false"));
      assertTrue(
          response.body().contains("\"reason\": \"no harness-enforced sandbox\""),
          "an unsupported mode travels with its reason so clients grey it honestly");
      assertTrue(response.body().contains("\"schema_version\": 1"));
    }
  }

  @Test
  void agentsRequiresABearerToken() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/agents", null);

      assertEquals(401, response.statusCode());
    }
  }

  @Test
  void roomMembersRoutesReadAddAndRemoveThroughThePort() throws Exception {
    var ops = new FakeOperations();
    try (var server = serverWith(ops, true)) {
      var listed = get(server, "/v1/rooms/auth-flow/members", "token");
      assertEquals(200, listed.statusCode());
      assertTrue(listed.body().contains("\"agent\": \"claude-code\""));
      assertTrue(listed.body().contains("\"count\": 1"));
      assertTrue(listed.body().contains("\"model\": \"opus-x\""));
      assertEquals("auth-flow", ops.lastMembersRoom);

      var added =
          post(
              server,
              "/v1/rooms/auth-flow/members",
              "token",
              "{\"agent\": \"claude-code\", \"mode\": \"read_only\"}");
      assertEquals(200, added.statusCode());
      assertTrue(added.body().contains("\"mode\": \"read_only\""));
      assertEquals("auth-flow", ops.lastAddMember.specId());
      assertEquals("read_only", ops.lastAddMember.request().mode());

      var removed = delete(server, "/v1/rooms/auth-flow/members", "token");
      assertEquals(200, removed.statusCode());
      assertTrue(removed.body().contains("\"disengaged\": true"));
      assertEquals("auth-flow", ops.lastRemoveMember);
    }
  }

  @Test
  void roomMessagesRouteReadAndPostThroughThePort() throws Exception {
    var ops = new FakeOperations();
    try (var server = serverWithOwnedToken(ops, true)) {
      var listed = get(server, "/v1/rooms/auth-flow/messages?limit=10", "token");
      assertEquals(200, listed.statusCode());
      assertEquals("auth-flow", ops.lastRoomMessagesRoom);

      var posted =
          post(server, "/v1/rooms/auth-flow/messages", "token", "{\"body\": \"hello room\"}");
      assertEquals(201, posted.statusCode());
      assertEquals("auth-flow:hello room", ops.lastRoomPost);

      assertEquals(405, delete(server, "/v1/rooms/auth-flow/messages", "token").statusCode());
    }
  }

  @Test
  void roomMessagePostsRejectUnownedCredentialsLikeTheSpecDoor() throws Exception {
    var ops = new FakeOperations();
    try (var server = serverWith(ops, true)) {
      var response =
          post(server, "/v1/rooms/auth-flow/messages", "token", "{\"body\":\"progress\"}");

      assertEquals(403, response.statusCode());
      assertTrue(response.body().contains("FDE-bound credential"));
      assertNull(ops.lastRoomPost);
    }
  }

  @Test
  void roomsCrudRoutesThroughThePort() throws Exception {
    var ops = new FakeOperations();
    try (var server = serverWith(ops, true)) {
      var listed = get(server, "/v1/rooms?project=acme", "token");
      assertEquals(200, listed.statusCode());
      assertEquals("acme", ops.lastRoomsProject);
      assertTrue(listed.body().contains("\"design-room\""));
      assertTrue(listed.body().contains("\"last_activity_at\": \"t9\""));
      assertTrue(listed.body().contains("\"spec_ids\""));

      var all = get(server, "/v1/rooms", "token");
      assertEquals(200, all.statusCode());
      assertNull(ops.lastRoomsProject);

      var created =
          post(
              server,
              "/v1/rooms",
              "token",
              "{\"id\": \"fresh-room\", \"project\": \"acme\", \"title\": \"Fresh\"}");
      assertEquals(201, created.statusCode());
      assertEquals("fresh-room", ops.lastRoomCreate.id());
      assertEquals(
          new Actor(null, Role.ADMIN, Actor.Lane.API),
          ops.lastRoomCreator,
          "the router binds the caller: a machine token acts as no FDE, so it names no creator");

      var one = get(server, "/v1/rooms/design-room", "token");
      assertEquals(200, one.statusCode());
      assertEquals("design-room", ops.lastRoomGet);
      assertTrue(one.body().contains("\"needs_reply\": false"));

      var gone = delete(server, "/v1/rooms/design-room", "token");
      assertEquals(200, gone.statusCode());
      assertEquals("design-room", ops.lastRoomDelete);
      assertTrue(gone.body().contains("\"deleted\": true"));

      assertEquals(405, put(server, "/v1/rooms/design-room", "token", "{}").statusCode());
      assertEquals(405, delete(server, "/v1/rooms", "token").statusCode());
    }
  }

  @Test
  void theRoomsSurfaceRefusesUnknownShapesAndMethods() throws Exception {
    var ops = new FakeOperations();
    try (var server = serverWith(ops, true)) {
      assertEquals(404, get(server, "/v1/rooms/auth-flow/bogus", "token").statusCode());
      assertEquals(404, get(server, "/v1/rooms/auth-flow/members/extra", "token").statusCode());
      assertEquals(405, put(server, "/v1/rooms/auth-flow/members", "token", "{}").statusCode());
    }
  }

  @Test
  void inviteDoorIsRetiredWithAPointedNotFound() throws Exception {
    try (var server = server()) {
      var response =
          post(server, "/v1/rooms/auth-flow/invite", "token", "{\"agent\": \"claude-code\"}");

      assertEquals(404, response.statusCode());
      assertTrue(response.body().contains("Room invites are retired"));
      assertTrue(response.body().contains("POST /v1/rooms/{id}/members"));
    }
  }

  @Test
  void followupPostReturns201WithDraftedSpec() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/specs/auth-flow/followup", "token", "{}");
      assertEquals(201, response.statusCode());
      assertTrue(response.body().contains("\"id\": \"auth-flow-followup\""));
      assertTrue(response.body().contains("\"source_spec_id\": \"auth-flow\""));
      assertTrue(response.body().contains("\"finding_count\": 2"));
    }
  }

  @Test
  void followupHonorsRequestedId() throws Exception {
    try (var server = server()) {
      var response =
          post(server, "/v1/specs/auth-flow/followup", "token", "{\"id\": \"auth-round2\"}");
      assertEquals(201, response.statusCode());
      assertTrue(response.body().contains("\"id\": \"auth-round2\""));
    }
  }

  @Test
  void followupRejectsNonPost() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/followup", "token");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void specReviewsListReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/reviews", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"spec_id\": \"auth-flow\""));
      assertTrue(response.body().contains("\"reviews\""));
    }
  }

  @Test
  void reviewDetailReturns200() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/reviews/review-123", "token");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"id\": \"review-123\""));
      assertTrue(response.body().contains("\"findings\""));
    }
  }

  @Test
  void reviewApproveReturns200() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/reviews/review-123/approve", "token", "");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"approved\": true"));
    }
  }

  @Test
  void reviewDismissFindingReturns200() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/reviews/review-123/dismiss/finding-456", "token", "");
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("\"dismissed\": true"));
    }
  }

  @Test
  void reviewUnknownSubResourceReturns404() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/reviews/review-123/unknown", "token");
      assertEquals(404, response.statusCode());
    }
  }

  @Test
  void reviewExtraDepthReturns404() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/reviews/r1/approve/extra", "token");
      assertEquals(404, response.statusCode());
    }
  }

  @Test
  void reviewGetOnApproveReturns405() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/reviews/r1/approve", "token");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void runViewModelCoversConstruction() {
    var row =
        new RunStore.RunRow(
            "s1",
            "proj",
            "auth",
            "node-a",
            "build",
            "claude-code",
            "feat/auth",
            "task",
            42,
            null,
            "completed",
            7,
            "/home/dev/.sail/runs/s1/agent.log",
            null,
            "t0",
            "t1",
            List.of(),
            null,
            "claude/abc123",
            "uday");
    var view = RunView.from(row);
    assertEquals("s1", view.id());
    assertEquals("node-a", view.node());
    assertEquals(42, view.pid());
    var map = view.toMap();
    assertEquals("claude-code", map.get("agent"));
    assertEquals("node-a", map.get("node"));
    assertEquals(7, map.get("exit_code"));
    assertTrue(map.containsKey("completed_at"));
    assertTrue(map.containsKey("log_path"));

    var list = new RunListResponse("proj", "auth", List.of(view));
    assertEquals("proj", list.toMap().get("project"));
    assertEquals("auth", list.toMap().get("spec"));
    assertEquals(view.toMap(), new RunDetailResponse(view).toMap());

    var summary = RunSummary.from(row).toMap();
    assertEquals("s1", summary.get("id"));
    assertEquals("node-a", summary.get("node"));
    assertEquals(7, summary.get("exit_code"));
    assertFalse(new RunListResponse(null, null, List.of()).toMap().containsKey("project"));
  }

  @Test
  void reviewsRequireAuth() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/reviews/review-123", null);
      assertEquals(401, response.statusCode());
    }
  }

  @Test
  void globalSpecsRequireAuth() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs", null);
      assertEquals(401, response.statusCode());
    }
  }

  @Test
  void globalSpecInvalidIdReturns422() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/Bad_Name!", "token");
      assertEquals(422, response.statusCode());
    }
  }

  @Test
  void globalSpecUnknownSubResourceReturns404() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/unknown", "token");
      assertEquals(404, response.statusCode());
    }
  }

  @Test
  void globalSpecExtraDepthReturns404() throws Exception {
    try (var server = server()) {
      var response = get(server, "/v1/specs/auth-flow/content/extra", "token");
      assertEquals(404, response.statusCode());
    }
  }

  @Test
  void globalSpecDeleteMethodNotAllowedOnContent() throws Exception {
    try (var server = server()) {
      var response = delete(server, "/v1/specs/auth-flow/content", "token");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void globalSpecPutMethodNotAllowedOnList() throws Exception {
    try (var server = server()) {
      var response = put(server, "/v1/specs", "token", "{}");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void globalSpecPostMethodNotAllowedOnDetail() throws Exception {
    try (var server = server()) {
      var response = post(server, "/v1/specs/auth-flow", "token", "{}");
      assertEquals(405, response.statusCode());
    }
  }

  @Test
  void noSyncHeaderScopesTheOptOutToTheRequest() throws Exception {
    var ops = new SyncScopeProbe();
    try (var server = serverWith(ops, true)) {
      var request =
          HttpRequest.newBuilder(uri(server, "/v1/specs"))
              .header("Authorization", "Bearer token")
              .header(SyncControl.NO_SYNC_HEADER, "true")
              .GET()
              .build();

      var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

      assertEquals(200, response.statusCode());
      assertTrue(ops.sawNoSync);
    }
  }

  @Test
  void requestsWithoutTheNoSyncHeaderSyncAsUsual() throws Exception {
    var ops = new SyncScopeProbe();
    try (var server = serverWith(ops, true)) {
      var response = get(server, "/v1/specs", "token");

      assertEquals(200, response.statusCode());
      assertFalse(ops.sawNoSync);
    }
  }

  private static final class SyncScopeProbe extends FakeOperations {
    private volatile boolean sawNoSync;

    @Override
    public Result<GlobalSpecsListResponse> globalSpecs(SpecStore.SpecFilter filter) {
      sawNoSync = SyncControl.noSync();
      return Result.success(new GlobalSpecsListResponse(List.of(), 0));
    }
  }

  private static final class MessageActorProbe extends FakeOperations {
    private Actor actor;
    private String author;
    private String body;
    private boolean question;

    @Override
    public Result<SpecMessageResponse> postRoomMessage(
        String specId, SpecMessageRequest request, Actor actor, String author) {
      this.actor = actor;
      this.author = author;
      this.body = request.body();
      this.question = request.question();
      return super.postRoomMessage(specId, request, actor, author);
    }
  }

  private static SailApiServer server() throws IOException {
    return serverWith(new FakeOperations(), true);
  }

  private static SailApiServer serverWith(Operations ops) throws IOException {
    return serverWith(ops, false);
  }

  private static SailApiServer serverWith(Operations ops, boolean autoStart) throws IOException {
    return serverWith(ops, autoStart, new FixedTokenTestAuth("token"));
  }

  private static SailApiServer serverWithOwnedToken(Operations ops, boolean autoStart)
      throws IOException {
    var delegate = new FixedTokenTestAuth("token");
    ApiAuth auth =
        exchange -> {
          delegate.require(exchange);
          exchange.setAttribute("token.fde", "ada");
        };
    return serverWith(ops, autoStart, auth);
  }

  private static SailApiServer serverWith(Operations ops, boolean autoStart, ApiAuth auth)
      throws IOException {
    var server = new SailApiServer("127.0.0.1", 0, ops, auth, null, null, null);
    if (autoStart) {
      server.start();
    }
    return server;
  }

  private static HttpResponse<String> get(SailApiServer server, String path, String token)
      throws Exception {
    var builder = HttpRequest.newBuilder(uri(server, path)).GET();
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> post(
      SailApiServer server, String path, String token, String body) throws Exception {
    var builder =
        HttpRequest.newBuilder(uri(server, path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> put(
      SailApiServer server, String path, String token, String body) throws Exception {
    var builder =
        HttpRequest.newBuilder(uri(server, path))
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<String> delete(SailApiServer server, String path, String token)
      throws Exception {
    var builder =
        HttpRequest.newBuilder(uri(server, path))
            .method("DELETE", HttpRequest.BodyPublishers.noBody());
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static URI uri(SailApiServer server, String path) {
    return URI.create("http://127.0.0.1:" + server.port() + path);
  }

  private static class FakeOperations extends TestOperations {
    @Override
    public Result<HealthResponse> health() {
      return Result.success(new HealthResponse("ok"));
    }

    @Override
    public Result<ProjectListResponse> projects() {
      return Result.success(
          new ProjectListResponse(
              List.of(
                  new ProjectListItemView("acme", "running"),
                  new ProjectListItemView("beta", "not_created"))));
    }

    @Override
    public Result<FdesResponse> fdes() {
      return Result.success(
          new FdesResponse(
              List.of(
                  new FdeSummaryView("ada", "Ada Lovelace", "ada@x.dev", "admin"),
                  new FdeSummaryView("bob", "Bob", "bob@x.dev", "member"))));
    }

    @Override
    public Result<AgentsResponse> agents() {
      return Result.success(
          new AgentsResponse(
              List.of(
                  new AgentView(
                      "claude-code",
                      "Claude Code",
                      List.of(
                          new AgentModeView("read_only", true, null),
                          new AgentModeView("full", true, null))),
                  new AgentView(
                      "codex",
                      "Codex CLI",
                      List.of(
                          new AgentModeView("read_only", false, "no harness-enforced sandbox"),
                          new AgentModeView("full", true, null))))));
    }

    String lastMembersRoom;
    Engage lastAddMember;
    String lastRemoveMember;

    record Engage(String specId, EngageRequest request, String localHandle) {}

    @Override
    public Result<RoomMembersResponse> roomMembers(String roomId) {
      lastMembersRoom = roomId;
      return Result.success(
          new RoomMembersResponse(
              List.of(Engagement.of("claude-code", "full", "opus-x", "2026-08-23T00:00:00Z"))));
    }

    @Override
    public Result<EngageResponse> addRoomMember(
        String roomId, EngageRequest request, Actor actor, String localHandle) {
      lastAddMember = new Engage(roomId, request, localHandle);
      return Result.success(
          new EngageResponse(
              request.agent(), request.mode() == null ? "full" : request.mode(), ""));
    }

    @Override
    public Result<DisengageResponse> removeRoomMember(
        String roomId, Actor actor, String localHandle) {
      lastRemoveMember = roomId;
      return Result.success(new DisengageResponse("claude-code"));
    }

    String lastRoomMessagesRoom;
    String lastRoomPost;
    RoomCreateRequest lastRoomCreate;
    Actor lastRoomCreator;
    String lastRoomsProject = "unset";
    String lastRoomGet;
    String lastRoomDelete;

    @Override
    public Result<RoomDetailResponse> createRoom(RoomCreateRequest request, Actor actor) {
      lastRoomCreate = request;
      lastRoomCreator = Actor.current();
      return Result.success(
          new RoomDetailResponse(
              new RoomView(
                  request.id(),
                  request.project(),
                  request.title(),
                  actor.handle(),
                  request.wake(),
                  "on",
                  null,
                  List.of(),
                  List.of(),
                  actor.handle(),
                  "t0",
                  "t0",
                  actor.handle()),
              null,
              null));
    }

    @Override
    public Result<RoomsListResponse> rooms(String project, Actor actor) {
      lastRoomsProject = project;
      return Result.success(
          new RoomsListResponse(
              List.of(
                  new RoomView(
                      "design-room",
                      "acme",
                      "Design talk",
                      "uday",
                      "on",
                      "on",
                      null,
                      List.of(),
                      List.of("attached-spec"),
                      "uday",
                      "t0",
                      "t1",
                      "uday")),
              Map.of("design-room", "t9"),
              Map.of()));
    }

    @Override
    public Result<RoomDetailResponse> room(String roomId) {
      lastRoomGet = roomId;
      return Result.success(
          new RoomDetailResponse(
              new RoomView(
                  roomId,
                  "acme",
                  "Design talk",
                  "uday",
                  "on",
                  "on",
                  null,
                  List.of(),
                  List.of(),
                  "uday",
                  "t0",
                  "t1",
                  "uday"),
              "t9",
              null));
    }

    @Override
    public Result<RoomDeletedResponse> deleteRoom(String roomId, Actor actor) {
      lastRoomDelete = roomId;
      return Result.success(new RoomDeletedResponse(roomId));
    }

    @Override
    public Result<SpecMessagesResponse> roomMessages(
        String roomId, String before, String after, int limit) {
      lastRoomMessagesRoom = roomId;
      return Result.success(new SpecMessagesResponse(roomId, List.of()));
    }

    @Override
    public Result<SpecMessageResponse> postRoomMessage(
        String roomId, SpecMessageRequest request, Actor principal, String authorHandle) {
      lastRoomPost = roomId + ":" + request.body();
      return Result.success(
          new SpecMessageResponse(
              new SpecMessageView(
                  "00000000-0000-7000-8000-000000000001",
                  roomId,
                  "uday",
                  request.body(),
                  null,
                  "2026-08-23T00:00:00Z",
                  false)));
    }

    @Override
    public Result<ProjectResponse> project(String project) {
      return Result.success(new ProjectResponse(project, "running", null));
    }

    @Override
    public Result<ConnectResponse> connect(String project) {
      return Result.success(
          new ConnectResponse(project, "203.0.113.7", "uday", "10.171.87.10", "dev", true));
    }

    @Override
    public Result<SpecsResponse> specs(String project) {
      return Result.success(
          new SpecsResponse(
              project,
              List.of(),
              new SpecSummaryView(0, 0, 0, 0, 0),
              new BoardSummaryView(new SpecSummaryView(0, 0, 0, 0, 0), 0, 0, null)));
    }

    @Override
    public Result<SpecResponse> spec(String project, String specId) {
      return Result.success(
          new SpecResponse(
              project,
              new SpecView(
                  specId, "Spec", "pending", null, List.of(), List.of(), null, null, null, null,
                  true, false, List.of()),
              "specs/" + specId + "/spec.md",
              true,
              "content"));
    }

    @Override
    public Result<DispatchResponse> dispatch(
        String project, DispatchRequest request, Actor actor, String localHandle) {
      return Result.success(
          new DispatchResponse(
              project,
              true,
              null,
              new DispatchedSpecView(
                  request.specId(), "Spec", "in_progress", request.repos(), null, null, null, null),
              null,
              "",
              false,
              request.restart()));
    }

    @Override
    public Result<SnapshotListResponse> snapshots(String project) {
      return Result.success(
          new SnapshotListResponse(
              List.of(
                  new SnapshotView("invite-run-7", "2026-08-17T10:00:00Z", "invite"),
                  new SnapshotView("snap-20260817-100000", "2026-08-17T11:00:00Z", "dispatch"))));
    }

    @Override
    public Result<SnapshotActionResponse> restoreSnapshot(
        String project, String label, String localHandle) {
      return Result.success(new SnapshotActionResponse(project, label, "restore", "accepted"));
    }

    @Override
    public Result<SnapshotActionResponse> deleteSnapshot(String project, String label) {
      return Result.success(new SnapshotActionResponse(project, label, "delete", "accepted"));
    }

    @Override
    public Result<AgentStatusResponse> agentStatus(String project, String localHandle) {
      return Result.success(
          new AgentStatusResponse(project, false, null, null, null, null, null, List.of()));
    }

    @Override
    public Result<RunListResponse> runs(String project, String spec) {
      return Result.success(new RunListResponse(project, spec, List.of()));
    }

    @Override
    public Result<RunDetailResponse> run(String runId) {
      return Result.success(
          new RunDetailResponse(
              new RunView(
                  runId,
                  "acme",
                  "auth",
                  "node-a",
                  "build",
                  "claude-code",
                  null,
                  null,
                  "running",
                  "t0",
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null,
                  null)));
    }

    @Override
    public Result<RunLogResponse> runLog(String runId, int tail, String localHandle, Actor actor) {
      return Result.success(new RunLogResponse(runId, List.of("tail=" + tail), null));
    }

    @Override
    public Result<StopRunResponse> stopRun(String runId, String localHandle, Actor actor) {
      return Result.success(new StopRunResponse(runId, false, null, null, false));
    }

    @Override
    public Result<AgentReportResponse> agentReport(String project, String localHandle) {
      return Result.success(
          new AgentReportResponse(
              project,
              "No session",
              null,
              null,
              null,
              null,
              List.of(),
              0,
              null,
              false,
              null,
              null,
              false,
              null));
    }

    @Override
    public Result<EventPublishResponse> publishEvent(Event event) {
      return Result.success(new EventPublishResponse(1L, event.toMap()));
    }

    @Override
    public Result<RecentEventsResponse> recentEvents(int limit) {
      return Result.success(new RecentEventsResponse(limit, 0, List.of()));
    }

    @Override
    public Result<SpecEventsResponse> specEvents(String specId, Long since, int limit) {
      return Result.success(new SpecEventsResponse(specId, since, limit, 0, List.of()));
    }

    @Override
    public Result<EventBusStatsResponse> eventBusStats() {
      return Result.success(new EventBusStatsResponse(0L, 0L, List.of()));
    }

    @Override
    public Result<GlobalSpecsListResponse> globalSpecs(SpecStore.SpecFilter filter) {
      return Result.success(new GlobalSpecsListResponse(List.of(), 0));
    }

    @Override
    public Result<GlobalSpecDetailResponse> globalSpec(String specId) {
      return Result.success(
          new GlobalSpecDetailResponse(
              new GlobalSpecView(
                  specId,
                  "test-project",
                  "Test",
                  "pending",
                  null,
                  null,
                  null,
                  null,
                  null,
                  0,
                  List.of(),
                  List.of(),
                  null,
                  null,
                  null,
                  "",
                  "",
                  null,
                  null),
              null,
              null,
              0,
              new RunSummary(
                  "run-1", "node-a", "running", null, null, null, null, null, null, null)));
    }

    @Override
    public Result<FollowupSpecResponse> createFollowupSpec(
        String specId, FollowupCreateRequest request) {
      return Result.success(
          new FollowupSpecResponse(
              new GlobalSpecView(
                  request.id() != null ? request.id() : specId + "-followup",
                  "test-project",
                  "Address review findings: Test",
                  "draft",
                  null,
                  null,
                  null,
                  null,
                  null,
                  3,
                  List.of(),
                  List.of(),
                  null,
                  null,
                  Actor.current().handle(),
                  "",
                  "",
                  Actor.current().handle(),
                  null),
              specId,
              "r1",
              2));
    }

    @Override
    public Result<GlobalSpecCreatedResponse> createGlobalSpec(
        SpecCreateRequest request, Actor actor) {
      return Result.success(
          new GlobalSpecCreatedResponse(
              new GlobalSpecView(
                  request.id(),
                  request.project(),
                  request.title(),
                  request.status(),
                  null,
                  null,
                  null,
                  null,
                  null,
                  0,
                  List.of(),
                  List.of(),
                  null,
                  null,
                  null,
                  "",
                  "",
                  null,
                  null)));
    }

    @Override
    public Result<GlobalSpecUpdatedResponse> updateGlobalSpec(
        String specId, SpecUpdateRequest request, Actor actor) {
      return Result.success(
          new GlobalSpecUpdatedResponse(
              new GlobalSpecView(
                  specId,
                  "test-project",
                  "Updated",
                  "pending",
                  null,
                  null,
                  null,
                  null,
                  null,
                  0,
                  List.of(),
                  List.of(),
                  null,
                  null,
                  null,
                  "",
                  "",
                  null,
                  null)));
    }

    @Override
    public Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId, Actor actor) {
      return Result.success(new GlobalSpecDeletedResponse(specId));
    }

    @Override
    public Result<GlobalSpecContentResponse> globalSpecContent(String specId) {
      return Result.success(new GlobalSpecContentResponse(specId, "", ""));
    }

    @Override
    public Result<GlobalSpecContentResponse> setGlobalSpecContent(
        String specId, SpecContentRequest request, Actor actor) {
      return Result.success(new GlobalSpecContentResponse(specId, request.body(), request.plan()));
    }

    @Override
    public Result<RunInboxResponse> runInbox(String runId) {
      return new TestOperations().runInbox(runId);
    }

    @Override
    public Result<RunAckResponse> ackRunMessages(String runId, List<String> delivered) {
      return new TestOperations().ackRunMessages(runId, delivered);
    }

    @Override
    public Result<RunSessionResponse> recordRunSession(
        String runId, String sessionId, String source, String transcriptPath) {
      return new TestOperations().recordRunSession(runId, sessionId, source, transcriptPath);
    }

    @Override
    public Result<RoomConversationResponse> recordRoomConversation(
        String roomId,
        String agent,
        String sessionId,
        String source,
        String transcriptPath,
        Actor actor) {
      return new TestOperations()
          .recordRoomConversation(roomId, agent, sessionId, source, transcriptPath, actor);
    }

    @Override
    public Result<GlobalSpecHistoryResponse> globalSpecHistory(String specId) {
      return Result.success(
          new GlobalSpecHistoryResponse(
              specId,
              List.of(
                  new SpecRevisionView(
                      "1-abc", "uday", "2026-06-13T00:00:00Z", "local", false, null, "revision"))));
    }

    @Override
    public Result<GlobalSpecRestoredResponse> restoreGlobalSpec(
        String specId, SpecRestoreRequest request, Actor actor) {
      return Result.success(
          new GlobalSpecRestoredResponse(
              GlobalSpecView.from(
                  new SpecStore.SpecRow(
                      specId,
                      "proj",
                      "t",
                      SpecStatus.fromWire("pending"),
                      null,
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
                      List.of())),
              request.rev()));
    }

    @Override
    public Result<GlobalBoardResponse> globalBoard(String project) {
      return Result.success(
          new GlobalBoardResponse(new SpecStore.BoardSummary(0, 0, 0, 0, 0, 0, 0, 0, null), 0));
    }

    @Override
    public Result<ReviewListResponse> reviewsForSpec(String specId) {
      var stage = new StageView("s1", "security", "agent", "passed", "codex", "t1", "t2", 2, null);
      var review =
          new ReviewView("r1", specId, 1, "passed", "t0", "t1", null, null, null, List.of(stage));
      return Result.success(new ReviewListResponse(specId, List.of(review)));
    }

    @Override
    public Result<ReviewDetailResponse> reviewDetail(String reviewId) {
      var stage = new StageView("s1", "security", "agent", "passed", "codex", "t1", "t2", 1, null);
      var review =
          new ReviewView(
              reviewId, "spec", 1, "passed", "t0", "t1", null, null, null, List.of(stage));
      var finding =
          Map.<String, Object>of("id", "f1", "severity", "HIGH", "title", "SQL injection");
      return Result.success(new ReviewDetailResponse(review, List.of(finding)));
    }

    @Override
    public Result<ReviewApproveResponse> approveReview(String reviewId, Actor actor) {
      return Result.success(new ReviewApproveResponse(reviewId, true));
    }

    @Override
    public Result<FindingDismissResponse> dismissFinding(
        String reviewId, String findingId, Actor actor) {
      return Result.success(new FindingDismissResponse(findingId, true));
    }
  }

  private static final class FailingOperations extends FakeOperations {
    @Override
    public Result<AgentStatusResponse> agentStatus(String project, String localHandle) {
      return Result.failure(ErrorCode.CONFLICT, "Agent is busy.", "Wait.");
    }
  }

  private static final class SnapshotRefusingOperations extends FakeOperations {
    @Override
    public Result<SnapshotActionResponse> restoreSnapshot(
        String project, String label, String localHandle) {
      return Result.failure(
          ErrorCode.AGENT_ALREADY_RUNNING,
          "Agent run r-7 is already working spec 'auth' in this container; restoring snapshot '"
              + label
              + "' would discard its live work.",
          "Wait for it to finish or stop it, then retry the restore.");
    }
  }

  private static final class ExplodingOperations extends FakeOperations {
    @Override
    public Result<AgentStatusResponse> agentStatus(String project, String localHandle) {
      throw new IllegalStateException("boom");
    }
  }

  @Test
  void operationsDefaultResolvesNoRunCredential() {
    assertTrue(new FakeOperations().runForCredential("sailrun_anything").isEmpty());
    assertTrue(new FakeOperations().boxActorForCredential("sailbox_anything").isEmpty());
  }
}
