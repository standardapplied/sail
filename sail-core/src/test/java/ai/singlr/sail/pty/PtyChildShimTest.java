/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shim runs as its own process here, on a real pty as the host spawns it — installing its
 * signal handlers inside the test JVM would leave them behind — so every case is a real {@code
 * kill} against a real pid.
 */
class PtyChildShimTest {

  @TempDir Path dir;

  private Pty pty;

  @AfterEach
  void closeThePty() {
    if (pty != null) {
      pty.close();
    }
  }

  /** {@code java -cp <this class path> PtyChildShim <exit file> <command...>}. */
  static List<String> shim(Path exitFile, String... command) {
    var argv = new ArrayList<>(PtyChildShim.onClassPath(exitFile));
    argv.addAll(List.of(command));
    return argv;
  }

  private Process start(Path exitFile, String... command) throws IOException {
    pty = Pty.open(80, 24);
    return pty.spawn(shim(exitFile, command), Map.of("TERM", "dumb"), dir);
  }

  private static long childPidFrom(Path pidFile) throws Exception {
    var deadline = System.nanoTime() + 20_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (Files.exists(pidFile) && !Files.readString(pidFile).isBlank()) {
        return Long.parseLong(Files.readString(pidFile).strip());
      }
      Thread.onSpinWait();
    }
    throw new AssertionError("the child never wrote its pid");
  }

  private static void signal(long pid, String name) throws Exception {
    var kill = new ProcessBuilder("kill", "-" + name, Long.toString(pid)).inheritIO().start();
    assertEquals(0, kill.waitFor(), "kill -" + name + " " + pid);
  }

  @Test
  void runOutsideAPtyHostTheShimNamesWhatIsMissingAndRunsNothing() throws Exception {
    var exit = dir.resolve("s.exit");
    var shim = new ProcessBuilder(shim(exit, "sh", "-c", "exit 7")).redirectErrorStream(true);
    shim.environment().remove(Pty.TERMINAL);
    var started = shim.start();
    var said = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

    assertEquals(1, started.waitFor());
    assertEquals(
        "Exception in thread \"main\" java.lang.IllegalStateException: No terminal is named in"
            + " SAIL_PTY_TERMINAL: the shim runs only as the pty host spawns it.",
        said.lines().findFirst().orElseThrow());
    assertFalse(Files.exists(exit), "no command ran, so no status is recorded");
  }

  @Test
  void theExitStatusIsRecordedAndMirrored() throws Exception {
    var exit = dir.resolve("s.exit");
    var shim = start(exit, "sh", "-c", "exit 7");

    assertTrue(shim.waitFor(30, TimeUnit.SECONDS), "the shim ends with its child");
    assertEquals(7, shim.exitValue(), "the shim's own status mirrors the child's");
    assertEquals("7", Files.readString(exit));
  }

  @Test
  void aChildKilledOutrightRecordsTheSignalStatus() throws Exception {
    var exit = dir.resolve("s.exit");
    var pid = dir.resolve("pid");
    var shim = start(exit, "sh", "-c", "echo $$ > " + pid + "; while :; do sleep 1; done");

    signal(childPidFrom(pid), "KILL");

    assertTrue(shim.waitFor(30, TimeUnit.SECONDS));
    assertEquals("137", Files.readString(exit));
    assertEquals(137, shim.exitValue());
  }

  @Test
  void aHangupOnTheShimReachesTheChildAndItsStatusIsStillRecorded() throws Exception {
    var exit = dir.resolve("s.exit");
    var pid = dir.resolve("pid");
    var shim = start(exit, "sh", "-c", "echo $$ > " + pid + "; while :; do sleep 1; done");
    var child = childPidFrom(pid);

    signal(shim.pid(), "HUP");

    assertTrue(shim.waitFor(30, TimeUnit.SECONDS), "the shim outlives the child, not the hangup");
    assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
    assertEquals("129", Files.readString(exit), "the child died of the hangup: 128 + SIGHUP");
  }

  @Test
  void aTerminationOfTheShimReachesTheChildWhichMayHandleIt() throws Exception {
    var exit = dir.resolve("s.exit");
    var pid = dir.resolve("pid");
    var shim =
        start(
            exit,
            "sh",
            "-c",
            "trap 'exit 3' TERM; echo $$ > " + pid + "; while :; do sleep 0.1; done");
    var child = childPidFrom(pid);

    signal(shim.pid(), "TERM");

    assertTrue(shim.waitFor(30, TimeUnit.SECONDS));
    assertFalse(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false));
    assertEquals("3", Files.readString(exit), "the child's own handling of TERM is what counts");
    assertEquals(3, shim.exitValue());
  }
}
