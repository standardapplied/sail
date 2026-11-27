/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.engine.LiveStream;
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
   * harness's own id of the conversation once said, whether a turn is open, whether a process was
   * started for it that is not yet known gone ({@code launched}), the last of the person's messages
   * handed to the harness ({@code handed}, a seq), and when it was opened and ended.
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
      boolean launched,
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

  /**
   * The most characters an item's payload holds. An item is said from one line the harness wrote,
   * of at most {@link LiveStream#MAX_LINE_CHARS}, each character of which it writes in at most six
   * (an escape) beside its own few names, or from Sail's own words, which are short: a payload past
   * this is no item, and every reader of one parses within it.
   */
  public static final int MAX_PAYLOAD_CHARS = 8 * LiveStream.MAX_LINE_CHARS;

  private static final String COLUMNS =
      "id, opener, project, workspace, harness, mode, inside, conversation, turn_open, launched,"
          + " handed, created_at, ended_at";

  private final Sqlite db;

  public ChatStore(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /** Records {@code chat}, opened. */
  public void open(Chat chat) {
    db.execute(
        "INSERT INTO chat_sessions ("
            + COLUMNS
            + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        chat.id(),
        chat.opener(),
        chat.project(),
        chat.workspace(),
        chat.harness(),
        chat.mode(),
        chat.inside() ? 1 : 0,
        chat.conversation(),
        chat.turnOpen() ? 1 : 0,
        chat.launched() ? 1 : 0,
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
   *
   * @throws IllegalArgumentException when {@code payload} is past {@link #MAX_PAYLOAD_CHARS}
   */
  public long append(String session, String kind, String payload, Instant at) {
    if (payload.length() > MAX_PAYLOAD_CHARS) {
      throw new IllegalArgumentException(
          "An item of chat "
              + session
              + " is "
              + payload.length()
              + " characters, past the "
              + MAX_PAYLOAD_CHARS
              + " any item can be.");
    }
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

  /**
   * The chat's items of {@code kinds} that name {@code id}, as their own ({@code id}) or as the
   * request they answer ({@code request}), oldest first.
   */
  public List<Item> naming(String session, String id, List<String> kinds) {
    var params = new ArrayList<Object>(List.of(session, id, id));
    params.addAll(kinds);
    return db.query(
        "SELECT seq, kind, payload, at FROM chat_items WHERE session = ? AND"
            + " (json_extract(payload, '$.id') = ? OR json_extract(payload, '$.request') = ?)"
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

  /** A process was started for the chat, or the one that was is known gone. */
  public void launched(String session, boolean launched) {
    db.execute("UPDATE chat_sessions SET launched = ? WHERE id = ?", launched ? 1 : 0, session);
  }

  /** The chats a process was started for that is not known gone, ended or not. */
  public List<String> launched() {
    return db.query(
        "SELECT id FROM chat_sessions WHERE launched = 1 ORDER BY id", row -> row.text(0));
  }

  /** The open chats with items of {@code kind} after the last message they handed. */
  public List<String> queued(String kind) {
    return db.query(
        "SELECT s.id FROM chat_sessions s WHERE s.ended_at IS NULL AND EXISTS (SELECT 1 FROM"
            + " chat_items i WHERE i.session = s.id AND i.kind = ? AND i.seq > s.handed) ORDER BY"
            + " s.id",
        row -> row.text(0),
        kind);
  }

  /** Runs {@code work}, its every write to the chats, as one: all of it is kept, or none. */
  public void together(Runnable work) {
    db.transaction(work);
  }

  /** The person's message logged at {@code seq} went to the harness. */
  public void handed(String session, long seq) {
    db.execute("UPDATE chat_sessions SET handed = ? WHERE id = ?", seq, session);
  }

  /**
   * The chat ended at {@code at}: no process is started for it again. A turn it has open is closed
   * by its process's cut, which says why.
   */
  public void ended(String session, Instant at) {
    db.execute(
        "UPDATE chat_sessions SET ended_at = ? WHERE id = ? AND ended_at IS NULL", at, session);
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
        row.integer(9) == 1,
        row.integer(10),
        row.instant(11),
        row.instant(12));
  }
}
