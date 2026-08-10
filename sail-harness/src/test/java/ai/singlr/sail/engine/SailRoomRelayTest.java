/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import java.io.Closeable;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Executes the real relay script under a fake {@code $HOME} with a bound Unix socket and a scripted
 * {@code curl}, so delivery, acknowledgement ordering, the interval gate, and every fail-open path
 * are asserted against what the shell actually does.
 */
class SailRoomRelayTest {

  private static final String RUN_ID = "run-1";
  private static final String CREDENTIAL = "sailrun_test";

  @TempDir Path home;
  private Path relay;
  private Path socket;

  private record RelayResult(int exitCode, String stdout, String stderr) {}

  @BeforeEach
  void installRelayUnderFakeHome() throws Exception {
    relay = home.resolve("sail-room-relay");
    socket = home.resolve("api.sock");
    writeExecutable(
        relay,
        SailRoomRelay.scriptContent()
            .replace(SailPaths.apiSocketContainerPath().toString(), socket.toString()));
  }

  @Test
  void freshMessagesAreDeliveredAsAdditionalContextBeforeAcknowledgement() throws Exception {
    inbox(
        """
        {"run_id": "run-1", "spec_id": "auth", "messages": [
          {"id": "m1", "author": "uday", "body": "please also update the docs"},
          {"id": "m2", "author": "ada", "body": "and rename the flag"}]}""");
    try (var bound = bind()) {
      var result = runRelay(RUN_ID, CREDENTIAL, fakeCurl());

      assertEquals(0, result.exitCode());
      var output = YamlUtil.parseMap(result.stdout());
      @SuppressWarnings("unchecked")
      var hookOutput = (Map<String, Object>) output.get("hookSpecificOutput");
      assertEquals("PostToolUse", hookOutput.get("hookEventName"));
      var context = (String) hookOutput.get("additionalContext");
      assertTrue(
          context.contains(
              "[Room message from uday, arrived while you were working]: please also update the"
                  + " docs"),
          context);
      assertTrue(
          context.contains(
              "[Room message from ada, arrived while you were working]: and rename the flag"),
          context);

      var calls = curlLog();
      assertEquals(2, calls.size(), "one inbox read, one acknowledgement");
      assertFalse(calls.get(0).contains("-X POST"));
      assertTrue(calls.get(1).contains("-X POST"));
      assertTrue(
          calls.get(1).contains("delivered=m1,m2"),
          "the ack names exactly the ids delivered, never more");
      assertTrue(
          Files.exists(runDir().resolve("room-relay-checked")),
          "the interval stamp records the check");
    }
  }

  @Test
  void aCappedBatchDeliversAndAcksOnlyItsOwnIds() throws Exception {
    inbox(
        """
        {"run_id": "run-1", "spec_id": "auth", "messages": [
          {"id": "m1", "author": "uday", "body": "first of many"}], "has_more": true}""");
    try (var bound = bind()) {
      var result = runRelay(RUN_ID, CREDENTIAL, fakeCurl());

      assertEquals(0, result.exitCode());
      assertTrue(result.stdout().contains("first of many"), result.stdout());
      assertTrue(
          curlLog().get(1).contains("delivered=m1"),
          "the rest stays undelivered for the next interval");
    }
  }

  @Test
  void anEmptyRoomEndsAfterTheInboxRead() throws Exception {
    inbox("{\"run_id\": \"run-1\", \"spec_id\": \"auth\", \"messages\": []}");
    try (var bound = bind()) {
      var result = runRelay(RUN_ID, CREDENTIAL, fakeCurl());

      assertEquals(0, result.exitCode());
      assertEquals("", result.stdout());
      assertEquals(1, curlLog().size(), "no fresh ids means nothing to acknowledge");
    }
  }

  @Test
  void aFailedApiCallStaysSilent() throws Exception {
    try (var bound = bind()) {
      var result = runRelay(RUN_ID, CREDENTIAL, fakeCurl(), Map.of("FAKE_CURL_EXIT", "7"));

      assertEquals(0, result.exitCode(), "a down API must never break a build");
      assertEquals("", result.stdout());
    }
  }

  @Test
  void aFailedAcknowledgementStillDeliversAndLeavesTheLedgerToRetry() throws Exception {
    inbox(
        """
        {"run_id": "run-1", "spec_id": "auth", "messages": [
          {"id": "m1", "author": "uday", "body": "hello"}]}""");
    try (var bound = bind()) {
      var result = runRelay(RUN_ID, CREDENTIAL, fakeCurl(), Map.of("FAKE_ACK", "500"));

      assertEquals(0, result.exitCode());
      assertTrue(
          result.stdout().contains("hello"),
          "emit-then-ack: the agent sees the message even when the acknowledgement fails —"
              + " the worst case is a duplicate next check, never a lost message");
      assertEquals(2, curlLog().size(), "the acknowledgement was attempted after the emit");
    }
  }

  @Test
  void aFailedOutputBuildNeverAcknowledges() throws Exception {
    inbox(
        """
        {"run_id": "run-1", "spec_id": "auth", "messages": [
          {"id": "m1", "author": "uday", "body": "hello"}]}""");
    var bin = fakeCurl();
    pythonFailingOnSecondCall(bin);
    try (var bound = bind()) {
      var result = runRelay(RUN_ID, CREDENTIAL, bin);

      assertEquals(0, result.exitCode());
      assertEquals("", result.stdout());
      assertTrue(
          curlLog().stream().noneMatch(call -> call.contains("-X POST")),
          "nothing emitted means nothing acknowledged — the batch stays undelivered and"
              + " retries next check");
    }
  }

  @Test
  void garbageInTheInboxStaysSilent() throws Exception {
    inbox("this is not json");
    try (var bound = bind()) {
      var result = runRelay(RUN_ID, CREDENTIAL, fakeCurl());

      assertEquals(0, result.exitCode());
      assertEquals("", result.stdout());
    }
  }

  @Test
  void theIntervalStampSkipsTheApiRoundTrip() throws Exception {
    inbox(
        """
        {"run_id": "run-1", "spec_id": "auth", "messages": [
          {"id": "m1", "author": "uday", "body": "hello"}]}""");
    Files.createDirectories(runDir());
    Files.writeString(
        runDir().resolve("room-relay-checked"), Long.toString(Instant.now().getEpochSecond()));
    try (var bound = bind()) {
      var result = runRelay(RUN_ID, CREDENTIAL, fakeCurl());

      assertEquals(0, result.exitCode());
      assertEquals("", result.stdout());
      assertTrue(curlLog().isEmpty(), "a fresh stamp means no API call at all");
    }
  }

  @Test
  void aStaleOrGarbageStampChecksAgain() throws Exception {
    inbox("{\"run_id\": \"run-1\", \"spec_id\": \"auth\", \"messages\": []}");
    Files.createDirectories(runDir());
    Files.writeString(runDir().resolve("room-relay-checked"), "not-a-number");
    try (var bound = bind()) {
      var result = runRelay(RUN_ID, CREDENTIAL, fakeCurl());

      assertEquals(0, result.exitCode());
      assertEquals(1, curlLog().size(), "an unreadable stamp counts as never-checked");
    }
  }

  @Test
  void relayIsInertWithoutARunId() throws Exception {
    inbox(
        """
        {"run_id": "run-1", "spec_id": "auth", "messages": [
          {"id": "m1", "author": "uday", "body": "hello"}]}""");
    try (var bound = bind()) {
      var result = runRelay(null, CREDENTIAL, fakeCurl());

      assertEquals(0, result.exitCode());
      assertEquals("", result.stdout());
      assertTrue(curlLog().isEmpty(), "reviewer and engineer sessions carry no run id: inert");
    }
  }

  @Test
  void relayIsInertWithoutACredential() throws Exception {
    try (var bound = bind()) {
      var result = runRelay(RUN_ID, null, fakeCurl());

      assertEquals(0, result.exitCode());
      assertTrue(curlLog().isEmpty());
    }
  }

  @Test
  void relayIsInertWithoutTheSocket() throws Exception {
    var result = runRelay(RUN_ID, CREDENTIAL, fakeCurl());

    assertEquals(0, result.exitCode());
    assertEquals("", result.stdout());
    assertTrue(curlLog().isEmpty());
  }

  @Test
  void scriptGatesOnTheRunIdAloneNeverTheSpecId() {
    var content = SailRoomRelay.scriptContent();
    assertTrue(content.contains("SAIL_RUN_ID"));
    assertFalse(
        content.contains("SAIL_SPEC_ID"),
        "the fix lane exports no spec id by design — the server resolves run to spec from the"
            + " credential");
    assertFalse(content.contains("__SAIL_API_SOCKET__"), "the socket placeholder is resolved");
    assertFalse(content.contains("__CHECK_INTERVAL__"), "the interval placeholder is resolved");
    assertTrue(content.contains(String.valueOf(SailRoomRelay.CHECK_INTERVAL_SECONDS)));
  }

  @Test
  void scriptPathAndTimeoutConstantsMatch() {
    assertEquals("/home/dev/.sail/bin/sail-room-relay", SailRoomRelay.SCRIPT_PATH);
    assertEquals(15, SailRoomRelay.HOOK_TIMEOUT_SECONDS);
    assertEquals(15, SailRoomRelay.CHECK_INTERVAL_SECONDS);
  }

  @Test
  void constructorRejectsNullShell() {
    assertThrows(NullPointerException.class, () -> new SailRoomRelay(null));
  }

  @Test
  void installWritesTheExecutableRelayScript() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    new SailRoomRelay(shell).install("acme");

    var cmds = shell.invocations();
    assertEquals(2, cmds.size());
    assertTrue(cmds.get(0).contains("mkdir"));
    assertTrue(cmds.get(1).contains(SailRoomRelay.SCRIPT_PATH));
    assertTrue(cmds.get(1).contains("chmod 0755"));
  }

  @Test
  void installPropagatesMkdirFailure() {
    var shell = new ScriptedShellExecutor().onFail("mkdir", "denied");
    assertThrows(IOException.class, () -> new SailRoomRelay(shell).install("acme"));
  }

  @Test
  void installPropagatesWriteFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("mkdir -p /home/dev/.sail/bin")
            .onFail("printf '%s'", "disk full");
    var ex = assertThrows(IOException.class, () -> new SailRoomRelay(shell).install("acme"));
    assertTrue(ex.getMessage().contains("disk full"));
  }

  @Test
  void installRejectsInvalidContainerName() {
    var shell = new ScriptedShellExecutor();
    assertThrows(Exception.class, () -> new SailRoomRelay(shell).install("../bad"));
  }

  private Closeable bind() throws IOException {
    var channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    channel.bind(UnixDomainSocketAddress.of(socket));
    return channel;
  }

  private void inbox(String json) throws IOException {
    Files.writeString(home.resolve("inbox.json"), json);
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
        case "$*" in
          *"-X POST"*) [ "${FAKE_ACK:-200}" = "200" ] || exit 22 ;;
          *) cat "$HOME/inbox.json" 2>/dev/null ;;
        esac
        exit 0
        """);
    return bin;
  }

  private void pythonFailingOnSecondCall(Path bin) throws IOException {
    writeExecutable(
        bin.resolve("python3"),
        """
        #!/bin/sh
        if [ -f "$HOME/python.calls" ]; then
          exit 1
        fi
        : > "$HOME/python.calls"
        exec /usr/bin/python3 "$@"
        """);
  }

  private List<String> curlLog() throws IOException {
    var log = home.resolve("curl.log");
    return Files.exists(log) ? Files.readAllLines(log) : List.of();
  }

  private Path runDir() {
    return home.resolve(".sail/runs/" + RUN_ID);
  }

  private RelayResult runRelay(String runId, String credential, Path pathPrefix) throws Exception {
    return runRelay(runId, credential, pathPrefix, Map.of());
  }

  private RelayResult runRelay(
      String runId, String credential, Path pathPrefix, Map<String, String> extraEnv)
      throws Exception {
    var pb = new ProcessBuilder("/bin/sh", relay.toString());
    var env = pb.environment();
    env.put("HOME", home.toString());
    env.remove("SAIL_RUN_ID");
    env.remove("SAIL_RUN_CREDENTIAL");
    if (runId != null) {
      env.put("SAIL_RUN_ID", runId);
    }
    if (credential != null) {
      env.put("SAIL_RUN_CREDENTIAL", credential);
    }
    env.put("PATH", pathPrefix + ":" + env.getOrDefault("PATH", "/usr/bin:/bin"));
    env.putAll(extraEnv);
    var process = pb.start();
    process.getOutputStream().close();
    var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    var stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the relay must finish well inside its 15s");
    return new RelayResult(process.exitValue(), stdout, stderr);
  }

  private static void writeExecutable(Path path, String content) throws IOException {
    Files.writeString(path, content);
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
  }
}
