/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Thin Panama FFI wrapper over the system {@code libsqlite3.so.0}. Opens a database with WAL mode,
 * foreign keys enabled, and a 5-second busy timeout. Thread-safe via SQLite's serialized mode
 * (default).
 *
 * <p>Not a JDBC driver. Exposes exactly the surface Sail needs: execute, query, and transactions.
 */
public final class Sqlite implements AutoCloseable {

  private static final int SQLITE_OK = 0;
  private static final int SQLITE_ROW = 100;
  private static final int SQLITE_DONE = 101;
  private static final int SQLITE_OPEN_READWRITE = 0x00000002;
  private static final int SQLITE_OPEN_CREATE = 0x00000004;
  private static final int SQLITE_OPEN_FULLMUTEX = 0x00010000;
  private static final int SQLITE_NULL = 5;

  private static final MemorySegment SQLITE_TRANSIENT = MemorySegment.ofAddress(-1);

  private final Arena arena;
  private final MemorySegment db;
  private final SqliteLib lib;
  private volatile boolean closed;

  private Sqlite(Arena arena, MemorySegment db, SqliteLib lib) {
    this.arena = arena;
    this.db = db;
    this.lib = lib;
  }

  public static Sqlite open(Path path) {
    var arena = Arena.ofShared();
    try {
      var lib = SqliteLib.load(arena);
      var dbPtr = arena.allocate(ValueLayout.ADDRESS);
      var pathStr = arena.allocateFrom(path.toAbsolutePath().toString());
      var flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX;
      var rc = (int) lib.open.invokeExact(pathStr, dbPtr, flags, MemorySegment.NULL);
      if (rc != SQLITE_OK) {
        arena.close();
        throw new SqliteException("Failed to open database: " + path, rc);
      }
      var db = dbPtr.get(ValueLayout.ADDRESS, 0);
      var sqlite = new Sqlite(arena, db, lib);
      sqlite.pragma("journal_mode", "WAL");
      sqlite.pragma("foreign_keys", "ON");
      sqlite.pragma("busy_timeout", "5000");
      return sqlite;
    } catch (SqliteException e) {
      throw e;
    } catch (Throwable t) {
      arena.close();
      throw new SqliteException("Failed to open database", t);
    }
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    try {
      var _ = (int) lib.close.invokeExact(db);
    } catch (Throwable t) {
      throw new SqliteException("Failed to close database", t);
    } finally {
      arena.close();
    }
  }

  public void execute(String sql, Object... params) {
    requireOpen();
    try (var stmtArena = Arena.ofConfined()) {
      var stmt = prepare(stmtArena, sql);
      try {
        bind(stmtArena, stmt, params);
        var rc = (int) lib.step.invokeExact(stmt);
        if (rc != SQLITE_DONE && rc != SQLITE_ROW) {
          throw new SqliteException(errmsg(), rc);
        }
      } finally {
        var _ = (int) lib.finalize_.invokeExact(stmt);
      }
    } catch (SqliteException e) {
      throw e;
    } catch (Throwable t) {
      throw new SqliteException("Execute failed: " + sql, t);
    }
  }

  public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
    requireOpen();
    try (var stmtArena = Arena.ofConfined()) {
      var stmt = prepare(stmtArena, sql);
      try {
        bind(stmtArena, stmt, params);
        var results = new ArrayList<T>();
        var row = new SqliteRow(lib, stmt);
        while (true) {
          var rc = (int) lib.step.invokeExact(stmt);
          if (rc == SQLITE_DONE) break;
          if (rc != SQLITE_ROW) throw new SqliteException(errmsg(), rc);
          results.add(mapper.map(row));
        }
        return List.copyOf(results);
      } finally {
        var _ = (int) lib.finalize_.invokeExact(stmt);
      }
    } catch (SqliteException e) {
      throw e;
    } catch (Throwable t) {
      throw new SqliteException("Query failed: " + sql, t);
    }
  }

  public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
    var results = query(sql, mapper, params);
    return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
  }

  public void transaction(Runnable work) {
    execute("BEGIN");
    try {
      work.run();
      execute("COMMIT");
    } catch (Exception e) {
      try {
        execute("ROLLBACK");
      } catch (Exception rollbackEx) {
        e.addSuppressed(rollbackEx);
      }
      throw e;
    }
  }

  public int changes() {
    requireOpen();
    try {
      return (int) lib.changes.invokeExact(db);
    } catch (Throwable t) {
      throw new SqliteException("Failed to get changes", t);
    }
  }

  private void pragma(String name, String value) {
    execute("PRAGMA " + name + " = " + value);
  }

  private MemorySegment prepare(Arena stmtArena, String sql) throws Throwable {
    var sqlStr = stmtArena.allocateFrom(sql);
    var stmtPtr = stmtArena.allocate(ValueLayout.ADDRESS);
    var rc =
        (int)
            lib.prepare.invokeExact(
                db, sqlStr, (int) sqlStr.byteSize(), stmtPtr, MemorySegment.NULL);
    if (rc != SQLITE_OK) {
      throw new SqliteException(errmsg() + " [SQL: " + sql + "]", rc);
    }
    return stmtPtr.get(ValueLayout.ADDRESS, 0);
  }

  private void bind(Arena stmtArena, MemorySegment stmt, Object[] params) throws Throwable {
    for (var i = 0; i < params.length; i++) {
      var idx = i + 1;
      var param = params[i];
      int rc;
      switch (param) {
        case null -> rc = (int) lib.bindNull.invokeExact(stmt, idx);
        case String s -> {
          var str = stmtArena.allocateFrom(s);
          rc =
              (int)
                  lib.bindText.invokeExact(
                      stmt, idx, str, (int) (str.byteSize() - 1), SQLITE_TRANSIENT);
        }
        case Integer n -> rc = (int) lib.bindInt64.invokeExact(stmt, idx, (long) n);
        case Long n -> rc = (int) lib.bindInt64.invokeExact(stmt, idx, (long) n);
        case Double d -> rc = (int) lib.bindDouble.invokeExact(stmt, idx, (double) d);
        case Float f -> rc = (int) lib.bindDouble.invokeExact(stmt, idx, (double) f);
        default ->
            throw new SqliteException("Unsupported bind type: " + param.getClass().getName(), 0);
      }
      if (rc != SQLITE_OK) {
        throw new SqliteException(errmsg(), rc);
      }
    }
  }

  private String errmsg() {
    try {
      var ptr = (MemorySegment) lib.errmsg.invokeExact(db);
      return ptr.reinterpret(1024).getString(0);
    } catch (Throwable t) {
      return "unknown error";
    }
  }

  private void requireOpen() {
    if (closed) throw new IllegalStateException("Database is closed");
  }

  @FunctionalInterface
  public interface RowMapper<T> {
    T map(Row row);
  }

  public interface Row {
    String text(int col);

    long integer(int col);

    boolean isNull(int col);

    int columnCount();
  }

  private record SqliteRow(SqliteLib lib, MemorySegment stmt) implements Row {
    @Override
    public String text(int col) {
      try {
        var ptr = (MemorySegment) lib.columnText.invokeExact(stmt, col);
        if (ptr.equals(MemorySegment.NULL)) return null;
        var bytes = (int) lib.columnBytes.invokeExact(stmt, col);
        return ptr.reinterpret(bytes + 1L).getString(0);
      } catch (Throwable t) {
        throw new SqliteException("Failed to read text column " + col, t);
      }
    }

    @Override
    public long integer(int col) {
      try {
        return (long) lib.columnInt64.invokeExact(stmt, col);
      } catch (Throwable t) {
        throw new SqliteException("Failed to read integer column " + col, t);
      }
    }

    @Override
    public boolean isNull(int col) {
      try {
        return (int) lib.columnType.invokeExact(stmt, col) == SQLITE_NULL;
      } catch (Throwable t) {
        throw new SqliteException("Failed to check null column " + col, t);
      }
    }

    @Override
    public int columnCount() {
      try {
        return (int) lib.columnCount.invokeExact(stmt);
      } catch (Throwable t) {
        throw new SqliteException("Failed to get column count", t);
      }
    }
  }

  private record SqliteLib(
      MethodHandle open,
      MethodHandle close,
      MethodHandle prepare,
      MethodHandle step,
      MethodHandle finalize_,
      MethodHandle bindText,
      MethodHandle bindInt64,
      MethodHandle bindDouble,
      MethodHandle bindNull,
      MethodHandle columnText,
      MethodHandle columnInt64,
      MethodHandle columnType,
      MethodHandle columnBytes,
      MethodHandle columnCount,
      MethodHandle errmsg,
      MethodHandle changes) {

    static SqliteLib load(Arena arena) {
      var lookup = SymbolLookup.libraryLookup("libsqlite3.so.0", arena);
      var linker = Linker.nativeLinker();

      return new SqliteLib(
          linker.downcallHandle(
              lookup.find("sqlite3_open_v2").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS,
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS)),
          linker.downcallHandle(
              lookup.find("sqlite3_close_v2").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
          linker.downcallHandle(
              lookup.find("sqlite3_prepare_v2").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS,
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS,
                  ValueLayout.ADDRESS)),
          linker.downcallHandle(
              lookup.find("sqlite3_step").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
          linker.downcallHandle(
              lookup.find("sqlite3_finalize").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
          linker.downcallHandle(
              lookup.find("sqlite3_bind_text").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS)),
          linker.downcallHandle(
              lookup.find("sqlite3_bind_int64").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_INT,
                  ValueLayout.JAVA_LONG)),
          linker.downcallHandle(
              lookup.find("sqlite3_bind_double").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT,
                  ValueLayout.ADDRESS,
                  ValueLayout.JAVA_INT,
                  ValueLayout.JAVA_DOUBLE)),
          linker.downcallHandle(
              lookup.find("sqlite3_bind_null").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
          linker.downcallHandle(
              lookup.find("sqlite3_column_text").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
          linker.downcallHandle(
              lookup.find("sqlite3_column_int64").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
          linker.downcallHandle(
              lookup.find("sqlite3_column_type").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
          linker.downcallHandle(
              lookup.find("sqlite3_column_bytes").orElseThrow(),
              FunctionDescriptor.of(
                  ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
          linker.downcallHandle(
              lookup.find("sqlite3_column_count").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)),
          linker.downcallHandle(
              lookup.find("sqlite3_errmsg").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS)),
          linker.downcallHandle(
              lookup.find("sqlite3_changes").orElseThrow(),
              FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)));
    }
  }
}
