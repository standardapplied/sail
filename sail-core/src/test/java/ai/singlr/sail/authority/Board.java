/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.authority;

import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;

/**
 * The box a rule's table reads its owners from: {@code lobby}, a room of {@link Actors#OWNER}'s,
 * {@code den}, a room of {@link Actors#OTHER}'s, and one run of each FDE's box, each acting for its
 * box's FDE — {@link Actors#RUN} carries both {@link Actors#AGENT_HANDLE} and {@link
 * Actors#ROOM_HANDLE} as principals.
 */
final class Board implements AutoCloseable {

  final Sqlite db = Sqlite.openMemory();

  Board() {
    new SchemaManager(db).migrate();
    room("lobby", Actors.OWNER);
    room("den", Actors.OTHER);
    Acting.system(
        () -> {
          var runs = new RunStore(db);
          run(runs, Actors.RUN, Actors.OWNER);
          run(runs, Actors.OTHER_RUN, Actors.OTHER);
        });
    db.execute(
        "INSERT INTO run_principals (run_id, principal) VALUES (?, ?)",
        Actors.RUN,
        Actors.ROOM_HANDLE);
  }

  /** A standalone room {@code id} assigned to and created by {@code owner}. */
  void room(String id, String owner) {
    db.execute(
        """
        INSERT INTO rooms (id, project, title, assignee, created_by, created_at, updated_at)
        VALUES (?, 'acme', ?, ?, ?, 'now', 'now')""",
        id,
        id,
        owner,
        owner);
  }

  /** A spec {@code id} living in {@code roomId}, assigned to {@code assignee}. */
  void spec(String id, String roomId, String assignee, String createdBy) {
    db.execute(
        """
        INSERT INTO specs (id, project, title, status, assignee, created_by, created_at,
            updated_at, room_id)
        VALUES (?, 'acme', ?, 'pending', ?, ?, 'now', 'now', ?)""",
        id,
        id,
        assignee,
        createdBy,
        roomId);
  }

  private static void run(RunStore runs, String id, String fde) {
    runs.create(
        id, "acme", "auth", fde, "build", "claude-code", "b", "t", null, null, "/log", "unit");
  }

  @Override
  public void close() {
    db.close();
  }
}
