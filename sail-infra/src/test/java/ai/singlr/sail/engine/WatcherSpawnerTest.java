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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WatcherSpawnerTest {

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
            "--property StandardOutput=append:" + log().toAbsolutePath(),
            "--property StandardError=append:" + log().toAbsolutePath(),
            SailPaths.binaryPath().toString(),
            "agent watch acme -f " + yaml().toAbsolutePath()),
        shell.invocationsMatching("systemd-run").getFirst());
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
  void spawnAdoptsAnAlreadyActiveUnitInsteadOfStackingASecondWatcher() throws Exception {
    var shell = new FakeShell().on("--quiet is-active sail-watch-acme", ok());
    var spawner = new WatcherSpawner(shell, WatcherSpawnerTest::failingFallback);

    var spawned = spawner.spawn("acme", yaml(), log());

    assertEquals(new WatcherSpawner.Unit("sail-watch-acme", "user", true), spawned);
    assertTrue(shell.invocationsMatching("systemd-run").isEmpty());
  }

  @Test
  void spawnAdoptsTheUnitALostRaceJustLaunched() {
    var shell =
        new FakeShell()
            .onSequence("systemctl --user --quiet is-active", fail(), ok())
            .on("systemctl --quiet is-active", fail())
            .on("systemd-run", fail());
    var spawner = new WatcherSpawner(shell, null);

    var unit = spawner.spawnUnit("acme", yaml(), log());

    assertEquals(new WatcherSpawner.Unit("sail-watch-acme", "user", true), unit.orElseThrow());
  }

  @Test
  void spawnUnitIsEmptyWhenNoSystemdScopeExists() {
    var spawner = new WatcherSpawner(new FakeShell(), null);

    assertTrue(spawner.spawnUnit("acme", yaml(), log()).isEmpty());
  }

  @Test
  void unitActiveProbesUserThenSystemScope() {
    assertTrue(
        new WatcherSpawner(new FakeShell().on("systemctl --user --quiet is-active", ok()), null)
            .unitActive("acme"));
    assertTrue(
        new WatcherSpawner(new FakeShell().on("systemctl --quiet is-active", ok()), null)
            .unitActive("acme"));
    assertFalse(new WatcherSpawner(new FakeShell(), null).unitActive("acme"));
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

    List<String> invocationsMatching(String pattern) {
      return invocations.stream().filter(line -> line.contains(pattern)).toList();
    }

    @Override
    public Result exec(List<String> command) throws IOException {
      var joined = String.join(" ", command);
      invocations.add(joined);
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
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
