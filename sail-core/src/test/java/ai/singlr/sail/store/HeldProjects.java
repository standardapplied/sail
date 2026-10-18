/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

/**
 * Projects a test's box holds from before the test began: a catalog every box already agrees on,
 * written as rows main has taken and journaled nowhere, so no round offers or adopts them. What a
 * box must hold before it takes a spec or room into a project ({@link ProjectStore#refusesInto}).
 */
public final class HeldProjects {

  private HeldProjects() {}

  /** {@code db} holding each of {@code names}; a name it already holds is left as it is. */
  public static Sqlite hold(Sqlite db, String... names) {
    for (var name : names) {
      db.execute(
          "INSERT OR IGNORE INTO projects (name, definition, created_by, created_at, updated_by,"
              + " updated_at, rev, base_rev) VALUES (?, ?, 'test', ?, 'test', ?, ?, ?)",
          name,
          "name: " + name + "\n",
          "2026-01-01T00:00:00Z",
          "2026-01-01T00:00:00Z",
          "1-held",
          "1-held");
    }
    return db;
  }
}
