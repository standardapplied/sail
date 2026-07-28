/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A fresh work branch must be cut off the latest upstream tip, not a stale local checkout of the
 * base: before {@code checkout -b}, dispatch fetches the repo's current base branch from origin and
 * forks the new branch from {@code origin/<base>}. When origin is unreachable (or the base cannot
 * be resolved) it falls back to the local {@code HEAD} rather than failing the dispatch.
 */
class DispatchBranchBaseTest {

  private static final String HANDLE = "me";
  private static final Actor ADMIN = Actor.cliOperator(HANDLE);
  private static final String REPO = "git -C /home/dev/workspace/app";

  private static final String RUNNING_JSON =
      """
      [
        {
          "name": "acme",
          "status": "Running",
          "state": {
            "network": {
              "eth0": {
                "addresses": [
                  {"family": "inet", "address": "10.0.0.42", "scope": "global"}
                ]
              }
            }
          }
        }
      ]
      """;

  private static final String YAML =
      """
      name: acme
      ssh:
        user: dev
      repos:
        - url: https://github.com/acme/app.git
          path: app
      agent:
        type: claude-code
        auto_branch: true
        branch_prefix: sail/
      """;

  @TempDir Path tempDir;

  @Test
  void aFreshBranchForksOffTheFetchedOriginBase() throws IOException {
    var shell =
        happyPath()
            .on(REPO + " rev-parse --verify --quiet refs/remotes/origin/main", "")
            .on(REPO + " checkout -b sail/auth origin/main", "");

    dispatch(shell);

    assertTrue(
        ranContaining(shell, REPO + " fetch --quiet origin main"),
        "dispatch must fetch the base from origin before cutting the branch");
    assertTrue(
        ranContaining(shell, REPO + " checkout -b sail/auth origin/main"),
        "the fresh branch must fork from the fetched origin/main, not the local base");
  }

  @Test
  void anUnreachableOriginFallsBackToTheLocalHead() throws IOException {
    var shell = happyPath().on(REPO + " checkout -b sail/auth", "");

    dispatch(shell);

    assertTrue(
        ranContaining(shell, REPO + " checkout -b sail/auth"),
        "a dispatch on a box that cannot reach origin still proceeds off the local HEAD");
    assertFalse(
        ranContaining(shell, REPO + " checkout -b sail/auth origin/main"),
        "without a resolvable origin base the branch is never forked from a remote ref");
  }

  private void dispatch(RecordingShell shell) throws IOException {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, YAML);
    var db = Sqlite.open(tempDir.resolve("sail.db"));
    new SchemaManager(db).migrate();
    var specStore = new SpecStore(db);
    specStore.create(
        new SpecStore.SpecRow(
            "auth",
            "acme",
            "Add auth",
            SpecStatus.PENDING,
            HANDLE,
            null,
            null,
            null,
            null,
            0,
            "me",
            null,
            null,
            "me",
            List.of(),
            List.of()));
    specStore.setContent("auth", "Do auth", "");
    new FdeStore(db).add(HANDLE, null, null, "admin");

    var ops =
        new DispatchOperations(
            shell,
            yaml.toString(),
            specStore,
            new ReviewStore(db),
            new RunStore(db),
            new FdeStore(db),
            new CopyOnWriteArrayList<Event>()::add,
            new WatcherSpawner(happyPath(), (command, logPath) -> 4242L),
            (project, config) -> "",
            command -> 0,
            DispatchOperations.Listener.NONE);

    var outcome =
        ops.dispatch(
            "acme",
            new DispatchOperations.Request("auth", "background", false, null, false),
            ADMIN,
            HANDLE);
    assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
  }

  private static RecordingShell happyPath() {
    return new RecordingShell()
        .on("incus list ^acme$", RUNNING_JSON)
        .on("mkdir -p /home/dev/.sail", "")
        .on("printf '%s'", "")
        .on("test -d /home/dev/workspace/app/.git", "")
        .on(REPO + " rev-parse --abbrev-ref origin/HEAD", "origin/main\n")
        .on(REPO + " fetch --quiet origin main", "")
        .on("claude", "");
  }

  private static boolean ranContaining(RecordingShell shell, String fragment) {
    return shell.executed.stream().anyMatch(command -> command.contains(fragment));
  }

  private static final class RecordingShell implements ShellExec {
    private final Map<String, Result> scripts = new LinkedHashMap<>();
    private final List<String> executed = new ArrayList<>();

    /** Every launch reconciles the in-container sail helpers; answer as already installed. */
    RecordingShell() {
      on("incus config device add", "");
      on("grep -qsF", "");
    }

    RecordingShell on(String pattern, String stdout) {
      scripts.put(pattern, new Result(0, stdout, ""));
      return this;
    }

    @Override
    public Result exec(List<String> command) {
      var joined = String.join(" ", command);
      executed.add(joined);
      for (var entry : scripts.entrySet()) {
        if (joined.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      return new Result(1, "", "no script for " + joined);
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }
}
