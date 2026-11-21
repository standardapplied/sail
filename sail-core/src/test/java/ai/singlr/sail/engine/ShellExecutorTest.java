/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class ShellExecutorTest {

  @Test
  void aDryRunsObserverRunsItsProbeAndARealShellIsItsOwnObserver() throws Exception {
    var dryRun = new ShellExecutor(true);
    var real = new ShellExecutor(false);

    var probe = dryRun.observer().exec(List.of("echo", "really-ran"));

    assertEquals("really-ran", probe.stdout().strip());
    assertFalse(dryRun.observer().isDryRun());
    assertSame(real, real.observer());
  }

  @Test
  void aFailuresReasonIsWhatItSaidOrItsExitCodeWhenItSaidNothing() {
    assertEquals(
        "Error: no such device", new ShellExec.Result(1, "", " Error: no such device\n").reason());
    assertEquals("exited 1", new ShellExec.Result(1, "out", " \n").reason());
  }

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
  void aCommandThatTimesOutLeavesNoProcessItStartedRunning(@TempDir Path dir) throws Exception {
    var pid = dir.resolve("child.pid");
    var executor = new ShellExecutor(false);

    assertThrows(
        TimeoutException.class,
        () ->
            executor.exec(
                List.of("bash", "-c", "sleep 300 & echo $! > \"$1\"; wait", "bash", pid.toString()),
                null,
                Duration.ofSeconds(1)));

    var child = ProcessHandle.of(Long.parseLong(Files.readString(pid).strip()));
    if (child.isPresent()) {
      child.get().onExit().get(10, TimeUnit.SECONDS);
    }
    assertFalse(
        child.map(ProcessHandle::isAlive).orElse(false), "the timed-out command's child runs on");
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  void aCommandThatTimesOutListsWhatItsProcessesWereDoingBeforeTheyWereKilled() {
    var executor = new ShellExecutor(false);

    CommandTimedOut timedOut;
    try (var listing = ShellExecutor.listingTimedOutProcesses()) {
      timedOut =
          assertThrows(
              CommandTimedOut.class,
              () ->
                  executor.exec(
                      List.of("sh", "-c", "sleep 30; true"), null, Duration.ofSeconds(1)));
    }

    assertTrue(
        timedOut.getMessage().startsWith("Command timed out after 1s: sh -c sleep 30; true"),
        timedOut.getMessage());
    var processes = timedOut.processes();
    assertTrue(processes.lines().findFirst().orElse("").contains("STAT"), processes);
    assertTrue(
        processes.lines().anyMatch(line -> line.matches("\\s*\\d+\\s+\\d+\\s+S\\S*\\s.*sleep 30")),
        processes);
  }

  @Test
  void aCommandThatTimesOutUnaskedListsNothingSoNoOtherCallerPaysForTheListing() {
    var executor = new ShellExecutor(false);

    var timedOut =
        assertThrows(
            CommandTimedOut.class,
            () ->
                executor.exec(List.of("sh", "-c", "sleep 30; true"), null, Duration.ofMillis(300)));

    assertEquals("", timedOut.processes());
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

  @Test
  void runHandsEachLineOverAsItArrivesAndWaitsPastAnyDeadline(@TempDir Path dir) {
    var executor = new ShellExecutor(false, Duration.ofMillis(100));
    var seen = dir.resolve("seen");
    var lines = new ArrayList<String>();
    var command =
        List.of(
            "sh",
            "-c",
            "echo one; while [ ! -e \"$1\" ]; do sleep 0.01; done; sleep 0.3; echo two;"
                + " echo oops >&2; exit 4",
            "sh",
            seen.toString());

    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () ->
                executor.run(
                    command,
                    line -> {
                      lines.add(line);
                      touch(seen);
                    }));

    assertEquals(List.of("one", "two"), lines);
    assertEquals(new ShellExec.Result(4, "one\ntwo\n", "oops\n"), result);
  }

  @Test
  void runInDryRunPrintsTheCommandAndRunsNothing() throws Exception {
    var lines = new ArrayList<String>();

    var result = new ShellExecutor(true).run(List.of("sh", "-c", "echo hi"), lines::add);

    assertEquals(new ShellExec.Result(0, "", ""), result);
    assertEquals(List.of(), lines);
  }

  private static void touch(Path path) {
    try {
      Files.writeString(path, "");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void onlyA401OrA403WithAChallengeAsksForCredentialsAndNoRedirectIsFollowed() throws Exception {
    var elsewhere =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    var followed = new CopyOnWriteArrayList<String>();
    elsewhere.createContext(
        "/",
        exchange -> {
          followed.add(exchange.getRequestURI().toString());
          exchange.sendResponseHeaders(401, -1);
          exchange.close();
        });
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          switch (exchange.getRequestURI().getPath()) {
            case "/private" -> exchange.sendResponseHeaders(401, -1);
            case "/challenged" -> {
              exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"git\"");
              exchange.sendResponseHeaders(403, -1);
            }
            case "/forbidden" -> exchange.sendResponseHeaders(403, -1);
            case "/moved" -> {
              exchange
                  .getResponseHeaders()
                  .add(
                      "Location",
                      "http://127.0.0.1:" + elsewhere.getAddress().getPort() + "/private");
              exchange.sendResponseHeaders(302, -1);
            }
            default -> exchange.sendResponseHeaders(200, -1);
          }
          exchange.close();
        });
    elsewhere.start();
    server.start();
    var probe = new ShellExecutor(false);
    var at = "http://127.0.0.1:" + server.getAddress().getPort();
    try {
      assertTrue(probe.asksForCredentials(at + "/private"));
      assertTrue(probe.asksForCredentials(at + "/challenged"));
      assertFalse(probe.asksForCredentials(at + "/forbidden"), "a 403 no token lifts");
      assertFalse(probe.asksForCredentials(at + "/public"));
      assertFalse(probe.asksForCredentials(at + "/moved"));
      assertEquals(List.of(), followed, "a redirect is not followed to another host");
      assertFalse(probe.asksForCredentials("http://127.0.0.1:1/info/refs"));
      assertFalse(probe.asksForCredentials("not a url"));
    } finally {
      server.stop(0);
      elsewhere.stop(0);
    }
  }

  /** More input than a pipe holds, so a write that is not taken meets it every time. */
  private static final String MIB = "token-like-input-".repeat((1 << 20) / 17 + 1);

  private static ShellExec.Result handed(String script, Duration timeout) throws Exception {
    return assertTimeoutPreemptively(
        timeout.plusSeconds(5),
        () -> new ShellExecutor(false).execWithInput(List.of("sh", "-c", script), MIB, timeout));
  }

  /**
   * A command that ends without taking its input, as a script that refuses before it reads does,
   * answers with its own exit status and words, never as a broken pipe.
   */
  @Test
  void aCommandThatEndsBeforeTakingItsInputAnswersWithItsOwnStatus() throws Exception {
    var result = handed("echo refused >&2; exit 3", Duration.ofSeconds(10));

    assertEquals(3, result.exitCode());
    assertEquals("refused\n", result.stderr());
  }

  /**
   * #507's M1: a command that never reads its input and never exits is ended at its own timeout,
   * the input's write ended with it, and neither the timeout nor anything else says the input.
   */
  @Test
  void aCommandThatNeverReadsNorExitsTimesOutAtItsBoundAndSaysNothingOfItsInput() {
    var started = System.nanoTime();
    var timedOut =
        assertThrows(CommandTimedOut.class, () -> handed("exec sleep 30", Duration.ofSeconds(1)));

    assertTrue(
        System.nanoTime() - started < Duration.ofSeconds(4).toNanos(),
        "ended at its bound, not when the command would have");
    assertFalse(timedOut.getMessage().contains("token-like-input"), timedOut.getMessage());
  }

  /** A command that writes back all it reads neither deadlocks nor loses a byte. */
  @Test
  void aCommandThatEchoesItsInputBackGivesItAllBackWithoutADeadlock() throws Exception {
    var result = handed("cat", Duration.ofSeconds(20));

    assertEquals(0, result.exitCode());
    assertEquals(MIB, result.stdout());
  }

  /** A command that reads its input slowly, after a pause, still takes all of it. */
  @Test
  void aCommandThatReadsItsInputSlowlyTakesAllOfIt() throws Exception {
    var result = handed("sleep 1; wc -c", Duration.ofSeconds(20));

    assertEquals(0, result.exitCode());
    assertEquals(String.valueOf(MIB.length()), result.stdout().strip());
  }

  /** A command that writes much more than a pipe holds before it reads is read meanwhile. */
  @Test
  void aCommandThatWritesALotBeforeItReadsIsReadMeanwhile() throws Exception {
    var result =
        handed("head -c 4194304 /dev/zero | tr '\\0' x; wc -c >&2", Duration.ofSeconds(20));

    assertEquals(0, result.exitCode());
    assertEquals(4194304, result.stdout().length());
    assertEquals(String.valueOf(MIB.length()), result.stderr().strip());
  }

  /**
   * A command that says it succeeded without taking all it was given did not do what it was asked:
   * that is raised, naming the command and never the input.
   */
  @Test
  void aCommandThatExitsZeroWithoutTakingItsInputIsRaisedWithoutTheInput() {
    var refused =
        assertThrows(
            IOException.class, () -> handed("head -c 10 > /dev/null", Duration.ofSeconds(10)));

    assertTrue(
        refused
            .getMessage()
            .startsWith("sh -c head -c 10 > /dev/null exited 0 without taking all of its input"),
        refused.getMessage());
    assertFalse(refused.getMessage().contains("token-like-input"), refused.getMessage());
  }

  /**
   * #507's last look, m1: the raise of an untaken input never races the writer: a command that
   * exits 0 at once without reading is raised every time.
   */
  @Test
  void anUntakenInputIsRaisedEveryTimeNotWhenTheWriterHappensToBeFirst() {
    for (var time = 0; time < 40; time++) {
      assertThrows(IOException.class, () -> handed("exit 0", Duration.ofSeconds(10)));
    }
  }

  /**
   * #507's last look, nit 1: a command whose own process ends while a process it left behind still
   * holds its output is answered within its timeout, raised as a timeout, never held until that
   * process ends.
   */
  @Test
  void aProcessLeftHoldingTheOutputDoesNotOutlastTheTimeout() {
    var started = System.nanoTime();
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () ->
            assertThrows(
                CommandTimedOut.class,
                () ->
                    new ShellExecutor(false)
                        .execWithInput(
                            List.of("sh", "-c", "sleep 8 & exit 0"), "", Duration.ofSeconds(1))));

    assertTrue(
        System.nanoTime() - started < Duration.ofSeconds(3).toNanos(), "ended at its timeout");
  }

  /**
   * #507's last look, nit 3: a command whose caller is interrupted while it waits is ended with it,
   * never left running.
   */
  @Test
  void aCommandWhoseCallerIsInterruptedIsEndedWithIt() throws Exception {
    var marker = "sleep 37.25";
    Thread.currentThread().interrupt();
    assertThrows(
        InterruptedException.class,
        () ->
            new ShellExecutor(false)
                .exec(List.of("sh", "-c", "exec " + marker), null, Duration.ofSeconds(60)));
    assertFalse(Thread.interrupted(), "the interrupt is the caller's, kept in the exception");

    for (var left :
        ProcessHandle.current()
            .descendants()
            .filter(handle -> handle.info().commandLine().orElse("").contains(marker))
            .toList()) {
      assertTrue(
          left.onExit()
              .thenApply(ended -> true)
              .completeOnTimeout(false, 5, TimeUnit.SECONDS)
              .get(),
          "the interrupted command was ended");
    }
  }
}
