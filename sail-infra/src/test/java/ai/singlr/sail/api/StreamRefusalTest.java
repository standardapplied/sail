/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.commands.CliCommand;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StreamRefusalTest {

  private static final String REASON =
      "This box does not know its FDE's role ('ada'), so it cannot tell what you may do.";
  private static final List<Map<String, Object>> REMEDY =
      List.of(
          Map.of("text", "Run '"),
          Map.of("run", "sail", "verb", "sync", "args", List.of()),
          Map.of("text", "' first. If it still refuses, ask an admin whether 'ada' is disabled."));

  @TempDir Path tempDir;
  private Sqlite db;
  private SailApiServer server;
  private String token;

  @BeforeEach
  void setUp() throws Exception {
    db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    token = new TokenStore(db).create("ci", "admin").token();
    var node = new SyncConfig(SyncConfig.ROLE_NODE, "sail@main", "ada");
    var auth = new TokenAuth(new TokenStore(db), TestAuth.roles(db, node));
    server =
        new SailApiServer(
            "127.0.0.1", 0, new SailOperations(), auth, new EventBus(), null, null, null);
    server.start();
  }

  @AfterEach
  void tearDown() {
    server.close();
    db.close();
  }

  @Test
  void aStreamRefusedAtTheDoorCarriesItsRemedyAsData() throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + server.port() + "/v1/events/stream"))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());

    assertEquals(409, response.statusCode());
    assertEquals(
        Map.of(
            "code",
            "conflict",
            "message",
            REASON,
            "action",
            "Run 'sync' first. If it still refuses, ask an admin whether 'ada' is disabled.",
            "remedy",
            REMEDY,
            "field_errors",
            List.of()),
        YamlUtil.parseMap(response.body()).get("error"));
    var refused = SailApiClient.Refused.fromBody(response.statusCode(), response.body());
    assertEquals(
        List.of(
            REASON
                + " Run 'sudo sail sync' first. If it still refuses, ask an admin whether 'ada' is"
                + " disabled. (HTTP 409)"),
        CliCommand.lines(refused, OperatorCommand.SUDO));
    assertEquals(
        List.of(
            REASON
                + " Run 'sail sync' first. If it still refuses, ask an admin whether 'ada' is"
                + " disabled. (HTTP 409)"),
        CliCommand.lines(refused, OperatorCommand.PLAIN));
  }

  @Test
  void aStreamEndedForItsCallerCarriesTheRemedyInItsLastEvent() throws Exception {
    var out = new ByteArrayOutputStream();

    StreamRecheck.writeEnded(out, new StreamRecheck.Ended(CliOperator.unplaced("ada").error()));

    var frame = out.toString(StandardCharsets.UTF_8);
    assertEquals(
        "event: error\ndata: {\"code\": \"conflict\", \"message\": \""
            + REASON
            + "\", \"action\": \"Run 'sync' first. If it still refuses, ask an admin whether"
            + " 'ada' is disabled.\", \"remedy\": [{\"text\": \"Run '\"}, {\"run\": \"sail\","
            + " \"verb\": \"sync\", \"args\": []}, {\"text\": \"' first. If it still refuses, ask"
            + " an admin whether 'ada' is disabled.\"}], \"field_errors\": []}\n\n",
        frame);
    var data = frame.substring(frame.indexOf("data: ") + "data: ".length()).strip();
    var refused = SailApiClient.Refused.of(YamlUtil.parseMap(data));
    assertEquals(
        List.of(
            REASON
                + " Run 'sudo sail sync' first. If it still refuses, ask an admin whether 'ada' is"
                + " disabled."),
        CliCommand.lines(refused, OperatorCommand.SUDO));
    assertEquals(
        List.of(
            REASON
                + " Run 'sail sync' first. If it still refuses, ask an admin whether 'ada' is"
                + " disabled."),
        CliCommand.lines(refused, OperatorCommand.PLAIN));
  }
}
