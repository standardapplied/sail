/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.LoginStore;
import ai.singlr.sail.store.RunStore;
import java.util.LinkedHashMap;
import java.util.List;
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

  /**
   * What replacing or removing a harness login leaves: an agent session already running took the
   * login into its harness when it started, and nothing of it rests in a workspace.
   */
  static final String SESSIONS_KEEP =
      "An agent session already running keeps the key it started with until it ends; no"
          + " workspace keeps any of it.";

  private final FdeStore fdes;
  private final RunStore runs;
  private final Consumer<Event> events;

  /** Answers each workspace a token change did not reach ({@code Workspaces#refresh}). */
  @FunctionalInterface
  interface Reach {
    List<String> workspacesOf(String handle) throws Exception;
  }

  private final Reach reach;

  LoginOperations(FdeStore fdes, RunStore runs, Consumer<Event> events, Reach reach) {
    this.fdes = fdes;
    this.runs = runs;
    this.events = events;
    this.reach = reach;
  }

  /** The logins of {@code fde}, the caller when blank. */
  LoginList list(String fde) {
    var person = whose(fde);
    return new LoginList(
        person.handle(),
        logins().list(person.id()).stream().map(row -> view(person, row)).toList());
  }

  /** Stores {@code value} as the caller's login {@code name} of {@code harness}, or replaces it. */
  LoginResponse store(String harness, String name, String kind, String value, String fde) {
    var person = caller();
    if (Strings.isNotBlank(fde) && !fde.equals(person.handle())) {
      throw new ApiException(
          ErrorCode.FORBIDDEN,
          "Nobody stores a login for another person: " + fde + " stores their own.");
    }
    if (LoginStore.GIT.equals(harness)) {
      var row = stored(() -> logins().storeGit(person.id(), name, value));
      announce(STORED, person, row);
      return new LoginResponse(view(person, row), unreached(person));
    }
    var named = named(name);
    var known = harness(harness);
    var still = liveOn(person, known, named);
    var replacing =
        logins().list(person.id()).stream()
            .anyMatch(
                stored -> stored.harness().equals(known.yamlName()) && stored.name().equals(named));
    var row = stored(() -> logins().store(person.id(), known, named, kind, value));
    announce(STORED, person, row);
    return new LoginResponse(
        view(person, row), replacing ? Remedied.joined(still, SESSIONS_KEEP) : still);
  }

  /** Makes the caller's login {@code name} of {@code harness} the default for launches after it. */
  LoginResponse use(String harness, String name) {
    var person = caller();
    if (LoginStore.GIT.equals(harness)) {
      throw new ApiException(
          ErrorCode.BAD_REQUEST,
          "A git token is no default: each host has its own, and each is used.");
    }
    var known = harness(harness);
    var row = found(() -> logins().use(person.id(), known, name));
    announce(DEFAULT, person, row);
    return new LoginResponse(view(person, row), SESSIONS_KEEP);
  }

  /**
   * Removes {@code fde}'s login {@code name} of {@code harness}, the caller's when blank; a login
   * not stored is nothing to remove, said so.
   */
  LoginResponse remove(String harness, String name, String fde) {
    var person = whose(fde);
    if (LoginStore.GIT.equals(harness)) {
      return removeGit(person, name);
    }
    var known = harness(harness);
    var removed = logins().remove(person.id(), known, name);
    if (removed.isEmpty()) {
      return new LoginResponse(
          null,
          "No "
              + known.displayName()
              + " login named '"
              + LoginStore.requireName(name)
              + "' is stored for "
              + person.handle()
              + "; nothing was removed.");
    }
    var row = removed.get();
    announce(REMOVED, person, row);
    return new LoginResponse(
        view(person, row), Remedied.joined(liveOn(person, known, row.name()), SESSIONS_KEEP));
  }

  private String unreached(FdeStore.Fde person) {
    try {
      var left = reach.workspacesOf(person.handle());
      return left.isEmpty()
          ? null
          : "These workspaces were not given all of it, and are given it again at their next start: "
              + String.join("; ", left)
              + ".";
    } catch (Exception e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return "This box's workspaces could not be read, so each is given it at its next start: "
          + e.getMessage();
    }
  }

  private LoginResponse removeGit(FdeStore.Fde person, String host) {
    var removed = logins().removeGit(person.id(), host);
    if (removed.isEmpty()) {
      return new LoginResponse(
          null,
          "No git token for "
              + LoginStore.requireHost(host)
              + " is stored for "
              + person.handle()
              + "; nothing was removed.");
    }
    announce(REMOVED, person, removed.get());
    return new LoginResponse(view(person, removed.get()), unreached(person));
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
    var person = person(handle);
    if (!person.active()) {
      throw new ApiException(
          ErrorCode.FORBIDDEN, handle + " is disabled on this box, so they keep no logins here.");
    }
    return person;
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

  /**
   * {@code name} once it is a login's name; refused naming the field, so a client says the rule
   * under the name and not under the value.
   */
  private static String named(String name) {
    try {
      return LoginStore.requireName(name);
    } catch (IllegalArgumentException refused) {
      throw ApiException.of(
          new Result.Failure<>(
              ErrorCode.INVALID_REQUEST,
              refused.getMessage(),
              Remedy.NONE,
              List.of(FieldError.of("name", refused.getMessage())),
              refused));
    }
  }

  /**
   * The harness a login is stored for, refused in words about logins: the harnesses one is stored
   * for, not a project's install list, and not the path's own segment, which is the caller's.
   */
  private static Harness harness(String name) {
    try {
      return Harnesses.of(name);
    } catch (IllegalArgumentException unknown) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST,
          "A login is stored for " + String.join(", ", Harnesses.names()) + " or as a git token.");
    }
  }

  /** What {@code store} stored, on a box that keeps logins. */
  private static <T> T stored(Supplier<T> store) {
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
    publish(type, data);
  }

  private void publish(String type, LinkedHashMap<String, Object> data) {
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
