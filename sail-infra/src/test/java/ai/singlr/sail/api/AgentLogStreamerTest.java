/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentLogStreamerTest {

  @Test
  void extractRunIdFromValidPath() {
    assertEquals("run-1", AgentLogStreamer.extractRunId("/v1/runs/run-1/stream"));
  }

  @Test
  void extractRunIdFromShortPath() {
    assertNull(AgentLogStreamer.extractRunId("/v1/health"));
  }

  @Test
  void extractRunIdFromInvalidPrefix() {
    assertNull(AgentLogStreamer.extractRunId("/v1/other/run-1/stream"));
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
  void buildTailCommandTailsTheRunScopedLog() {
    var cmd = AgentLogStreamer.buildTailCommand("backend", "/home/dev/.sail/runs/r1/agent.log", 0);
    assertEquals("incus", cmd[0]);
    assertEquals("exec", cmd[1]);
    assertEquals("backend", cmd[2]);
    assertTrue(cmd[cmd.length - 1].contains("tail -f"));
    assertTrue(cmd[cmd.length - 1].contains("/home/dev/.sail/runs/r1/agent.log"));
  }

  @Test
  void buildTailCommandTouchesTheLogSoAnEmptyRunStreamsClean() {
    var cmd = AgentLogStreamer.buildTailCommand("backend", "/home/dev/.sail/runs/r1/agent.log", 0);
    assertTrue(cmd[cmd.length - 1].contains("touch /home/dev/.sail/runs/r1/agent.log"));
  }

  @Test
  void buildTailCommandWithSince() {
    var cmd = AgentLogStreamer.buildTailCommand("backend", "/home/dev/.sail/runs/r1/agent.log", 50);
    assertTrue(cmd[cmd.length - 1].contains("tail -n +50 -f"));
  }

  @Test
  void buildTailCommandRunsAsTheDevUser() {
    var cmd = AgentLogStreamer.buildTailCommand("proj", "/home/dev/.sail/runs/r1/agent.log", 0);
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("--user 1000"));
    assertTrue(joined.contains("--group 1000"));
  }

  @Test
  void isStreamPathMatchesCanonicalRunPath() {
    assertTrue(AgentLogStreamer.isStreamPath("/v1/runs/run-1/stream"));
  }

  @Test
  void isStreamPathRejectsOtherRunSubResources() {
    assertFalse(AgentLogStreamer.isStreamPath("/v1/runs/run-1/log"));
    assertFalse(AgentLogStreamer.isStreamPath("/v1/runs/run-1"));
  }

  @Test
  void isStreamPathRejectsWrongPrefix() {
    assertFalse(AgentLogStreamer.isStreamPath("/v1/other/run-1/stream"));
    assertFalse(AgentLogStreamer.isStreamPath("/v1/events/stream"));
  }

  @Test
  void pumpTerminatesWhenTheTailProcessExitsInsteadOfHeartbeatingForever() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          var process = new ProcessBuilder("sh", "-c", "printf 'first\\nsecond\\n'").start();
          var reader =
              new BufferedReader(
                  new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
          var out = new ByteArrayOutputStream();

          AgentLogStreamer.pump(process, reader, out, 0);

          var body = out.toString(StandardCharsets.UTF_8);
          assertTrue(body.contains("first"), body);
          assertTrue(body.contains("second"), body);
          assertFalse(process.isAlive());
        });
  }

  @Test
  void pumpStopsPromptlyWhenAnAlreadyDeadProcessHasNoOutput() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          var process = new ProcessBuilder("sh", "-c", "true").start();
          process.waitFor();
          var reader =
              new BufferedReader(
                  new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

          AgentLogStreamer.pump(process, reader, new ByteArrayOutputStream(), 0);

          assertFalse(process.isAlive());
        });
  }
}
