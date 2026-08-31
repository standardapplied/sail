/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Closeable;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executes the real session-report script under a fake {@code $HOME} with a bound Unix socket and a
 * scripted {@code curl}, so payload parsing, the re-report contract, and every fail-open path are
 * asserted against what the shell actually does.
 */
class SailSessionReportTest {

  private static final String RUN_ID = "run-1";
  private static final String CREDENTIAL = "sailrun_test";

  @TempDir Path home;
  private Path report;
  private Path socket;

  private record ReportResult(int exitCode, String stdout, String stderr) {}

  @BeforeEach
  void installReportUnderFakeHome() throws Exception {
    report = home.resolve("sail-session-report");
    socket = home.resolve("api.sock");
    writeExecutable(
        report,
        SailSessionReport.scriptContent()
            .replace(SailPaths.apiSocketContainerPath().toString(), socket.toString()));
  }

  @Test
  void theSessionStartPayloadPostsItsIdentityToTheRunSessionLane() throws Exception {
    try (var bound = bind()) {
      var result =
          runReport(
              RUN_ID,
              CREDENTIAL,
              fakeCurl(),
              """
              {"session_id": "abc-123", "source": "startup",
               "transcript_path": "/home/dev/.claude/projects/p/abc-123.jsonl",
               "hook_event_name": "SessionStart"}""");

      assertEquals(0, result.exitCode());
      assertEquals("", result.stdout(), "SessionStart stdout joins the agent context: stay mute");
      var calls = curlLog();
      assertEquals(1, calls.size(), "one bounded POST per session start");
      assertTrue(calls.get(0).contains("-X POST"));
      assertTrue(calls.get(0).contains("/v1/run/session"), calls.get(0));
      assertTrue(calls.get(0).contains("session_id=abc-123"), calls.get(0));
      assertTrue(calls.get(0).contains("source=startup"), calls.get(0));
      assertTrue(
          calls
              .get(0)
              .contains("transcript_path=%2Fhome%2Fdev%2F.claude%2Fprojects%2Fp%2Fabc-123.jsonl"),
          "values travel urlencoded so a path never breaks the form body: " + calls.get(0));
    }
  }

  @Test
  void aCompactRestartReReportsTheNewConversation() throws Exception {
    try (var bound = bind()) {
      runReport(
          RUN_ID, CREDENTIAL, fakeCurl(), "{\"session_id\": \"abc-123\", \"source\": \"startup\"}");
      var result =
          runReport(
              RUN_ID,
              CREDENTIAL,
              fakeCurl(),
              "{\"session_id\": \"def-456\", \"source\": \"compact\"}");

      assertEquals(0, result.exitCode());
      var calls = curlLog();
      assertEquals(2, calls.size(), "every start source reports; the server keeps the last write");
      assertTrue(calls.get(1).contains("session_id=def-456"), calls.get(1));
      assertTrue(calls.get(1).contains("source=compact"), calls.get(1));
    }
  }

  @Test
  void aPayloadWithoutATranscriptOrSourceStillReportsTheSession() throws Exception {
    try (var bound = bind()) {
      var result = runReport(RUN_ID, CREDENTIAL, fakeCurl(), "{\"session_id\": \"only-id\"}");

      assertEquals(0, result.exitCode());
      var calls = curlLog();
      assertEquals(1, calls.size());
      assertTrue(calls.get(0).contains("session_id=only-id"), calls.get(0));
      assertFalse(calls.get(0).contains("source="), "an absent source is omitted, never guessed");
      assertFalse(calls.get(0).contains("transcript_path="), calls.get(0));
    }
  }

  @Test
  void aMalformedPayloadStaysSilent() throws Exception {
    try (var bound = bind()) {
      var result = runReport(RUN_ID, CREDENTIAL, fakeCurl(), "this is not json");

      assertEquals(0, result.exitCode());
      assertEquals("", result.stdout());
      assertTrue(curlLog().isEmpty(), "nothing parseable means nothing reported");
    }
  }

  @Test
  void aPayloadWithoutASessionIdStaysSilent() throws Exception {
    try (var bound = bind()) {
      var result = runReport(RUN_ID, CREDENTIAL, fakeCurl(), "{\"source\": \"startup\"}");

      assertEquals(0, result.exitCode());
      assertTrue(curlLog().isEmpty(), "a null session means fresh context only, never a guess");
    }
  }

  @Test
  void aFailedApiCallStaysSilent() throws Exception {
    try (var bound = bind()) {
      var result =
          runReport(
              RUN_ID,
              CREDENTIAL,
              fakeCurl(),
              "{\"session_id\": \"abc\"}",
              Map.of("FAKE_CURL_EXIT", "7"));

      assertEquals(0, result.exitCode(), "a down API must never block a session start");
      assertEquals("", result.stdout());
    }
  }

  @Test
  void aRoomBoundSessionReportsOverTheBoxLaneNamingItsRoomAndCli() throws Exception {
    try (var bound = bind()) {
      Files.writeString(home.resolve("box.credential"), "sailbox_ambient\n");
      reportArgs = List.of("claude-code");
      var result =
          runReport(
              null,
              null,
              fakeCurl(),
              "{\"session_id\": \"conv-1\", \"source\": \"startup\"}",
              Map.of("SAIL_ROOM_ID", "design-talk"));

      assertEquals(0, result.exitCode());
      assertEquals("", result.stdout());
      var calls = curlLog();
      assertEquals(1, calls.size(), "an interactive room session reports exactly once");
      assertTrue(calls.get(0).contains("/v1/run/session"), calls.get(0));
      assertTrue(calls.get(0).contains("room_id=design-talk"), calls.get(0));
      assertTrue(calls.get(0).contains("agent=claude-code"), "the hook names its CLI: " + calls);
      assertTrue(calls.get(0).contains("session_id=conv-1"), calls.get(0));
      assertTrue(
          calls.get(0).contains("Bearer sailbox_ambient"),
          "no run credential: the box's ambient credential beside the socket speaks: " + calls);
    }
  }

  @Test
  void aRunsReportStillPrefersItsRunCredentialAndCarriesNoRoomUnlessBound() throws Exception {
    try (var bound = bind()) {
      Files.writeString(home.resolve("box.credential"), "sailbox_ambient\n");
      reportArgs = List.of("codex");
      runReport(RUN_ID, CREDENTIAL, fakeCurl(), "{\"session_id\": \"abc\"}");

      var calls = curlLog();
      assertEquals(1, calls.size());
      assertTrue(calls.get(0).contains("Bearer " + CREDENTIAL), calls.get(0));
      assertFalse(calls.get(0).contains("room_id="), "a plain run names no room: " + calls);
      assertTrue(calls.get(0).contains("agent=codex"), calls.get(0));
    }
  }

  @Test
  void aRoomBoundSessionWithoutAnAmbientCredentialStaysSilent() throws Exception {
    try (var bound = bind()) {
      var result =
          runReport(
              null, null, fakeCurl(), "{\"session_id\": \"abc\"}", Map.of("SAIL_ROOM_ID", "x"));

      assertEquals(0, result.exitCode());
      assertTrue(curlLog().isEmpty(), "no credential of either kind: nothing to say");
    }
  }

  @Test
  void reportIsInertWithoutARunId() throws Exception {
    try (var bound = bind()) {
      var result = runReport(null, CREDENTIAL, fakeCurl(), "{\"session_id\": \"abc\"}");

      assertEquals(0, result.exitCode());
      assertEquals("", result.stdout());
      assertTrue(curlLog().isEmpty(), "reviewer and engineer sessions carry no run id: inert");
    }
  }

  @Test
  void reportIsInertWithoutACredential() throws Exception {
    try (var bound = bind()) {
      var result = runReport(RUN_ID, null, fakeCurl(), "{\"session_id\": \"abc\"}");

      assertEquals(0, result.exitCode());
      assertTrue(curlLog().isEmpty());
    }
  }

  @Test
  void reportIsInertWithoutTheSocket() throws Exception {
    var result = runReport(RUN_ID, CREDENTIAL, fakeCurl(), "{\"session_id\": \"abc\"}");

    assertEquals(0, result.exitCode());
    assertEquals("", result.stdout());
    assertTrue(curlLog().isEmpty());
  }

  @Test
  void scriptGatesOnTheRunIdAloneNeverTheSpecId() {
    var content = SailSessionReport.scriptContent();
    assertTrue(content.contains("SAIL_RUN_ID"));
    assertFalse(
        content.contains("SAIL_SPEC_ID"),
        "the fix lane exports no spec id by design — its sessions are resumable conversations"
            + " too, and the server resolves run to spec from the credential");
    assertFalse(content.contains("__SAIL_API_SOCKET__"), "the socket placeholder is resolved");
  }

  @Test
  void scriptPathAndTimeoutConstantsMatch() {
    assertEquals("/home/dev/.sail/bin/sail-session-report", SailSessionReport.SCRIPT_PATH);
    assertEquals(10, SailSessionReport.HOOK_TIMEOUT_SECONDS);
  }

  @Test
  void constructorRejectsNullShell() {
    assertThrows(NullPointerException.class, () -> new SailSessionReport(null));
  }

  @Test
  void installWritesTheExecutableReportScript() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    new SailSessionReport(shell).install("acme");

    var cmds = shell.invocations();
    assertEquals(2, cmds.size());
    assertTrue(cmds.get(0).contains("mkdir"));
    assertTrue(cmds.get(1).contains(SailSessionReport.SCRIPT_PATH));
    assertTrue(cmds.get(1).contains("chmod 0755"));
  }

  @Test
  void installPropagatesMkdirFailure() {
    var shell = new ScriptedShellExecutor().onFail("mkdir", "denied");
    assertThrows(IOException.class, () -> new SailSessionReport(shell).install("acme"));
  }

  @Test
  void installPropagatesWriteFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("mkdir -p /home/dev/.sail/bin")
            .onFail("printf '%s'", "disk full");
    var ex = assertThrows(IOException.class, () -> new SailSessionReport(shell).install("acme"));
    assertTrue(ex.getMessage().contains("disk full"));
  }

  @Test
  void installRejectsInvalidContainerName() {
    var shell = new ScriptedShellExecutor();
    assertThrows(Exception.class, () -> new SailSessionReport(shell).install("../bad"));
  }

  private Closeable bind() throws IOException {
    var channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    channel.bind(UnixDomainSocketAddress.of(socket));
    return channel;
  }

  private Path fakeCurl() throws IOException {
    var bin = home.resolve("fakebin");
    Files.createDirectories(bin);
    writeExecutable(
        bin.resolve("curl"),
        """
        #!/bin/sh
        printf '%s\\n' "$*" >> "$HOME/curl.log"
        [ "${FAKE_CURL_EXIT:-0}" = "0" ] || exit "$FAKE_CURL_EXIT"
        exit 0
        """);
    return bin;
  }

  private List<String> curlLog() throws IOException {
    var log = home.resolve("curl.log");
    return Files.exists(log) ? Files.readAllLines(log) : List.of();
  }

  private ReportResult runReport(String runId, String credential, Path pathPrefix, String stdin)
      throws Exception {
    return runReport(runId, credential, pathPrefix, stdin, Map.of());
  }

  private List<String> reportArgs = List.of();

  private ReportResult runReport(
      String runId, String credential, Path pathPrefix, String stdin, Map<String, String> extraEnv)
      throws Exception {
    var argv = new java.util.ArrayList<>(List.of("/bin/sh", report.toString()));
    argv.addAll(reportArgs);
    var pb = new ProcessBuilder(argv);
    var env = pb.environment();
    env.put("HOME", home.toString());
    env.keySet().removeIf(key -> key.startsWith("SAIL_"));
    if (runId != null) {
      env.put("SAIL_RUN_ID", runId);
    }
    if (credential != null) {
      env.put("SAIL_RUN_CREDENTIAL", credential);
    }
    env.put("PATH", pathPrefix + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
    env.putAll(extraEnv);
    var process = pb.start();
    writeStdinToleratingEarlyExit(process, stdin);
    var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the report must finish well inside its 10s");
    return new ReportResult(process.exitValue(), stdout, stderr);
  }

  /**
   * Feeds the report's stdin, swallowing the broken pipe a script that exits before reading — the
   * inert no-run-id and no-credential paths — legitimately produces. Whether the child consumed its
   * stdin is never the assertion; every test verifies exit code and output, and a child that
   * wrongly died early fails those checks instead.
   */
  private static void writeStdinToleratingEarlyExit(Process process, String stdin) {
    try (var in = process.getOutputStream()) {
      in.write(stdin.getBytes(StandardCharsets.UTF_8));
    } catch (IOException earlyExit) {
    }
  }

  private static void writeExecutable(Path path, String content) throws IOException {
    Files.writeString(path, content);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
  }
}
