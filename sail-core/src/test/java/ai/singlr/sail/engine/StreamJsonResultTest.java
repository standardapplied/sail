/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StreamJsonResultTest {

  @Test
  void extractsTheResultFieldOfTheTerminalResultEvent() {
    var log =
        """
        {"type":"system","subtype":"init"}
        {"type":"assistant","message":{"content":[{"type":"text","text":"reviewing"}]}}
        {"type":"result","subtype":"success","result":"```json\\n[]\\n```"}
        """;

    assertEquals("```json\n[]\n```", StreamJsonResult.extract(log));
  }

  @Test
  void keepsTheLastResultWhenSeveralAppendedAcrossIterations() {
    var log =
        """
        {"type":"result","result":"first"}
        {"type":"assistant","message":{"content":[]}}
        {"type":"result","result":"second"}
        """;

    assertEquals("second", StreamJsonResult.extract(log));
  }

  @Test
  void returnsTheInputUnchangedWhenThereIsNoResultEvent() {
    var plain = "```json\n[{\"severity\":\"LOW\"}]\n```";

    assertEquals(
        plain,
        StreamJsonResult.extract(plain),
        "Codex's plain transcript and non-streamed output must parse the same way");
  }

  @Test
  void skipsUnparseableLinesInsteadOfFailing() {
    var log =
        """
        not json at all
        {"type":"result","result":"ok"}
        """;

    assertEquals("ok", StreamJsonResult.extract(log));
  }

  @Test
  void handlesBlankInput() {
    assertEquals("", StreamJsonResult.extract(""));
    assertEquals("", StreamJsonResult.extract(null));
  }
}
