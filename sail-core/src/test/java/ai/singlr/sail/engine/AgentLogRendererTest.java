/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentLogRendererTest {

  @Test
  void rendersAssistantText() {
    var line =
        """
        {"type":"assistant","message":{"content":[{"type":"text","text":"Reading the config file."}]}}""";

    assertEquals("Reading the config file.", AgentLogRenderer.render(line));
  }

  @Test
  void rendersToolUseAsOneLineSummary() {
    var line =
        """
        {"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"mvn test"}}]}}""";

    var rendered = AgentLogRenderer.render(line);

    assertTrue(rendered.contains("Bash"), "tool name must be shown");
    assertTrue(rendered.contains("mvn test"), "a one-line summary of the input must be shown");
    assertFalse(rendered.contains("\n"), "tool calls render to a single line");
  }

  @Test
  void rendersToolResultStatus() {
    var ok =
        """
        {"type":"user","message":{"content":[{"type":"tool_result","content":"done"}]}}""";
    var err =
        """
        {"type":"user","message":{"content":[{"type":"tool_result","is_error":true,"content":"boom"}]}}""";

    assertFalse(AgentLogRenderer.render(ok).isEmpty(), "a tool result must produce a status line");
    assertTrue(
        AgentLogRenderer.render(err).toLowerCase().contains("error"),
        "an errored tool result must be marked as such");
  }

  @Test
  void surfacesFinalResult() {
    var line =
        """
        {"type":"result","subtype":"success","result":"All tests pass."}""";

    assertTrue(
        AgentLogRenderer.render(line).contains("All tests pass."),
        "the terminal result event carries the final summary and must not be lost");
  }

  @Test
  void skipsSystemInitEvent() {
    var line =
        """
        {"type":"system","subtype":"init","model":"claude","tools":["Bash"]}""";

    assertEquals("", AgentLogRenderer.render(line), "the noisy init event is not shown");
  }

  @Test
  void passesPlainTextThrough() {
    var line = "Codex: applying patch to src/main.rs";

    assertEquals(line, AgentLogRenderer.render(line), "non-JSON transcript lines pass through");
  }

  @Test
  void passesNonStreamJsonObjectThrough() {
    var line = "key: a human readable colon line";

    assertEquals(
        line,
        AgentLogRenderer.render(line),
        "a plain line that happens to parse as a map but has no stream-json type is left untouched");
  }

  @Test
  void toleratesMalformedJson() {
    var line = "{not valid json";

    assertEquals(line, AgentLogRenderer.render(line));
  }

  @Test
  void passesCommentAndBareScalarLinesThroughWithoutCrashing() {
    assertEquals("# Summary of changes", AgentLogRenderer.render("# Summary of changes"));
    assertEquals("Building the project now", AgentLogRenderer.render("Building the project now"));
  }
}
