/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.LoginStore;
import ai.singlr.sail.store.RunStore;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The doors to a person's harness logins (Keep logins): store or replace one, make one the default,
 * list them, remove one. Storing, replacing and choosing the default act on the caller's own logins
 * only; listing or removing another person's is managing people, an admin's. No door answers a
 * value, to anyone: a login is shown by name, kind, date and fingerprint. Each change is announced
 * with the same facts, never the value.
 */
public final class LoginOperations {

  static final String STORED = "login_stored";
  static final String DEFAULT = "login_default";
  static final String REMOVED = "login_removed";

  private final FdeStore fdes;
  private final RunStore runs;
  private final Consumer<Event> events;

  LoginOperations(FdeStore fdes, RunStore runs, Consumer<Event> events) {
    this.fdes = fdes;
    this.runs = runs;
    this.events = events;
  }

  /** The logins of {@code fde}, the caller when blank. */
  LoginList list(String fde) {
    var person = whose(fde);
    return new LoginList(
        person.handle(),
        logins().list(person.id()).stream().map(row -> view(person, row)).toList());
  }

  /** Stores {@code value} as the caller's login {@code name} of {@code harness}, or replaces it. */
  LoginResponse store(String harness, String name, String kind, String value) {
    var person = caller();
    var named = LoginStore.requireName(name);
    var known = Harnesses.of(harness);
    var still = liveOn(person, known, named);
    var row = stored(() -> logins().store(person.id(), known, named, kind, value));
    announce(STORED, person, row);
    return new LoginResponse(view(person, row), still);
  }

  /** Makes the caller's login {@code name} of {@code harness} the default for launches after it. */
  LoginResponse use(String harness, String name) {
    var person = caller();
    var row = found(() -> logins().use(person.id(), Harnesses.of(harness), name));
    announce(DEFAULT, person, row);
    return new LoginResponse(view(person, row), null);
  }

  /** Removes {@code fde}'s login {@code name} of {@code harness}, the caller's when blank. */
  LoginResponse remove(String harness, String name, String fde) {
    var person = whose(fde);
    var known = Harnesses.of(harness);
    var row = found(() -> logins().remove(person.id(), known, name));
    announce(REMOVED, person, row);
    return new LoginResponse(view(person, row), liveOn(person, known, row.name()));
  }

  private FdeStore roster() {
    if (fdes == null) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED, "This box keeps no roster, so it keeps no logins.");
    }
    return fdes;
  }

  private LoginStore logins() {
    return roster().logins();
  }

  /** The person calling, once they may keep logins at all. */
  private FdeStore.Fde caller() {
    var actor = Actor.current();
    refuseUnless(Access.decide(actor, Action.KEEP_LOGINS, Resources.people()));
    var handle =
        Access.attendee(actor)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.FORBIDDEN,
                        "This credential acts for no person, so it keeps no logins.",
                        "Use a token of your own, or sail on the host."));
    return person(handle);
  }

  /** {@code fde}'s roster entry, the caller's when blank or theirs; another's only to an admin. */
  private FdeStore.Fde whose(String fde) {
    var actor = Actor.current();
    if (Strings.isBlank(fde) || Access.attendee(actor).filter(fde::equals).isPresent()) {
      return caller();
    }
    refuseUnless(Access.decide(actor, Action.MANAGE_PEOPLE, Resources.people()));
    return person(fde);
  }

  private FdeStore.Fde person(String handle) {
    return roster()
        .byHandle(handle)
        .orElseThrow(
            () ->
                new ApiException(
                    ErrorCode.NOT_FOUND, "No FDE '" + handle + "' on this box's roster."));
  }

  private static void refuseUnless(Decision decision) {
    if (decision instanceof Decision.Refused(var refusal)) {
      throw Refusals.exception(refusal);
    }
  }

  /** What {@code store} stored, on a box that keeps logins. */
  private static LoginStore.Row stored(Supplier<LoginStore.Row> store) {
    try {
      return store.get();
    } catch (IllegalStateException kept) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, kept.getMessage());
    }
  }

  private static LoginStore.Row found(Supplier<LoginStore.Row> change) {
    try {
      return change.get();
    } catch (NoSuchElementException absent) {
      throw new ApiException(ErrorCode.NOT_FOUND, absent.getMessage());
    }
  }

  /**
   * What is said once when a login live runs started with is replaced or removed: they finish on
   * it, since their processes hold it; null when none did.
   */
  private String liveOn(FdeStore.Fde person, Harness harness, String name) {
    if (runs == null) {
      return null;
    }
    var live =
        Stream.concat(runs.running().stream(), runs.stopping().stream())
            .filter(run -> person.handle().equals(run.owner()))
            .filter(run -> harness.yamlName().equals(run.agent()))
            .filter(run -> run.login() != null && name.equals(run.login().name()))
            .count();
    if (live == 0) {
      return null;
    }
    return live
        + (live == 1 ? " live run finishes" : " live runs finish")
        + " on the old `"
        + name
        + "`.";
  }

  private void announce(String type, FdeStore.Fde person, LoginStore.Row row) {
    var data = new LinkedHashMap<String, Object>();
    data.put("person", person.handle());
    data.put("harness", row.harness());
    data.put("name", row.name());
    data.put("kind", row.kind());
    data.put("fingerprint", row.fingerprint());
    events.accept(
        Event.of(
            Event.SAIL_AGENT,
            null,
            type,
            Access.attendee(Actor.current()).orElse(Event.SAIL_AGENT),
            HostInfo.hostname(),
            data));
  }

  private static LoginView view(FdeStore.Fde person, LoginStore.Row row) {
    return new LoginView(
        person.handle(),
        row.harness(),
        row.name(),
        row.kind(),
        row.isDefault(),
        row.storedAt(),
        row.fingerprint());
  }
}
