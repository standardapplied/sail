/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellExecutorTest {

  @Test
  void executesSimpleCommand() throws Exception {
    var executor = new ShellExecutor(false);
    var result = executor.exec(List.of("echo", "hello"));

    assertTrue(result.ok());
    assertEquals("hello\n", result.stdout());
    assertEquals("", result.stderr());
  }

  @Test
  void capturesExitCode() throws Exception {
    var executor = new ShellExecutor(false);
    var result = executor.exec(List.of("sh", "-c", "exit 42"));

    assertFalse(result.ok());
    assertEquals(42, result.exitCode());
  }

  @Test
  void capturesStderr() throws Exception {
    var executor = new ShellExecutor(false);
    var result = executor.exec(List.of("sh", "-c", "echo err >&2"));

    assertTrue(result.ok());
    assertEquals("err\n", result.stderr());
  }

  @Test
  void drainsLargeStdoutAndStderrConcurrentlyWithoutDeadlock() throws Exception {
    var executor = new ShellExecutor(false);
    var result =
        executor.exec(
            List.of("sh", "-c", "yes out | head -c 200000; yes err | head -c 200000 >&2"),
            null,
            Duration.ofSeconds(30));

    assertTrue(result.ok());
    assertEquals(200000, result.stdout().length());
    assertEquals(200000, result.stderr().length());
  }

  @Test
  void dryRunDoesNotExecute() throws Exception {
    var executor = new ShellExecutor(true);
    var result = executor.exec(List.of("rm", "-rf", "/should-not-run"));

    assertTrue(result.ok());
    assertEquals("", result.stdout());
    assertTrue(executor.isDryRun());
  }

  @TempDir Path tempDir;

  @Test
  void executesWithWorkingDirectory() throws Exception {
    var executor = new ShellExecutor(false);
    var result = executor.exec(List.of("pwd"), tempDir, Duration.ofSeconds(5));

    assertTrue(result.ok());
    assertTrue(result.stdout().strip().endsWith(tempDir.getFileName().toString()));
  }

  @Test
  void timesOutOnLongRunningCommand() {
    var executor = new ShellExecutor(false);
    assertThrows(
        TimeoutException.class,
        () ->
            executor.exec(
                List.of("sh", "-c", "exec 1>/dev/null 2>/dev/null; sleep 30"),
                null,
                Duration.ofMillis(200)));
  }

  @Test
  void timesOutWhenProcessKeepsStdoutOpen() {
    var executor = new ShellExecutor(false);

    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () ->
            assertThrows(
                TimeoutException.class,
                () ->
                    executor.exec(List.of("sh", "-c", "sleep 30"), null, Duration.ofMillis(200))));
  }

  @Test
  void customDefaultTimeout() throws Exception {
    var executor = new ShellExecutor(false, Duration.ofSeconds(30));
    var result = executor.exec(List.of("echo", "fast"));

    assertTrue(result.ok());
    assertFalse(executor.isDryRun());
  }

  @Test
  void aStreamThatKeepsDeliveringOutlivesTheIdleTimeout() throws Exception {
    var executor = new ShellExecutor(false, Duration.ofSeconds(2));
    var command = List.of("sh", "-c", "for i in 1 2 3 4 5 6; do printf x; sleep 0.2; done");

    try (var stream = executor.stream(command)) {
      assertEquals("xxxxxx", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void aStreamThatStallsIsDestroyedNamingTheCommandTheSilenceAndTheBytesReceived() {
    var executor = new ShellExecutor(false, Duration.ofSeconds(1));
    var command = List.of("sh", "-c", "printf hello; exec sleep 30");

    var failure =
        assertThrows(
            IOException.class,
            () -> {
              try (var stream = executor.stream(command)) {
                stream.readAllBytes();
              }
            });

    assertTrue(failure.getMessage().contains("exec sleep 30"), failure.getMessage());
    assertTrue(failure.getMessage().contains("no output for 1s"), failure.getMessage());
    assertTrue(failure.getMessage().contains("after 5 bytes"), failure.getMessage());
  }

  @Test
  void aStreamReadByteByByteCountsEveryByteAsProgress() throws Exception {
    var executor = new ShellExecutor(false, Duration.ofSeconds(5));
    try (var stream = executor.stream(List.of("sh", "-c", "printf abc"))) {
      assertEquals('a', stream.read());
      assertEquals('b', stream.read());
      assertEquals('c', stream.read());
      assertEquals(-1, stream.read());
    }
  }

  @Test
  void aStreamWhoseCommandFailsNamesTheCommand() {
    var executor = new ShellExecutor(false, Duration.ofSeconds(5));
    var failure =
        assertThrows(
            IOException.class,
            () -> {
              try (var stream = executor.stream(List.of("sh", "-c", "printf partial; exit 3"))) {
                stream.readAllBytes();
              }
            });
    assertTrue(failure.getMessage().startsWith("Content command failed:"), failure.getMessage());
  }
}
