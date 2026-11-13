/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Main;
import ai.singlr.sail.pty.Account;
import ai.singlr.sail.pty.Locales;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The local terminal host as the process a launchd agent runs: a JVM of its own, with an
 * environment as bare as a service's ({@code SHELL} naming no shell, no locale), {@code
 * SAIL_DATA_DIR} an empty directory, and {@code ssh}, {@code incus} and {@code systemctl} on its
 * {@code PATH} recording any call. The host runs from {@link LocalHostProcess}, the command's own
 * process shell in a home of the test's; the client-mode routing runs the real {@link Main} on a
 * machine whose {@code ~/.sail/config.yaml} names a server. The home is made under {@code /tmp},
 * since a macOS temporary directory leaves little of a socket path's room.
 */
class LocalPtyHostProcessTest {

  private Path root;
  private Path home;
  private Path data;
  private Path calls;
  private final Instant began = Instant.now();
  private final List<Process> started = new ArrayList<>();

  @BeforeEach
  void aMachine() throws IOException {
    root = Files.createTempDirectory(Path.of("/tmp"), "sail-lh");
    home = LocalPtyHostTest.at(root.resolve("home")).home();
    data = Files.createDirectories(root.resolve("data"));
    calls = root.resolve("calls");
    var bin = Files.createDirectories(root.resolve("bin"));
    for (var tool : List.of("ssh", "incus", "systemctl")) {
      var stand = bin.resolve(tool);
      Files.writeString(stand, "#!/bin/sh\necho \"$0 $*\" >> '" + calls + "'\nexit 1\n");
      Files.setPosixFilePermissions(stand, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @AfterEach
  void leaveNothing() throws IOException {
    for (var process : started) {
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
    }
    LocalPtyHostTest.startedSince(began).forEach(ProcessHandle::destroyForcibly);
    try (var left = Files.walk(root)) {
      for (var path : left.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private Path socket() {
    return home.resolve(".sail").resolve("local").resolve("pty.sock");
  }

  /** A process this test started; its standard error, line by line. */
  private record Run(Process process, BlockingQueue<String> said) {

    String awaitLine(String prefix) throws InterruptedException {
      while (true) {
        var line = said.poll(LocalPtyHostTest.STEP.toMillis(), TimeUnit.MILLISECONDS);
        if (line == null) {
          throw new AssertionError("no line starting '" + prefix + "' within the step");
        }
        if (line.startsWith(prefix)) {
          return line;
        }
      }
    }

    /** Its exit status, once it exits within the step, and every line it said. */
    List<String> ended() throws InterruptedException {
      assertTrue(
          process.waitFor(LocalPtyHostTest.STEP.toMillis(), TimeUnit.MILLISECONDS),
          "it exited within the step");
      var lines = new ArrayList<String>();
      String line;
      while ((line = said.poll(LocalPtyHostTest.STEP.toMillis(), TimeUnit.MILLISECONDS)) != null
          && !line.equals(EOF)) {
        lines.add(line);
      }
      return lines;
    }
  }

  private static final String EOF = "\u0000";

  /** {@code main} of {@code entry} with {@code args}, in this machine's bare environment. */
  private Run run(Class<?> entry, String... args) throws IOException {
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Duser.home=" + home,
                "-cp",
                System.getProperty("java.class.path"),
                entry.getName()));
    command.addAll(List.of(args));
    var builder = new ProcessBuilder(command);
    var env = builder.environment();
    for (var locale : List.of("LANG", "LC_ALL", "LC_CTYPE")) {
      env.remove(locale);
    }
    env.put("HOME", home.toString());
    env.put("SHELL", "/nonexistent/shell");
    env.put("SAIL_DATA_DIR", data.toString());
    env.put("PATH", root.resolve("bin") + ":" + System.getenv("PATH"));
    builder.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
    builder.redirectErrorStream(true);
    var process = builder.start();
    started.add(process);
    var said = new LinkedBlockingQueue<String>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (var lines =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                lines.lines().forEach(said::add);
              } catch (IOException gone) {
                var unused = gone;
              } finally {
                said.add(EOF);
              }
            });
    return new Run(process, said);
  }

  private Run host() throws IOException {
    return run(LocalHostProcess.class, home.toString());
  }

  /** Scenario 12, and scenario 1 under a service's bare environment. */
  @Test
  void theLocalHostRunsASessionTouchingNoStoreIncusOrNetwork() throws Exception {
    var account = LocalPtyHostTest.at(home);
    var work = Files.createDirectories(root.resolve("work"));
    var host = host();
    assertEquals(
        "pty-host: serving " + account.name() + " at " + socket(),
        host.awaitLine("pty-host: serving"));

    try (var client = LocalPtyHostTest.Client.connect(socket())) {
      client.open("here", work, List.of());
      client.attach("here");
      client.type(LocalPtyHostTest.Client.PROBE);
      assertEquals(
          "@42@" + account.shell() + " -l@" + work.toRealPath() + "@xterm-256color@UTF-8@",
          client.probed());
    }
    try (var client = LocalPtyHostTest.Client.connect(socket())) {
      client.open("environment", work, LocalPtyHostTest.Client.ENVIRONMENT);
      client.attach("environment");
      assertEquals(
          "@42@"
              + account.home()
              + "@"
              + account.name()
              + "@"
              + account.name()
              + "@"
              + account.shell()
              + "@"
              + (Locales.installed("C.UTF-8") ? "C.UTF-8" : "en_US.UTF-8")
              + "@",
          client.probed());

      assertEquals(List.of(), internetSockets(host.process().pid()), "no port, no connection");
      assertFalse(Files.exists(calls), "no ssh, incus or systemctl was run");
      try (var dataDir = Files.list(data)) {
        assertEquals(List.of(), dataDir.toList(), "the data directory stays empty");
      }
      try (var sail = Files.list(home.resolve(".sail"))) {
        assertEquals(
            List.of(home.resolve(".sail/local")),
            sail.toList(),
            "nothing but the local host's own directory");
      }
      try (var anywhere = Files.walk(root)) {
        assertEquals(
            List.of(),
            anywhere.filter(path -> path.getFileName().toString().contains(".db")).toList(),
            "no database file anywhere");
      }
    }

    var processes = host.process().descendants().toList();
    host.process().destroy();
    assertTrue(host.process().waitFor(LocalPtyHostTest.STEP.toMillis(), TimeUnit.MILLISECONDS));
    assertEquals(List.of(), processes.stream().filter(ProcessHandle::isAlive).toList());
    assertFalse(Files.exists(socket()), "a stopped host takes its socket with it");
  }

  /** Scenario 8, across two processes, as two launchd starts or a start by hand would be. */
  @Test
  void aSecondHostForTheSamePersonExitsSayingOneIsRunningAndLeavesTheFirstServing()
      throws Exception {
    var first = host();
    first.awaitLine("pty-host: serving");
    String boot;
    try (var client = LocalPtyHostTest.Client.connect(socket())) {
      boot = client.boot();
    }

    var secondHost = host();
    var second = secondHost.ended();

    assertEquals(1, secondHost.process().exitValue(), "the second exits 1");

    assertEquals(
        List.of(
            "A terminal host already serves "
                + Account.current().name()
                + " on this machine, at "
                + socket()
                + ": connect to it there, or stop it before starting another."),
        second);
    try (var client = LocalPtyHostTest.Client.connect(socket())) {
      assertEquals(boot, client.boot());
    }
    assertTrue(first.process().isAlive());
  }

  /**
   * Scenario 10 through the real {@code sail}: on a machine whose client config names a server, the
   * pty verbs run here and nothing reaches {@code ssh}. The shim's refusal outside a host is said
   * as one sentence, not a stack trace, and the self-test, its local probe included, passes.
   */
  @Test
  void onAMachineWithAClientConfigThePtyVerbsRunHereAndSendNothingToTheServer() throws Exception {
    Files.writeString(
        Files.createDirectories(home.resolve(".sail")).resolve("config.yaml"),
        "host: server.invalid\n");

    var shim =
        run(Main.class, "_pty-child", "--exit", root.resolve("x.exit").toString(), "--", "true");
    assertEquals(
        List.of(
            "No terminal is named in SAIL_PTY_TERMINAL: the shim runs only as the pty host spawns"
                + " it."),
        shim.ended());
    assertEquals(1, shim.process().exitValue());

    var selftest = run(Main.class, "_pty-selftest");
    var said = selftest.ended();
    assertEquals(0, selftest.process().exitValue(), said.toString());
    assertTrue(said.contains("local-selftest: ok"), said.toString());

    assertFalse(Files.exists(calls), "nothing was sent to the server");
    try (var dataDir = Files.list(data)) {
      assertEquals(List.of(), dataDir.toList(), "the data directory stays empty");
    }
  }

  /**
   * The internet sockets {@code pid} holds: on Linux its descriptors matched against its network
   * namespace's tables, on macOS as {@code lsof} lists them.
   */
  private static List<String> internetSockets(long pid) throws Exception {
    var proc = Path.of("/proc", Long.toString(pid));
    if (!Files.isDirectory(proc)) {
      var lsof = new ProcessBuilder("lsof", "-nP", "-a", "-p", Long.toString(pid), "-i").start();
      assertTrue(lsof.waitFor(LocalPtyHostTest.STEP.toMillis(), TimeUnit.MILLISECONDS));
      return new String(lsof.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .toList();
    }
    var inodes = new ArrayList<String>();
    var socket = Pattern.compile("socket:\\[(\\d+)]");
    try (var fds = Files.list(proc.resolve("fd"))) {
      for (var fd : fds.toList()) {
        try {
          var matcher = socket.matcher(Files.readSymbolicLink(fd).toString());
          if (matcher.matches()) {
            inodes.add(matcher.group(1));
          }
        } catch (IOException closedMeanwhile) {
          var unused = closedMeanwhile;
        }
      }
    }
    var held = new ArrayList<String>();
    for (var table : List.of("tcp", "tcp6", "udp", "udp6")) {
      for (var row :
          Files.readAllLines(proc.resolve("net").resolve(table)).stream().skip(1).toList()) {
        var columns = row.trim().split("\\s+");
        if (inodes.contains(columns[9])) {
          held.add(table + " " + row.trim());
        }
      }
    }
    return held;
  }
}
