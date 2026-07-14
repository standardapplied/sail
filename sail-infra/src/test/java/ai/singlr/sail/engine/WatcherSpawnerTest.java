/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatcherSpawnerTest {

  private static final String RUN_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String AGENT_UNIT = "sail-agent-" + RUN_ID;
  private static final String WATCH_UNIT = "sail-watch-" + RUN_ID;

  @TempDir Path tempDir;

  private Path yaml() {
    return tempDir.resolve("sail.yaml");
  }

  private Path log() {
    return tempDir.resolve("acme").resolve("watch.log");
  }

  @Test
  void watchCommandNamesTheBinaryProjectAndDescriptor() {
    var command = WatcherSpawner.watchCommand("acme", yaml());

    assertEquals(
        List.of(
            SailPaths.binaryPath().toString(),
            "agent",
            "watch",
            "acme",
            "-f",
            yaml().toAbsolutePath().toString()),
        command);
  }

  @Test
  void unitNameIsDeterministicPerProject() {
    assertEquals("sail-watch-acme", WatcherSpawner.unitName("acme"));
  }

  @Test
  void runScopedUnitNameRequiresACanonicalRunId() {
    assertEquals(WATCH_UNIT, WatcherSpawner.unitNameForRun(RUN_ID));
    assertThrows(IllegalArgumentException.class, () -> WatcherSpawner.unitNameForRun("../escape"));
  }

  @Test
  void watchCommandForRunAddressesTheRunAndItsRecordedUnit() {
    var command = WatcherSpawner.watchCommandForRun("acme", yaml(), RUN_ID, AGENT_UNIT);

    assertEquals(
        List.of(
            SailPaths.binaryPath().toString(),
            "agent",
            "watch",
            "acme",
            "--run",
            RUN_ID,
            "--unit",
            AGENT_UNIT,
            "-f",
            yaml().toAbsolutePath().toString()),
        command);
  }

  @Test
  void spawnForRunLaunchesTheRunScopedUnitWithItsOwnWatchLog() throws Exception {
    var shell = new FakeShell().on("systemd-run --user", ok());
    var spawner = new WatcherSpawner(shell, WatcherSpawnerTest::failingFallback);

    var spawned = spawner.spawnForRun("acme", yaml(), RUN_ID, AGENT_UNIT);

    assertEquals(new WatcherSpawner.Unit(WATCH_UNIT, "user", false), spawned);
    var invocation = shell.invocationsMatching("systemd-run").getFirst();
    assertTrue(invocation.contains("--unit " + WATCH_UNIT));
    assertTrue(invocation.contains("--run " + RUN_ID));
    assertTrue(invocation.contains("--unit " + AGENT_UNIT));
    assertTrue(
        invocation.contains(
            WatcherSpawner.watchLogForRun("acme", RUN_ID).toAbsolutePath().toString()));
    assertTrue(shell.invocationsMatching("is-active").isEmpty(), "per-run units never collide");
    assertTrue(shell.invocationsMatching("systemctl --user stop").isEmpty());
  }

  @Test
  void twoConcurrentRunsGetDistinctWatcherUnitsAndLogs() throws Exception {
    var other = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    var shell = new FakeShell().on("systemd-run --user", ok());
    var spawner = new WatcherSpawner(shell, WatcherSpawnerTest::failingFallback);

    var first = spawner.spawnForRun("acme", yaml(), RUN_ID, AGENT_UNIT);
    var second = spawner.spawnForRun("acme", yaml(), other, "sail-agent-" + other);

    assertEquals(new WatcherSpawner.Unit(WATCH_UNIT, "user", false), first);
    assertEquals(new WatcherSpawner.Unit("sail-watch-" + other, "user", false), second);
    assertFalse(
        WatcherSpawner.watchLogForRun("acme", RUN_ID)
            .equals(WatcherSpawner.watchLogForRun("acme", other)));
  }

  @Test
  void spawnLaunchesAUserScopeUnitFirst() throws Exception {
    var shell = new FakeShell().on("systemd-run --user", ok());
    var spawner = new WatcherSpawner(shell, WatcherSpawnerTest::failingFallback);

    var spawned = spawner.spawn("acme", yaml(), log());

    var unit = assertInstanceOf(WatcherSpawner.Unit.class, spawned);
    assertEquals(new WatcherSpawner.Unit("sail-watch-acme", "user", false), unit);
    assertEquals(
        String.join(
                " ",
                "systemd-run --user --collect --quiet --unit sail-watch-acme",
                "--property Type=exec",
                "--property StandardOutput=append:" + log().toAbsolutePath(),
                "--property StandardError=append:" + log().toAbsolutePath(),
                forwardedEnvArgs(),
                SailPaths.binaryPath().toString(),
                "agent watch acme -f " + yaml().toAbsolutePath())
            .replace("  ", " "),
        shell.invocationsMatching("systemd-run").getFirst());
  }

  private static String forwardedEnvArgs() {
    var parts = new ArrayList<String>();
    for (var name : List.of("SAIL_TOKEN", "SAIL_TOKEN_FILE", "SAIL_SERVER", "SAIL_DATA_DIR")) {
      var value = System.getenv(name);
      if (value != null && !value.isBlank()) {
        parts.add("--setenv");
        parts.add(name + "=" + value);
      }
    }
    return String.join(" ", parts);
  }

  @Test
  void spawnFallsToSystemScopeWhenTheUserManagerRefuses() throws Exception {
    var shell = new FakeShell().on("systemd-run --user", fail()).on("systemd-run --collect", ok());
    var spawner = new WatcherSpawner(shell, WatcherSpawnerTest::failingFallback);

    var spawned = spawner.spawn("acme", yaml(), log());

    assertEquals(new WatcherSpawner.Unit("sail-watch-acme", "system", false), spawned);
  }

  @Test
  void spawnFallsBackToADetachedProcessWhenNoScopeAccepts() throws Exception {
    var launched = new LinkedHashMap<String, Object>();
    var spawner =
        new WatcherSpawner(
            new FakeShell(),
            (command, logPath) -> {
              launched.put("command", command);
              launched.put("log", logPath);
              return 4242L;
            });

    var spawned = spawner.spawn("acme", yaml(), log());

    assertEquals(new WatcherSpawner.Fallback(4242L), spawned);
    var command = new ArrayList<>(List.of("nohup"));
    command.addAll(WatcherSpawner.watchCommand("acme", yaml()));
    assertEquals(command, launched.get("command"));
    assertEquals(log(), launched.get("log"));
    assertTrue(Files.isDirectory(log().getParent()));
  }

  @Test
  void spawnWithoutAFallbackFailsLoudWhenNoScopeAccepts() {
    var spawner = new WatcherSpawner(new FakeShell(), null);

    var error = assertThrows(IOException.class, () -> spawner.spawn("acme", yaml(), log()));

    assertTrue(error.getMessage().contains("sail-watch-acme"));
    assertTrue(error.getMessage().contains("no fallback"));
  }

  @Test
  void spawnStopsAStaleUnitAndLaunchesFreshInsteadOfAdoptingItsOldDeadline() throws Exception {
    var shell =
        new FakeShell()
            .onSequence("systemctl --user --quiet is-active", ok(), fail())
            .on("systemctl --user stop sail-watch-acme", ok())
            .on("systemd-run --user", ok());
    var spawner = new WatcherSpawner(shell, WatcherSpawnerTest::failingFallback);

    var spawned = spawner.spawn("acme", yaml(), log());

    assertEquals(new WatcherSpawner.Unit("sail-watch-acme", "user", false), spawned);
    assertEquals(1, shell.invocationsMatching("stop sail-watch-acme").size());
    assertEquals(1, shell.invocationsMatching("systemd-run").size());
  }

  @Test
  void spawnUnitAdoptsAnAlreadyActiveUnitInsteadOfStackingASecondWatcher() throws Exception {
    var shell = new FakeShell().on("--quiet is-active " + WATCH_UNIT, ok());
    var spawner = new WatcherSpawner(shell, WatcherSpawnerTest::failingFallback);

    var spawned = spawner.spawnUnitForRun("acme", yaml(), RUN_ID, AGENT_UNIT);

    assertEquals(new WatcherSpawner.Unit(WATCH_UNIT, "user", true), spawned.orElseThrow());
    assertTrue(shell.invocationsMatching("systemd-run").isEmpty());
  }

  @Test
  void anInterruptMidLadderAbortsInsteadOfCascadingToTheFallback() {
    var shell = new FakeShell().interruptOn("systemd-run");
    var spawner = new WatcherSpawner(shell, (command, logPath) -> 4242L);

    var error = assertThrows(IOException.class, () -> spawner.spawn("acme", yaml(), log()));

    assertTrue(error.getMessage().contains("Interrupted"));
    assertTrue(Thread.interrupted(), "interrupt flag must be restored");
  }

  @Test
  void watcherProcessRunningForRunProbesByRunIdPattern() {
    var shell = new FakeShell().on("pgrep -f -- --run " + RUN_ID, ok());

    assertTrue(new WatcherSpawner(shell, null).watcherProcessRunningForRun(RUN_ID));
    assertFalse(new WatcherSpawner(new FakeShell(), null).watcherProcessRunningForRun(RUN_ID));
  }

  @Test
  void spawnAdoptsTheUnitALostRaceJustLaunched() throws Exception {
    var shell =
        new FakeShell()
            .onSequence("systemctl --user --quiet is-active", fail(), ok())
            .on("systemctl --quiet is-active", fail())
            .on("systemd-run", fail());
    var spawner = new WatcherSpawner(shell, null);

    var unit = spawner.spawnUnitForRun("acme", yaml(), RUN_ID, AGENT_UNIT);

    assertEquals(new WatcherSpawner.Unit(WATCH_UNIT, "user", true), unit.orElseThrow());
  }

  @Test
  void spawnUnitIsEmptyWhenNoSystemdScopeExists() throws Exception {
    var spawner = new WatcherSpawner(new FakeShell(), null);

    assertTrue(spawner.spawnUnitForRun("acme", yaml(), RUN_ID, AGENT_UNIT).isEmpty());
  }

  @Test
  void aThrowingShellCountsAsAnUnavailableScope() throws Exception {
    var shell = new FakeShell().throwOn("systemd-run", new IOException("no bus"));
    var spawner = new WatcherSpawner(shell, (command, logPath) -> 7L);

    assertEquals(new WatcherSpawner.Fallback(7L), spawner.spawn("acme", yaml(), log()));
  }

  @Test
  void spawnProcessStartsTheCommandAndReturnsItsPid() throws Exception {
    var logPath = tempDir.resolve("watch.log");

    var pid = WatcherSpawner.spawnProcess(List.of("sh", "-c", "true"), logPath);

    assertTrue(pid > 0);
    assertTrue(Files.exists(logPath));
  }

  private static ShellExec.Result ok() {
    return new ShellExec.Result(0, "", "");
  }

  private static ShellExec.Result fail() {
    return new ShellExec.Result(1, "", "refused");
  }

  private static long failingFallback(List<String> command, Path logPath) throws IOException {
    throw new IOException("fallback must not be used");
  }

  private static final class FakeShell implements ShellExec {
    private final Map<String, ShellExec.Result> scripts = new LinkedHashMap<>();
    private final Map<String, Deque<ShellExec.Result>> sequences = new LinkedHashMap<>();
    private final Map<String, IOException> failures = new LinkedHashMap<>();
    private final Set<String> interrupts = new LinkedHashSet<>();
    private final List<String> invocations = new ArrayList<>();

    FakeShell on(String pattern, ShellExec.Result result) {
      scripts.put(pattern, result);
      return this;
    }

    FakeShell onSequence(String pattern, ShellExec.Result... results) {
      sequences.put(pattern, new ArrayDeque<>(List.of(results)));
      return this;
    }

    FakeShell throwOn(String pattern, IOException failure) {
      failures.put(pattern, failure);
      return this;
    }

    FakeShell interruptOn(String pattern) {
      interrupts.add(pattern);
      return this;
    }

    List<String> invocationsMatching(String pattern) {
      return invocations.stream().filter(line -> line.contains(pattern)).toList();
    }

    @Override
    public Result exec(List<String> command) throws IOException, InterruptedException {
      var joined = String.join(" ", command);
      invocations.add(joined);
      for (var pattern : interrupts) {
        if (joined.contains(pattern)) {
          throw new InterruptedException("interrupted");
        }
      }
      for (var entry : failures.entrySet()) {
        if (joined.contains(entry.getKey())) {
          throw entry.getValue();
        }
      }
      for (var entry : sequences.entrySet()) {
        if (joined.contains(entry.getKey())) {
          var queue = entry.getValue();
          return queue.size() > 1 ? queue.poll() : queue.peek();
        }
      }
      for (var entry : scripts.entrySet()) {
        if (joined.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      return new Result(1, "", "no script for " + joined);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout)
        throws IOException, InterruptedException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
