/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.commands.CliCommand;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.HeldProjects;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * sail-api cannot see who reads its answer, so a refusal's remedy leaves it as data, and the
 * terminal that prints it renders each command for the account reading it: the same answer reads
 * {@code sudo sail} to a {@code sudo} caller and plain {@code sail} to a thin client's gateway
 * account or root logged in directly.
 */
class RemedyOverHttpTest {

  private static final String TAKEN =
      "{\"id\": \"taken\", \"project\": \"acme\", \"title\": \"T\"}";

  @TempDir Path tempDir;
  private Sqlite db;
  private SailApiServer server;
  private String token;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "acme");
    var fde = new FdeStore(db).add("ada", "Ada", "ada@example.com", "admin");
    token = new TokenStore(db).create("ada", "admin", fde.id(), null).token();
    var operations =
        new SailOperations(
            new ShellExecutor(false),
            ProjectReader.ofCatalog(new ProjectStore(db)),
            null,
            null,
            new SpecStore(db),
            new ReviewStore(db));
    server =
        new SailApiServer(
            "127.0.0.1", 0, operations, TestAuth.tokens(db), new EventBus(), null, null, null);
    server.start();
    try (var client = client()) {
      client.post("/v1/specs", YamlUtil.parseMap(TAKEN));
    }
  }

  @AfterEach
  void tearDown() {
    server.close();
    db.close();
  }

  private SailApiClient client() {
    return new SailApiClient("http://127.0.0.1:" + server.port(), token);
  }

  private SailApiClient.Refused refusedCreate() {
    try (var client = client()) {
      return assertThrows(
          SailApiClient.Refused.class, () -> client.post("/v1/specs", YamlUtil.parseMap(TAKEN)));
    }
  }

  @Test
  void theRefusalCarriesItsRemedyAsDataAndNoSailSentence() throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/specs"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(TAKEN))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertEquals(409, response.statusCode());
    var error = YamlUtil.parseMap(response.body()).get("error");
    assertEquals(
        Map.of(
            "code",
            "conflict",
            "message",
            "Spec 'taken' already exists.",
            "action",
            "Pick another spec id, or edit it with: spec update taken",
            "remedy",
            List.of(
                Map.of("text", "Pick another spec id, or edit it with: "),
                Map.of("run", "sail", "verb", "spec update", "args", List.of("taken"))),
            "field_errors",
            List.of()),
        error);
    assertFalse(response.body().contains("sail spec"), response.body());
  }

  @Test
  void aSudoCallerOnASystemBoxIsToldToEditItWithSudo() {
    assertEquals(
        List.of(
            "Spec 'taken' already exists. Pick another spec id, or edit it with: sudo sail spec"
                + " update taken (HTTP 409)"),
        CliCommand.lines(refusedCreate(), OperatorCommand.SUDO));
  }

  @Test
  void aThinClientThroughTheGatewayIsToldPlainSail() {
    assertEquals(
        List.of(
            "Spec 'taken' already exists. Pick another spec id, or edit it with: sail spec update"
                + " taken (HTTP 409)"),
        CliCommand.lines(refusedCreate(), OperatorCommand.UNPRIVILEGED));
  }

  @Test
  void rootLoggedInDirectlyIsToldPlainSail() {
    assertEquals(
        List.of(
            "Spec 'taken' already exists. Pick another spec id, or edit it with: sail spec update"
                + " taken (HTTP 409)"),
        CliCommand.lines(refusedCreate(), OperatorCommand.PLAIN));
  }

  @Test
  void aStoreIntegrityFailureIsAnInternalErrorThatNamesNoRepair() throws Exception {
    db.execute("UPDATE specs SET body_hash = NULL WHERE id = 'taken'");
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/specs/taken/content"))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertEquals(500, response.statusCode());
    assertNull(
        ((Map<?, ?>) YamlUtil.parseMap(response.body()).get("error")).get("remedy"),
        "an integrity failure names no command that would repair it");
    SailApiClient.Refused refused;
    try (var client = client()) {
      refused =
          assertThrows(SailApiClient.Refused.class, () -> client.get("/v1/specs/taken/content"));
    }
    assertEquals(
        List.of("sail API operation failed. (HTTP 500)"),
        CliCommand.lines(refused, OperatorCommand.SUDO));
  }

  @Test
  void anIfMatchCheckFailingBelowTheDoorReachesTheReaderWithItsReason() throws Exception {
    db.execute("UPDATE specs SET body_hash = NULL WHERE id = 'taken'");
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/specs/taken"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("If-Match", "\"2026-10-22T00:00:00Z\"")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"title\": \"U\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertEquals(500, response.statusCode(), response.body());
    assertNull(
        ((Map<?, ?>) YamlUtil.parseMap(response.body()).get("error")).get("remedy"),
        "an integrity failure names no command that would repair it");
  }

  @Test
  void aCatalogDefinitionRefusedByItsReaderSaysWhyOverHttp() {
    Actor.run(Actor.system(), () -> new ProjectStore(db).upsert(TestDefinitions.named("beta")));
    db.execute(
        "UPDATE projects SET definition = ? WHERE name = 'beta'",
        "{\"name\": \"beta\", \"agent_context\": \"old\"}");
    SailApiClient.Refused refused;
    try (var client = client()) {
      refused = assertThrows(SailApiClient.Refused.class, () -> client.get("/v1/projects/beta"));
    }

    var reason =
        "The definition of project 'beta' in the catalog could not be read: agent_context is not"
            + " a key of a project definition. Its keys are: name, description, image, resources,"
            + " runtimes, repos, services, processes, packages, agent, pipeline. (HTTP 500)";
    assertEquals(List.of(reason), CliCommand.lines(refused, OperatorCommand.SUDO));
    assertEquals(List.of(reason), CliCommand.lines(refused, OperatorCommand.PLAIN));
  }

  @Test
  void anEnvelopeFromAServerThatPredatesRemediesStillReadsWhole() {
    var refused =
        assertInstanceOf(
            SailApiClient.Refused.class,
            SailApiClient.Refused.of(
                409, Map.of("message", "Busy.", "action", "Wait for it, then retry.")));
    var bare = SailApiClient.Refused.of(500, null);

    assertEquals("Busy. Wait for it, then retry. (HTTP 409)", refused.getMessage());
    assertEquals("API request failed (HTTP 500)", bare.getMessage());
    assertEquals(
        "Busy. (HTTP 409)", SailApiClient.Refused.of(409, Map.of("message", "Busy.")).getMessage());
  }
}
