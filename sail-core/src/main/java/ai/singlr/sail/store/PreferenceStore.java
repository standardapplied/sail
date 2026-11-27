/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Each person's preferences on this box, keyed by their FDE id: box-local, never synced, and erased
 * with the person (the roster's cascade). A person using two boxes sets each.
 */
public final class PreferenceStore {

  /** What a person starts a session in: a chat, a terminal, or {@code both}, chosen each time. */
  public enum StartsIn {
    CHAT,
    TERMINAL,
    BOTH;

    /** The choice as the API says it. */
    public String wire() {
      return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The choice {@code wire} says, exactly as {@link #wire} writes it; empty for any other word.
     */
    public static Optional<StartsIn> of(String wire) {
      return Arrays.stream(values()).filter(choice -> choice.wire().equals(wire)).findFirst();
    }
  }

  private final Sqlite db;

  public PreferenceStore(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /** What {@code person} starts a session in, or empty when they never set it here. */
  public Optional<StartsIn> sessionForm(String person) {
    return db.queryOne(
        "SELECT session_form FROM preferences WHERE person = ?",
        row -> StartsIn.of(row.text(0)).orElseThrow(),
        person);
  }

  /** {@code person} starts a session in {@code form} from now on. */
  public void sessionForm(String person, StartsIn form) {
    db.execute(
        "INSERT INTO preferences (person, session_form) VALUES (?, ?)"
            + " ON CONFLICT (person) DO UPDATE SET session_form = excluded.session_form",
        person,
        form.wire());
  }
}
