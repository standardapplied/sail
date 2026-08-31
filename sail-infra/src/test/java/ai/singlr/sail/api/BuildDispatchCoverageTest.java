/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
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
 * The build lane's edge behaviors, each asserted through a real dispatch: a project with no agent
 * block is refused loud before any claim; a repo that pins its own base branch forks the work
 * branch off that pin rather than {@code origin/HEAD}; a shell failure while preparing the checkout
 * surfaces as {@code COMMAND_FAILED}; and a room message rendered into the prompt is recorded on
 * the run's delivery ledger at launch.
 */
class BuildDispatchCoverageTest {

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

  private static final String YAML_PINNED_BASE =
      """
      name: acme
      ssh:
        user: dev
      repos:
        - url: https://github.com/acme/app.git
          path: app
          branch: develop
      agent:
        type: claude-code
        auto_branch: true
        branch_prefix: sail/
      """;

  private static final String YAML_NO_AGENT =
      """
      name: acme
      ssh:
        user: dev
      repos:
        - url: https://github.com/acme/app.git
          path: app
      """;

  @TempDir Path tempDir;

  @Test
  void aDispatchWithNoAgentConfiguredFailsLoud() throws IOException {
    var db = seedDb();
    var ops = ops(happyPath(), YAML_NO_AGENT, db);

    var thrown = assertThrows(ApiException.class, () -> dispatch(ops));

    assertEquals(ErrorCode.AGENT_NOT_CONFIGURED, thrown.failure().errorCode());
  }

  @Test
  void aFreshBranchForksOffThePinnedBaseBranch() throws IOException {
    var shell =
        happyPath()
            .on(REPO + " rev-parse --verify --quiet refs/remotes/origin/develop", "")
            .on(REPO + " checkout -b sail/auth origin/develop", "");
    var ops = ops(shell, YAML_PINNED_BASE, seedDb());

    assertInstanceOf(DispatchOperations.Dispatched.class, dispatch(ops));

    assertTrue(
        ranContaining(shell, REPO + " fetch --quiet origin develop"),
        "the pinned base is fetched from origin before the branch is cut");
    assertTrue(
        ranContaining(shell, REPO + " checkout -b sail/auth origin/develop"),
        "the fresh branch forks off the repo's pinned base, not origin/HEAD");
  }

  @Test
  void aShellFailureWhilePreparingTheCheckoutFailsWithCommandFailed() throws IOException {
    var shell = happyPath().throwOn("test -d /home/dev/workspace/app/.git");
    var ops = ops(shell, YAML, seedDb());

    var thrown = assertThrows(ApiException.class, () -> dispatch(ops));

    assertEquals(ErrorCode.COMMAND_FAILED, thrown.failure().errorCode());
  }

  @Test
  void aRoomMessageRenderedIntoThePromptIsMarkedDeliveredAtLaunch() throws IOException {
    var shell =
        happyPath()
            .on(REPO + " rev-parse --verify --quiet refs/remotes/origin/main", "")
            .on(REPO + " checkout -b sail/auth origin/main", "");
    var db = seedDb();
    var messages = new MessageStore(db);
    var seeded = messages.append("auth", HANDLE, "Please start with the login form.", null);
    var runStore = new RunStore(db);
    var ops = ops(shell, YAML, db, runStore).useMessages(messages);

    var outcome = dispatch(ops);

    var dispatched = assertInstanceOf(DispatchOperations.Dispatched.class, outcome);
    assertTrue(
        runStore.deliveredMessageIds(dispatched.runId()).contains(seeded.id()),
        "the message rendered into the dispatch prompt is recorded as delivered on the run");
  }

  private Sqlite seedDb() {
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
    return db;
  }

  private DispatchOperations ops(RecordingShell shell, String yamlBody, Sqlite db)
      throws IOException {
    return ops(shell, yamlBody, db, new RunStore(db));
  }

  private DispatchOperations ops(
      RecordingShell shell, String yamlBody, Sqlite db, RunStore runStore) throws IOException {
    var yaml = tempDir.resolve("sail.yaml");
    Files.writeString(yaml, yamlBody);
    return new DispatchOperations(
        shell,
        yaml.toString(),
        new SpecStore(db),
        new ReviewStore(db),
        runStore,
        new FdeStore(db),
        new CopyOnWriteArrayList<Event>()::add,
        new WatcherSpawner(happyPath(), (command, logPath) -> 4242L),
        (project, config) -> "",
        command -> 0,
        DispatchOperations.Listener.NONE,
        SessionYield.NONE);
  }

  private static DispatchOperations.Outcome dispatch(DispatchOperations ops) {
    return ops.dispatch(
        "acme",
        new DispatchOperations.Request("auth", "background", false, null, false),
        ADMIN,
        HANDLE);
  }

  private static RecordingShell happyPath() {
    return new RecordingShell()
        .on("incus list ^acme$", RUNNING_JSON)
        .on("mkdir -p /home/dev/.sail", "")
        .on("printf '%s'", "")
        .on("test -d /home/dev/workspace/app/.git", "")
        .on(REPO + " rev-parse --abbrev-ref origin/HEAD", "origin/main\n")
        .on(REPO + " fetch --quiet origin main", "")
        .on(REPO + " fetch --quiet origin develop", "")
        .on("claude", "");
  }

  private static boolean ranContaining(RecordingShell shell, String fragment) {
    return shell.executed.stream().anyMatch(command -> command.contains(fragment));
  }

  private static final class RecordingShell implements ShellExec {
    private final Map<String, Result> scripts = new LinkedHashMap<>();
    private final List<String> executed = new ArrayList<>();
    private String throwPattern;

    RecordingShell() {
      on("incus config device add", "");
      on("cat " + ContainerSailSetup.STAMP_PATH, ContainerSailSetup.fingerprint());
    }

    RecordingShell on(String pattern, String stdout) {
      scripts.put(pattern, new Result(0, stdout, ""));
      return this;
    }

    RecordingShell throwOn(String pattern) {
      this.throwPattern = pattern;
      return this;
    }

    @Override
    public Result exec(List<String> command) throws IOException {
      var joined = String.join(" ", command);
      executed.add(joined);
      if (throwPattern != null && joined.contains(throwPattern)) {
        throw new IOException("shell unavailable for " + joined);
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
