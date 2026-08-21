/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The defensive edges of the room commit guard: best-effort baseline, timestamp and shell
 * fallbacks.
 */
class RoomCommitGuardTest {

  @TempDir Path tempDir;
  private Sqlite db;
  private RunStore runStore;
  private final List<Event> events = new ArrayList<>();

  @BeforeEach
  void setUp() {
    db = Sqlite.open(tempDir.resolve("test.db"));
    new SchemaManager(db).migrate();
    runStore = new RunStore(db);
  }

  @AfterEach
  void tearDown() {
    if (db != null) db.close();
  }

  private static final SailYaml CONFIG =
      SailYaml.fromMap(
          Map.of(
              "name",
              "acme",
              "ssh",
              Map.of("user", "dev"),
              "repos",
              List.of(Map.of("url", "https://example.com/app.git", "path", "app"))));

  private static ShellExec shell(Function<List<String>, ShellExec.Result> handler) {
    return new ShellExec() {
      @Override
      public ShellExec.Result exec(List<String> command) {
        return handler.apply(command);
      }

      @Override
      public ShellExec.Result exec(List<String> command, Path workDir, Duration timeout) {
        return handler.apply(command);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  private static ShellExec throwingShell() {
    return shell(
        command -> {
          throw new RuntimeException("boom");
        });
  }

  private RoomCommitGuard guard(RunStore store, ShellExec shell) {
    return new RoomCommitGuard(store, null, events::add, shell);
  }

  private static RunStore.RunRow run(String status, String startedAt, String completedAt) {
    return new RunStore.RunRow(
        "cand",
        "acme",
        "auth",
        "node",
        "build",
        "codex",
        "b",
        "t",
        null,
        null,
        status,
        null,
        "log",
        "unit",
        startedAt,
        completedAt,
        List.of("app"),
        null,
        null,
        null);
  }

  @Test
  void parseInstantIsNullSafeAndTolerantOfGarbage() {
    assertNull(RoomCommitGuard.parseInstant(null));
    assertNull(RoomCommitGuard.parseInstant("   "));
    assertNull(RoomCommitGuard.parseInstant("not-a-timestamp"));
    assertEquals(
        Instant.parse("2026-01-01T00:00:00Z"),
        RoomCommitGuard.parseInstant("2026-01-01T00:00:00Z"));
  }

  @Test
  void aRunStartedAfterTheGuardCheckCannotHaveOverlapped() {
    var started = Instant.parse("2026-01-01T00:05:00Z");
    var guardAt = Instant.parse("2026-01-01T00:00:00Z");
    assertFalse(
        RoomCommitGuard.overlapsRoomInterval(
            run("running", started.toString(), null), null, guardAt));
  }

  @Test
  void aLiveRunAlwaysOverlaps() {
    var guardAt = Instant.parse("2026-01-01T01:00:00Z");
    assertTrue(
        RoomCommitGuard.overlapsRoomInterval(
            run("running", "2026-01-01T00:00:00Z", null), null, guardAt));
  }

  @Test
  void aRunThatFinishedBeforeTheRoomStartedDidNotOverlap() {
    var roomStarted = Instant.parse("2026-01-01T00:30:00Z");
    var guardAt = Instant.parse("2026-01-01T01:00:00Z");
    var candidate = run("completed", "2026-01-01T00:00:00Z", "2026-01-01T00:10:00Z");
    assertFalse(RoomCommitGuard.overlapsRoomInterval(candidate, roomStarted, guardAt));
  }

  @Test
  void aCompletedRunWithoutAKnownRoomStartCountsAsOverlapping() {
    var guardAt = Instant.parse("2026-01-01T01:00:00Z");
    var candidate = run("completed", "2026-01-01T00:00:00Z", "2026-01-01T00:10:00Z");
    assertTrue(RoomCommitGuard.overlapsRoomInterval(candidate, null, guardAt));
  }

  @Test
  void captureIsANoOpWithoutARunStore() {
    var guard = new RoomCommitGuard(null, null, events::add, throwingShell());
    assertDoesNotThrow(() -> guard.captureRoomBaseline("acme", CONFIG, "run-1"));
  }

  @Test
  void captureRecordsHeadAndFingerprintWhenGitReadsCleanly() {
    runStore.create(
        "run-1", "acme", "auth", "node", "node", "room", "codex", "b", "t", null, null, "log",
        "unit");
    var guard = guard(runStore, shell(command -> new ShellExec.Result(0, "deadbeef\n", "")));

    guard.captureRoomBaseline("acme", CONFIG, "run-1");

    assertNotNull(runStore.consumeRoomGuardBaseline("run-1").orElse(null));
  }

  @Test
  void captureSavesNothingWhenAReposHeadCannotBeRead() {
    var guard = guard(runStore, shell(command -> new ShellExec.Result(1, "", "no git")));

    guard.captureRoomBaseline("acme", CONFIG, "run-1");

    assertNull(
        runStore.consumeRoomGuardBaseline("run-1").orElse(null),
        "an unreadable repo records no baseline");
  }

  @Test
  void captureDegradesTheGuardButNeverThrowsWhenTheShellFails() {
    var guard = guard(runStore, throwingShell());

    assertDoesNotThrow(() -> guard.captureRoomBaseline("acme", CONFIG, "run-1"));
    assertNull(runStore.consumeRoomGuardBaseline("run-1").orElse(null));
  }

  @Test
  void guardIsANoOpWithoutARunStore() {
    var guard = new RoomCommitGuard(null, null, events::add, throwingShell());
    assertDoesNotThrow(() -> guard.guardRoomRun("acme", "run-1"));
    assertTrue(events.isEmpty());
  }

  @Test
  void guardAttributesNothingWhenAConcurrentRunHoldsNoReservedRepos() {
    runStore.create(
        "run-1", "acme", "auth", "node", "node", "room", "codex", "b", "t", null, null, "log",
        "unit");
    runStore.create(
        "cand", "acme", "auth2", "node", "node", "build", "codex", "b", "t", null, null, "log2",
        "unit2");
    runStore.saveRoomGuardBaseline("run-1", "{\"app\": {\"head\": \"X\"}}");
    var guard = guard(runStore, throwingShell());

    guard.guardRoomRun("acme", "run-1");

    assertTrue(
        events.isEmpty(),
        "a concurrent run reserving no specific repo could have authored anything — the guard"
            + " attributes nothing rather than misattribute");
  }

  @Test
  void guardTreatsUnreadableOrStatelessBaselineEntriesAsUntouched() throws IOException {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(
        yaml,
        "name: acme\nssh:\n  user: dev\nrepos:\n  - url: https://example.com/app.git\n    path:"
            + " app\n");
    runStore.create(
        "run-1", "acme", "auth", "node", "node", "room", "codex", "b", "t", null, null, "log",
        "unit");
    runStore.saveRoomGuardBaseline(
        "run-1",
        "{\"repoA\": \"not-a-map\","
            + " \"repoB\": {\"head\": \"X\"},"
            + " \"repoC\": {\"head\": \"X\", \"state\": \"S\"}}");
    var shell =
        shell(
            command -> {
              var joined = String.join(" ", command);
              if (joined.contains("incus list")) {
                return new ShellExec.Result(
                    0, "[{\"name\": \"acme\", \"status\": \"Running\"}]", "");
              }
              if (joined.contains("/repoC")) {
                return new ShellExec.Result(1, "", "no git");
              }
              return new ShellExec.Result(0, "X\n", "");
            });
    var guard =
        new RoomCommitGuard(
            runStore, new ProjectLoader(shell, yaml.toString()), events::add, shell);

    guard.guardRoomRun("acme", "run-1");

    assertTrue(
        events.isEmpty(),
        "a non-map entry, an unreadable repo, and an unchanged head with no fingerprint are all"
            + " untouched — no guardrail");
  }
}
