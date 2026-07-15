/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.RunStore;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentLogStreamerTest {

  private static RunStore.RunRow runOn(String node) {
    return run(node, "/home/dev/.sail/runs/r1/agent.log");
  }

  private static RunStore.RunRow run(String node, String logPath) {
    return run(node, "build", logPath);
  }

  private static RunStore.RunRow run(String node, String role, String logPath) {
    return new RunStore.RunRow(
        "r1",
        "acme",
        "auth",
        node,
        role,
        "claude-code",
        "feat/x",
        "do it",
        1,
        null,
        "running",
        null,
        logPath,
        null,
        "t0",
        null,
        java.util.List.of());
  }

  private static AgentLogStreamer streamer(
      java.util.function.Function<String, Optional<RunStore.RunRow>> lookup, String localHandle) {
    return streamer(lookup, id -> Optional.empty(), localHandle);
  }

  private static AgentLogStreamer streamer(
      java.util.function.Function<String, Optional<RunStore.RunRow>> lookup,
      java.util.function.Function<String, Optional<String>> assignee,
      String localHandle) {
    return new AgentLogStreamer(exchange -> {}, lookup, assignee, () -> localHandle);
  }

  @Test
  void streamRefusesAMemberWhoIsNotTheRunSpecAssignee() throws Exception {
    var out = new CapturingExchange("/v1/runs/r1/stream").as("raj", "member");

    streamer(
            id -> Optional.of(run("node-a", "review", "/review.log")),
            id -> Optional.of("uday"),
            "node-a")
        .handle(out);

    assertEquals(403, out.status);
    assertTrue(out.body().contains("forbidden_not_assignee"), out.body());
    assertTrue(out.body().contains("uday"), out.body());
    assertTrue(out.body().contains("\"fix\""), out.body());
  }

  @Test
  void streamLetsTheRunSpecAssigneePastThePolicyGate() throws Exception {
    var out = new CapturingExchange("/v1/runs/r1/stream").as("uday", "member");

    streamer(id -> Optional.of(run("node-a", "")), id -> Optional.of("uday"), "node-a").handle(out);

    assertEquals(404, out.status);
    assertTrue(out.body().contains("This run has no log file."), out.body());
  }

  @Test
  void streamLetsAnAdminPastThePolicyGate() throws Exception {
    var out = new CapturingExchange("/v1/runs/r1/stream").as("ops", "admin");

    streamer(id -> Optional.of(run("node-a", "")), id -> Optional.of("uday"), "node-a").handle(out);

    assertEquals(404, out.status);
    assertTrue(out.body().contains("This run has no log file."), out.body());
  }

  @Test
  void handleRefusesAForeignRunWithA409AndNames() throws Exception {
    var out = new CapturingExchange("/v1/runs/r1/stream");

    streamer(id -> Optional.of(runOn("node-b")), "node-a").handle(out);

    assertEquals(409, out.status);
    assertTrue(out.body().contains("run_on_other_node"), out.body());
    assertTrue(out.body().contains("node-b"), out.body());
  }

  @Test
  void foreignReviewRunUsesTheSameProvenanceGuard() throws Exception {
    var out = new CapturingExchange("/v1/runs/r1/stream");

    streamer(id -> Optional.of(run("node-b", "review", "/review.log")), "node-a").handle(out);

    assertEquals(409, out.status);
    assertTrue(out.body().contains("run_on_other_node"), out.body());
  }

  @Test
  void handleReturns404ForAnUnknownRun() throws Exception {
    var out = new CapturingExchange("/v1/runs/nope/stream");

    streamer(id -> Optional.empty(), "node-a").handle(out);

    assertEquals(404, out.status);
    assertTrue(out.body().contains("run_not_found"), out.body());
  }

  @Test
  void handleFailsClosedOnABlankNodeRun() throws Exception {
    var out = new CapturingExchange("/v1/runs/r1/stream");

    streamer(id -> Optional.of(runOn("")), "node-a").handle(out);

    assertEquals(409, out.status);
    assertTrue(out.body().contains("run_on_other_node"), out.body());
  }

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

  private static final String RUN_UUID = "0195e0a0-1111-7abc-8def-0123456789ab";
  private static final String RUN_LOG = "/home/dev/.sail/runs/" + RUN_UUID + "/agent.log";

  @Test
  void buildTailCommandTailsTheRunScopedLogDerivedFromTheUuid() {
    var cmd = AgentLogStreamer.buildTailCommand("backend", RUN_UUID, "build", 0);
    assertEquals("incus", cmd[0]);
    assertEquals("exec", cmd[1]);
    assertEquals("backend", cmd[2]);
    assertTrue(String.join(" ", cmd).contains("tail -f"));
    assertTrue(Arrays.asList(cmd).contains(RUN_LOG), Arrays.toString(cmd));
  }

  @Test
  void buildTailCommandUsesTheReviewersRunScopedLog() {
    var cmd = AgentLogStreamer.buildTailCommand("backend", RUN_UUID, "review", 0);

    assertTrue(Arrays.asList(cmd).contains("/home/dev/.sail/runs/" + RUN_UUID + "/review.log"));
  }

  @Test
  void buildTailCommandPassesTheLogAsAPositionalArgNotShellSyntax() {
    var cmd = AgentLogStreamer.buildTailCommand("backend", RUN_UUID, "build", 0);
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("touch -- \"$1\""), joined);
    assertTrue(Arrays.asList(cmd).contains(RUN_LOG), Arrays.toString(cmd));
  }

  @Test
  void buildTailCommandWithSince() {
    var cmd = AgentLogStreamer.buildTailCommand("backend", RUN_UUID, "build", 50);
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("tail -n \"+$2\" -f"), joined);
    assertTrue(Arrays.asList(cmd).contains("50"), Arrays.toString(cmd));
  }

  @Test
  void buildTailCommandRunsAsTheDevUser() {
    var cmd = AgentLogStreamer.buildTailCommand("proj", RUN_UUID, "build", 0);
    var joined = String.join(" ", cmd);
    assertTrue(joined.contains("--user 1000"));
    assertTrue(joined.contains("--group 1000"));
  }

  @Test
  void buildTailCommandRejectsANonUuidRunId() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AgentLogStreamer.buildTailCommand(
                "proj", "/home/dev/.sail/runs/x; id > /tmp/pwned #", "build", 0));
  }

  @Test
  void buildTailCommandRejectsAnUnknownRole() {
    assertThrows(
        IllegalArgumentException.class,
        () -> AgentLogStreamer.buildTailCommand("proj", RUN_UUID, "../../etc", 0));
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

  private static final class CapturingExchange extends HttpExchange {
    private final URI uri;
    private final Headers responseHeaders = new Headers();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private final java.util.Map<String, Object> attributes = new java.util.HashMap<>();
    private int status = -1;

    private CapturingExchange(String path) {
      this.uri = URI.create(path);
    }

    private CapturingExchange as(String fde, String role) {
      attributes.put("token.fde", fde);
      attributes.put("token.role", role);
      attributes.put("token.name", fde);
      return this;
    }

    private String body() {
      return responseBody.toString(StandardCharsets.UTF_8);
    }

    @Override
    public String getRequestMethod() {
      return "GET";
    }

    @Override
    public URI getRequestURI() {
      return uri;
    }

    @Override
    public Headers getRequestHeaders() {
      return new Headers();
    }

    @Override
    public Headers getResponseHeaders() {
      return responseHeaders;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) {
      this.status = rCode;
    }

    @Override
    public OutputStream getResponseBody() {
      return responseBody;
    }

    @Override
    public InputStream getRequestBody() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public void close() {}

    @Override
    public Object getAttribute(String name) {
      return attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
      attributes.put(name, value);
    }

    @Override
    public HttpContext getHttpContext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
      throw new UnsupportedOperationException();
    }

    @Override
    public int getResponseCode() {
      return status;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getProtocol() {
      return "HTTP/1.1";
    }

    @Override
    public void setStreams(InputStream i, OutputStream o) {}

    @Override
    public HttpPrincipal getPrincipal() {
      throw new UnsupportedOperationException();
    }
  }
}
