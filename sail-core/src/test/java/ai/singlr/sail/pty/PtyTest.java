/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every child here is spawned as the host spawns one: under the real shim, run from this class
 * path.
 */
class PtyTest {

  @TempDir Path dir;

  /**
   * Reads {@code pty} until {@code marker} has been said. A read blocks while the child is silent,
   * so a deadline ends it: past ten seconds the pty is closed and the read fails.
   */
  private static String readUntil(Pty pty, String marker) throws Exception {
    var out = new StringBuilder();
    var buf = new byte[4096];
    var deadline =
        CompletableFuture.runAsync(
            pty::close, CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS));
    try {
      while (!out.toString().contains(marker)) {
        var n = pty.read(buf);
        if (n < 0) {
          throw new AssertionError("marker '" + marker + "' not seen; got: " + out);
        }
        out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
      }
      return out.toString();
    } finally {
      deadline.cancel(false);
    }
  }

  /**
   * Reads {@code pty} to its end of stream and returns what was said. A child's status is taken
   * only after this: on macOS a session leader's exit waits for its terminal's output to be read.
   */
  private static String readToEnd(Pty pty) throws Exception {
    var out = new StringBuilder();
    var buf = new byte[4096];
    var deadline =
        CompletableFuture.runAsync(
            pty::close, CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS));
    try {
      for (var n = pty.read(buf); n >= 0; n = pty.read(buf)) {
        out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
      }
      return out.toString();
    } finally {
      deadline.cancel(false);
    }
  }

  private static int exitOf(Process child) throws InterruptedException {
    assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the child is still running");
    return child.exitValue();
  }

  private Process spawn(Pty pty, String script) throws IOException {
    return pty.spawn(
        PtyChildShimTest.shim(dir.resolve("child.exit"), "sh", "-c", script),
        Map.of("TERM", "xterm-256color"),
        dir);
  }

  private static String ps(long pid) throws Exception {
    var ps = new ProcessBuilder("ps", "-o", "stat=", "-p", Long.toString(pid)).start();
    var out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    exitOf(ps);
    return out;
  }

  @Test
  void childGetsAControllingTtyWithTheRequestedSizeAndConverses() throws Exception {
    try (var pty = Pty.open(132, 43)) {
      var child =
          spawn(pty, "[ -t 0 ] && echo is-a-tty; stty size; read line; echo got:$line; exit 7");

      var head = readUntil(pty, "43 132");
      assertTrue(head.contains("is-a-tty"), "the child must see a controlling terminal: " + head);

      pty.write("world\n".getBytes(StandardCharsets.UTF_8));

      assertTrue(readToEnd(pty).contains("got:world"), "a dead child reads as end of stream");
      assertEquals(7, exitOf(child), "the child exit code rides through");
    }
  }

  @Test
  void resizeReachesTheForegroundProcess() throws Exception {
    Process child;
    try (var pty = Pty.open(80, 24)) {
      child = spawn(pty, "trap 'stty size' WINCH; echo ready; while read l; do :; done");
      readUntil(pty, "ready");

      pty.resize(100, 30);
      pty.write("poke\n".getBytes(StandardCharsets.UTF_8));

      readUntil(pty, "30 100");
    }
    assertTrue(child.waitFor(10, TimeUnit.SECONDS), "closing the master ends the child");
  }

  @Test
  void theCommandRunsInTheForegroundOfTheSessionTheShimLeadsOnThePtysTerminal() throws Exception {
    try (var pty = Pty.open(80, 24)) {
      var child =
          spawn(
              pty,
              "[ -t 0 ] && tty; ps -o stat= -p $$; ps -o stat= -p $PPID;"
                  + " echo \"leaked:${SAIL_PTY_TERMINAL:-none}\"; echo done");

      var lines = readToEnd(pty).lines().map(String::strip).toList();
      assertEquals(pty.slavePath(), lines.get(0), "the command's terminal is the pty's: " + lines);
      assertTrue(lines.get(1).contains("+"), "the command is in the foreground group: " + lines);
      assertFalse(lines.get(1).contains("s"), "the shim, not the command, leads: " + lines);
      assertTrue(
          lines.get(2).contains("s") && lines.get(2).contains("+"),
          "the shim leads the session from the foreground group: " + lines);
      assertEquals("leaked:none", lines.get(3), "the terminal's name stays the shim's");
      assertEquals(0, exitOf(child));
    }
  }

  /**
   * A host that leads its own session, as one under systemd or launchd does: if any of its opens,
   * spawns or reads made a pty its own controlling terminal, {@code /dev/tty} would open.
   */
  static final class SessionLeadingHost {
    public static void main(String[] args) throws Throwable {
      var setsid = Native.downcall("setsid", FunctionDescriptor.of(ValueLayout.JAVA_INT));
      if ((int) setsid.invokeExact() < 0) {
        throw new IllegalStateException("setsid failed");
      }
      var ptys = new ArrayList<Pty>();
      var children = new ArrayList<Process>();
      for (var i = 0; i < 10; i++) {
        var pty = Pty.open(80, 24);
        ptys.add(pty);
        children.add(
            pty.spawn(
                PtyChildShimTest.shim(Path.of(args[0], i + ".exit"), "sh", "-c", "echo up; read a"),
                Map.of("TERM", "dumb"),
                Path.of(args[0])));
        readUntil(pty, "up");
      }
      try (var tty = new FileInputStream("/dev/tty")) {
        System.out.println("a controlling terminal");
      } catch (IOException none) {
        System.out.println("no controlling terminal");
      }
      ptys.forEach(Pty::close);
      for (var child : children) {
        exitOf(child);
      }
    }
  }

  /** What {@code host}'s {@code main}, run in a JVM of its own on this class path, says. */
  private String said(Class<?> host) throws Exception {
    return said(host, dir);
  }

  /** What {@code host}'s {@code main}, run in a JVM of its own with {@code dir}, says. */
  static String said(Class<?> host, Path dir) throws Exception {
    var process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                host.getName(),
                dir.toString())
            .redirectErrorStream(true)
            .start();
    var finished = process.waitFor(60, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly().waitFor();
    }
    var said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(finished, "the host never finished: " + said);
    assertEquals(0, process.exitValue(), said);
    return said;
  }

  @Test
  void aHostLeadingItsOwnSessionNeverTakesAPtyAsItsControllingTerminal() throws Exception {
    assertEquals("no controlling terminal\n", said(SessionLeadingHost.class), "after ten sessions");
  }

  /**
   * A host spawning a stand-in that never claims its terminal, and whose child keeps its standard
   * output open too: says what {@code spawn} refused, whether within its bound, whether the
   * stand-in still runs, and how many descriptors the attempt left open.
   */
  static final class UnclaimedTerminalHost {
    public static void main(String[] args) throws Exception {
      var dir = Path.of(args[0]);
      var pid = dir.resolve("stand-in.pid");
      try (var warm = Pty.open(80, 24)) {
        warm.spawn(List.of("true"), Map.of(), dir).waitFor(10, TimeUnit.SECONDS);
      }
      var before = descriptors();
      try (var pty = Pty.open(80, 24)) {
        var started = System.nanoTime();
        try {
          pty.spawn(
              List.of("sh", "-c", "sleep 100 & echo $$ $! > " + pid + "; wait"), Map.of(), dir);
          System.out.println("spawned");
        } catch (IOException refused) {
          System.out.println(refused.getMessage().replace(pty.slavePath(), "<tty>"));
          var bound = Pty.CLAIM_WAIT.plusSeconds(5).toNanos();
          System.out.println("within the bound: " + (System.nanoTime() - started < bound));
          System.out.println("open beside the master: " + (descriptors() - before - 1));
        }
      }
      var running =
          Arrays.stream(Files.readString(pid).strip().split(" "))
              .map(Long::parseLong)
              .anyMatch(each -> ProcessHandle.of(each).map(ProcessHandle::isAlive).orElse(false));
      System.out.println("stand-in or its child running: " + running);
      System.out.println("descriptors left open: " + (descriptors() - before));
    }

    private static long descriptors() throws IOException {
      try (var fds = Files.list(Path.of("/dev/fd"))) {
        return fds.count();
      }
    }
  }

  @Test
  void aShimThatNeverClaimsItsTerminalIsStoppedAndRefusedWithNothingLeftOpen() throws Exception {
    assertEquals(
        """
        The session shim did not claim <tty> within 10 s, so it was stopped.
        within the bound: true
        open beside the master: 0
        stand-in or its child running: false
        descriptors left open: 0
        """,
        said(UnclaimedTerminalHost.class));
  }

  /** A host whose child speaks before any terminal is its own, as a shim that cannot claim one. */
  static final class SpeakingBeforeATerminalHost {
    public static void main(String[] args) throws Exception {
      try (var pty = Pty.open(80, 24)) {
        pty.spawn(List.of("sh", "-c", "echo before-a-terminal >&2"), Map.of(), Path.of(args[0]))
            .waitFor(10, TimeUnit.SECONDS);
      }
    }
  }

  @Test
  void whatTheShimSaysBeforeItHasATerminalReachesTheHostsStandardError() throws Exception {
    assertEquals("before-a-terminal\n", said(SpeakingBeforeATerminalHost.class));
  }

  /** A host that spawns a second session shim onto a terminal the first one has claimed. */
  static final class ClaimingATakenTerminalHost {
    public static void main(String[] args) throws Exception {
      try (var pty = Pty.open(80, 24)) {
        var first =
            pty.spawn(
                PtyChildShimTest.shim(Path.of(args[0], "first.exit"), "sh", "-c", "read a"),
                Map.of(),
                Path.of(args[0]));
        var second =
            pty.spawn(
                PtyChildShimTest.shim(Path.of(args[0], "second.exit"), "sh", "-c", "echo ran"),
                Map.of(),
                Path.of(args[0]));
        System.err.println(pty.slavePath() + " " + exitOf(second));
        pty.close();
        exitOf(first);
      }
    }
  }

  @Test
  void aTerminalAnotherSessionHasClaimedIsRefusedWithTheKernelsAnswer() throws Exception {
    var said = said(ClaimingATakenTerminalHost.class).lines().toList();

    var terminal = said.getLast().split(" ")[0];
    assertEquals(
        "Exception in thread \"main\" java.io.IOException: TIOCSCTTY failed: "
            + terminal
            + " is not this session's to claim (errno 1).",
        said.getFirst(),
        "EPERM: the terminal is the first session's");
    assertEquals(terminal + " 1", said.getLast(), "the second shim ran nothing and exited 1");
    assertFalse(Files.exists(dir.resolve("second.exit")));
  }

  /**
   * The foreground shell traps the interrupt and exits 3, so its arrival is what is read, whatever
   * the shell was doing when it landed: a shell forking a command runs its handler in the child
   * until the exec, and bash 3.2 (macOS's {@code /bin/sh}) acts on an untrapped {@code SIGINT} only
   * at its own checkpoints, either of which would lose an interrupt aimed at a command it runs.
   */
  @Test
  void anInterruptReachesTheForegroundCommandWhileTheShimAndTheBackgroundJobRideItOut()
      throws Exception {
    try (var pty = Pty.open(80, 24)) {
      var child =
          spawn(
              pty, "sleep 100 & jobs; trap 'exit 3' INT; echo waiting; while :; do sleep 1; done");
      var jobs = readUntil(pty, "waiting");
      assertTrue(jobs.contains("Running"), "jobs lists the background job: " + jobs);

      pty.write(new byte[] {3});
      readToEnd(pty);

      assertEquals(3, exitOf(child), "the foreground shell took the interrupt");
      assertEquals("3", Files.readString(dir.resolve("child.exit")), "the shim rode it out");
    }
  }

  @Test
  void closingTheMasterHangsTheCommandUpThroughTheShimAndLeavesNoZombie() throws Exception {
    var marker = dir.resolve("hup");
    var pty = Pty.open(80, 24);
    var child =
        spawn(
            pty,
            "trap 'echo hup > "
                + marker
                + "; exit 0' HUP; echo pid:$$:; while :; do sleep 1; done");
    var said = readUntil(pty, ":");
    var command = Long.parseLong(said.replaceAll("(?s).*pid:(\\d+):.*", "$1"));

    pty.close();

    assertTrue(child.waitFor(10, TimeUnit.SECONDS), "the hangup ends the session's shim");
    assertEquals("hup\n", Files.readString(marker), "the hangup reached the command");
    assertEquals("0", Files.readString(dir.resolve("child.exit")), "and its status was recorded");
    assertEquals("", ps(child.pid()), "the shim is gone, not a zombie");
    assertEquals("", ps(command), "the command is gone, not a zombie");
  }

  @Test
  void anAdoptedMasterIsTheSameTerminalResizeReachesTheChildAndCloseHangsItUp() throws Exception {
    var original = Pty.open(80, 24);
    var child = spawn(original, "trap 'stty size' WINCH; echo ready; while read l; do :; done");
    readUntil(original, "ready");
    original.release();
    assertEquals(-1, original.read(new byte[16]), "a released master reads as end of stream");
    assertTrue(child.isAlive(), "releasing the master does not hang the child up");

    try (var adopted = Pty.adopt(original.fd())) {
      assertEquals(original.slavePath(), adopted.slavePath(), "ptsname resolves on the adoptee");
      adopted.resize(132, 43);
      adopted.write("poke\n".getBytes(StandardCharsets.UTF_8));
      readUntil(adopted, "43 132");
    }
    assertTrue(child.waitFor(10, TimeUnit.SECONDS), "close hangs the child up");
  }

  @Test
  void adoptingSomethingThatIsNotAPtyMasterNamesTheDescriptorAndLeavesItOpen() throws Throwable {
    var dup =
        Native.downcall("dup", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    var fd = (int) dup.invokeExact(2);

    var refused = assertThrows(IOException.class, () -> Pty.adopt(fd));

    assertEquals("File descriptor " + fd + " is not a pty master.", refused.getMessage());
    assertDoesNotThrow(() -> Pty.closeFd(fd), "the refused descriptor is still open");
  }

  @Test
  void writeAfterCloseFailsLoudly() throws Exception {
    var pty = Pty.open(80, 24);
    pty.close();

    assertThrows(IOException.class, () -> pty.write("x".getBytes(StandardCharsets.UTF_8)));
  }
}
