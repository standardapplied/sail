/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentLogCommandTest {

  private static final String STREAM_EVENT =
      "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Reading.\"}]}}";

  @Test
  void jsonOutputStreamsTheRawStructuredLineForMachineConsumers() {
    assertEquals(
        STREAM_EVENT,
        AgentLogCommand.renderForLog(STREAM_EVENT, true),
        "--json (incl. --follow --json) must stream raw NDJSON events, not human-rendered text");
  }

  @Test
  void humanOutputRendersStreamJsonToReadableText() {
    assertEquals("Reading.", AgentLogCommand.renderForLog(STREAM_EVENT, false));
  }
}
