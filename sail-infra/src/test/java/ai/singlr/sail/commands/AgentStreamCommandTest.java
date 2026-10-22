/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static ai.singlr.sail.engine.OperatorCommand.PLAIN;
import static ai.singlr.sail.engine.OperatorCommand.SUDO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.CliOperator;
import org.junit.jupiter.api.Test;

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
}
