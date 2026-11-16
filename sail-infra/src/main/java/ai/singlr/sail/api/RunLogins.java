/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.harness.LoginKind;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.LoginStore;
import ai.singlr.sail.store.RunStore;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Which login a run starts its harness with, decided by the one fact a run is placed by, whether it
 * names a spec (HL1): a run that does starts with the default login the person it acts for stored
 * for its harness, and is refused before anything is reserved when there is none (HL6); a run that
 * names none (an ad-hoc run, a spec-less room's member) uses whatever login its container has.
 * There is no fallback to another login, another person's, or the container's. A refusal of a run
 * is said in its spec's room once per cause, until the spec's runs start again, so a person who
 * stored no login learns it where the work is, however often Sail itself tries. A build's login of
 * a kind that lasts a known time is said in its last {@link #ENDING} (RL5, {@link #ending}): a
 * warning, and the run starts.
 */
final class RunLogins {

  /** How long before a login's end a build's dispatch says it is ending. */
  static final Duration ENDING = Duration.ofDays(14);

  private final FdeStore fdes;
  private final RunLauncher.Room room;
  private final DispatchOperations.Listener listener;
  private final DispatchOperations.EventSink events;
  private final Supplier<Instant> clock;

  /**
   * {@code room} is where a spec's run is said, {@code listener} the dispatch's own output and
   * {@code events} the box's bus: where an ending login is said too.
   */
  RunLogins(
      FdeStore fdes,
      RunLauncher.Room room,
      DispatchOperations.Listener listener,
      DispatchOperations.EventSink events,
      Supplier<Instant> clock) {
    this.fdes = fdes;
    this.room = Objects.requireNonNull(room, "room");
    this.listener = Objects.requireNonNull(listener, "listener");
    this.events = Objects.requireNonNull(events, "events");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Whether {@code failure}, or what it wraps, is a run refused for want of a stored login: what
   * {@link #forRun} has already said in the spec's room, so no lane that met it says more.
   */
  static boolean wantsALogin(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ApiException refused
          && refused.failure().errorCode() == ErrorCode.LOGIN_NOT_STORED) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@link #resolve}, telling the spec's room a refusal with its remedy; the room says it once
   * ({@link RunLauncher.Room#tell}), however many processes meet it.
   */
  Optional<LoginStore.Login> forRun(String specId, String owner, String agent) {
    try {
      return resolve(specId, owner, agent);
    } catch (ApiException refused) {
      room.tell(specId, Remedied.message(refused.getMessage(), refused.failure().remedy()));
      throw refused;
    }
  }

  /**
   * Says, once {@code run} is reserved, that the login {@code stored} it was given ends within
   * {@link #ENDING}, when it is a build's (a dispatch, or a resumed build) and its kind has a known
   * end: in the dispatch's own output, as an event, and in the spec's room. Said after the
   * reservation, not before, so on a node the room's line has the run that keeps it there (a node
   * posts only what its sync keeps), whether or not the spec ever ran on that box before.
   */
  void ending(RunStore.RunRow run, LoginStore.Login stored) {
    if (!Lane.BUILD.matches(run.role())) {
      return;
    }
    var harness = Harnesses.of(run.agent());
    var now = clock.get();
    var login = named(run.owner(), stored.row().name(), stored.kind());
    stored
        .kind()
        .endsAt(stored.row().storedAt())
        .filter(end -> end.isBefore(now.plus(ENDING)))
        .map(
            end ->
                Remedied.message(
                    harness.displayName()
                        + (end.isAfter(now)
                            ? " will refuse "
                                + login
                                + " from "
                                + day(end)
                                + ", "
                                + within(now, end)
                            : " refuses " + login + " since " + day(end))
                        + ".",
                    howToReplace(harness, stored.kind(), stored.row().name())))
        .ifPresent(
            said -> {
              listener.loginEnding(said);
              events.publish(
                  RunReports.event(
                      run,
                      new RunStore.RunLogin(stored.row().name(), stored.kind().kind()),
                      "ending",
                      said));
              room.tell(run.specId(), said);
            });
  }

  private static String day(Instant at) {
    return DateTimeFormatter.ISO_LOCAL_DATE.format(at.atOffset(ZoneOffset.UTC));
  }

  /** How many calendar days, in UTC, from {@code now} to {@code end}: the count its date reads. */
  private static String within(Instant now, Instant end) {
    var days =
        ChronoUnit.DAYS.between(
            now.atOffset(ZoneOffset.UTC).toLocalDate(), end.atOffset(ZoneOffset.UTC).toLocalDate());
    return days == 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days";
  }

  /** How a stored login is named where it is said: whose, its name and its kind. */
  static String named(String owner, String name, LoginKind kind) {
    return (Strings.isBlank(owner) ? "the" : owner + "'s")
        + " login '"
        + name
        + "' ("
        + kind.noun()
        + ")";
  }

  /**
   * How to make a login of {@code kind} anew and store it as {@code name}, where the one it
   * replaces was: the command names only what differs from the defaults.
   */
  static Remedy howToReplace(Harness harness, LoginKind kind, String name) {
    var args = new ArrayList<String>(List.of(harness.yamlName()));
    if (!kind.equals(harness.loginKinds().getFirst())) {
      args.addAll(List.of("--kind", kind.kind()));
    }
    if (!LoginStore.DEFAULT_NAME.equals(name)) {
      args.addAll(List.of("--name", name));
    }
    return Remedy.of(
        kind.howToMake(), ", then ", Remedy.sail("agent login", args.toArray(String[]::new)), ".");
  }

  /**
   * The stored login a run of {@code specId} acting for {@code owner} starts {@code agent} with, or
   * empty for a run that names no spec.
   *
   * @throws ApiException when the run names a spec and its person has no default login for it
   */
  Optional<LoginStore.Login> resolve(String specId, String owner, String agent) {
    if (Strings.isBlank(specId)) {
      return Optional.empty();
    }
    var harness = Harnesses.of(agent);
    var fde = fdes == null || Strings.isBlank(owner) ? null : fdes.byHandle(owner).orElse(null);
    var stored = fde == null ? null : fdes.logins().defaultFor(fde.id(), harness).orElse(null);
    if (stored != null) {
      return Optional.of(stored);
    }
    var others =
        fde == null
            ? List.<LoginStore.Row>of()
            : fdes.logins().list(fde.id()).stream()
                .filter(row -> row.harness().equals(harness.yamlName()))
                .toList();
    throw others.isEmpty() ? noneStored(harness, owner) : noDefault(harness, owner, others);
  }

  private static ApiException noneStored(Harness harness, String owner) {
    return new ApiException(
        ErrorCode.LOGIN_NOT_STORED,
        "No "
            + harness.displayName()
            + " login stored for "
            + (Strings.isBlank(owner) ? "this box's person" : owner)
            + ", so a run of a spec cannot start: a login made by hand in the project's container"
            + " is not used by runs.",
        howToStore(harness));
  }

  private static ApiException noDefault(
      Harness harness, String owner, List<LoginStore.Row> others) {
    return new ApiException(
        ErrorCode.LOGIN_NOT_STORED,
        owner
            + " has no default "
            + harness.displayName()
            + " login, so a run of a spec cannot start; stored: "
            + String.join(", ", others.stream().map(LoginStore.Row::name).toList())
            + ".",
        Remedy.of(
            "Choose one: ",
            Remedy.sail("agent login use", harness.yamlName(), others.getFirst().name()),
            "."));
  }

  /** Each kind its harness takes, how to make one, and the command that stores it. */
  static Remedy howToStore(Harness harness) {
    var parts = new ArrayList<Object>();
    for (var kind : harness.loginKinds()) {
      parts.add(parts.isEmpty() ? "" : " Or: ");
      parts.add(howToReplace(harness, kind, LoginStore.DEFAULT_NAME));
    }
    return Remedy.of(parts.toArray());
  }
}
