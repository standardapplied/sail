/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Strings;
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
import java.util.function.Function;

/**
 * The one read of a finished run's harness report, and the one place what it says of the run's
 * stored login is worded and said (RL4): every finisher — the tracker of a watcher's stop, the
 * review pipeline, a foreground run, the reconciler's finish and its late read, a live member's
 * turn — reads the report here and, once its write recorded it, hands it back to {@link #recorded}.
 * Only the write that recorded a report says it, so a report is said once however many finishers
 * race; the spec's room and an {@link Event.WellKnownTypes#AGENT_LOGIN_OUTCOME} event carry the
 * same sentence. A sentence names the login by its person, name and kind, and says how to replace
 * it; it quotes nothing a harness said but a reset time or a credential source's name (RL6).
 */
public final class RunReports {

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

  /** What {@code run}'s harness reported, read once; null when its container did not answer. */
  public RunReport read(RunStore.RunRow run) {
    return reader.apply(run);
  }

  /**
   * Says what came of {@code run}'s stored login, as {@code report} says, which a write just
   * recorded on the run: in its spec's room, and as an event carrying the same sentence. A report
   * that says nothing of it, or a run given no stored login, says nothing. Best-effort: a line
   * telling of how a run ended never undoes the write that ended it.
   */
  public void recorded(RunStore.RunRow run, RunReport report) {
    var outcome = report == null ? Optional.<LoginOutcome>empty() : report.outcome();
    var kind = outcome.isEmpty() ? Optional.<LoginKind>empty() : kindOf(run);
    if (kind.isEmpty()) {
      return;
    }
    var said = sentence(run, kind.get(), outcome.get());
    try {
      room.tell(run.specId(), said);
      events.publish(event(run, outcome.get(), said));
    } catch (RuntimeException e) {
      System.err.println(
          "  [api] Warning: could not say how run " + run.id() + "'s login fared: " + e);
    }
  }

  /** The kind of the stored login {@code run} was given, as its harness declares it. */
  private static Optional<LoginKind> kindOf(RunStore.RunRow run) {
    return Strings.isBlank(run.specId())
        ? Optional.empty()
        : Harnesses.of(run.agent()).declaredKind(run.loginKind());
  }

  /** The one sentence an outcome of {@code run}'s login of {@code kind} is said in. */
  static String sentence(RunStore.RunRow run, LoginKind kind, LoginOutcome outcome) {
    var harness = Harnesses.of(run.agent());
    var name = run.login().name();
    var login = RunLogins.named(run.owner(), name, kind);
    return switch (outcome) {
      case LoginOutcome.Refused refused ->
          Remedied.message(
              harness.displayName() + " refused " + login + ", so its run stopped.",
              RunLogins.howToReplace(harness, kind, name));
      case LoginOutcome.Limited limited ->
          harness.displayName()
              + " says the account behind "
              + login
              + " is out of quota"
              + (limited.resets() == null ? "" : " until " + limited.resets())
              + ", so its run stopped"
              + (Strings.isBlank(run.branch())
                  ? "."
                  : "; what it committed stays on branch " + run.branch() + ".");
      case LoginOutcome.Overridden overridden ->
          harness.displayName()
              + " ran on a credential from "
              + overridden.source()
              + ", not on "
              + login
              + ": its container's own settings outrank the login Sail gave it.";
    };
  }

  private static Event event(RunStore.RunRow run, LoginOutcome outcome, String said) {
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.RUN_ID, run.id());
    data.put(Event.WellKnownData.RUN_ROLE, run.role());
    data.put(
        "outcome",
        switch (outcome) {
          case LoginOutcome.Refused refused -> "refused";
          case LoginOutcome.Limited limited -> "limited";
          case LoginOutcome.Overridden overridden -> "overridden";
        });
    data.put("login", run.login().name());
    data.put("login_kind", run.login().kind());
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
