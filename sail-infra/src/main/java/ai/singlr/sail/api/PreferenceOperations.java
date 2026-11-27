/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.access.Access;
import ai.singlr.sail.access.Action;
import ai.singlr.sail.access.Decision;
import ai.singlr.sail.access.Resources;
import ai.singlr.sail.harness.SessionForm;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.PreferenceStore;
import ai.singlr.sail.store.Sqlite;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The doors to a person's own preferences on this box (Keep preferences): the form they start a
 * session in, {@code chat} until they set another. Each reads and writes its caller's own alone, a
 * viewer's too; a credential that acts for no person holds none, and no agent or container
 * credential reaches them.
 */
public final class PreferenceOperations {

  static final String SESSION_FORM = "session_form";

  /** What a person starts a session in until they say otherwise: new people start in a chat. */
  static final SessionForm STARTS_IN = SessionForm.CHAT;

  /** A person's preferences, as the API says them. */
  record Preferences(SessionForm sessionForm) implements Mappable {
    @Override
    public Map<String, Object> toMap() {
      return Map.of(SESSION_FORM, sessionForm.wire());
    }
  }

  private final FdeStore fdes;
  private final PreferenceStore preferences;

  PreferenceOperations(Sqlite db) {
    Objects.requireNonNull(db, "db");
    this.fdes = new FdeStore(db);
    this.preferences = new PreferenceStore(db);
  }

  /** The caller's preferences. */
  Preferences read() {
    return new Preferences(preferences.sessionForm(person()).orElse(STARTS_IN));
  }

  /**
   * Sets the caller's preferences to {@code body}, which names {@code session_form} alone, as
   * {@code chat}, {@code terminal} or {@code both}; answers them.
   *
   * @throws IllegalArgumentException for any other field or value
   */
  Preferences keep(Map<String, Object> body) {
    var person = person();
    if (!body.keySet().stream().allMatch(SESSION_FORM::equals)) {
      throw new IllegalArgumentException("A preference names only session_form.");
    }
    var said =
        body.get(SESSION_FORM) instanceof String word
            ? SessionForm.of(word)
            : Optional.<SessionForm>empty();
    var form =
        said.orElseThrow(
            () -> new IllegalArgumentException("session_form is chat, terminal or both."));
    preferences.sessionForm(person, form);
    return new Preferences(form);
  }

  /** The FDE id of the person the bound caller is, once they may keep preferences at all. */
  private String person() {
    var actor = Actor.current();
    if (Access.decide(actor, Action.KEEP_PREFERENCES, Resources.people())
        instanceof Decision.Refused(var refusal)) {
      throw Refusals.exception(refusal);
    }
    return Access.attendee(actor)
        .flatMap(fdes::byHandle)
        .map(FdeStore.Fde::id)
        .orElseThrow(
            () ->
                new ApiException(
                    ErrorCode.FORBIDDEN,
                    "This credential acts for no person, so it holds no preferences.",
                    "Use a credential of your own."));
  }
}
