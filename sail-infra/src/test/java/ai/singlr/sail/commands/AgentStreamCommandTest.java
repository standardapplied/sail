/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static ai.singlr.sail.engine.OperatorCommand.PLAIN;
import static ai.singlr.sail.engine.OperatorCommand.SUDO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.OperatorCommand;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class AgentStreamCommandTest {

  @Test
  void aForeignRunRefusalSurfacesTheMessageAndTheOwningBox() {
    var body =
        "{\"code\": \"run_on_other_node\", \"message\": \"Run r1 executed on sumesh; its logs live"
            + " there, not on this box.\", \"node\": \"sumesh\", \"spec\": \"auth\", \"project\":"
            + " \"acme\"}";

    var rendered = AgentStreamCommand.formatHttpError(409, body, SUDO);

    assertTrue(rendered.contains("its logs live there"), rendered);
    assertTrue(rendered.contains("Connect to sumesh's box"), rendered);
  }

  @Test
  void aBodylessErrorFallsBackToTheStatusCode() {
    assertEquals("Server returned HTTP 500.", AgentStreamCommand.formatHttpError(500, "", SUDO));
  }

  @Test
  void anUnparseableBodyFallsBackToTheStatusCode() {
    assertEquals(
        "Server returned HTTP 502.", AgentStreamCommand.formatHttpError(502, "<html>", SUDO));
  }

  @Test
  void aMessageWithoutANodeIsShownAsIs() {
    var rendered =
        AgentStreamCommand.formatHttpError(
            400, "{\"code\": \"invalid\", \"message\": \"Bad run.\"}", SUDO);

    assertEquals("Bad run.", rendered);
  }

  @Test
  void anUnplacedFdeRefusalNamesItsRemedyForTheReader() {
    var body = YamlUtil.dumpJson(CliOperator.unplaced("ada").error().toMap());

    assertEquals(
        "This box does not know its FDE's role ('ada'), so it cannot tell what you may do. Run"
            + " 'sudo sail sync' first. If it still refuses, ask an admin whether 'ada' is"
            + " disabled.",
        AgentStreamCommand.formatHttpError(409, body, SUDO));
    assertEquals(
        "This box does not know its FDE's role ('ada'), so it cannot tell what you may do. Run"
            + " 'sail sync' first. If it still refuses, ask an admin whether 'ada' is disabled.",
        AgentStreamCommand.formatHttpError(409, body, PLAIN));
  }

  private static final String UNPLACED_SUDO =
      "This box does not know its FDE's role ('ada'), so it cannot tell what you may do. Run"
          + " 'sudo sail sync' first. If it still refuses, ask an admin whether 'ada' is"
          + " disabled.";
  private static final String UNPLACED_PLAIN =
      "This box does not know its FDE's role ('ada'), so it cannot tell what you may do. Run"
          + " 'sail sync' first. If it still refuses, ask an admin whether 'ada' is disabled.";

  private static String closingFrame() {
    return "event: error\ndata: "
        + YamlUtil.dumpJson(CliOperator.unplaced("ada").error().toMap())
        + "\n\n";
  }

  @Test
  void aStreamTheServerEndsEndsInTheRefusalItNamesRenderedForItsReader() {
    var out = new ByteArrayOutputStream();
    var frames = ("id: 1\ndata: first line\n\n" + closingFrame()).lines().iterator();

    var refused =
        assertThrows(
            SailApiClient.Refused.class,
            () -> AgentStreamCommand.print(frames, new PrintStream(out)));

    assertEquals("first line\n", out.toString(StandardCharsets.UTF_8));
    assertEquals(UNPLACED_SUDO, String.join(" ", CliCommand.lines(refused, SUDO)));
    assertEquals(UNPLACED_PLAIN, String.join(" ", CliCommand.lines(refused, PLAIN)));
  }

  @Test
  void aDataLineAfterAnErrorEventEndsIsAnOrdinaryLogLine() throws Exception {
    var out = new ByteArrayOutputStream();

    AgentStreamCommand.print(
        List.of("event: error", "", "data: {\"a\": 1}").iterator(), new PrintStream(out));

    assertEquals("{\"a\": 1}\n", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void theCommandFailsWithTheRenderedRefusalWhenTheServerEndsTheStream() throws Exception {
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/v1/runs/r1/stream",
        exchange -> {
          var body =
              ("id: 1\ndata: first line\n\n" + closingFrame()).getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, body.length);
          try (var os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    server.start();
    var originalOut = System.out;
    var originalErr = System.err;
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    int exit;
    try {
      exit =
          new CommandLine(new Sail())
              .execute(
                  "agent",
                  "stream",
                  "acme",
                  "--run",
                  "r1",
                  "--server",
                  "http://127.0.0.1:" + server.getAddress().getPort(),
                  "--token",
                  "tok");
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
      server.stop(0);
    }

    assertNotEquals(0, exit);
    var printed = out.toString(StandardCharsets.UTF_8);
    assertTrue(printed.contains("first line"), printed);
    assertFalse(printed.contains("{"), printed);
    var expected =
        String.join(
            " ", CliCommand.lines(SailApiClient.Refused.of(error()), OperatorCommand.here()));
    assertTrue(err.toString(StandardCharsets.UTF_8).contains(expected), err.toString());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> error() {
    return (Map<String, Object>)
        YamlUtil.parseMap(YamlUtil.dumpJson(CliOperator.unplaced("ada").error().toMap()));
  }
}
