/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerFilePush;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.engine.StageSkillInstaller;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.gen.BuiltInSkills;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The review loop against a <strong>real incus container</strong> with a fake agent binary standing
 * in for claude/codex — no LLM, no API key. Validates what the in-process tests cannot reach: a
 * reviewer and a fix agent each launching as their own {@code systemd --user} unit of the dev user,
 * exactly as a build does; the prompt staged and read by {@code $(cat ...)}; the binary invoked on
 * PATH; the unit's exit read back from systemd and its session file; the findings parsed from the
 * run's own log; and a killed unit leaving no agent process behind. The test stands in for the
 * watcher only: it reads each unit's exit the way the watcher does and publishes the stop the
 * watcher would. Runs only under the {@code integration} profile (maven-failsafe) against a real
 * incus daemon; skips elsewhere via {@link #ensureIncusOrSkip}. Every test is bounded: a loop that
 * hangs against a real container fails its test instead of holding the lane.
 */
@Timeout(value = 10, unit = TimeUnit.MINUTES)
class ReviewAgentLoopIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-review-loop";
  private static final String HANDLE = "it-node";
  private static final Duration PATIENCE = Duration.ofSeconds(60);

  /**
   * A stand-in for the agent CLI binary. A review prompt yields scripted findings — a critical
   * issue the first time, clean once the fix has supposedly landed (tracked by a counter file) —
   * and any other prompt (a fix task) is acknowledged. While {@code review-hold} exists it waits,
   * so a test can look at a run that is still running, and with {@code slow-tool} present it first
   * runs one command for that many seconds, as an agent's long tool call does. Deterministic,
   * offline, no model.
   */
  private static final String FAKE_AGENT =
      """
      #!/usr/bin/env bash
      if [ -f "$HOME/.sail/review-hold" ]; then
        printf 'review started\n'
        touch "$HOME/.sail/review-started"
        while [ -f "$HOME/.sail/review-hold" ]; do sleep 0.1; done
      fi
      if [ -f "$HOME/.sail/slow-tool" ]; then
        sleep "$(cat "$HOME/.sail/slow-tool")"
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
  private final BlockingQueue<Event> heard = new LinkedBlockingQueue<>();

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
            TestProjects.reading(yaml),
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
    bus.subscribe(listener());
    bus.subscribe(
        new ReviewPipelineController(
                specStore,
                reviewStore,
                runStore,
                project -> config,
                project -> "codex",
                dispatch.reviewLanes(),
                StageSkills.builtInOnly(),
                bus,
                () -> {},
                () -> HANDLE)
            .useMessages(new MessageStore(db)));
    spec("auth");
  }

  /** Records spec {@code id}, in progress under {@code codex}, working the whole container. */
  private void spec(String id) {
    Acting.as(
        null,
        () ->
            specStore.create(
                new SpecStore.SpecRow(
                    id,
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

  /**
   * The files of the installed skill {@code name}, each with its mode and owner: the stamp with
   * what it holds, any other file with its size.
   */
  private List<String> installedSkill(String name) throws Exception {
    var listed =
        exec(
            CONTAINER,
            List.of(
                "sh",
                "-c",
                "cd \"$1\" && for f in .sail-skill SKILL.md; do"
                    + " if [ \"$f\" = .sail-skill ]; then v=$(cat \"$f\"); else v=$(wc -c < \"$f\");"
                    + " fi; echo \"$f $(stat -c '%a %U' \"$f\") $v\"; done",
                "sh",
                "/home/dev/.agents/skills/" + name));
    assertTrue(listed.ok(), listed.stderr());
    return listed.stdout().lines().toList();
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

    var reviewer = launched("review", "review_stage_started");
    assertEquals("auth", reviewer.specId());
    assertEquals(HANDLE, reviewer.node());
    assertEquals("codex", reviewer.agent());
    assertEquals(AgentUnit.forRun(reviewer.id()).unitName(), reviewer.unit());
    assertEquals(reviewStore.latestReviewForSpec("auth").orElseThrow().id(), reviewer.reviewId());
    eventually(
        () -> logOf(reviewer).contains("review started"),
        "the reviewer's log streams to its own run directory while it runs");
    assertTrue(
        new AgentSession(shell).unitActive(CONTAINER, AgentUnit.forRun(reviewer.id())),
        "the reviewer runs as its run's systemd unit, like a build");
    assertEquals(
        List.of(
            ".sail-skill 644 dev "
                + StageSkillInstaller.fingerprint(
                    BuiltInSkills.of(StageSkill.REVIEW).orElseThrow()),
            "SKILL.md 644 dev "
                + BuiltInSkills.text(StageSkill.REVIEW)
                    .orElseThrow()
                    .getBytes(StandardCharsets.UTF_8)
                    .length),
        installedSkill(StageSkill.REVIEW),
        "the reviewer's skill is in Codex's skills folder: whole, the dev user's, and stamped");
    assertTrue(reviewer.task().contains("## How to do this work (skill: sail-review)"));

    release();
    watcherObservesTheExitOf(reviewer);

    var fix = launched("fix", "review_iteration_started");
    assertEquals(reviewer.reviewId(), fix.reviewId(), "the fix run serves the failed review");
    assertEquals(1, reviewStore.findingsForReview(reviewer.reviewId()).size());
    watcherObservesTheExitOf(fix);

    var second = launched("review", "review_stage_started");
    watcherObservesTheExitOf(second);
    assertEquals(
        List.of("sail-fix", "sail-review"),
        exec(CONTAINER, List.of("ls", "-A", "/home/dev/.agents/skills")).stdout().lines().toList(),
        "each stage's skill is installed once, and no build folder is left beside them");

    awaitEvent("review_completed");
    assertEquals(
        SpecStatus.AWAITING_MERGE,
        specStore.findById("auth").orElseThrow().status(),
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
    var reviewer = launched("review", "review_stage_started");
    var unit = AgentUnit.forRun(reviewer.id());
    var session = new AgentSession(shell);
    eventually(() -> logOf(reviewer).contains("review started"), "the fake agent is running");

    var halt = session.killAgent(CONTAINER, unit);

    assertInstanceOf(
        AgentSession.Halt.Ended.class, halt, "a signal was delivered and the unit answered gone");
    assertFalse(session.unitActive(CONTAINER, unit), "the unit is gone");
    var survivors =
        shell.exec(
            ContainerExec.asDevUser(CONTAINER, List.of("pgrep", "-f", "/usr/local/bin/codex")));
    assertFalse(
        survivors.ok(),
        "the kill takes the unit's whole cgroup: no agent process is left to push work: "
            + survivors.stdout());
    var exit = session.answeredExitStatus(CONTAINER, unit).orElseThrow();
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

    awaitEvent("review_errored");
    assertEquals(
        "reviewer killed: time limit (45m)",
        reviewStore.findReview(reviewer.reviewId()).orElseThrow().error());
    assertEquals("stopped", runStore.findById(reviewer.id()).orElseThrow().status());
  }

  @Test
  void aReviewerRefusedTheRepoARealRunHoldsWaitsOnItAndStartsWhenItStops() throws Exception {
    inContainer("printf 1 > \"$HOME/.sail/review-count\"");
    spec("billing");
    hold();
    bus.publish(buildStop("billing"));
    var holder = launched("billing", "review", "review_stage_started");
    eventually(() -> logOf(holder).contains("review started"), "billing's reviewer runs");

    bus.publish(stopOfABuildRunOf("auth"));

    eventually(
        () ->
            reviewStore
                .latestReviewForSpec("auth")
                .filter(review -> review.waitingOn() != null)
                .isPresent(),
        "auth's review records the run it waits on");
    var waiting = reviewStore.latestReviewForSpec("auth").orElseThrow();
    assertEquals(holder.id(), waiting.waitingOn(), "the wait names the run that holds the repo");
    assertEquals("running", waiting.status());
    assertEquals(
        List.of("pending"),
        reviewStore.stagesForReview(waiting.id()).stream()
            .map(ReviewStore.StageRow::status)
            .toList(),
        "no stage is running for a reviewer that does not exist");
    assertTrue(runStore.forReview(waiting.id()).isEmpty(), "nothing was started for auth");
    assertEquals(
        List.of(
            "Review is waiting for run `" + holder.id() + "` (`review` of `billing`) to finish."),
        new MessageStore(db)
            .list("auth", null, 20).stream().map(MessageStore.MessageRow::body).toList());

    release();
    watcherObservesTheExitOf(holder);

    awaitEvent("review_completed");
    var reviewer = launched("auth", "review", "review_stage_started");
    assertEquals(waiting.id(), reviewer.reviewId(), "the holder's stop launched the reviewer");
    assertNull(reviewStore.findReview(waiting.id()).orElseThrow().waitingOn());
    assertTrue(
        new AgentSession(shell).unitActive(CONTAINER, AgentUnit.forRun(reviewer.id()))
            || !logOf(reviewer).isBlank(),
        "the reviewer runs as its own unit");
    watcherObservesTheExitOf(reviewer);
    awaitEvent("review_completed");
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aRealToolCallLongerThanTheStallWindowIsNotKilledAsAStall() throws Exception {
    inContainer("printf 1 > \"$HOME/.sail/review-count\"; printf 8 > \"$HOME/.sail/slow-tool\"");
    bus.publish(buildStop("auth"));
    var reviewer = launched("auth", "review", "review_stage_started");
    var feed = new LinkedBlockingQueue<Event>();
    feed.add(
        Event.of(
            CONTAINER,
            "auth",
            Event.WellKnownTypes.AGENT_TOOL_STARTED,
            "codex",
            "host",
            Map.of(Event.WellKnownData.RUN_ID, reviewer.id())));

    new RunWatch(
            CONTAINER,
            reviewer.id(),
            AgentUnit.forRun(reviewer.id()),
            new Guardrails("5m", "2s", "stop"),
            Instant.parse(reviewer.startedAt()),
            false,
            shell,
            new RunWatch.Feed() {
              @Override
              public Event poll(Duration wait) throws InterruptedException {
                return feed.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
              }

              @Override
              public boolean live() {
                return true;
              }

              @Override
              public boolean reopen() {
                return true;
              }
            },
            bus::publish,
            new QuietNarrator(),
            Instant::now)
        .run();

    var stop = stopOf(reviewer);
    assertNull(
        stop.data().get(Event.WellKnownData.REASON),
        "eight seconds inside one command, four stall windows long, is work: the watcher killed"
            + " nothing");
    assertEquals(0, Event.WellKnownData.exitCode(stop.data()));
    awaitEvent("review_completed");
    assertEquals("stopped", runStore.findById(reviewer.id()).orElseThrow().status());
    assertEquals(SpecStatus.AWAITING_MERGE, specStore.findById("auth").orElseThrow().status());
  }

  @Test
  void aFixAgentAnOlderServerLeftRunningForTheReviewIsKilledBeforeTheFixRunStarts()
      throws Exception {
    hold();
    bus.publish(buildStop("auth"));
    var reviewer = launched("auth", "review", "review_stage_started");
    eventually(() -> logOf(reviewer).contains("review started"), "the reviewer is running");
    var legacy = processWithRunId(reviewer.reviewId());
    var otherRun = processWithRunId(reviewer.id());
    assertTrue(alive(legacy) && alive(otherRun), "both stand-in processes are running");

    release();
    var exit = exitOf(reviewer);
    hold();
    publishStop(exit);

    var fix = launched("auth", "fix", "review_iteration_started");
    assertEquals(reviewer.reviewId(), fix.reviewId());
    eventually(
        () -> new AgentSession(shell).unitActive(CONTAINER, AgentUnit.forRun(fix.id())),
        "the fix agent's unit is running");
    assertFalse(
        alive(legacy),
        "the process an older server started for this review, exporting the review id as its run"
            + " id, was killed before the fix run's unit started");
    assertTrue(alive(otherRun), "a process of a run this server started is not its to kill");

    release();
    watcherObservesTheExitOf(fix);
    awaitEvent("review_stage_started");
  }

  /** Starts a long-lived process as the dev user whose environment holds SAIL_RUN_ID=runId. */
  private String processWithRunId(String runId) throws Exception {
    var started =
        shell.exec(
            ContainerExec.asDevUser(
                CONTAINER,
                List.of(
                    "bash",
                    "-c",
                    "SAIL_RUN_ID=\"$1\" sleep 600 </dev/null >/dev/null 2>&1 & echo $!",
                    "bash",
                    runId)));
    assertTrue(started.ok(), started.stderr());
    return started.stdout().strip();
  }

  private boolean alive(String pid) throws Exception {
    return shell.exec(ContainerExec.asDevUser(CONTAINER, List.of("kill", "-0", pid))).ok();
  }

  private void inContainer(String script) throws Exception {
    var ran = shell.exec(ContainerExec.asDevUser(CONTAINER, List.of("bash", "-c", script)));
    assertTrue(ran.ok(), ran.stderr());
  }

  /** The authoritative stop published for {@code run}, once it has been. */
  private Event stopOf(RunStore.RunRow run) throws InterruptedException {
    while (true) {
      var stop = awaitEvent(Event.WellKnownTypes.AGENT_SESSION_STOPPED);
      if (run.id().equals(stop.data().get(Event.WellKnownData.RUN_ID))) {
        return stop;
      }
    }
  }

  private Event buildStop() {
    return buildStop("auth");
  }

  /**
   * Records a build run of {@code specId} that this box executed, and returns the stop its watcher
   * publishes: the run that makes this box the one that drives the spec's loop.
   */
  private Event stopOfABuildRunOf(String specId) {
    var id = DateTimeUtils.newId().toString();
    var unit = AgentUnit.forRun(id);
    Acting.system(
        () ->
            runStore.create(
                id,
                CONTAINER,
                specId,
                HANDLE,
                "build",
                "codex",
                "feat/test",
                "work",
                null,
                null,
                unit.logPath(),
                unit.unitName()));
    return RunStops.of(
        Event.WellKnownData.SOURCE_WATCHER, CONTAINER, specId, "codex", id, "build", 0, null);
  }

  private Event buildStop(String specId) {
    return RunStops.of(
        Event.WellKnownData.SOURCE_WATCHER, CONTAINER, specId, "codex", null, "build", 0, null);
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
    publishStop(exitOf(run));
  }

  /** Waits for {@code run}'s unit to go inactive and reads its exit as the watcher reads it. */
  private AgentSession.ExitState exitOf(RunStore.RunRow run) throws Exception {
    var session = new AgentSession(shell);
    var unit = AgentUnit.forRun(run.id());
    var ended = new AtomicReference<AgentSession.ExitState>();
    eventually(
        () -> {
          var exit = session.answeredExitStatus(CONTAINER, unit);
          exit.ifPresent(ended::set);
          return exit.filter(state -> !state.active() && run.id().equals(state.runId()))
              .isPresent();
        },
        "the " + run.role() + " unit exits");
    var exit = ended.get();
    assertEquals(run.role(), exit.role(), "the stop names the lane of the run it ends");
    return exit;
  }

  /** Publishes the stop the watcher would for a run that exited on its own. */
  private void publishStop(AgentSession.ExitState exit) {
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
  }

  /** Records every event the loop publishes, in order, for {@link #awaitEvent}. */
  private EventSubscriber listener() {
    return new EventSubscriber() {
      @Override
      public String name() {
        return "review-agent-loop-it";
      }

      @Override
      public Predicate<Event> filter() {
        return event -> true;
      }

      @Override
      public void onEvent(Event event) {
        heard.add(event);
      }
    };
  }

  /**
   * Blocks until the loop publishes an event of {@code type}, passing over what it published before
   * it: the loop says what it did after it did it, so its rows are there to read on return.
   */
  private Event awaitEvent(String type) throws InterruptedException {
    var deadline = System.nanoTime() + PATIENCE.toNanos();
    var passed = new ArrayList<String>();
    while (true) {
      var event = heard.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
      if (event == null) {
        throw new AssertionError(
            "the loop never published "
                + type
                + "; it published "
                + passed
                + "; runs: "
                + runStore.listForSpec("auth"));
      }
      if (type.equals(event.type())) {
        return event;
      }
      passed.add(event.type());
    }
  }

  /** The run the loop launched in lane {@code role}, once it has announced it with {@code type}. */
  private RunStore.RunRow launched(String role, String type) throws InterruptedException {
    return launched("auth", role, type);
  }

  /** The run the loop launched for {@code specId} in lane {@code role}, once announced. */
  private RunStore.RunRow launched(String specId, String role, String type)
      throws InterruptedException {
    awaitEvent(type);
    return runStore
        .latestLoopRun(specId)
        .filter(run -> role.equals(run.role()))
        .orElseThrow(
            () -> new AssertionError("no " + role + " run; runs: " + runStore.listForSpec(specId)));
  }

  /** Something that is true by now, or not yet. */
  @FunctionalInterface
  private interface Condition {
    boolean holds() throws Exception;
  }

  /**
   * Waits for something that publishes nothing this test could wait on: the container's state,
   * which is another machine's, or a row the loop writes without an event — the run a refused
   * review waits on. It is asked again until it holds.
   */
  private static void eventually(Condition condition, String what) throws Exception {
    var deadline = Instant.now().plus(PATIENCE);
    while (Instant.now().isBefore(deadline)) {
      if (condition.holds()) {
        return;
      }
      Thread.sleep(Duration.ofMillis(200));
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
    eventually(
        () -> exec(CONTAINER, List.of("test", "-S", "/run/user/1000/bus")).ok(),
        "the dev user's systemd manager (bus) comes up");
  }
}
