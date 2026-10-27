/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import ai.singlr.sail.api.LiveRoom.Lines;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.ProjectScope;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A room's stream over HTTP, served by the handler the server mounts and told by a live member sail
 * reads: what a reader who opens the room is told first is where the member's turn stands.
 */
@ActingAs
class RoomStreamIT {

  private static final String SPEC = "stream-spec";

  @TempDir Path dir;
  private LiveRoom room;
  private HttpServer server;

  @BeforeEach
  void setUp() {
    room = new LiveRoom(dir);
    room.spec(SPEC);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    room.close();
  }

  @Test
  void aReaderWhoOpensTheRoomIsFirstToldWhereTheMembersTurnStands() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(BusTesting.DELIVERY_TIMEOUT_SECONDS),
        () -> {
          serve();
          assertEquals(Map.of("open", false), firstData(open()), "no member yet");

          var process = room.prompted(SPEC, "full", "first");
          room.says(process, Lines.init("sess-1"), Lines.delta("Half"));
          var run = room.memberRun();
          var midTurn = open();
          assertEquals(LiveRoom.live(run, SPEC, 1, Map.of("open", true)), firstData(midTurn));

          room.says(process, Lines.result("Half done"));
          assertEquals(
              "agent_turn_ended", type(nextData(midTurn)), "the turn's end follows the frame");

          var reply = room.messages.list(SPEC, null, 20).getLast();
          assertEquals(
              LiveRoom.live(
                  run, SPEC, 1, Map.of("open", false, "message_id", reply.id(), "is_error", false)),
              firstData(open()),
              "a reader who came back after the turn is told the message it became");
        });
  }

  private void serve() throws Exception {
    ApiAuth auth =
        exchange -> {
          exchange.setAttribute("token.role", "member");
          exchange.setAttribute(ApiAuth.SCOPE, ProjectScope.all());
        };
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/events/stream", new SseHandler(room.bus, auth, room.live::turnState));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  private BufferedReader open() throws Exception {
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://127.0.0.1:"
                        + server.getAddress().getPort()
                        + "/v1/events/stream?room="
                        + SPEC
                        + "&type=agent_turn_state,agent_turn_began,agent_text_delta,"
                        + "agent_turn_ended"))
            .GET()
            .build();
    var response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
    assertEquals(200, response.statusCode());
    return new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
  }

  /** The first frame's data, which a frame the bus never stamped carries with no id before it. */
  private static Map<String, Object> firstData(BufferedReader reader) throws Exception {
    assertEquals(List.of(": subscribed", ""), List.of(reader.readLine(), reader.readLine()));
    var line = reader.readLine();
    var event = YamlUtil.parseMap(line.substring("data: ".length()));
    assertEquals("agent_turn_state", event.get("type"), line);
    assertEquals(SPEC, event.get("spec"));
    return data(event);
  }

  private static Map<String, Object> nextData(BufferedReader reader) throws Exception {
    String line;
    while ((line = reader.readLine()) != null) {
      if (line.startsWith("data: ")) {
        return YamlUtil.parseMap(line.substring("data: ".length()));
      }
    }
    throw new AssertionError("the stream ended");
  }

  private static String type(Map<String, Object> event) {
    return String.valueOf(event.get("type"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> data(Map<String, Object> event) {
    return (Map<String, Object>) event.get("data");
  }
}
