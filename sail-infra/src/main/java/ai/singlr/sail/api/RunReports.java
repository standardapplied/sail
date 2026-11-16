/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.RunReportReader;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.harness.LoginKind;
import ai.singlr.sail.harness.LoginOutcome;
import ai.singlr.sail.harness.RunReport;
import ai.singlr.sail.store.RunStore;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The one read of a finished run's harness report, and the one place what it says of the run's
 * stored login is worded and said (RL4): every finisher of a run — the tracker of a watcher's stop
 * and the review pipeline, a foreground run, the reconciler's finish and its late read — finishes
 * through {@link #finish}, which reads the report, hands it to the finisher's write, and says it in
 * the spec's room in that very transaction, so a report is said once, by the write that recorded
 * it, however many finishers race, and never lost to a crash between the two. An {@link
 * Event.WellKnownTypes#AGENT_LOGIN_OUTCOME} event carrying the same sentence follows the commit. A
 * live member's turn, which no finish reads, is said through {@link #heardLive}. A sentence names
 * the login by its person, name and kind, says how to replace it, and repeats nothing its harness
 * printed (RL6). A login its harness never saw is Sail's delivery failing: said to the operator,
 * not to the person. An override is said once a spec, until its source changes.
 */
public final class RunReports {

  /** The {@code outcome} of the warning that a login ends soon ({@link RunLogins#ending}). */
  static final String ENDING = "ending";

  private final Function<RunStore.RunRow, RunReport> reader;
  private final RunLauncher.Room room;
  private final DispatchOperations.EventSink events;

  /**
   * @param reader the read of a finished run's report, null when its container did not answer
   * @param room where a spec's run is said
   * @param events where the outcome's event goes
   */
  public RunReports(
      Function<RunStore.RunRow, RunReport> reader,
      RunLauncher.Room room,
      DispatchOperations.EventSink events) {
    this.reader = Objects.requireNonNull(reader, "reader");
    this.room = Objects.requireNonNull(room, "room");
    this.events = Objects.requireNonNull(events, "events");
  }

  /** Reports read in the run's container through {@code shell} ({@link RunReportReader}). */
  public static RunReports reading(
      ShellExec shell, RunLauncher.Room room, DispatchOperations.EventSink events) {
    return new RunReports(new RunReportReader(shell)::read, room, events);
  }

  /**
   * Finishes {@code run} as {@code write} does with its report, read here first (null when its
   * container did not answer), and, when the write recorded it, says what it says of the run's
   * login in the same transaction of {@code runs}. Returns whether the write recorded it.
   */
  public boolean finish(RunStore runs, RunStore.RunRow run, Predicate<RunReport> write) {
    var report = reader.apply(run);
    var said = new AtomicReference<String>();
    boolean wrote =
        runs.atomically(
            () -> {
              if (!write.test(report)) {
                return false;
              }
              said.set(say(runs, run, report, true));
              return true;
            });
    published(run, report, said.get());
    return wrote;
  }

  /**
   * Says what a live member's turn, {@code report}, said of its login: as a finish would, except
   * that the member's run goes on, so the sentence says nothing of it stopping.
   */
  public void heardLive(RunStore runs, RunStore.RunRow run, RunReport report) {
    published(run, report, say(runs, run, report, false));
  }

  /** Says it in the room, best-effort; the sentence, or null when there is nothing to say. */
  private String say(RunStore runs, RunStore.RunRow run, RunReport report, boolean ended) {
    var outcome = report == null ? Optional.<LoginOutcome>empty() : report.outcome();
    var kind = outcome.isEmpty() ? Optional.<LoginKind>empty() : kindOf(run);
    if (kind.isEmpty() || saidBefore(runs, run, outcome.get())) {
      return null;
    }
    var said = sentence(run, kind.get(), outcome.get(), ended);
    if (outcome.get() instanceof LoginOutcome.Undelivered) {
      System.err.println("  [api] run " + run.id() + ": " + said);
      return null;
    }
    try {
      room.tell(run.specId(), said);
    } catch (RuntimeException e) {
      System.err.println("  [api] Warning: could not tell run " + run.id() + "'s room: " + e);
    }
    return said;
  }

  /** Whether an earlier run of the spec recorded this same override: it was said then. */
  private static boolean saidBefore(RunStore runs, RunStore.RunRow run, LoginOutcome outcome) {
    return outcome instanceof LoginOutcome.Overridden overridden
        && runs.listForSpec(run.specId()).stream()
            .anyMatch(
                other ->
                    !other.id().equals(run.id())
                        && overridden.source().equals(other.loginOverridden()));
  }

  private void published(RunStore.RunRow run, RunReport report, String said) {
    if (said != null) {
      events.publish(event(run, run.login(), outcome(report.login()), said));
    }
  }

  /** The kind of the stored login {@code run} was given, as its harness declares it. */
  private static Optional<LoginKind> kindOf(RunStore.RunRow run) {
    return Strings.isBlank(run.specId())
        ? Optional.empty()
        : Harnesses.of(run.agent()).declaredKind(run.loginKind());
  }

  /**
   * The one sentence an outcome of {@code run}'s login of {@code kind} is said in; {@code ended}
   * when the run ended with it, so the sentence says the run stopped and, for a lane that commits,
   * where its work stays.
   */
  static String sentence(RunStore.RunRow run, LoginKind kind, LoginOutcome outcome, boolean ended) {
    var harness = Harnesses.of(run.agent());
    var name = run.login().name();
    var login = RunLogins.named(run.owner(), name, kind);
    var stopped = ended ? ", so its run stopped" : "";
    return switch (outcome) {
      case LoginOutcome.Refused refused ->
          Remedied.message(
              harness.displayName() + " refused " + login + stopped + ".",
              ended && Lane.BUILD.matches(run.role())
                  ? RunLogins.howToReplace(harness, kind, name)
                      .then(
                          " Then build it again: ",
                          RestartResolution.redispatch(run.project(), run.specId()),
                          ".")
                  : RunLogins.howToReplace(harness, kind, name));
      case LoginOutcome.Limited limited ->
          harness.displayName()
              + " says the account behind "
              + login
              + " is out of quota"
              + (limited.resets() == null ? "" : " until " + limited.resets())
              + stopped
              + (ended && commits(run) && Strings.isNotBlank(run.branch())
                  ? "; what it committed stays on branch " + run.branch() + "."
                  : ".");
      case LoginOutcome.RefusedOther other ->
          harness.displayName()
              + " refused the credential from "
              + other.source()
              + stopped
              + "; it never used "
              + login
              + ", which its container's own settings outrank.";
      case LoginOutcome.Overridden overridden ->
          harness.displayName()
              + " ran on a credential from "
              + overridden.source()
              + ", not on "
              + login
              + ": its container's own settings outrank the login Sail gave it.";
      case LoginOutcome.Undelivered undelivered ->
          harness.displayName()
              + " saw no login, though Sail gave it "
              + login
              + ": Sail's delivery of it failed, and the login itself was never tried.";
    };
  }

  /** {@code outcome} as the {@code agent_login_outcome} event names it. */
  static String outcome(LoginOutcome outcome) {
    return switch (outcome) {
      case LoginOutcome.Refused refused -> "refused";
      case LoginOutcome.RefusedOther other -> "refused_other";
      case LoginOutcome.Limited limited -> "limited";
      case LoginOutcome.Overridden overridden -> "overridden";
      case LoginOutcome.Undelivered undelivered -> "undelivered";
    };
  }

  /** Whether {@code run}'s lane commits its work to the spec's branch: a build or a fix. */
  private static boolean commits(RunStore.RunRow run) {
    return Lane.BUILD.matches(run.role()) || Lane.FIX.matches(run.role());
  }

  /**
   * The {@code agent_login_outcome} of {@code run}, given {@code login}: {@code outcome} and the
   * sentence {@code said}.
   */
  static Event event(RunStore.RunRow run, RunStore.RunLogin login, String outcome, String said) {
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.RUN_ID, run.id());
    data.put(Event.WellKnownData.RUN_ROLE, run.role());
    data.put("outcome", outcome);
    data.put("login", login.name());
    data.put("login_kind", login.kind());
    data.put("detail", said);
    return Event.of(
        run.project(),
        run.specId(),
        Event.WellKnownTypes.AGENT_LOGIN_OUTCOME,
        run.agent(),
        HostInfo.hostname(),
        data);
  }
}
