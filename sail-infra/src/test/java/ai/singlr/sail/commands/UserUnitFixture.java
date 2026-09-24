/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.singlr.sail.engine.PtyHostUnit;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The real pty host under the runner's real systemd user manager: the production unit text ({@link
 * PtyHostUnit#renderUnit()}) installed under a throwaway unit name, {@code ExecStart} pointing at a
 * wrapper that runs this class path's {@code ai.singlr.sail.Main} with a private {@code user.home}.
 * A developer's own {@code sail-pty-host.service} is never touched. Every wait is a bounded poll.
 */
final class UserUnitFixture implements AutoCloseable {

  static final int MIN_SYSTEMD = 254;
  static final long WAIT_NANOS = 60_000_000_000L;

  final Path tmp;
  final Path home;
  final String unit;
  private final Path unitFile;
  private final ShellExec shell = new ShellExecutor(false);

  private UserUnitFixture(Path tmp, String unit, Path unitFile) {
    this.tmp = tmp;
    this.home = tmp.resolve("home");
    this.unit = unit;
    this.unitFile = unitFile;
  }

  /**
   * A running systemd user manager of at least {@link #MIN_SYSTEMD}, or the reason there is none.
   * Under {@code -Pintegration} the lane guarantees one, so a missing manager is a failure with the
   * command output; elsewhere the test is skipped with the same reason.
   */
  static void ensureSystemdOrSkip() {
    var reason = systemdUnavailableReason();
    if (reason == null) {
      return;
    }
    if (Boolean.getBoolean("sail.it.requireSystemd")) {
      throw new AssertionError(
          "a systemd user manager is required in this lane (-Dsail.it.requireSystemd=true) but"
              + " is not usable: "
              + reason);
    }
    assumeTrue(
        false, "systemd user manager not available; integration test skipped (" + reason + ")");
  }

  private static String systemdUnavailableReason() {
    var shell = new ShellExecutor(false);
    try {
      var version = shell.exec(List.of("systemctl", "--version"));
      var head = version.stdout().lines().findFirst().orElse("");
      var number = head.replaceAll("^systemd (\\d+).*$", "$1");
      if (!version.ok() || !number.matches("\\d+") || Integer.parseInt(number) < MIN_SYSTEMD) {
        return "`systemctl --version` answered: " + head + " " + version.stderr().strip();
      }
      var state = shell.exec(List.of("systemctl", "--user", "is-system-running"));
      var answer = state.stdout().strip();
      if (!answer.equals("running") && !answer.equals("degraded")) {
        return "`systemctl --user is-system-running` answered: "
            + answer
            + " "
            + state.stderr().strip();
      }
      return null;
    } catch (Exception e) {
      return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  /** Installs and starts the unit; returns once the host's socket accepts a Hello. */
  static UserUnitFixture start() throws Exception {
    var tmp = Files.createTempDirectory("sail-fdstore-it");
    var unit = "sail-it-pty-host-" + ProcessHandle.current().pid() + ".service";
    var unitDir =
        Files.createDirectories(
            Path.of(System.getProperty("user.home"), ".config", "systemd", "user"));
    var fixture = new UserUnitFixture(tmp, unit, unitDir.resolve(unit));
    fixture.prepareHome();
    var wrapper = fixture.writeWrapper();
    Files.writeString(
        fixture.unitFile,
        new PtyHostUnit(fixture.shell, SystemdServiceInstaller.Mode.USER, fixture.home, wrapper)
            .renderUnit());
    fixture.systemctl("daemon-reload");
    fixture.systemctl("start", unit);
    fixture.connect().close();
    return fixture;
  }

  /** A box identity (the sync handle a blank token resolves to) and a migrated control plane. */
  private void prepareHome() throws Exception {
    var sail = Files.createDirectories(home.resolve(".sail"));
    Files.writeString(
        sail.resolve("host.yaml"),
        """
        storage_backend: dir
        sync:
          handle: uday
        """);
    try (var db = Sqlite.open(sail.resolve("sail.db"))) {
      MigrateCommand.applyMigrations(
          db, "sail.db", DataMigration.Prompter.NON_INTERACTIVE, false, true);
    }
  }

  private Path writeWrapper() throws IOException {
    var wrapper = tmp.resolve("sail");
    var java = Path.of(System.getProperty("java.home"), "bin", "java");
    Files.writeString(
        wrapper,
        """
        #!/bin/sh
        exec "%s" --enable-native-access=ALL-UNNAMED -Duser.home="%s" -cp "%s" ai.singlr.sail.Main "$@"
        """
            .formatted(java, home, System.getProperty("java.class.path")));
    Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
    return wrapper;
  }

  Path socket() {
    return home.resolve(".sail").resolve("pty.sock");
  }

  /** Connects as the box owner, retrying until the host answers or the wait runs out. */
  SessionClient connect() throws Exception {
    return connect(socket());
  }

  /** Connects to the host on {@code socket} as the box owner, as {@link #connect()} does. */
  static SessionClient connect(Path socket) {
    var deadline = System.nanoTime() + WAIT_NANOS;
    IOException last = null;
    while (System.nanoTime() < deadline) {
      try {
        return SessionClient.connect(socket, "");
      } catch (IOException notYet) {
        last = notYet;
        Thread.onSpinWait();
      }
    }
    throw new AssertionError("the pty host never answered on " + socket + ": " + last);
  }

  void systemctl(String... args) throws Exception {
    var command = new ArrayList<>(List.of("systemctl", "--user"));
    command.addAll(List.of(args));
    var result = shell.exec(command);
    assertTrue(result.ok(), String.join(" ", command) + " failed: " + result.stderr());
  }

  String show(String property) throws Exception {
    var result =
        shell.exec(List.of("systemctl", "--user", "show", "-p", property, "--value", unit));
    assertTrue(result.ok(), "systemctl show " + property + " failed: " + result.stderr());
    return result.stdout().strip();
  }

  static void await(BooleanSupplier condition, String what) {
    var deadline = System.nanoTime() + WAIT_NANOS;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.onSpinWait();
    }
  }

  /** Every process whose command line mentions this fixture's directory — sessions included. */
  List<String> processesUnderTmp() {
    return ProcessHandle.allProcesses()
        .map(handle -> handle.info().commandLine().orElse(""))
        .filter(line -> line.contains(tmp.toString()))
        .toList();
  }

  @Override
  public void close() throws Exception {
    try {
      shell.exec(List.of("systemctl", "--user", "stop", unit));
      await(() -> processesUnderTmp().isEmpty(), "every process of the fixture to be gone");
    } finally {
      Files.deleteIfExists(unitFile);
      shell.exec(List.of("systemctl", "--user", "daemon-reload"));
      try (var paths = Files.walk(tmp)) {
        paths.sorted(Comparator.reverseOrder()).forEach(UserUnitFixture::deleteQuietly);
      }
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      var unused = ignored;
    }
  }
}
