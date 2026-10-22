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
import ai.singlr.sail.engine.OperatorCommand;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class EventsCommandTest {

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
            () -> EventsCommand.print(frames.iterator(), new PrintStream(out), true));

    assertEquals("", out.toString(StandardCharsets.UTF_8));
    assertEquals(
        List.of("This box does not know its FDE's role ('ada'). Run 'sudo sail sync' first."),
        CliCommand.lines(refused, OperatorCommand.SUDO));
    assertEquals(
        List.of("This box does not know its FDE's role ('ada'). Run 'sail sync' first."),
        CliCommand.lines(refused, OperatorCommand.PLAIN));
  }

  @Test
  void aStreamPrintsEachEventItSends() throws Exception {
    var out = new ByteArrayOutputStream();
    var event = Event.of("acme", null, "spec_created", "sail", "main");

    EventsCommand.print(
        List.of("id: 7", "data: " + event.toJsonLine(), "").iterator(), new PrintStream(out), true);

    assertEquals(event.toJsonLine() + "\n", out.toString(StandardCharsets.UTF_8));
  }
}
