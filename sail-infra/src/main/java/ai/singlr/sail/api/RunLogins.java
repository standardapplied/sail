/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.harness.LoginKind;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.LoginStore;
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
      room.tell(specId, refused.getMessage() + " " + refused.failure().remedy().plain());
      throw refused;
    }
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
    var kinds = harness.loginKinds();
    for (var i = 0; i < kinds.size(); i++) {
      parts.add(i == 0 ? "" : " Or: ");
      parts.add(kinds.get(i).howToMake());
      parts.add(", then ");
      parts.add(storeCommand(harness, kinds.get(i), i == 0));
      parts.add(".");
    }
    return Remedy.of(parts.toArray());
  }

  private static Remedy.Command storeCommand(Harness harness, LoginKind kind, boolean first) {
    return first
        ? Remedy.sail("agent login", harness.yamlName())
        : Remedy.sail("agent login", harness.yamlName(), "--kind", kind.kind());
  }
}
