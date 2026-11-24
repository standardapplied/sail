/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.harness.LoginKind;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.LoginStore;
import ai.singlr.sail.store.RunStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Which login a run starts its harness with, decided by the one fact a run is placed by, whether it
 * names a spec (HL1): a run that does starts with the default login the person it acts for stored
 * for its harness, and is refused before anything is reserved when there is none (HL6); a run that
 * names none (an ad-hoc run, a spec-less room's member) uses whatever login its container has.
 * There is no fallback to another login, another person's, or the container's. A refusal of a run
 * is said in its spec's room once per cause, until the spec's runs start again, so a person who
 * stored no login learns it where the work is, however often Sail itself tries.
 */
final class RunLogins {

  private final FdeStore fdes;
  private final RunLauncher.Room room;

  /** {@code room} is where a spec's run refused for want of a login is said. */
  RunLogins(FdeStore fdes, RunLauncher.Room room) {
    this.fdes = fdes;
    this.room = Objects.requireNonNull(room, "room");
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
   * Whether the login {@code run} was given is no longer what its person's runs start with: their
   * default login for its harness is another, or was stored after the run started. What mends a
   * refusal is that state, whether or not the store's event was heard. {@code run} was given a
   * stored login: only such a run's login is refused.
   */
  static boolean replaced(FdeStore fdes, RunStore.RunRow run) {
    var harness = Harnesses.of(run.agent());
    return Optional.ofNullable(run.owner())
        .flatMap(fdes::byHandle)
        .flatMap(person -> fdes.logins().defaultFor(person.id(), harness))
        .map(LoginStore.Login::row)
        .filter(
            stored ->
                !stored.name().equals(run.login().name())
                    || stored.storedAt().isAfter(run.startedAt()))
        .isPresent();
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
    var fde = fdes == null || Strings.isBlank(owner) ? null : fdes.byHandle(owner).orElse(null);
    return Optional.of(
        stored(
            fdes,
            fde == null ? null : fde.id(),
            owner,
            Harnesses.of(agent),
            "a run of a spec cannot start",
            ": a login made by hand in the project's container is not used by runs"));
  }

  /**
   * The default login {@code person}, of FDE id {@code fdeId} (null for none on this box), stored
   * for {@code harness}: what {@code cannot} says cannot start without one, as the refusal says it,
   * with {@code why} after it when none is stored at all.
   *
   * @throws ApiException {@link ErrorCode#LOGIN_NOT_STORED} with the remedy that stores one, or
   *     that makes one of their logins the default when they have some
   */
  static LoginStore.Login stored(
      FdeStore fdes, String fdeId, String person, Harness harness, String cannot, String why) {
    var stored = fdeId == null ? null : fdes.logins().defaultFor(fdeId, harness).orElse(null);
    if (stored != null) {
      return stored;
    }
    var others =
        fdeId == null
            ? List.<LoginStore.Row>of()
            : fdes.logins().list(fdeId).stream()
                .filter(row -> row.harness().equals(harness.yamlName()))
                .toList();
    throw others.isEmpty()
        ? noneStored(harness, person, cannot + why)
        : noDefault(harness, person, cannot, others);
  }

  private static ApiException noneStored(Harness harness, String owner, String cannot) {
    return new ApiException(
        ErrorCode.LOGIN_NOT_STORED,
        "No "
            + harness.displayName()
            + " login stored for "
            + (Strings.isBlank(owner) ? "this box's person" : owner)
            + ", so "
            + cannot
            + ".",
        howToStore(harness));
  }

  private static ApiException noDefault(
      Harness harness, String owner, String cannot, List<LoginStore.Row> others) {
    return new ApiException(
        ErrorCode.LOGIN_NOT_STORED,
        owner
            + " has no default "
            + harness.displayName()
            + " login, so "
            + cannot
            + "; stored: "
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
