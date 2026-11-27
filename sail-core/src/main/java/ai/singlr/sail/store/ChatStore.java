/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The chats people hold with agents on this box, and every item of each, numbered in the order it
 * was logged: box-local, never synced. A chat is its opener's: it goes with them (the roster's
 * cascade), with its workspace and with its project. Nothing a person is shown of a chat exists
 * only in memory: what an item says is here before anyone reads it.
 */
public final class ChatStore {

  /**
   * One chat: whose ({@code opener}, an FDE id), where, with which harness, in the mode in force
   * (launched, then whatever the harness last said), whether it signs in inside the workspace, the
   * harness's own id of the conversation once said, whether a turn is open, the last of the
   * person's messages handed to the harness ({@code handed}, a seq), and when it was opened and
   * ended.
   */
  public record Chat(
      String id,
      String opener,
      String project,
      String workspace,
      String harness,
      String mode,
      boolean inside,
      String conversation,
      boolean turnOpen,
      long handed,
      Instant createdAt,
      Instant endedAt) {

    public Chat {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(opener, "opener");
    }

    public boolean ended() {
      return endedAt != null;
    }
  }

  /** One logged item: its number in its chat, its kind, what it says as JSON, and when. */
  public record Item(long seq, String kind, String payload, Instant at) {}

  private static final String COLUMNS =
      "id, opener, project, workspace, harness, mode, inside, conversation, turn_open, handed,"
          + " created_at, ended_at";

  private final Sqlite db;

  public ChatStore(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /** Records {@code chat}, opened. */
  public void open(Chat chat) {
    db.execute(
        "INSERT INTO chat_sessions (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        chat.id(),
        chat.opener(),
        chat.project(),
        chat.workspace(),
        chat.harness(),
        chat.mode(),
        chat.inside() ? 1 : 0,
        chat.conversation(),
        chat.turnOpen() ? 1 : 0,
        chat.handed(),
        chat.createdAt(),
        chat.endedAt());
  }

  public Optional<Chat> find(String id) {
    return db.queryOne(
        "SELECT " + COLUMNS + " FROM chat_sessions WHERE id = ?", ChatStore::chat, id);
  }

  /**
   * Logs an item of {@code kind} saying {@code payload} at {@code at}, numbered one past the chat's
   * last; returns its number, or 0 when the chat is gone (erased while its process spoke).
   */
  public long append(String session, String kind, String payload, Instant at) {
    return db.transaction(
        () -> {
          db.execute(
              "INSERT INTO chat_items (session, seq, kind, payload, at)"
                  + " SELECT ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ? FROM chat_items WHERE session = ?"
                  + " HAVING EXISTS (SELECT 1 FROM chat_sessions WHERE id = ?)",
              session,
              kind,
              payload,
              at,
              session,
              session);
          return db.changes() == 0
              ? 0L
              : db.queryOne(
                      "SELECT MAX(seq) FROM chat_items WHERE session = ?",
                      row -> row.integer(0),
                      session)
                  .orElseThrow();
        });
  }

  /** The chat's items after {@code seq}, oldest first, at most {@code limit} of them. */
  public List<Item> after(String session, long seq, int limit) {
    return db.query(
        "SELECT seq, kind, payload, at FROM chat_items WHERE session = ? AND seq > ?"
            + " ORDER BY seq LIMIT ?",
        ChatStore::item,
        session,
        seq,
        limit);
  }

  /**
   * The chat's items of {@code kinds} logged after its last item of kind {@code since} (all of them
   * when it has none), oldest first.
   */
  public List<Item> since(String session, String since, List<String> kinds) {
    var params = new ArrayList<Object>(List.of(session, session, since));
    params.addAll(kinds);
    return db.query(
        "SELECT seq, kind, payload, at FROM chat_items WHERE session = ? AND seq >"
            + " (SELECT COALESCE(MAX(seq), 0) FROM chat_items WHERE session = ? AND kind = ?)"
            + " AND kind IN ("
            + String.join(", ", Collections.nCopies(kinds.size(), "?"))
            + ") ORDER BY seq",
        ChatStore::item,
        params.toArray());
  }

  /** The chat's items of {@code kind} after the last message it handed, oldest first. */
  public List<Item> unhanded(String session, String kind) {
    return db.query(
        "SELECT i.seq, i.kind, i.payload, i.at FROM chat_items i"
            + " JOIN chat_sessions s ON s.id = i.session"
            + " WHERE i.session = ? AND i.kind = ? AND i.seq > s.handed ORDER BY i.seq",
        ChatStore::item,
        session,
        kind);
  }

  /**
   * The harness said the chat is its conversation {@code conversation}, in {@code mode}; either
   * null when it did not say.
   */
  public void said(String session, String conversation, String mode) {
    db.execute(
        "UPDATE chat_sessions SET conversation = COALESCE(?, conversation), mode = COALESCE(?, mode)"
            + " WHERE id = ?",
        conversation,
        mode,
        session);
  }

  /** A turn of the chat opened, or ended. */
  public void turn(String session, boolean open) {
    db.execute("UPDATE chat_sessions SET turn_open = ? WHERE id = ?", open ? 1 : 0, session);
  }

  /** The person's message logged at {@code seq} went to the harness. */
  public void handed(String session, long seq) {
    db.execute("UPDATE chat_sessions SET handed = ? WHERE id = ?", seq, session);
  }

  /** The chat ended at {@code at}: no process holds it again. */
  public void ended(String session, Instant at) {
    db.execute(
        "UPDATE chat_sessions SET ended_at = ?, turn_open = 0 WHERE id = ? AND ended_at IS NULL",
        at,
        session);
  }

  /** The chats a turn was open in, whose process went with a restart of sail. */
  public List<String> openTurns() {
    return db.query(
        "SELECT id FROM chat_sessions WHERE turn_open = 1 ORDER BY id", row -> row.text(0));
  }

  /** Erases the chats of workspace {@code workspace} of {@code project}, with their items. */
  public void eraseWorkspace(String project, String workspace) {
    db.execute("DELETE FROM chat_sessions WHERE project = ? AND workspace = ?", project, workspace);
  }

  /** Erases every chat of {@code project}, with their items. */
  public void eraseProject(String project) {
    db.execute("DELETE FROM chat_sessions WHERE project = ?", project);
  }

  /** Erases the chats that ended before {@code before}; returns how many. */
  public int expire(Instant before) {
    db.execute("DELETE FROM chat_sessions WHERE ended_at IS NOT NULL AND ended_at < ?", before);
    return db.changes();
  }

  private static Item item(Sqlite.Row row) {
    return new Item(row.integer(0), row.text(1), row.text(2), row.instant(3));
  }

  private static Chat chat(Sqlite.Row row) {
    return new Chat(
        row.text(0),
        row.text(1),
        row.text(2),
        row.text(3),
        row.text(4),
        row.text(5),
        row.integer(6) == 1,
        row.text(7),
        row.integer(8) == 1,
        row.integer(9),
        row.instant(10),
        row.instant(11));
  }
}
