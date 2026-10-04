/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Guardrails;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.GuardrailChecker;
import ai.singlr.sail.engine.GuardrailChecker.GuardrailResult.Triggered;
import ai.singlr.sail.engine.GuardrailTrigger;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SnapshotManager;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The watch over one agent run, whichever lane launched it: the loop {@code sail agent watch} runs
 * until the run ends, and the one place a run is ended for a limit. It holds the run to its
 * guardrails — a wall-clock ceiling ({@code max_duration}) anchored to when the run's row says it
 * started, and a stall window ({@code max_idle}) measured from the run's last progress event — and
 * publishes the authoritative {@code agent_session_stopped} that finishes the run and that the
 * review pipeline advances on.
 *
 * <p>The loop wakes for an event, for a limit, and at least every {@link #LIVENESS_POLL} to ask the
 * container whether the unit is still active — on the clock, not on a quiet feed, so another run's
 * events in the same project never delay noticing that this one ended. A run ends here one of two
 * ways. The agent exits on its own: the stop carries its exit code. Or a limit with a stopping
 * action is crossed: the unit's whole cgroup is killed, and only once the container itself answers
 * that the unit is gone is the trigger recorded beside the run and the stop published, carrying
 * why. A run is never reported ended while its agent can still push work: a unit that survives the
 * kill is killed again at the next poll, and whenever it does end its stop still says it was ended
 * for its limit. No kill is tried while the container cannot say whether it worked; the limit is
 * enforced once it answers. A kill counts as tried only once a signal was delivered and the
 * container answered what it did ({@link AgentSession.Halt}): an agent no signal reached, or whose
 * halt nothing answered for, that then ends exited on its own, and its stop says so. Silence is
 * never an agent's death: a container that does not answer is asked again. One found stopped, or
 * one that answers while the unit's manager and the agent's process are both gone, ends the watch
 * with no stop, since nothing there can say how the run ended; the missed-stop reconciler speaks
 * for that run.
 *
 * <p>The watch ends with its run. Whoever publishes the run's authoritative stop or its cancel — an
 * operator's stop, the reconciler speaking for a run whose manager went silent for good — ends the
 * watch too: it returns, publishing nothing and recording no trigger. The agent's own turn-end
 * stop, which carries no {@code source}, is not the run's end.
 *
 * <p>The stall window counts only time the watcher could see. It starts when the watch does —
 * idleness the watcher never observed is not idleness — and it stands still while the event feed is
 * down: a watcher outlives the daemon it listens to, and a feed that ended with a daemon restart is
 * opened again at the next poll, the stall window starting afresh. And a run with a tool call in
 * flight is working, not stalled: while a call the run started has not finished and a wall-clock
 * limit bounds the run, there is no stall deadline, and the window starts again when the last call
 * in flight finishes. With no wall-clock limit the stall window alone bounds the run, so it runs
 * through a tool call too. The wall clock runs on regardless.
 */
public final class RunWatch {

  /**
   * The longest the loop goes without asking the container whether the unit is still active, so an
   * agent that exits without its hook firing is noticed within this window rather than at a limit
   * that may be hours away.
   */
  public static final Duration LIVENESS_POLL = Duration.ofSeconds(15);

  /** The project's live events as the watch waits on them. */
  public interface Feed {

    /** The next event, or null once {@code wait} passes with none. */
    Event poll(Duration wait) throws InterruptedException;

    /** Whether events are still arriving: false once the stream behind the feed has ended. */
    boolean live();

    /** Opens a feed that ended again. Returns whether it is live now. */
    boolean reopen();
  }

  /** Where the watch publishes the stop of its run. */
  @FunctionalInterface
  public interface StopPublisher {
    void publish(Event event) throws Exception;
  }

  /** What the watch tells whoever runs it, as it happens. */
  public interface Narrator {

    /** A limit was crossed and acted on; {@code snapshot} is blank when none was taken. */
    void tripped(Triggered limit, Duration elapsed, String snapshot);

    /** The agent exited on its own and its stop was published. */
    void exited();

    /** The watch is over because a limit ended the run. */
    void ended();
  }

  private final String project;
  private final String runId;
  private final AgentUnit unit;
  private final Guardrails guardrails;
  private final Instant startedAt;
  private final boolean dryRun;
  private final ShellExec shell;
  private final AgentSession session;
  private final Feed feed;
  private final StopPublisher publisher;
  private final Narrator narrator;
  private final Supplier<Instant> clock;

  /**
   * @param unit the unit the run was launched as, as recorded on the run
   * @param guardrails the limits of the run's lane, as the project set them when the watcher was
   *     spawned
   * @param startedAt when the run started, as its run row records it: the anchor of its wall-clock
   *     limit, so a watcher armed onto a run already under way holds it to what is left of its
   *     budget, and nothing the agent can write moves it
   * @param publisher where the stop goes, or null to publish none
   */
  public RunWatch(
      String project,
      String runId,
      AgentUnit unit,
      Guardrails guardrails,
      Instant startedAt,
      boolean dryRun,
      ShellExec shell,
      Feed feed,
      StopPublisher publisher,
      Narrator narrator,
      Supplier<Instant> clock) {
    this.project = Objects.requireNonNull(project, "project");
    this.runId = Objects.requireNonNull(runId, "runId");
    this.unit = Objects.requireNonNull(unit, "unit");
    this.guardrails = Objects.requireNonNull(guardrails, "guardrails");
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
    this.dryRun = dryRun;
    this.shell = Objects.requireNonNull(shell, "shell");
    this.session = new AgentSession(shell);
    this.feed = Objects.requireNonNull(feed, "feed");
    this.publisher = publisher;
    this.narrator = Objects.requireNonNull(narrator, "narrator");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Watches the run until it ends, by its own exit or for a limit, and its stop is published — or
   * until someone else publishes its stop or its cancel, or its container is found stopped, or its
   * unit's manager gone with its process, where nothing can be read of how the run ended and it is
   * left to the missed-stop reconciler.
   */
  public void run() throws Exception {
    var maxIdle = Guardrails.parseDuration(guardrails.maxIdle());
    var wallDeadline = deadline(startedAt, guardrails.maxDuration());
    var lastProgressAt = clock.get();
    var polledAt = lastProgressAt;
    var notified = false;
    Triggered enforcing = null;
    var killTried = false;
    var snapshot = "";
    var toolCalls = 0;
    while (true) {
      var limitAt =
          notified || enforcing != null
              ? Instant.MAX
              : earlier(wallDeadline, stallDeadline(lastProgressAt, maxIdle, toolCalls));
      var nextPollAt = polledAt.plus(LIVENESS_POLL);
      var wait = until(limitAt.isAfter(polledAt) ? earlier(nextPollAt, past(limitAt)) : nextPollAt);
      var event = wait.isZero() ? null : feed.poll(wait);
      if (event != null) {
        if (!matchesRun(event, runId)) {
          continue;
        }
        if (endsTheRun(event)) {
          System.err.println(
              "  [watch] run "
                  + runId
                  + " was ended by its "
                  + event.type()
                  + "; the watch ends with it, publishing nothing");
          return;
        }
        if (isProgressEvent(event)) {
          lastProgressAt = clock.get();
          toolCalls = toolCallsInFlight(toolCalls, event.type());
        }
        continue;
      }
      var now = clock.get();
      polledAt = now;
      if (!feed.live() && feed.reopen()) {
        lastProgressAt = now;
      }
      var answered = session.answeredExitStatus(project, unit);
      if (answered.isEmpty()) {
        if (containerDown() || agentGone()) {
          System.err.println(
              "  [watch] nothing in "
                  + project
                  + " says how run "
                  + runId
                  + " ended: its container is stopped, or its unit's manager is gone and its"
                  + " process with it; the run is left to the missed-stop reconciler");
          return;
        }
        continue;
      }
      var exit = addressedTo(runId, answered.get());
      if (!exit.active()) {
        if (killTried) {
          reaped(enforcing, exit, now, snapshot);
        } else {
          emitStop(publisher, project, exit, null);
          narrator.exited();
        }
        return;
      }
      var limit =
          enforcing != null
              ? enforcing
              : crossed(now, lastProgressAt, notified, toolCalls).orElse(null);
      if (limit == null) {
        continue;
      }
      if (enforcing == null) {
        snapshot = snapshotBefore(limit);
        if (!limit.stops() || dryRun) {
          GuardrailTrigger.of(limit, now).write(shell, project, unit);
          narrator.tripped(limit, Duration.between(startedAt, now), snapshot);
          notified = true;
          if (limit.stops()) {
            narrator.ended();
            return;
          }
          continue;
        }
        var beforeTheKill = session.answeredExitStatus(project, unit);
        if (beforeTheKill.isEmpty()) {
          enforcing = limit;
          continue;
        }
        if (!beforeTheKill.get().active()) {
          emitStop(publisher, project, addressedTo(runId, beforeTheKill.get()), null);
          narrator.exited();
          return;
        }
      }
      var halt = session.killAgent(project, unit);
      killTried |= !(halt instanceof AgentSession.Halt.Unanswered);
      if (!(halt instanceof AgentSession.Halt.Ended)) {
        enforcing = limit;
        System.err.println(
            "  [watch] "
                + unit.unitName()
                + (halt instanceof AgentSession.Halt.Survived
                    ? " survived the kill for "
                    : " gave no answer to the kill for ")
                + limit.cause()
                + "; no stop published, trying again at the next poll");
        continue;
      }
      reaped(limit, exit, now, snapshot);
      return;
    }
  }

  /**
   * Whether the agent's process is gone from a container that answers, asked only when the unit's
   * own manager did not: a user manager that died took the unit with it, and nothing will ever say
   * how that unit ended. Only a process the run's pid file names and the container reports dead is
   * gone: a container that does not answer says nothing either way. The pid file is still there to
   * ask about — a kill removes it only once the agent is known gone.
   */
  private boolean agentGone() throws Exception {
    try {
      session.requireReachable(project);
    } catch (IOException unreachable) {
      return false;
    }
    var status = session.queryStatus(project, unit);
    return status != null && !status.running();
  }

  /**
   * Says a run the watcher ended for {@code limit} is over: the trigger is recorded beside the run,
   * and only then is the stop published, carrying why.
   */
  private void reaped(Triggered limit, AgentSession.ExitState exit, Instant now, String snapshot)
      throws Exception {
    GuardrailTrigger.of(limit, now).write(shell, project, unit);
    emitStop(publisher, project, exit, limit.cause());
    narrator.tripped(limit, Duration.between(startedAt, now), snapshot);
    narrator.ended();
  }

  /**
   * Whether the run's container is stopped or gone, asked only when it gave no answer about the
   * unit: a container incus cannot report on, or one that is running and merely missed a command,
   * is not down.
   */
  private boolean containerDown() throws Exception {
    var state = new ContainerManager(shell).queryState(project);
    return state instanceof ContainerState.Stopped || state instanceof ContainerState.NotCreated;
  }

  /**
   * The limit the run has crossed at {@code now}, or empty when it has crossed none: the wall clock
   * first, then the stall window — which is judged only while the feed is live, since progress a
   * dead feed could not deliver is not a stall. A limit that only notifies says so once.
   */
  private Optional<Triggered> crossed(
      Instant now, Instant lastProgressAt, boolean notified, int toolCalls) {
    if (notified) {
      return Optional.empty();
    }
    if (GuardrailChecker.checkDuration(startedAt, now, guardrails) instanceof Triggered late) {
      return Optional.of(late);
    }
    if (feed.live()
        && !working(toolCalls)
        && GuardrailChecker.checkStall(lastProgressAt, now, guardrails)
            instanceof Triggered stalled) {
      return Optional.of(stalled);
    }
    return Optional.empty();
  }

  private Instant stallDeadline(Instant lastProgressAt, Duration maxIdle, int toolCalls) {
    return maxIdle == null || working(toolCalls) ? Instant.MAX : lastProgressAt.plus(maxIdle);
  }

  /**
   * Whether the run is known to be working through {@code toolCalls} calls in flight, and so is not
   * stalled however long they take: only while a wall-clock limit bounds it, since a call that
   * never returns must still end somewhere.
   */
  private boolean working(int toolCalls) {
    return toolCalls > 0 && guardrails.maxDuration() != null;
  }

  /**
   * The run's tool calls in flight after an event of {@code type}: one more for a call that
   * started, one fewer — never below none — for one that finished.
   */
  static int toolCallsInFlight(int toolCalls, String type) {
    return switch (type) {
      case Event.WellKnownTypes.AGENT_TOOL_STARTED -> toolCalls + 1;
      case Event.WellKnownTypes.AGENT_TOOL_FINISHED -> Math.max(0, toolCalls - 1);
      default -> toolCalls;
    };
  }

  /**
   * Whether {@code event}, one of the watched run's own, says the run is over: its cancel, or its
   * authoritative stop. A stop with no {@code source} is the agent's turn-end hook, not its end.
   */
  static boolean endsTheRun(Event event) {
    return switch (event.type()) {
      case Event.WellKnownTypes.AGENT_CANCELLED -> true;
      case Event.WellKnownTypes.AGENT_SESSION_STOPPED ->
          event.data().get(Event.WellKnownData.SOURCE) != null;
      default -> false;
    };
  }

  /**
   * The first instant at which a limit that runs out at {@code limitAt} reads as crossed: a run is
   * past its limit only once it has outlasted it, so waking exactly at the limit would find nothing
   * to do and wake again.
   */
  private static Instant past(Instant limitAt) {
    return limitAt.equals(Instant.MAX) ? limitAt : limitAt.plusNanos(1);
  }

  private Duration until(Instant wakeAt) {
    var remaining = Duration.between(clock.get(), wakeAt);
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  /**
   * The snapshot a {@code snapshot-and-stop} limit takes while the agent's state is still there;
   * blank for every other action and on a dry run.
   */
  private String snapshotBefore(Triggered limit) throws Exception {
    if (dryRun || !"snapshot-and-stop".equals(limit.action())) {
      return "";
    }
    var label = "guardrail-" + SnapshotManager.defaultLabel().substring(5);
    new SnapshotManager(shell).create(project, label);
    return label;
  }

  /**
   * Publishes the stop of a run that ended before its watcher attached — an agent that died at
   * launch — with the exit code its unit still holds, so the run ends by a stop that says how
   * rather than, minutes later, by a reconciled one that cannot. Returns false, publishing nothing,
   * when the unit is not known to have ended or its session file does not name this run: a watcher
   * started for a run that never launched has nothing to report.
   */
  public static boolean stopIfAlreadyEnded(
      String project,
      String runId,
      AgentUnit unit,
      AgentSession agentSession,
      StopPublisher publisher)
      throws Exception {
    var exit = agentSession.queryExitStatus(project, unit);
    if (exit.active() || !runId.equals(exit.runId())) {
      return false;
    }
    emitStop(publisher, project, exit, null);
    return true;
  }

  /**
   * The unit's exit state addressed to the run this watcher was started for. The unit's recorded
   * environment is gone once a cleanly exited unit is collected, and what is read back then comes
   * from the run's session file, which the agent can write: the run a stop ends is the one the
   * watcher's own command line names, never one the container says.
   */
  static AgentSession.ExitState addressedTo(String runId, AgentSession.ExitState exit) {
    return new AgentSession.ExitState(
        exit.active(), exit.exitCode(), exit.specId(), exit.agentType(), runId, exit.role());
  }

  /** The wall-clock deadline of a run started at {@code startedAt}; never, with no limit. */
  public static Instant deadline(Instant startedAt, String maxDuration) {
    var limit = Guardrails.parseDuration(maxDuration);
    return limit == null ? Instant.MAX : startedAt.plus(limit);
  }

  /** Whether an event signals the agent is actively working — resets the stall timer. */
  static boolean isProgressEvent(Event event) {
    return Event.WellKnownTypes.progress(event.type());
  }

  /**
   * Whether an event belongs to the watched run. The watcher accepts only events stamped with its
   * run id — the in-container hooks send {@code SAIL_RUN_ID} on every heartbeat — so a concurrent
   * run's tool calls never reset this run's stall timer.
   */
  static boolean matchesRun(Event event, String watchedRunId) {
    return watchedRunId.equals(
        Objects.toString(event.data().get(Event.WellKnownData.RUN_ID), null));
  }

  private static Instant earlier(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  /**
   * Publishes the run's authoritative stop: {@code reason} is why the watcher ended it, null when
   * the agent exited on its own. A publish that fails is said and not retried: the run's exit code
   * is still held by its unit and its trigger is recorded beside it, which is where the missed-stop
   * reconciler reads how the run ended.
   */
  public static void emitStop(
      StopPublisher publisher, String project, AgentSession.ExitState exit, String reason) {
    if (publisher == null) {
      return;
    }
    if (Strings.isBlank(exit.specId()) && Strings.isBlank(exit.runId())) {
      System.err.println(
          "  [watch] no spec or run id recovered for " + project + "; no stop published");
      return;
    }
    try {
      publisher.publish(stop(project, exit, reason));
      System.err.println(
          "  [watch] published stop for "
              + (Strings.isBlank(exit.specId()) ? "run " + exit.runId() : "spec " + exit.specId())
              + " ("
              + Optional.ofNullable(reason).orElse("exit " + exit.exitCode())
              + ")");
    } catch (Exception e) {
      System.err.println("  [watch] could not publish stop for " + project + ": " + e.getMessage());
    }
  }

  /**
   * The {@code agent_session_stopped} the watcher emits when the run it supervises ends. A run that
   * exited on its own carries its real exit code, so consumers can tell a crash from a clean
   * finish. A run the watcher killed carries {@code reason} instead — it did not end itself, so it
   * has no exit code of its own, and reporting the one systemd shows for a reset unit would call a
   * reaped run clean.
   */
  static Event stop(String project, AgentSession.ExitState exit, String reason) {
    return RunStops.of(
        Event.WellKnownData.SOURCE_WATCHER,
        project,
        exit.specId(),
        exit.agentType(),
        exit.runId(),
        exit.role(),
        exit.exitCode(),
        reason);
  }
}
