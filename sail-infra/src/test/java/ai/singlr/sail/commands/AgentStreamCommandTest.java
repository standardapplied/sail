/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentStreamCommandTest {

  @Test
  void aForeignRunRefusalSurfacesTheMessageAndTheOwningBox() {
    var body =
        "{\"code\": \"run_on_other_node\", \"message\": \"Run r1 executed on sumesh; its logs live"
            + " there, not on this box.\", \"node\": \"sumesh\", \"spec\": \"auth\", \"project\":"
            + " \"acme\"}";

    var rendered = AgentStreamCommand.formatHttpError(409, body);

    assertTrue(rendered.contains("its logs live there"), rendered);
    assertTrue(rendered.contains("Connect to sumesh's box"), rendered);
  }

  @Test
  void aBodylessErrorFallsBackToTheStatusCode() {
    assertEquals("Server returned HTTP 500.", AgentStreamCommand.formatHttpError(500, ""));
  }

  @Test
  void anUnparseableBodyFallsBackToTheStatusCode() {
    assertEquals("Server returned HTTP 502.", AgentStreamCommand.formatHttpError(502, "<html>"));
  }

  @Test
  void aMessageWithoutANodeIsShownAsIs() {
    var rendered =
        AgentStreamCommand.formatHttpError(
            400, "{\"code\": \"invalid\", \"message\": \"Bad run.\"}");

    assertEquals("Bad run.", rendered);
  }
}
