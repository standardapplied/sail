/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code GET /v1/fdes} over the real server and its real authentication: a session or a token of a
 * scoped FDE resolves to a scoped caller, who reads the roster a scoped node mirrors, and only an
 * admin reads who is scoped to what.
 */
class ScopedRosterApiTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private FdeStore fdes;
  private SailApiServer server;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("main.db"));
    new SchemaManager(db).migrate();
    fdes = new FdeStore(db);
    fdes.add("ada", "Ada", "ada@x.dev", "admin");
    fdes.add("uma", "Uma", "uma@x.dev", "member");
    for (var scoped : List.of("nia", "sam", "zed")) {
      fdes.add(scoped, scoped, scoped + "@x.dev", "member");
      fdes.restrict(scoped);
    }
    fdes.grant("nia", "alpha");
    fdes.grant("sam", "alpha");
    fdes.grant("zed", "beta");
    var operations =
        new SailOperations(
            new ScriptedShellExecutor(),
            ProjectReader.ofCatalog(new ProjectStore(db)),
            null,
            null,
            new SpecStore(db),
            new ReviewStore(db),
            new RunStore(db),
            new ProjectStore(db),
            null,
            fdes,
            SessionYield.NONE);
    server =
        new SailApiServer(
            "127.0.0.1", 0, operations, TestAuth.sessions(db), new EventBus(), null, null, null);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.close();
    db.close();
  }

  @Test
  void anAdminReadsTheWholeRosterAndWhoIsScopedToWhat() throws Exception {
    assertEquals(
        List.of(
            Map.of(
                "handle",
                "ada",
                "display_name",
                "Ada",
                "email",
                "ada@x.dev",
                "role",
                "admin",
                "scope",
                "all"),
            Map.of(
                "handle",
                "nia",
                "display_name",
                "nia",
                "email",
                "nia@x.dev",
                "role",
                "member",
                "scope",
                "granted",
                "projects",
                List.of("alpha")),
            Map.of(
                "handle",
                "sam",
                "display_name",
                "sam",
                "email",
                "sam@x.dev",
                "role",
                "member",
                "scope",
                "granted",
                "projects",
                List.of("alpha")),
            Map.of(
                "handle",
                "uma",
                "display_name",
                "Uma",
                "email",
                "uma@x.dev",
                "role",
                "member",
                "scope",
                "all"),
            Map.of(
                "handle",
                "zed",
                "display_name",
                "zed",
                "email",
                "zed@x.dev",
                "role",
                "member",
                "scope",
                "granted",
                "projects",
                List.of("beta"))),
        roster(sessionOf("ada")));
  }

  @Test
  void anUnscopedMemberReadsTheWholeRosterAndNoOnesScope() throws Exception {
    var roster = roster(sessionOf("uma"));

    assertEquals(
        List.of("ada", "nia", "sam", "uma", "zed"),
        roster.stream().map(fde -> fde.get("handle")).toList());
    assertEquals(
        List.of("ada@x.dev", "nia@x.dev", "sam@x.dev", "uma@x.dev", "zed@x.dev"),
        roster.stream().map(fde -> fde.get("email")).toList());
    assertEquals(
        List.of(false, false, false, false, false),
        roster.stream().map(fde -> fde.containsKey("scope")).toList());
  }

  @Test
  void aScopedFdesSessionAndItsTokenBothReadOnlyThoseWhoShareItsProjects() throws Exception {
    var expected =
        List.of(
            Map.of("handle", "ada", "display_name", "Ada", "email", "", "role", "admin"),
            Map.of("handle", "nia", "display_name", "nia", "email", "nia@x.dev", "role", "member"),
            Map.of("handle", "sam", "display_name", "sam", "email", "", "role", "member"),
            Map.of("handle", "uma", "display_name", "Uma", "email", "", "role", "member"));
    var token =
        new TokenStore(db)
            .create("nia-laptop", "member", fdes.byHandle("nia").orElseThrow().id(), null)
            .token();

    assertEquals(expected, roster(sessionOf("nia")));
    assertEquals(expected, roster(token));
  }

  @Test
  void aCredentialThatNamesNoFdeIsNotScoped() throws Exception {
    var machine = new TokenStore(db).create("ci-bot", "member").token();

    assertEquals(
        List.of("ada", "nia", "sam", "uma", "zed"),
        roster(machine).stream().map(fde -> fde.get("handle")).toList());
  }

  private String sessionOf(String handle) {
    return new AuthSessionStore(db)
        .create(fdes.byHandle(handle).orElseThrow().id(), Duration.ofMinutes(30))
        .token();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> roster(String bearer) throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/fdes"))
                    .header("Authorization", "Bearer " + bearer)
                    .build(),
                HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return (List<Map<String, Object>>) YamlUtil.parseMap(response.body()).get("fdes");
  }
}
