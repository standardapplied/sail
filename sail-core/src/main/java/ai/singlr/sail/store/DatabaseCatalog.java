/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.Objects;
import java.util.Optional;

/**
 * Which database this is, and on a node which of main's databases it syncs with. The id is random,
 * minted when the database is created, kept by every upgrade and by every copy of the file: main's
 * database is the org, so main set up again from a copy of it is the same main to every node, and
 * main set up empty is another org. A node records main's catalog the first time a main that names
 * one welcomes it, and never replaces it.
 */
public final class DatabaseCatalog {

  private final Sqlite db;

  public DatabaseCatalog(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /** This database's own catalog id. */
  public String id() {
    return db.queryOne("SELECT id FROM catalog", row -> row.text(0)).orElseThrow();
  }

  /** The catalog of the main database this node syncs with; empty until a round records one. */
  public Optional<String> main() {
    return db.queryOne("SELECT COALESCE(main, '') FROM catalog", row -> row.text(0))
        .filter(main -> !main.isEmpty());
  }

  /**
   * Records {@code offered} as main's catalog unless one is recorded already, and returns the one
   * recorded: {@code offered} itself only when it is the database this node synced with.
   */
  public String recordMain(String offered) {
    Objects.requireNonNull(offered, "offered");
    db.execute("UPDATE catalog SET main = ? WHERE main IS NULL", offered);
    return main().orElseThrow();
  }
}
