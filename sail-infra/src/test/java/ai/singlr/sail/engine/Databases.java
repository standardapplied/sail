/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.Sqlite;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The databases a test opens, each closed when the test closes this. */
public final class Databases implements AutoCloseable {

  private final List<Sqlite> opened = new CopyOnWriteArrayList<>();

  /** The database at {@code path}, closed with the others. */
  public Sqlite open(Path path) {
    var db = Sqlite.open(path);
    opened.add(db);
    return db;
  }

  @Override
  public void close() {
    opened.forEach(Sqlite::close);
  }
}
