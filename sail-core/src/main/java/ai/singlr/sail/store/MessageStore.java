/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Append-only messages attached to rooms and journaled as independently synced records. */
public final class MessageStore implements SyncedStore {

  public static final int MAX_BODY_BYTES = 64 * 1024;
  private static final String ENTITY = "message";
  private static final String COLUMNS =
      "id, room_id, author, body, reply_to, created_at, rev, base_rev, question";

  private final Sqlite db;
  private final ChangeLog changeLog;

  public MessageStore(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
    this.changeLog = new ChangeLog(db);
  }

  public record MessageRow(
      String id,
      String roomId,
      String author,
      String body,
      String replyTo,
      String createdAt,
      String rev,
      String baseRev,
      boolean question) {}

  public MessageRow append(String roomId, String author, String body, String replyTo) {
    return append(roomId, author, body, replyTo, false);
  }

  public MessageRow append(
      String roomId, String author, String body, String replyTo, boolean question) {
    requireBody(body);
    if (Strings.isBlank(author)) {
      throw new IllegalArgumentException("message author is required");
    }
    if (replyTo != null) {
      Ids.requireUuid(replyTo);
    }
    return db.transaction(
        () -> {
          var id = DateTimeUtils.newId().toString();
          var row =
              new MessageRow(
                  id,
                  roomId,
                  author.strip(),
                  body,
                  replyTo,
                  DateTimeUtils.now().toString(),
                  null,
                  null,
                  question);
          requireReplyTarget(row);
          var snapshot = snapshot(row);
          var rev = Revisions.next(null, YamlUtil.dumpJson(snapshot));
          write(row, rev, null);
          changeLog.append(
              ENTITY, id, rev, author.strip(), "local", false, YamlUtil.dumpJson(snapshot));
          return findById(id).orElseThrow();
        });
  }

  public List<MessageRow> list(String roomId, String before, int limit) {
    if (before != null) {
      Ids.requireUuid(before);
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    var newest =
        before == null
            ? db.query(
                "SELECT "
                    + COLUMNS
                    + " FROM room_messages WHERE room_id = ?"
                    + " ORDER BY id DESC LIMIT ?",
                MessageStore::map,
                roomId,
                limit)
            : db.query(
                "SELECT "
                    + COLUMNS
                    + " FROM room_messages WHERE room_id = ? AND id < ?"
                    + " ORDER BY id DESC LIMIT ?",
                MessageStore::map,
                roomId,
                before,
                limit);
    var oldest = new ArrayList<>(newest);
    Collections.reverse(oldest);
    return List.copyOf(oldest);
  }

  /**
   * Messages of {@code roomId} strictly newer than {@code after} (a message id, or null for all),
   * oldest first, capped at {@code limit}. Ids are UUIDv7 strings, so {@code id > ?} is mint-time
   * order — a forward paging cursor for room reads. Not a delivery primitive: mint order is not
   * arrival order across synced boxes, so delivery goes through {@link #listUndelivered}.
   */
  public List<MessageRow> listAfter(String roomId, String after, int limit) {
    if (after != null) {
      Ids.requireUuid(after);
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    if (after == null) {
      return db.query(
          "SELECT " + COLUMNS + " FROM room_messages WHERE room_id = ? ORDER BY id ASC LIMIT ?",
          MessageStore::map,
          roomId,
          limit);
    }
    return db.query(
        "SELECT "
            + COLUMNS
            + " FROM room_messages WHERE room_id = ? AND id > ? ORDER BY id ASC LIMIT ?",
        MessageStore::map,
        roomId,
        after,
        limit);
  }

  /**
   * Messages of {@code roomId} absent from {@code runId}'s delivery ledger and not authored by
   * {@code excludeAuthor} (a run is never told its own story), oldest first, capped at {@code
   * limit}. Delivery is by exact identity, so a message that synchronized in with an older id after
   * newer messages were already delivered still appears here.
   */
  public List<MessageRow> listUndelivered(
      String roomId, String runId, String excludeAuthor, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    return db.query(
        "SELECT "
            + COLUMNS
            + " FROM room_messages WHERE room_id = ? AND author != ?"
            + " AND NOT EXISTS (SELECT 1 FROM run_delivered_messages"
            + " WHERE run_id = ? AND message_id = room_messages.id)"
            + " ORDER BY id ASC LIMIT ?",
        MessageStore::map,
        roomId,
        excludeAuthor,
        runId,
        limit);
  }

  /** The id of the room's newest message, or empty for a silent room. */
  public Optional<String> newestId(String roomId) {
    return db.queryOne(
        "SELECT id FROM room_messages WHERE room_id = ? ORDER BY id DESC LIMIT 1",
        row -> row.text(0),
        roomId);
  }

  /**
   * Each room whose latest agent-authored question is still unanswered, mapped to that question's
   * message id — one aggregate query. A question is answered by any later human message in the
   * room; the author classes are structural, mirroring {@code RoomWakePolicy.humanAuthor}: an agent
   * principal always carries a {@code /}, the orchestrator posts as the literal {@code sail}, and
   * FDE handles contain neither.
   *
   * <p>"Later" is message-id order, which is origin-mint order, so this is content-deterministic
   * and consistent across boxes at sync-freshness. Known edge: a human message posted in the room
   * during the sync lag <em>before</em> a question arrives has a higher id and reads as answering
   * it, so the chip and notification will not fire for that question. This never breaks the loop —
   * {@code RoomWakeReactor} resumes the agent on any human reply regardless of this flag, and the
   * question stays visible in the room. A robust fix needs per-box arrival order (which breaks
   * cross-box determinism) or delivery receipts (a deliberate non-goal), so it stays a documented
   * edge.
   */
  public Map<String, String> openQuestions() {
    var open = new LinkedHashMap<String, String>();
    for (var entry :
        db.query(
            "SELECT q.room_id, MAX(q.id) FROM room_messages q"
                + " WHERE q.question != 0 AND q.author LIKE '%/%'"
                + " AND NOT EXISTS (SELECT 1 FROM room_messages h"
                + " WHERE h.room_id = q.room_id AND h.id > q.id"
                + " AND h.author NOT LIKE '%/%' AND h.author != 'sail')"
                + " GROUP BY q.room_id",
            row -> Map.entry(row.text(0), row.text(1)))) {
      open.put(entry.getKey(), entry.getValue());
    }
    return open;
  }

  /** Each room's newest message timestamp — one aggregate query, keyed by spec id. */
  public Map<String, String> latestByRoom() {
    var latest = new LinkedHashMap<String, String>();
    for (var entry :
        db.query(
            "SELECT room_id, MAX(created_at) FROM room_messages GROUP BY room_id",
            row -> Map.entry(row.text(0), row.text(1)))) {
      latest.put(entry.getKey(), entry.getValue());
    }
    return latest;
  }

  public Optional<MessageRow> findById(String id) {
    return db.queryOne(
        "SELECT " + COLUMNS + " FROM room_messages WHERE id = ?", MessageStore::map, id);
  }

  @Override
  public String entityType() {
    return ENTITY;
  }

  public Map<String, Object> comparableSnapshot(String id) {
    return findById(id).map(MessageStore::snapshot).orElse(null);
  }

  public Map<String, Object> comparableAtRev(String id, String rev) {
    if (Strings.isBlank(rev)) {
      return null;
    }
    return changeLog
        .at(ENTITY, id, rev)
        .map(entry -> YamlUtil.parseMap(entry.snapshot()))
        .orElse(null);
  }

  public String latestRev(String id) {
    return findById(id).map(MessageRow::rev).orElse(null);
  }

  public String baseRevOf(String id) {
    return findById(id).map(MessageRow::baseRev).orElse(null);
  }

  public Set<String> syncEntityIds() {
    return new LinkedHashSet<>(
        db.query(
            "SELECT entity_id FROM change_log WHERE entity_type = ?"
                + " GROUP BY entity_id ORDER BY MIN(seq)",
            row -> row.text(0),
            ENTITY));
  }

  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    if (snapshot == null) {
      throw new IllegalArgumentException("messages are immutable and cannot be deleted");
    }
    db.transaction(
        () -> {
          var existing = findById(id);
          if (existing.isPresent() && !comparableSnapshot(id).equals(snapshot)) {
            throw new IllegalArgumentException("message '" + id + "' is immutable");
          }
          var row = fromSnapshot(id, snapshot);
          requireReplyTarget(row);
          write(row, rev, rev);
          if (!Objects.equals(existing.map(MessageRow::rev).orElse(null), rev)) {
            changeLog.append(
                ENTITY,
                id,
                rev,
                Objects.toString(snapshot.get("author"), null),
                "sync",
                false,
                YamlUtil.dumpJson(snapshot));
          }
        });
  }

  public PushOutcome commitRevision(String id, Map<String, Object> snapshot, String expectedRev) {
    return db.immediateTransaction(
        () -> {
          var currentRev = latestRev(id);
          if (!Objects.equals(currentRev, expectedRev)) {
            return new PushOutcome.Stale(currentRev, comparableSnapshot(id));
          }
          if (snapshot == null) {
            throw new IllegalArgumentException("messages are immutable and cannot be deleted");
          }
          if (currentRev != null) {
            throw new IllegalArgumentException("message '" + id + "' is immutable");
          }
          var json = YamlUtil.dumpJson(snapshot);
          var rev = Revisions.next(null, json);
          var row = fromSnapshot(id, snapshot);
          var peer = SyncPeer.current();
          if (!mayPostAs(peer, row.author(), row.roomId())) {
            throw new IllegalArgumentException(
                "sync principal '" + peer + "' may not post as '" + row.author() + "'");
          }
          requireReplyTarget(row);
          write(row, rev, null);
          changeLog.append(ENTITY, id, rev, row.author(), "sync", false, json);
          return new PushOutcome.Accepted(rev);
        });
  }

  /** The platform narrator: the review pipeline posts room verdicts under this author. */
  public static final String SAIL_AUTHOR = "sail";

  /**
   * Whether {@code peer} may sync a message authored by {@code author} into {@code roomId}. A peer
   * owns its own handle, the agent principals of runs its box executed (current or historical), and
   * the platform narrator {@link #SAIL_AUTHOR} — but the narrator only for conversations the peer's
   * box ran something in: the review pipeline narrates where it executed, and runs sync before
   * messages, so the run row is the evidence. Posting authority over the room is required on top.
   */
  private boolean mayPostAs(String peer, String author, String roomId) {
    if (peer == null) {
      return false;
    }
    var ownsAuthor =
        peer.equals(author)
            || (SAIL_AUTHOR.equals(author) && ranInConversation(peer, roomId))
            || db.queryOne(
                    "SELECT 1 FROM runs r WHERE r.owner = ? AND (r.spec_id = ? OR r.room_id = ?)"
                        + " AND (r.principal = ? OR EXISTS (SELECT 1 FROM run_principals rp"
                        + " WHERE rp.run_id = r.id AND rp.principal = ?)) LIMIT 1",
                    row -> true,
                    peer,
                    roomId,
                    roomId,
                    author,
                    author)
                .orElse(false);
    if (!ownsAuthor) {
      return false;
    }
    return postAuthority(peer, "FROM rooms s", "s.id = ?", roomId)
        || postAuthority(peer, "FROM specs s", "s.room_id = ?", roomId);
  }

  private boolean ranInConversation(String peer, String roomId) {
    return db.queryOne(
            "SELECT 1 FROM runs r WHERE r.owner = ? AND (r.spec_id = ? OR r.room_id = ?) LIMIT 1",
            row -> true,
            peer,
            roomId,
            roomId)
        .orElse(false);
  }

  /**
   * Whether {@code peer}'s box holds posting authority over the conversation: an admin FDE, the
   * assignee, or the creator of an unassigned surface. Resolved against the room row and against
   * any spec attached to the room — a spec's ownership fields stay authoritative for policy even
   * when its room row has not been minted or has not arrived yet (a synced-in or imported spec).
   */
  private boolean postAuthority(String peer, String from, String where, String roomId) {
    return db.queryOne(
            "SELECT 1 "
                + from
                + " LEFT JOIN fdes f ON f.handle = ? WHERE "
                + where
                + " AND (lower(coalesce(f.role, '')) = 'admin' OR s.assignee = ?"
                + " OR (trim(coalesce(s.assignee, '')) = '' AND s.created_by = ?)) LIMIT 1",
            row -> true,
            peer,
            roomId,
            peer,
            peer)
        .orElse(false);
  }

  private void write(MessageRow row, String rev, String baseRev) {
    db.execute(
        """
        INSERT INTO room_messages
            (id, room_id, author, body, reply_to, created_at, rev, base_rev, question)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET rev = excluded.rev, base_rev = excluded.base_rev""",
        row.id(),
        row.roomId(),
        row.author(),
        row.body(),
        row.replyTo(),
        row.createdAt(),
        rev,
        baseRev,
        row.question() ? 1 : 0);
  }

  private void requireReplyTarget(MessageRow row) {
    if (row.replyTo() != null
        && findById(row.replyTo())
            .filter(parent -> parent.roomId().equals(row.roomId()))
            .isEmpty()) {
      throw new IllegalArgumentException(
          "reply_to must reference a message in room '" + row.roomId() + "'");
    }
  }

  private static MessageRow fromSnapshot(String id, Map<String, Object> snapshot) {
    Ids.requireUuid(id);
    var body = Objects.toString(snapshot.get("body"), null);
    requireBody(body);
    var author = Objects.toString(snapshot.get("author"), null);
    if (Strings.isBlank(author)) {
      throw new IllegalArgumentException("message author is required");
    }
    var replyTo = nullable(snapshot.get("reply_to"));
    if (replyTo != null) {
      Ids.requireUuid(replyTo);
    }
    return new MessageRow(
        id,
        roomIdOf(snapshot),
        author,
        body,
        replyTo,
        required(snapshot, "created_at"),
        null,
        null,
        Boolean.parseBoolean(Objects.toString(snapshot.get("question"), "false")));
  }

  /**
   * The room a snapshot belongs to: {@code room_id}, falling back to the {@code spec_id} key that
   * pre-rename journal entries and pre-rename revisions carry — rooms kept the spec's id at the
   * split, so the value is the same room either way.
   */
  private static String roomIdOf(Map<String, Object> snapshot) {
    var roomId = Objects.toString(snapshot.get("room_id"), null);
    return roomId != null ? roomId : required(snapshot, "spec_id");
  }

  private static Map<String, Object> snapshot(MessageRow row) {
    var map = new LinkedHashMap<String, Object>();
    map.put("room_id", row.roomId());
    map.put("author", row.author());
    map.put("body", row.body());
    if (row.replyTo() != null) {
      map.put("reply_to", row.replyTo());
    }
    map.put("created_at", row.createdAt());
    if (row.question()) {
      map.put("question", true);
    }
    return map;
  }

  private static MessageRow map(Sqlite.Row row) {
    return new MessageRow(
        row.text(0),
        row.text(1),
        row.text(2),
        row.text(3),
        row.text(4),
        row.text(5),
        row.text(6),
        row.text(7),
        row.integer(8) != 0);
  }

  private static void requireBody(String body) {
    if (Strings.isBlank(body)) {
      throw new IllegalArgumentException("message body must not be empty");
    }
    if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
      throw new IllegalArgumentException("message body exceeds 65536 bytes");
    }
  }

  private static String required(Map<String, Object> map, String key) {
    var value = nullable(map.get(key));
    if (Strings.isBlank(value)) {
      throw new IllegalArgumentException("message " + key + " is required");
    }
    return value;
  }

  private static String nullable(Object value) {
    return value == null ? null : value.toString();
  }
}
