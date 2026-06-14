/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AgentLogStreamerTest {

  @Test
  void extractProjectFromValidPath() {
    assertEquals("backend", AgentLogStreamer.extractProject("/v1/projects/backend/agent/stream"));
  }

  @Test
  void extractProjectFromShortPath() {
    assertNull(AgentLogStreamer.extractProject("/v1/health"));
  }

  @Test
  void extractProjectFromInvalidPrefix() {
    assertNull(AgentLogStreamer.extractProject("/v1/other/backend/agent/stream"));
  }

  @Test
  void parseSinceFromQuery() {
    assertEquals(42, AgentLogStreamer.parseSince("since=42"));
  }

  @Test
  void parseSinceWithMultipleParams() {
    assertEquals(100, AgentLogStreamer.parseSince("format=json&since=100&limit=50"));
  }

  @Test
  void parseSinceReturnsZeroForNull() {
    assertEquals(0, AgentLogStreamer.parseSince(null));
  }

  @Test
  void parseSinceReturnsZeroForMissing() {
    assertEquals(0, AgentLogStreamer.parseSince("format=json"));
  }

  @Test
  void parseSinceReturnsZeroForInvalid() {
    assertEquals(0, AgentLogStreamer.parseSince("since=abc"));
  }

  @Test
  void buildTailCommandWithoutSince() {
    var cmd = AgentLogStreamer.buildTailCommand("backend", 0);
    assertEquals("incus", cmd[0]);
    assertEquals("exec", cmd[1]);
    assertEquals("backend", cmd[2]);
    assertTrue(cmd[cmd.length - 1].contains("tail -f"));
    assertTrue(cmd[cmd.length - 1].contains("agent.log"));
  }

  @Test
  void buildTailCommandWithSince() {
    var cmd = AgentLogStreamer.buildTailCommand("backend", 50);
    assertTrue(cmd[cmd.length - 1].contains("tail -n +50 -f"));
  }

  @Test
  void buildTailCommandIncludesUserFlags() {
    var cmd = AgentLogStreamer.buildTailCommand("proj", 0);
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("--user 1000"));
    assertTrue(joined.contains("--group 1000"));
  }
}
