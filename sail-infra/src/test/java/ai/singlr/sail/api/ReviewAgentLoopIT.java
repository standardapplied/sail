/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerFilePush;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The review loop against a <strong>real incus container</strong> with a fake agent binary standing
 * in for claude/codex — no LLM, no API key. Validates what the in-process tests cannot reach: a
 * reviewer and a fix agent each launching as their own {@code systemd --user} unit of the dev user,
 * exactly as a build does; the prompt staged and read by {@code $(cat ...)}; the binary invoked on
 * PATH; the unit's exit read back from systemd and its session file; the findings parsed from the
 * run's own log; and a killed unit leaving no agent process behind. The test stands in for the
 * watcher only: it reads each unit's exit the way the watcher does and publishes the stop the
 * watcher would. Runs only under the {@code integration} profile (maven-failsafe) against a real
 * incus daemon; skips elsewhere via {@link #ensureIncusOrSkip}.
 */
class ReviewAgentLoopIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-review-loop";
  private static final String HANDLE = "it-node";
  private static final Duration PATIENCE = Duration.ofSeconds(60);

  /**
   * A stand-in for the agent CLI binary. A review prompt yields scripted findings — a critical
   * issue the first time, clean once the fix has supposedly landed (tracked by a counter file) —
   * and any other prompt (a fix task) is acknowledged. While {@code review-hold} exists it waits,
   * so a test can look at a run that is still running. Deterministic, offline, no model.
   */
  private static final String FAKE_AGENT =
      """
      #!/usr/bin/env bash
      if [ -f "$HOME/.sail/review-hold" ]; then
        printf 'review started\n'
        touch "$HOME/.sail/review-started"
        while [ -f "$HOME/.sail/review-hold" ]; do sleep 0.1; done
      fi
      case "$*" in
        *"Review the changes on branch"*)
          n_file="$HOME/.sail/review-count"
          n=$(( $(cat "$n_file" 2>/dev/null || echo 0) + 1 ))
          printf '%s' "$n" > "$n_file"
          if [ "$n" -eq 1 ]; then
            printf '```json\\n{"verdicts": [], "findings": [{"severity":"CRITICAL","category":"SECURITY","file":"a.java","line_start":1,"line_end":1,"title":"Bad","description":"Very bad","evidence":"trace","confidence":0.95}]}\\n```\\n'
          else
            v=""
            for id in $(printf '%s' "$*" | grep -o 'finding_id [^ ]*' | cut -d' ' -f2); do
              v="$v{\\"finding_id\\": \\"$id\\", \\"verdict\\": \\"fixed\\", \\"evidence\\": \\"commit abc\\"},"
            done
            printf '```json\\n{"verdicts": [%s], "findings": []}\\n```\\n' "${v%,}"
          fi
          ;;
        *)
          printf 'fix applied\\n'
          ;;
      esac
      """;

  private Path stateDir;
  private Sqlite db;
  private SpecStore specStore;
  private ReviewStore reviewStore;
  private RunStore runStore;
  private EventBus bus;
  private final Set<String> seen = new HashSet<>();

  @BeforeEach
  void provision() throws Exception {
    ensureIncusOrSkip();
    launchPrepared(CONTAINER);
    var setup =
        exec(
            CONTAINER,
            List.of(
                "bash",
                "-c",
                "set -e;"
                    + " userdel -r ubuntu 2>/dev/null || true;"
                    + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev;"
                    + " mkdir -p /home/dev/.sail /home/dev/workspace;"
                    + " chown -R dev:dev /home/dev;"
                    + " for i in $(seq 1 30); do"
                    + " loginctl enable-linger dev 2>/dev/null && exit 0; sleep 1; done;"
                    + " loginctl enable-linger dev"));
    assertTrue(setup.ok(), "container provisioning failed: " + setup.stderr());
    awaitUserManager();
    ContainerFilePush.push(
        shell, CONTAINER, "/usr/local/bin/codex", FAKE_AGENT, List.of("--mode", "0755"));
    stateDir = Files.createTempDirectory("review-loop-it");
    var yaml = stateDir.resolve("sail.yaml");
    Files.writeString(
        yaml,
        """
        name: %s
        ssh:
          user: dev
        repos:
          - url: https://example.invalid/app.git
            path: app
        agent:
          type: codex
        """
            .formatted(CONTAINER));
    db = Sqlite.open(stateDir.resolve("loop.db"));
    new SchemaManager(db).migrate();
    specStore = new SpecStore(db);
    reviewStore = new ReviewStore(db);
    runStore = new RunStore(db);
    bus = new EventBus();
    var dispatch =
        new DispatchOperations(
            shell,
            yaml.toString(),
            specStore,
            reviewStore,
            runStore,
            new FdeStore(db),
            bus::publish,
            new WatcherSpawner(refusingShell(), (command, logPath) -> 4242L),
            (project, config) -> "",
            DispatchOperations.shellLauncher(shell),
            DispatchOperations.Listener.NONE,
            SessionYield.NONE);
    var config =
        ReviewPipelineConfig.fromMap(
            Map.of(
                "max_iterations",
                3,
                "stages",
                List.of(
                    Map.of(
                        "name",
                        "security",
                        "type",
                        "agent",
                        "agent",
                        "codex",
                        "gate",
                        "no_critical"))));
    bus.subscribe(new RunTracker(runStore, SyncScheduler.disabled(), () -> HANDLE));
    bus.subscribe(
        new ReviewPipelineController(
            specStore,
            reviewStore,
            runStore,
            project -> config,
            project -> "codex",
            dispatch.reviewLanes(),
            bus,
            () -> {},
            () -> HANDLE));
    Acting.as(
        null,
        () ->
            specStore.create(
                new SpecStore.SpecRow(
                    "auth",
                    CONTAINER,
                    "T",
                    SpecStatus.IN_PROGRESS,
                    null,
                    "codex",
                    null,
                    null,
                    "feat/test",
                    0,
                    null,
                    "",
                    "",
                    null,
                    List.of(),
                    List.of())));
  }

  @AfterEach
  void cleanup() {
    if (bus != null) {
      bus.close();
    }
    if (db != null) {
      db.close();
    }
    deleteContainerQuietly(CONTAINER);
    if (stateDir != null) {
      deleteRecursively(stateDir);
    }
  }

  @Test
  void theReviewLoopReachesAwaitingMergeWithEveryAgentAsItsOwnUnit() throws Exception {
    hold();
    bus.publish(buildStop());

    var reviewer = awaitRunning("review");
    assertEquals("auth", reviewer.specId());
    assertEquals(HANDLE, reviewer.node());
    assertEquals("codex", reviewer.agent());
    assertEquals(AgentUnit.forRun(reviewer.id()).unitName(), reviewer.unit());
    assertEquals(reviewStore.latestReviewForSpec("auth").orElseThrow().id(), reviewer.reviewId());
    await(
        () -> logOf(reviewer).contains("review started"),
        "the reviewer's log streams to its own run directory while it runs");
    assertTrue(
        new AgentSession(shell).unitActive(CONTAINER, AgentUnit.forRun(reviewer.id())),
        "the reviewer runs as its run's systemd unit, like a build");

    release();
    watcherObservesTheExitOf(reviewer);

    var fix = awaitRunning("fix");
    assertEquals(reviewer.reviewId(), fix.reviewId(), "the fix run serves the failed review");
    assertEquals(1, reviewStore.findingsForReview(reviewer.reviewId()).size());
    watcherObservesTheExitOf(fix);

    var second = awaitRunning("review");
    watcherObservesTheExitOf(second);

    await(
        () -> specStore.findById("auth").orElseThrow().status() == SpecStatus.AWAITING_MERGE,
        "the re-review passes and the spec awaits merge");
    assertEquals(2, reviewStore.reviewsForSpec("auth").size());
    for (var run : List.of(reviewer, fix, second)) {
      var ended = runStore.findById(run.id()).orElseThrow();
      assertEquals("stopped", ended.status(), ended.role());
      assertEquals(0, ended.exitCode(), ended.role());
      assertFalse(logOf(run).isBlank(), "each run wrote its own log: " + ended.role());
    }
  }

  @Test
  void aKilledReviewerLeavesNoAgentProcessBehindAndItsReviewErrorsWithTheReason() throws Exception {
    hold();
    bus.publish(buildStop());
    var reviewer = awaitRunning("review");
    var unit = AgentUnit.forRun(reviewer.id());
    var session = new AgentSession(shell);
    await(() -> logOf(reviewer).contains("review started"), "the fake agent is running");

    session.killAgent(CONTAINER, unit);

    assertFalse(session.unitActive(CONTAINER, unit), "the unit is gone");
    var survivors =
        shell.exec(
            ContainerExec.asDevUser(CONTAINER, List.of("pgrep", "-f", "/usr/local/bin/codex")));
    assertFalse(
        survivors.ok(),
        "the kill takes the unit's whole cgroup: no agent process is left to push work: "
            + survivors.stdout());
    var exit = session.queryExitStatus(CONTAINER, unit);
    bus.publish(
        RunStops.of(
            Event.WellKnownData.SOURCE_WATCHER,
            CONTAINER,
            exit.specId(),
            exit.agentType(),
            exit.runId(),
            exit.role(),
            exit.exitCode(),
            "time limit (45m)"));

    await(
        () -> reviewStore.findReview(reviewer.reviewId()).orElseThrow().errored(),
        "the review errors on the reaped reviewer's stop");
    assertEquals(
        "reviewer killed: time limit (45m)",
        reviewStore.findReview(reviewer.reviewId()).orElseThrow().error());
    assertEquals("stopped", runStore.findById(reviewer.id()).orElseThrow().status());
  }

  private Event buildStop() {
    return RunStops.of(
        Event.WellKnownData.SOURCE_WATCHER, CONTAINER, "auth", "codex", null, "build", 0, null);
  }

  private void hold() throws Exception {
    var held =
        shell.exec(
            ContainerExec.asDevUser(CONTAINER, List.of("touch", "/home/dev/.sail/review-hold")));
    assertTrue(held.ok(), held.stderr());
  }

  private void release() throws Exception {
    var released =
        shell.exec(
            ContainerExec.asDevUser(CONTAINER, List.of("rm", "-f", "/home/dev/.sail/review-hold")));
    assertTrue(released.ok(), released.stderr());
  }

  private String logOf(RunStore.RunRow run) {
    try {
      var log =
          shell.exec(
              ContainerExec.asDevUser(
                  CONTAINER, List.of("cat", AgentUnit.forRun(run.id()).logPath())));
      return log.ok() ? log.stdout() : "";
    } catch (Exception e) {
      return "";
    }
  }

  /**
   * Plays the watcher for one run: waits for its unit to go inactive, reads the exit the way the
   * watcher reads it — from systemd, falling back to the run's session file once the transient unit
   * is collected — and publishes the stop the watcher would. A unit that reads inactive before its
   * session file names the run has not been launched yet — the run's row is written first — and,
   * like the watcher, this waits for it.
   */
  private void watcherObservesTheExitOf(RunStore.RunRow run) throws Exception {
    var session = new AgentSession(shell);
    var unit = AgentUnit.forRun(run.id());
    var deadline = Instant.now().plus(PATIENCE);
    while (Instant.now().isBefore(deadline)) {
      var exit = session.queryExitStatus(CONTAINER, unit);
      if (!exit.active() && run.id().equals(exit.runId())) {
        assertEquals(run.role(), exit.role(), "the stop names the lane of the run it ends");
        bus.publish(
            RunStops.of(
                Event.WellKnownData.SOURCE_WATCHER,
                CONTAINER,
                exit.specId(),
                exit.agentType(),
                exit.runId(),
                exit.role(),
                exit.exitCode(),
                null));
        return;
      }
      Thread.sleep(Duration.ofMillis(200));
    }
    throw new AssertionError("the " + run.role() + " unit never exited: " + logOf(run));
  }

  private RunStore.RunRow awaitRunning(String role) throws Exception {
    var deadline = Instant.now().plus(PATIENCE);
    while (Instant.now().isBefore(deadline)) {
      var next =
          runStore.listForSpec("auth").stream()
              .filter(run -> role.equals(run.role()) && "running".equals(run.status()))
              .filter(run -> !seen.contains(run.id()))
              .findFirst();
      if (next.isPresent()) {
        seen.add(next.get().id());
        return next.get();
      }
      Thread.sleep(Duration.ofMillis(100));
    }
    throw new AssertionError(
        "no running " + role + " run appeared; runs: " + runStore.listForSpec("auth"));
  }

  private static void await(Supplier<Boolean> condition, String what) throws Exception {
    var deadline = Instant.now().plus(PATIENCE);
    while (Instant.now().isBefore(deadline)) {
      if (condition.get()) {
        return;
      }
      Thread.sleep(Duration.ofMillis(100));
    }
    throw new AssertionError("never happened: " + what);
  }

  /**
   * A shell that refuses every command: the watcher spawner falls straight to its fake process
   * fallback instead of launching host-side systemd units the CI runner would have to clean up.
   */
  private static ShellExec refusingShell() {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) {
        return new Result(1, "", "refused");
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout) {
        return new Result(1, "", "refused");
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  private void awaitUserManager() throws Exception {
    var deadline = Instant.now().plus(PATIENCE);
    while (Instant.now().isBefore(deadline)) {
      var probe = exec(CONTAINER, List.of("test", "-S", "/run/user/1000/bus"));
      if (probe.ok()) {
        return;
      }
      Thread.sleep(Duration.ofSeconds(1));
    }
    throw new AssertionError("dev user's systemd manager (bus) never came up in " + CONTAINER);
  }
}
