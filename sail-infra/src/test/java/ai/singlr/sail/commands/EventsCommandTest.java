/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SailApiClient;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.engine.OperatorCommand;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class EventsCommandTest {

  private static final Remedy.Command FOLLOW_AGAIN = Remedy.sail("events", "--follow");

  @Test
  void registeredAsRootSubcommand() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    cmd.execute("--help");

    assertTrue(sw.toString().contains("events"));
  }

  @Test
  void helpAdvertisesKeyFlags() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setOut(new PrintWriter(sw));

    var exit = cmd.execute("events", "--help");

    assertEquals(0, exit);
    var help = sw.toString();
    assertTrue(help.contains("--follow"));
    assertTrue(help.contains("--lines"));
    assertTrue(help.contains("--project"));
    assertTrue(help.contains("--type"));
    assertTrue(help.contains("--json"));
    assertTrue(help.contains("--host"));
    assertTrue(help.contains("--port"));
  }

  @Test
  void rejectsNonPositiveLines() {
    var cmd = new CommandLine(new Sail());
    var sw = new StringWriter();
    cmd.setErr(new PrintWriter(sw));

    var exit = cmd.execute("events", "-n", "0", "--port", "65000");

    assertNotEquals(0, exit);
  }

  @Test
  void aStreamTheServerEndsEndsInTheRefusalItNamesRenderedForItsReader() {
    var out = new ByteArrayOutputStream();
    var frames =
        List.of(
            ": subscribed",
            "",
            "event: error",
            "data: {\"code\": \"conflict\", \"message\": \"This box does not know its FDE's"
                + " role ('ada').\", \"action\": \"Run 'sync' first.\", \"remedy\": [{\"text\":"
                + " \"Run '\"}, {\"run\": \"sail\", \"verb\": \"sync\", \"args\": []}, {\"text\":"
                + " \"' first.\"}], \"field_errors\": []}",
            "");

    var refused =
        assertThrows(
            SailApiClient.Refused.class,
            () -> EventsCommand.print(frames.iterator(), new PrintStream(out), true, FOLLOW_AGAIN));

    assertEquals("", out.toString(StandardCharsets.UTF_8));
    assertEquals(
        List.of("This box does not know its FDE's role ('ada'). Run 'sudo sail sync' first."),
        CliCommand.lines(refused, OperatorCommand.SUDO));
    assertEquals(
        List.of("This box does not know its FDE's role ('ada'). Run 'sail sync' first."),
        CliCommand.lines(refused, OperatorCommand.PLAIN));
  }

  @Test
  void aStreamPrintsEachEventItSendsAndAnEndTheServerDidNotNameFailsNamingTheFollowAgain() {
    var out = new ByteArrayOutputStream();
    var event = Event.of("acme", null, "spec_created", "sail", "main");

    var closed =
        assertThrows(
            IllegalStateException.class,
            () ->
                EventsCommand.print(
                    List.of("id: 7", "data: " + event.toJsonLine(), "").iterator(),
                    new PrintStream(out),
                    true,
                    FOLLOW_AGAIN));

    assertEquals(event.toJsonLine() + "\n", out.toString(StandardCharsets.UTF_8));
    assertEquals(
        List.of(
            "The sail API closed the stream (it restarted or stopped). Follow again: sudo sail"
                + " events --follow"),
        CliCommand.lines(closed, OperatorCommand.SUDO));
    assertEquals(
        List.of(
            "The sail API closed the stream (it restarted or stopped). Follow again: sail events"
                + " --follow"),
        CliCommand.lines(closed, OperatorCommand.PLAIN));
  }

  @Test
  void aStreamThatDropsFailsAsOneThatEndsNamingNoJavaClass() {
    var dropping = List.of(": subscribed", "").iterator();
    var lines =
        new Iterator<String>() {
          @Override
          public boolean hasNext() {
            if (dropping.hasNext()) {
              return true;
            }
            throw new UncheckedIOException(new IOException("closed"));
          }

          @Override
          public String next() {
            return dropping.next();
          }
        };

    var closed =
        assertThrows(
            IllegalStateException.class,
            () ->
                EventsCommand.print(
                    lines, new PrintStream(new ByteArrayOutputStream()), false, FOLLOW_AGAIN));

    assertEquals(
        List.of(
            "The sail API closed the stream (it restarted or stopped). Follow again: sail events"
                + " --follow"),
        CliCommand.lines(closed, OperatorCommand.PLAIN));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void followingAStreamTheApiClosesExitsOneNamingTheCommandAsTyped(boolean dropped)
      throws Exception {
    var frames = ": subscribed\n\n";
    var token = System.getProperty("SAIL_TOKEN");
    System.setProperty("SAIL_TOKEN", "tok");
    try (var server = dropped ? StreamServer.dropping(frames) : StreamServer.ending(frames)) {
      var port = Integer.toString(server.port());

      var followed = SailCli.run("events", "--follow", "--port", port);

      assertEquals(1, followed.exit());
      assertEquals("", followed.out());
      assertEquals(
          "  ✗ The sail API closed the stream (it restarted or stopped). Follow again: "
              + OperatorCommand.here().sail("events --follow --port " + port)
              + "\n",
          followed.err());
    } finally {
      if (token == null) {
        System.clearProperty("SAIL_TOKEN");
      } else {
        System.setProperty("SAIL_TOKEN", token);
      }
    }
  }
}
