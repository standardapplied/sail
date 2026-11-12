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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every child here is spawned as the host spawns one: under the real shim, run from this class
 * path.
 */
class PtyTest {

  @TempDir Path dir;

  private static String readUntil(Pty pty, String marker) throws Exception {
    var out = new StringBuilder();
    var buf = new byte[4096];
    var deadline = System.nanoTime() + 10_000_000_000L;
    while (!out.toString().contains(marker)) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("marker '" + marker + "' not seen; got: " + out);
      }
      var n = pty.read(buf);
      if (n < 0) {
        throw new AssertionError("pty closed before marker '" + marker + "'; got: " + out);
      }
      out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
    }
    return out.toString();
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
    ps.waitFor();
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
      readUntil(pty, "got:world");

      assertEquals(7, child.waitFor(), "the child exit code rides through");
      var buf = new byte[256];
      int n;
      do {
        n = pty.read(buf);
      } while (n > 0);
      assertEquals(-1, n, "a dead child reads as end of stream, never an exception");
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

      var lines = readUntil(pty, "done").lines().map(String::strip).toList();
      assertEquals(pty.slavePath(), lines.get(0), "the command's terminal is the pty's: " + lines);
      assertTrue(lines.get(1).contains("+"), "the command is in the foreground group: " + lines);
      assertFalse(lines.get(1).contains("s"), "the shim, not the command, leads: " + lines);
      assertTrue(
          lines.get(2).contains("s") && lines.get(2).contains("+"),
          "the shim leads the session from the foreground group: " + lines);
      assertEquals("leaked:none", lines.get(3), "the terminal's name stays the shim's");
      assertEquals(0, child.waitFor());
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
        child.waitFor();
      }
    }
  }

  /** What {@code host}'s {@code main}, run in a JVM of its own on this class path, says. */
  private String said(Class<?> host) throws Exception {
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
    var said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), said);
    return said;
  }

  @Test
  void aHostLeadingItsOwnSessionNeverTakesAPtyAsItsControllingTerminal() throws Exception {
    assertEquals("no controlling terminal\n", said(SessionLeadingHost.class), "after ten sessions");
  }

  /** A host whose child speaks before any terminal is its own, as a shim that cannot claim one. */
  static final class SpeakingBeforeATerminalHost {
    public static void main(String[] args) throws Exception {
      try (var pty = Pty.open(80, 24)) {
        pty.spawn(List.of("sh", "-c", "echo before-a-terminal >&2"), Map.of(), Path.of(args[0]))
            .waitFor();
      }
    }
  }

  @Test
  void whatTheShimSaysBeforeItHasATerminalReachesTheHostsStandardError() throws Exception {
    assertEquals("before-a-terminal\n", said(SpeakingBeforeATerminalHost.class));
  }

  @Test
  void anInterruptEndsTheForegroundCommandWhileTheShimAndTheBackgroundJobRideItOut()
      throws Exception {
    try (var pty = Pty.open(80, 24)) {
      var child = spawn(pty, "sleep 100 & jobs; echo listed; read a; echo not-reached");
      var jobs = readUntil(pty, "listed");
      assertTrue(jobs.contains("Running"), "jobs lists the background job: " + jobs);

      pty.write(new byte[] {3});

      assertEquals(130, child.waitFor(), "the foreground read died of SIGINT: 128 + 2");
      assertEquals("130", Files.readString(dir.resolve("child.exit")), "the shim rode it out");
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
