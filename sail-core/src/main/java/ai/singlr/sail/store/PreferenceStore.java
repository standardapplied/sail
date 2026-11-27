/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.harness.SessionForm;
import java.util.Objects;
import java.util.Optional;

/**
 * Each person's preferences on this box, keyed by their FDE id: box-local, never synced, and erased
 * with the person (the roster's cascade). A person using two boxes sets each.
 */
public final class PreferenceStore {

  private final Sqlite db;

  public PreferenceStore(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /** The form {@code person} starts a session in, or empty when they never set it here. */
  public Optional<SessionForm> sessionForm(String person) {
    return db.queryOne(
        "SELECT session_form FROM preferences WHERE person = ?",
        row -> SessionForm.of(row.text(0)).orElseThrow(),
        person);
  }

  /** {@code person} starts a session in {@code form} from now on. */
  public void sessionForm(String person, SessionForm form) {
    db.execute(
        "INSERT INTO preferences (person, session_form) VALUES (?, ?)"
            + " ON CONFLICT (person) DO UPDATE SET session_form = excluded.session_form",
        person,
        form.wire());
  }
}
