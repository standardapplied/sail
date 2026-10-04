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

import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ClaudeCodeHookConfig;
import ai.singlr.sail.engine.GuardrailChecker;
import ai.singlr.sail.engine.GuardrailTrigger;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RunWatchTest {

  private static final String PROJECT = "acme";
  private static final String RUN = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String OTHER_RUN = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  private static final AgentUnit UNIT = AgentUnit.forRun(RUN);
  private static final Instant START = Instant.parse("2026-10-03T12:00:00Z");
  private static final Guardrails REVIEW_LIMITS = Guardrails.reviewDefaults();

  /** Longer than any test's scenario: a watch still polling past it will never end. */
  private static final Duration HORIZON = Duration.ofHours(6);

  private final FakeContainer container = new FakeContainer(PROJECT);
  private final AtomicReference<Instant> time = new AtomicReference<>(START);
  private final List<Event> published = new ArrayList<>();
  private final List<Instant> publishedAt = new ArrayList<>();
  private final List<Boolean> triggerAtPublish = new ArrayList<>();
  private final List<String> told = new ArrayList<>();
  private final Timeline feed = new Timeline();

  @BeforeEach
  void aFixAgentIsRunning() throws Exception {
    container.started(RUN);
    new AgentSession(container)
        .writeSession(
            PROJECT,
            "fix it",
            "feat/auth",
            "auth",
            "claude-code",
            RUN,
            "fix",
            List.of("api"),
            UNIT);
  }

  /**
   * The project's event feed over a clock that passes as the watch waits on it, with what happens
   * in the container and on the feed scheduled against that clock.
   */
  private final class Timeline implements RunWatch.Feed {

    private final ArrayDeque<Event> queued = new ArrayDeque<>();
    private final TreeMap<Instant, Runnable> scheduled = new TreeMap<>();
    private boolean live = true;
    private boolean reachable = true;
    private int reopened;

    void at(Duration offset, Runnable happening) {
      scheduled.put(START.plus(offset), happening);
    }

    void every(Duration period, Duration until, Runnable happening) {
      for (var offset = period; offset.compareTo(until) <= 0; offset = offset.plus(period)) {
        at(offset, happening);
      }
    }

    /** A tool call of {@code runId} that returns at once: it starts and it finishes. */
    void toolCallBy(String runId) {
      toolStartedBy(runId);
      toolFinishedBy(runId);
    }

    void toolStartedBy(String runId) {
      said(Event.WellKnownTypes.AGENT_TOOL_STARTED, runId, Map.of());
    }

    void toolFinishedBy(String runId) {
      said(Event.WellKnownTypes.AGENT_TOOL_FINISHED, runId, Map.of());
    }

    /** An event of {@code type} about {@code runId} arrives on the feed. */
    void said(String type, String runId, Map<String, Object> data) {
      var named = new LinkedHashMap<>(data);
      named.put(Event.WellKnownData.RUN_ID, runId);
      queued.add(Event.of(PROJECT, "auth", type, "claude-code", "host", named));
    }

    @Override
    public Event poll(Duration wait) {
      if (Duration.between(START, time.get()).compareTo(HORIZON) > 0) {
        throw new AssertionError("the watch was still polling " + HORIZON + " in");
      }
      var wakeAt = time.get().plus(wait);
      while (true) {
        var event = queued.poll();
        if (event != null) {
          return event;
        }
        if (scheduled.isEmpty() || scheduled.firstKey().isAfter(wakeAt)) {
          time.set(wakeAt);
          return null;
        }
        var next = scheduled.pollFirstEntry();
        if (next.getKey().isAfter(time.get())) {
          time.set(next.getKey());
        }
        next.getValue().run();
      }
    }

    @Override
    public boolean live() {
      return live;
    }

    @Override
    public boolean reopen() {
      reopened++;
      live = reachable;
      return live;
    }
  }

  private void watch(Guardrails guardrails) throws Exception {
    watch(guardrails, START, false, this::publish);
  }

  private void watch(
      Guardrails guardrails, Instant startedAt, boolean dryRun, RunWatch.StopPublisher publisher)
      throws Exception {
    new RunWatch(
            PROJECT,
            RUN,
            UNIT,
            guardrails,
            startedAt,
            dryRun,
            container,
            feed,
            publisher,
            narrator(),
            time::get)
        .run();
  }

  private void publish(Event event) {
    published.add(event);
    publishedAt.add(time.get());
    triggerAtPublish.add(container.file(UNIT.guardrailTriggerPath()) != null);
  }

  private RunWatch.Narrator narrator() {
    return new RunWatch.Narrator() {
      @Override
      public void tripped(
          GuardrailChecker.GuardrailResult.Triggered limit, Duration elapsed, String snapshot) {
        told.add("tripped " + limit.cause() + (snapshot.isEmpty() ? "" : " after a snapshot"));
      }

      @Override
      public void exited() {
        told.add("exited");
      }

      @Override
      public void ended() {
        told.add("ended");
      }
    };
  }

  private Duration elapsed() {
    return Duration.between(START, time.get());
  }

  private int kills() {
    return container.commandsContaining("--signal=SIGTERM " + UNIT.service()).size();
  }

  private GuardrailTrigger recordedTrigger() throws Exception {
    return GuardrailTrigger.read(container, PROJECT, UNIT).orElse(null);
  }

  private int indexOfCommand(String fragment) {
    var commands = container.commands();
    for (var i = 0; i < commands.size(); i++) {
      if (String.join(" ", commands.get(i)).contains(fragment)) {
        return i;
      }
    }
    return -1;
  }

  @Test
  void aRunThatExitsOnItsOwnIsReportedWithItsExitCodeWithinOnePoll() throws Exception {
    feed.at(Duration.ofMinutes(5), () -> container.exited(RUN, "crashed", 3));

    watch(REVIEW_LIMITS);

    var stop = published.getFirst();
    assertEquals(1, published.size());
    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, stop.type());
    assertEquals(3, stop.data().get("exit_code"));
    assertNull(stop.data().get("reason"));
    assertEquals(RUN, stop.data().get("run_id"));
    assertEquals("fix", stop.data().get("run_role"));
    assertEquals("auth", stop.spec());
    assertEquals(0, kills(), "nothing ended this run but itself");
    assertNull(recordedTrigger());
    assertTrue(
        elapsed().compareTo(Duration.ofMinutes(5).plus(RunWatch.LIVENESS_POLL)) <= 0,
        "noticed within one liveness poll, not at a limit: " + elapsed());
    assertEquals(List.of("exited"), told);
  }

  @Test
  void aRunPastItsTimeLimitIsKilledThenItsTriggerRecordedThenItsStopPublishedWithTheReason()
      throws Exception {
    feed.every(Duration.ofMinutes(5), Duration.ofHours(1), () -> feed.toolCallBy(RUN));

    watch(REVIEW_LIMITS);

    var kill = indexOfCommand("kill --kill-who=all --signal=SIGTERM " + UNIT.service());
    var trigger = indexOfCommand(UNIT.guardrailTriggerPath());
    assertTrue(kill >= 0, "the unit's whole cgroup is signalled");
    assertTrue(trigger > kill, "the trigger is recorded beside the run, after the kill");
    assertEquals(List.of(true), triggerAtPublish, "and before the stop is published");
    assertFalse(container.alive(RUN), "a reaped agent is dead before anything says it ended");
    var stop = published.getFirst();
    assertEquals(1, published.size());
    assertEquals("time limit (45m)", stop.data().get("reason"));
    assertEquals("watcher", stop.data().get("source"));
    assertEquals(RUN, stop.data().get("run_id"));
    assertEquals("fix", stop.data().get("run_role"));
    assertNull(
        stop.data().get("exit_code"),
        "a run the watcher killed has no exit code of its own; the reason says why it ended");
    assertEquals("time limit (45m)", recordedTrigger().cause());
    assertEquals("max_duration", recordedTrigger().reason());
    assertTrue(recordedTrigger().stops());
    assertFalse(elapsed().compareTo(Duration.ofMinutes(45)) < 0, "not before its limit");
    assertTrue(elapsed().compareTo(Duration.ofMinutes(46)) < 0, "and at it: " + elapsed());
    assertEquals(List.of("tripped time limit (45m)", "ended"), told);
  }

  @Test
  void aRunThatCallsNoToolForItsStallWindowIsKilledForTheStall() throws Exception {
    feed.at(Duration.ofMinutes(10), () -> feed.toolCallBy(RUN));

    watch(REVIEW_LIMITS);

    assertEquals("stall (20m)", published.getFirst().data().get("reason"));
    assertEquals("stall", recordedTrigger().reason());
    assertFalse(container.alive(RUN));
    assertFalse(
        elapsed().compareTo(Duration.ofMinutes(30)) < 0,
        "the window is measured from the run's last tool call, at 10m: " + elapsed());
    assertTrue(elapsed().compareTo(Duration.ofMinutes(31)) < 0, elapsed().toString());
  }

  @Test
  void aRunThatKeepsCallingToolsIsNeverStalled() throws Exception {
    feed.every(Duration.ofMinutes(15), Duration.ofMinutes(40), () -> feed.toolCallBy(RUN));
    feed.at(Duration.ofMinutes(40), () -> container.exited(RUN, "done", 0));

    watch(REVIEW_LIMITS);

    assertEquals(0, kills(), "forty minutes of work with a tool call every fifteen is no stall");
    assertEquals(0, published.getFirst().data().get("exit_code"));
    assertNull(published.getFirst().data().get("reason"));
  }

  @Test
  void anotherRunsToolCallsNeverKeepAStalledRunAlive() throws Exception {
    feed.every(Duration.ofMinutes(1), Duration.ofHours(1), () -> feed.toolCallBy(OTHER_RUN));

    watch(REVIEW_LIMITS);

    assertEquals("stall (20m)", published.getFirst().data().get("reason"));
    assertTrue(elapsed().compareTo(Duration.ofMinutes(21)) < 0, elapsed().toString());
  }

  @Test
  void aBusyProjectNeverDelaysNoticingThatTheRunEnded() throws Exception {
    feed.every(Duration.ofSeconds(5), Duration.ofMinutes(10), () -> feed.toolCallBy(OTHER_RUN));
    feed.at(Duration.ofMinutes(2), () -> container.exited(RUN, "done", 0));

    watch(REVIEW_LIMITS);

    assertEquals(0, published.getFirst().data().get("exit_code"));
    assertTrue(
        Duration.between(START, publishedAt.getFirst())
                .compareTo(Duration.ofMinutes(2).plus(RunWatch.LIVENESS_POLL))
            <= 0,
        "liveness is polled on the clock, however many events another run sends meanwhile: "
            + Duration.between(START, publishedAt.getFirst()));
  }

  @Test
  void aRunThatSurvivesTheKillIsNeverReportedEndedAndIsKilledAgainAtTheNextPoll() throws Exception {
    container.survivesKill(true);
    feed.at(Duration.ofMinutes(21), () -> container.survivesKill(false));

    watch(new Guardrails(null, "20m", "snapshot-and-stop"));

    assertEquals(1, published.size());
    assertEquals("stall (20m)", published.getFirst().data().get("reason"));
    assertFalse(
        Duration.between(START, publishedAt.getFirst()).compareTo(Duration.ofMinutes(21)) < 0,
        "no stop while the agent could still push work");
    assertFalse(container.alive(RUN));
    var attempts = kills();
    assertTrue(
        attempts >= 3 && attempts <= 6,
        "a minute of survival is a kill at every liveness poll, not a kill in a tight loop: "
            + attempts);
    assertEquals(
        1,
        container.commandsContaining("incus snapshot create").size(),
        "the snapshot is the run's state at its limit, taken once");
    assertEquals(List.of("tripped stall (20m) after a snapshot", "ended"), told);
  }

  @Test
  void aKillTheContainerRefusesIsTriedAgainAtTheNextPollNotFatalToTheWatch() throws Exception {
    container.refusesKill(true);
    feed.at(Duration.ofMinutes(21), () -> container.refusesKill(false));

    watch(new Guardrails(null, "20m", "stop"));

    assertEquals(1, published.size(), "the watcher outlived the refusal and ended the run");
    assertEquals("stall (20m)", published.getFirst().data().get("reason"));
    assertFalse(
        Duration.between(START, publishedAt.getFirst()).compareTo(Duration.ofMinutes(21)) < 0);
    assertFalse(container.alive(RUN));
  }

  @Test
  void aRunThatEndsItselfAfterSurvivingAKillIsStillStoppedForItsLimit() throws Exception {
    container.survivesKill(true);
    feed.at(Duration.ofMinutes(21), () -> container.exited(RUN, "half done", 0));

    watch(new Guardrails(null, "20m", "stop"));

    assertEquals(1, published.size());
    assertEquals(
        "stall (20m)",
        published.getFirst().data().get("reason"),
        "a run past its limit and under a kill did not end well, however it ended");
    assertNull(published.getFirst().data().get("exit_code"));
    assertEquals("stall (20m)", recordedTrigger().cause());
    assertEquals(
        List.of("tripped stall (20m)", "ended"), told, "and it is told as the trip it was");
  }

  @Test
  void aContainerThatDoesNotAnswerIsNeverTakenForAKilledAgent() throws Exception {
    feed.at(Duration.ofMinutes(19), () -> container.unreachable(true));
    feed.at(Duration.ofMinutes(25), () -> container.unreachable(false));

    watch(new Guardrails(null, "20m", "stop"));

    assertEquals(1, published.size());
    assertFalse(
        Duration.between(START, publishedAt.getFirst()).compareTo(Duration.ofMinutes(25)) < 0,
        "while incus could not reach the container nothing said the agent was gone, so nothing"
            + " said the run had ended: "
            + Duration.between(START, publishedAt.getFirst()));
    assertEquals("stall (20m)", published.getFirst().data().get("reason"));
    assertFalse(container.alive(RUN), "the stop is published once the container says it is gone");
    assertEquals(List.of(true), triggerAtPublish);
  }

  @Test
  void aRunWhoseUnitManagerDiedWithItsAgentEndsTheWatchWithNoStop() throws Exception {
    feed.at(
        Duration.ofMinutes(5),
        () -> {
          container.exited(RUN, "half done", 0);
          container.managerDown(true);
        });

    watch(REVIEW_LIMITS);

    assertTrue(
        published.isEmpty(),
        "the manager that held the unit is gone and the agent's process with it: nothing says how"
            + " it ended, and a watcher that polled on would keep the reconciler from saying so");
    assertTrue(
        elapsed().compareTo(Duration.ofMinutes(5).plus(RunWatch.LIVENESS_POLL)) <= 0,
        "noticed at the next poll: " + elapsed());
  }

  @Test
  void aLimitCrossedWhileTheManagerIsSilentIsEnforcedOnceItAnswersAndNoKillIsTriedBlind()
      throws Exception {
    container.beforeHost("incus snapshot create", () -> container.managerDown(true));
    feed.at(Duration.ofMinutes(25), () -> container.managerDown(false));

    watch(new Guardrails(null, "20m", "snapshot-and-stop"));

    assertEquals(
        1,
        kills(),
        "no kill is tried while nothing can say whether it worked: the one kill is the one that"
            + " is verified");
    assertEquals(1, container.commandsContaining("incus snapshot create").size());
    assertEquals(
        1,
        published.size(),
        "the manager went silent before the kill: an agent nothing says is dead is still"
            + " watched");
    assertFalse(
        Duration.between(START, publishedAt.getFirst()).compareTo(Duration.ofMinutes(25)) < 0,
        "and it is ended, for its limit, once the manager answers again");
    assertEquals("stall (20m)", published.getFirst().data().get("reason"));
    assertFalse(container.alive(RUN));
  }

  @Test
  void anAgentThatEndsItselfBeforeAnyKillWasTriedIsNotReportedKilled() throws Exception {
    container.beforeHost("incus snapshot create", () -> container.managerDown(true));
    feed.at(
        Duration.ofMinutes(21),
        () -> {
          container.exited(RUN, "fixed and pushed", 0);
          container.managerDown(false);
        });

    watch(new Guardrails(null, "20m", "snapshot-and-stop"));

    assertEquals(0, kills(), "the manager never answered while the agent lived: no kill was sent");
    assertEquals(1, published.size());
    assertNull(
        published.getFirst().data().get("reason"),
        "a limit crossed and never enforced ended nothing: the agent exited on its own, and its"
            + " work is its to keep");
    assertEquals(0, published.getFirst().data().get("exit_code"));
    assertNull(recordedTrigger());
    assertEquals(List.of("exited"), told);
  }

  @Test
  void aLiveAgentWhoseUnitManagerDoesNotAnswerIsStillWatched() throws Exception {
    feed.at(Duration.ofMinutes(5), () -> container.managerDown(true));
    feed.at(Duration.ofMinutes(8), () -> container.managerDown(false));
    feed.at(Duration.ofMinutes(10), () -> container.exited(RUN, "done", 0));

    watch(REVIEW_LIMITS);

    assertEquals(1, published.size(), "the agent's process was there all along: the watch held");
    assertEquals(0, published.getFirst().data().get("exit_code"));
    assertFalse(
        Duration.between(START, publishedAt.getFirst()).compareTo(Duration.ofMinutes(10)) < 0);
  }

  @Test
  void anAgentNoSignalReachedThatThenExitsOnItsOwnIsReportedAsThePlainExitItWas() throws Exception {
    container.undeliverable(true);
    feed.at(Duration.ofMinutes(21), () -> container.exited(RUN, "fixed and pushed", 0));

    watch(new Guardrails(null, "20m", "stop"));

    assertTrue(kills() >= 1, "the limit was crossed and a kill was sent");
    assertEquals(1, published.size());
    assertEquals(
        0,
        published.getFirst().data().get("exit_code"),
        "no signal was ever delivered: the agent ended itself, and its finished work is its own");
    assertNull(published.getFirst().data().get("reason"));
    assertNull(recordedTrigger(), "a kill that never landed ended nothing");
    assertEquals(List.of("exited"), told);
  }

  @Test
  void aKillNothingAnsweredForKeepsThePidFileAndIsAskedAgainAtTheNextPoll() throws Exception {
    var duringTheSilence = new ArrayList<String>();
    container.survivesKill(true);
    container.afterSigterm(() -> container.managerDown(true));
    feed.at(
        Duration.ofMinutes(21),
        () -> {
          duringTheSilence.add("pid file " + container.file(UNIT.pidPath()));
          duringTheSilence.add(
              "sigkills " + container.commandsContaining("--signal=SIGKILL").size());
          duringTheSilence.add("resets " + container.commandsContaining("reset-failed").size());
          duringTheSilence.add("reconciler reads it active: " + reconcilerReadsItActive());
          duringTheSilence.add("published " + published.size());
        });
    feed.at(
        Duration.ofMinutes(22),
        () -> {
          container.managerDown(false);
          container.survivesKill(false);
        });

    watch(new Guardrails(null, "20m", "stop"));

    assertEquals(
        List.of(
            "pid file " + container.pidOf(RUN),
            "sigkills 0",
            "resets 0",
            "reconciler reads it active: true",
            "published 0"),
        duringTheSilence,
        "a manager that went silent after the SIGTERM said nothing of the agent: nothing was"
            + " removed, reset or escalated, so nothing reads the live agent as gone");
    assertEquals(1, published.size(), "the watcher asked again once the manager answered");
    assertEquals("stall (20m)", published.getFirst().data().get("reason"));
    assertFalse(
        Duration.between(START, publishedAt.getFirst()).compareTo(Duration.ofMinutes(22)) < 0);
    assertNull(container.file(UNIT.pidPath()), "the pid file goes once the agent is known gone");
  }

  private boolean reconcilerReadsItActive() {
    try {
      return MissedStopReconciler.systemdUnitProbe(container).active(PROJECT, RUN, UNIT.unitName());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void aWatcherPollingASilentManagerEndsWhenItsRunsStopIsPublishedByTheReconciler()
      throws Exception {
    feed.at(Duration.ofMinutes(5), () -> container.managerDown(true));
    feed.at(
        Duration.ofMinutes(8),
        () ->
            feed.said(
                Event.WellKnownTypes.AGENT_SESSION_STOPPED,
                RUN,
                Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_RECONCILE)));

    watch(REVIEW_LIMITS);

    assertTrue(published.isEmpty(), "the run's stop was said: the watcher says nothing more");
    assertNull(recordedTrigger());
    assertTrue(told.isEmpty(), told.toString());
    assertTrue(
        elapsed().compareTo(Duration.ofMinutes(8).plus(RunWatch.LIVENESS_POLL)) <= 0,
        "the watch ended with its run, not hours later at a limit: " + elapsed());
  }

  @Test
  void aWatcherEndsWhenItsRunsCancelIsPublished() throws Exception {
    feed.at(
        Duration.ofMinutes(8),
        () ->
            feed.said(
                Event.WellKnownTypes.AGENT_CANCELLED,
                RUN,
                Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_OPERATOR)));

    watch(REVIEW_LIMITS);

    assertTrue(published.isEmpty(), "an operator ended the run: its watcher publishes nothing");
    assertNull(recordedTrigger());
    assertEquals(0, kills());
    assertTrue(elapsed().compareTo(Duration.ofMinutes(8).plus(RunWatch.LIVENESS_POLL)) <= 0);
  }

  @Test
  void theAgentsOwnTurnEndStopAndAnotherRunsEndNeverEndTheWatch() throws Exception {
    feed.at(
        Duration.ofMinutes(5),
        () -> {
          feed.said(Event.WellKnownTypes.AGENT_SESSION_STOPPED, RUN, Map.of());
          feed.said(
              Event.WellKnownTypes.AGENT_SESSION_STOPPED,
              OTHER_RUN,
              Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_WATCHER));
          feed.said(
              Event.WellKnownTypes.AGENT_CANCELLED,
              OTHER_RUN,
              Map.of(Event.WellKnownData.SOURCE, Event.WellKnownData.SOURCE_OPERATOR));
        });
    feed.at(Duration.ofMinutes(10), () -> container.exited(RUN, "done", 0));

    watch(REVIEW_LIMITS);

    assertEquals(1, published.size(), "a turn boundary is not the run's end, nor is another run's");
    assertEquals(0, published.getFirst().data().get("exit_code"));
    assertFalse(
        Duration.between(START, publishedAt.getFirst()).compareTo(Duration.ofMinutes(10)) < 0);
  }

  @Test
  void aToolCallLongerThanTheStallWindowIsWorkNotAStall() throws Exception {
    feed.at(Duration.ofMinutes(5), () -> feed.toolStartedBy(RUN));
    feed.at(Duration.ofMinutes(35), () -> feed.toolFinishedBy(RUN));
    feed.at(Duration.ofMinutes(36), () -> container.exited(RUN, "verified and pushed", 0));

    watch(new Guardrails("45m", "20m", "stop"));

    assertEquals(0, kills(), "thirty minutes inside one tool call is thirty minutes of work");
    assertEquals(1, published.size());
    assertEquals(0, published.getFirst().data().get("exit_code"));
    assertNull(published.getFirst().data().get("reason"));
  }

  @Test
  void aToolCallThatNeverFinishesIsEndedAtTheWallClockLimit() throws Exception {
    feed.at(Duration.ofMinutes(5), () -> feed.toolStartedBy(RUN));

    watch(new Guardrails("45m", "20m", "stop"));

    assertEquals("time limit (45m)", published.getFirst().data().get("reason"));
    assertFalse(elapsed().compareTo(Duration.ofMinutes(45)) < 0, elapsed().toString());
    assertTrue(elapsed().compareTo(Duration.ofMinutes(46)) < 0, elapsed().toString());
  }

  @Test
  void aToolCallThatNeverFinishesIsAStallWhenNoWallClockLimitBoundsTheRun() throws Exception {
    feed.toolStartedBy(RUN);

    watch(new Guardrails(null, "20m", "stop"));

    assertEquals("stall (20m)", published.getFirst().data().get("reason"));
    assertFalse(elapsed().compareTo(Duration.ofMinutes(20)) < 0, elapsed().toString());
    assertTrue(elapsed().compareTo(Duration.ofMinutes(21)) < 0, elapsed().toString());
  }

  @Test
  void oneOfTwoToolCallsStillInFlightIsNotAStall() throws Exception {
    feed.at(
        Duration.ofMinutes(5),
        () -> {
          feed.toolStartedBy(RUN);
          feed.toolStartedBy(RUN);
        });
    feed.at(Duration.ofMinutes(6), () -> feed.toolFinishedBy(RUN));
    feed.at(Duration.ofMinutes(40), () -> container.exited(RUN, "done", 0));

    watch(new Guardrails("45m", "20m", "stop"));

    assertEquals(0, kills(), "one call finished, the other is still running: the run is working");
    assertEquals(0, published.getFirst().data().get("exit_code"));
  }

  @Test
  void theStallWindowStartsWhenTheLastToolCallInFlightFinishes() throws Exception {
    feed.at(Duration.ofMinutes(5), () -> feed.toolStartedBy(RUN));
    feed.at(Duration.ofMinutes(30), () -> feed.toolFinishedBy(RUN));

    watch(new Guardrails("2h", "20m", "stop"));

    assertEquals("stall (20m)", published.getFirst().data().get("reason"));
    assertFalse(
        elapsed().compareTo(Duration.ofMinutes(50)) < 0,
        "silent since the call finished at 30m, not since it started at 5m: " + elapsed());
    assertTrue(elapsed().compareTo(Duration.ofMinutes(51)) < 0, elapsed().toString());
  }

  @Test
  void aToolCallInFlightIsStillInFlightAfterTheFeedOpensAgain() throws Exception {
    feed.at(Duration.ofMinutes(5), () -> feed.toolStartedBy(RUN));
    feed.at(Duration.ofMinutes(10), () -> feed.live = false);

    watch(new Guardrails("2h", "20m", "stop"));

    assertTrue(feed.reopened >= 1);
    assertEquals(
        "time limit (2h)",
        published.getFirst().data().get("reason"),
        "the call that started before the feed went down is still running after it came back");
  }

  @Test
  void aToolCallThatFinishesTwiceNeverCountsBelowNone() {
    var inFlight = RunWatch.toolCallsInFlight(0, Event.WellKnownTypes.AGENT_TOOL_FINISHED);

    assertEquals(0, inFlight);
    assertEquals(1, RunWatch.toolCallsInFlight(inFlight, Event.WellKnownTypes.AGENT_TOOL_STARTED));
    assertEquals(1, RunWatch.toolCallsInFlight(1, Event.WellKnownTypes.AGENT_LOG_CHUNK));
  }

  @Test
  void aStoppedContainerEndsTheWatchWithNoStopSinceNothingSaysHowTheRunEnded() throws Exception {
    feed.at(Duration.ofMinutes(5), container::stopped);

    watch(REVIEW_LIMITS);

    assertTrue(
        published.isEmpty(),
        "a stopped container cannot say how the run ended: the reconciler, which reads the"
            + " container's state, speaks for it");
    assertTrue(told.isEmpty());
    assertTrue(
        elapsed().compareTo(Duration.ofMinutes(5).plus(RunWatch.LIVENESS_POLL)) <= 0,
        "noticed at the next poll, not at a limit: " + elapsed());
  }

  @Test
  void aRunThatFinishedWhileItsSnapshotWasTakenIsNotReportedKilled() throws Exception {
    container.beforeHost("incus snapshot create", () -> container.exited(RUN, "done", 0));

    watch(new Guardrails(null, "20m", "snapshot-and-stop"));

    assertEquals(0, kills(), "the agent ended itself before the kill");
    assertEquals(0, published.getFirst().data().get("exit_code"));
    assertNull(published.getFirst().data().get("reason"));
    assertNull(recordedTrigger());
    assertEquals(List.of("exited"), told);
  }

  @Test
  void aLimitThatOnlyNotifiesIsSaidOnceAndLeavesTheRunToEndItself() throws Exception {
    feed.at(Duration.ofMinutes(50), () -> container.exited(RUN, "done", 0));

    watch(new Guardrails("45m", "20m", "notify"));

    assertEquals(0, kills());
    assertEquals(List.of("tripped stall (20m)", "exited"), told);
    assertEquals(0, published.getFirst().data().get("exit_code"));
    assertNull(published.getFirst().data().get("reason"), "a notified run ended on its own");
    assertFalse(recordedTrigger().stops(), "the record says the limit was only announced");
  }

  @Test
  void aDryRunOfAStoppingLimitKillsNothingAndSaysWhatItWouldDo() throws Exception {
    watch(REVIEW_LIMITS, START, true, this::publish);

    assertEquals(0, kills());
    assertTrue(container.alive(RUN));
    assertTrue(published.isEmpty());
    assertEquals(List.of("tripped stall (20m)", "ended"), told);
  }

  @Test
  void aFeedThatEndedIsOpenedAgainAndItsSilenceIsNeverCountedAsAStall() throws Exception {
    feed.at(
        Duration.ofMinutes(5),
        () -> {
          feed.live = false;
          feed.reachable = false;
        });
    feed.at(Duration.ofMinutes(35), () -> feed.reachable = true);
    feed.at(Duration.ofMinutes(40), () -> container.exited(RUN, "done", 0));

    watch(new Guardrails(null, "20m", "stop"));

    assertEquals(
        0,
        kills(),
        "thirty minutes with no feed — a daemon restart — is not thirty minutes of stall");
    assertTrue(feed.reopened > 1, "the feed is tried again at every poll until it opens");
    assertTrue(feed.live);
    assertEquals(0, published.getFirst().data().get("exit_code"));
  }

  @Test
  void theStallWindowStartsAfreshWhenTheFeedOpensAgain() throws Exception {
    feed.at(
        Duration.ofMinutes(5),
        () -> {
          feed.live = false;
          feed.reachable = false;
        });
    feed.at(Duration.ofMinutes(35), () -> feed.reachable = true);

    watch(new Guardrails(null, "20m", "stop"));

    assertEquals("stall (20m)", published.getFirst().data().get("reason"));
    assertFalse(
        elapsed().compareTo(Duration.ofMinutes(55)) < 0,
        "twenty minutes of observed silence, counted from when the feed came back: " + elapsed());
  }

  @Test
  void theWallClockRunsOnWhileTheFeedIsDown() throws Exception {
    feed.live = false;
    feed.reachable = false;

    watch(REVIEW_LIMITS);

    assertEquals("time limit (45m)", published.getFirst().data().get("reason"));
    assertFalse(container.alive(RUN));
  }

  @Test
  void aStopThatCannotBePublishedLeavesItsTriggerBesideTheRun() throws Exception {
    assertDoesNotThrow(
        () ->
            watch(
                REVIEW_LIMITS,
                START,
                false,
                event -> {
                  throw new IllegalStateException("connection refused");
                }));

    assertFalse(container.alive(RUN));
    assertEquals(
        "stall (20m)",
        recordedTrigger().cause(),
        "the record needs no daemon: whoever reconstructs the stop reads why the run ended");
  }

  @Test
  void aWatcherArmedOntoARunUnderWayHoldsItToWhatIsLeftOfItsBudget() throws Exception {
    feed.every(Duration.ofMinutes(1), Duration.ofHours(1), () -> feed.toolCallBy(RUN));

    watch(REVIEW_LIMITS, START.minus(Duration.ofMinutes(40)), false, this::publish);

    assertEquals("time limit (45m)", published.getFirst().data().get("reason"));
    assertTrue(
        elapsed().compareTo(Duration.ofMinutes(6)) < 0,
        "forty minutes in, five are left, not a fresh forty-five: " + elapsed());
  }

  @Test
  void theDeadlineIsTheSessionsStartPlusItsLimitAndNeverWithNone() {
    assertEquals(START.plusSeconds(30 * 60), RunWatch.deadline(START, "30m"));
    assertEquals(Instant.MAX, RunWatch.deadline(START, null));
  }

  @Test
  void toolCallsAndLogChunksAreProgressAndNothingElseIs() {
    assertTrue(RunWatch.isProgressEvent(sampleEvent(Event.WellKnownTypes.AGENT_TOOL_STARTED)));
    assertTrue(RunWatch.isProgressEvent(sampleEvent(Event.WellKnownTypes.AGENT_TOOL_FINISHED)));
    assertTrue(RunWatch.isProgressEvent(sampleEvent(Event.WellKnownTypes.AGENT_LOG_CHUNK)));
    assertFalse(RunWatch.isProgressEvent(sampleEvent(Event.WellKnownTypes.SNAPSHOT_CREATED)));
  }

  @Test
  void anUnstampedEventNeverResetsARunScopedStallTimer() {
    assertFalse(RunWatch.matchesRun(sampleEvent(Event.WellKnownTypes.AGENT_TOOL_STARTED), RUN));
  }

  @Test
  void toolProgressEventsTheWatcherResetsOnAreActuallyEmittedByTheHookConfig() {
    var hookJson = ClaudeCodeHookConfig.render();

    assertTrue(
        hookJson.contains(Event.WellKnownTypes.AGENT_TOOL_STARTED)
            && RunWatch.isProgressEvent(sampleEvent(Event.WellKnownTypes.AGENT_TOOL_STARTED)),
        "the stall watcher resets on agent_tool_started, so the hook config must emit it — "
            + "otherwise a busy agent is killed at max_idle");
    assertTrue(
        hookJson.contains(Event.WellKnownTypes.AGENT_TOOL_FINISHED)
            && RunWatch.isProgressEvent(sampleEvent(Event.WellKnownTypes.AGENT_TOOL_FINISHED)),
        "the stall watcher resets on agent_tool_finished, so the hook config must emit it");
  }

  @Test
  void aStopCarriesExitCodeSpecAndAgent() {
    var exit = new AgentSession.ExitState(false, 137, "scrum-12", "claude-code", "run-12", "build");

    var event = RunWatch.stop(PROJECT, exit, null);

    assertEquals(Event.WellKnownTypes.AGENT_SESSION_STOPPED, event.type());
    assertEquals(PROJECT, event.project());
    assertEquals("scrum-12", event.spec());
    assertEquals("claude-code", event.agent());
    assertEquals(137, event.data().get("exit_code"));
    assertEquals("watcher", event.data().get("source"));
    assertEquals("run-12", event.data().get("run_id"));
    assertEquals("build", event.data().get("run_role"));
  }

  @Test
  void aStopCarriesTheRoomRoleSoThePipelineCanIgnoreTheChat() {
    var exit = new AgentSession.ExitState(false, 0, "scrum-12", "claude-code", "run-12", "room");

    assertEquals("room", RunWatch.stop(PROJECT, exit, null).data().get("run_role"));
  }

  @Test
  void aStopOmitsRunIdWhenTheSessionHadNone() {
    var exit = new AgentSession.ExitState(false, 0, "scrum-12", "claude-code", "", "");

    var event = RunWatch.stop(PROJECT, exit, null);

    assertNull(event.data().get("run_id"));
    assertNull(event.data().get("run_role"));
  }

  @Test
  void aStopFallsBackToSailAgentWhenTheTypeIsUnknown() {
    var exit = new AgentSession.ExitState(false, 0, "scrum-12", "", "run-12", "");

    var event = RunWatch.stop(PROJECT, exit, null);

    assertEquals(Event.SAIL_AGENT, event.agent());
    assertEquals(0, event.data().get("exit_code"));
  }

  @Test
  void aStopIsPublishedForARunThatWorksNoSpec() {
    var captured = new AtomicReference<Event>();
    var exit = new AgentSession.ExitState(false, 3, "", "codex", RUN, "");

    RunWatch.emitStop(captured::set, PROJECT, exit, null);

    var event = captured.get();
    assertNotNull(event, "a run-addressed session with no spec must still publish its stop");
    assertNull(event.spec());
    assertEquals(RUN, event.data().get("run_id"));
    assertEquals(3, event.data().get("exit_code"));
  }

  @Test
  void noStopIsPublishedWhenNeitherSpecNorRunIsKnown() {
    var captured = new AtomicReference<Event>();
    var exit = new AgentSession.ExitState(false, 0, "", "codex", "", "");

    RunWatch.emitStop(captured::set, PROJECT, exit, null);

    assertNull(captured.get());
  }

  @Test
  void aWatcherWithNoPublisherPublishesNothing() {
    var exit = new AgentSession.ExitState(false, 0, "scrum-7", "codex", "run-7", "build");

    assertDoesNotThrow(() -> RunWatch.emitStop(null, PROJECT, exit, null));
  }

  @Test
  void aCleanExitOnACollectedUnitStillPublishesASpecAttributedStop() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "systemctl --user show " + UNIT.service(),
                """
                ActiveState=inactive
                ExecMainStatus=0
                Environment=
                """)
            .onOk(
                "cat " + UNIT.sessionPath(),
                """
                {"task":"t","branch":"b","spec_id":"auth","agent_type":"claude-code","run_id":"%s","started_at":"2026-06-30T16:55:14Z","log_path":"%s"}
                """
                    .formatted(RUN, UNIT.logPath()));
    var captured = new AtomicReference<Event>();

    RunWatch.emitStop(
        captured::set, PROJECT, new AgentSession(shell).queryExitStatus(PROJECT, UNIT), null);

    assertEquals("auth", captured.get().spec());
    assertEquals("claude-code", captured.get().agent());
    assertEquals(0, captured.get().data().get("exit_code"));
    assertEquals(RUN, captured.get().data().get("run_id"));
  }

  @Test
  void aStopEndsTheRunTheWatcherWasStartedForWhateverTheSessionFileNames() {
    var forged =
        new AgentSession.ExitState(false, 0, "auth", "claude-code", "another-run", "review");

    var addressed = RunWatch.addressedTo(RUN, forged);

    assertEquals(RUN, addressed.runId(), "the session file is the agent's to write");
    assertEquals(RUN, RunWatch.stop(PROJECT, addressed, null).data().get("run_id"));
  }

  @Test
  void anAgentThatDiedBeforeItsWatcherAttachedHasItsStopPublishedWithTheCodeItsUnitHolds()
      throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "systemctl --user show " + UNIT.service(),
                """
                ActiveState=failed
                ExecMainStatus=127
                Environment=SAIL_SPEC_ID=auth SAIL_AGENT=codex SAIL_RUN_ID=%s SAIL_RUN_ROLE=fix
                """
                    .formatted(RUN));

    var stopped =
        RunWatch.stopIfAlreadyEnded(PROJECT, RUN, UNIT, new AgentSession(shell), this::publish);

    assertTrue(stopped);
    assertEquals(127, published.getFirst().data().get("exit_code"));
    assertEquals("fix", published.getFirst().data().get("run_role"));
    assertEquals(RUN, published.getFirst().data().get("run_id"));
  }

  @Test
  void aWatcherStartedForARunThatNeverLaunchedPublishesNothing() throws Exception {
    var stopped =
        RunWatch.stopIfAlreadyEnded(
            PROJECT, RUN, UNIT, new AgentSession(new ScriptedShellExecutor()), this::publish);

    assertFalse(stopped, "no unit and no session file name this run");
    assertTrue(published.isEmpty());
  }

  private static Event sampleEvent(String type) {
    return Event.of("test-project", "test-spec", type, "claude-code", "host-01", Map.of());
  }
}
