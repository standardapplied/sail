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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Append-only messages attached to specs and journaled as independently synced records. */
public final class MessageStore {

  public static final int MAX_BODY_BYTES = 64 * 1024;
  private static final String ENTITY = "message";
  private static final String COLUMNS =
      "id, spec_id, author, body, reply_to, created_at, rev, base_rev";

  private final Sqlite db;
  private final ChangeLog changeLog;

  public MessageStore(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
    this.changeLog = new ChangeLog(db);
  }

  public record MessageRow(
      String id,
      String specId,
      String author,
      String body,
      String replyTo,
      String createdAt,
      String rev,
      String baseRev) {}

  public MessageRow append(String specId, String author, String body, String replyTo) {
    requireBody(body);
    if (Strings.isBlank(author)) {
      throw new IllegalArgumentException("message author is required");
    }
    if (replyTo != null) {
      Ids.requireUuid(replyTo);
    }
    return db.transaction(
        () -> {
          var id = Ids.newId().toString();
          var row =
              new MessageRow(
                  id,
                  specId,
                  author.strip(),
                  body,
                  replyTo,
                  DateTimeUtils.now().toString(),
                  null,
                  null);
          requireReplyTarget(row);
          var snapshot = snapshot(row);
          var rev = Revisions.next(null, YamlUtil.dumpJson(snapshot));
          write(row, rev, null);
          changeLog.append(
              ENTITY, id, rev, author.strip(), "local", false, YamlUtil.dumpJson(snapshot));
          return findById(id).orElseThrow();
        });
  }

  public List<MessageRow> list(String specId, String before, int limit) {
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
                    + " FROM spec_messages WHERE spec_id = ?"
                    + " ORDER BY id DESC LIMIT ?",
                MessageStore::map,
                specId,
                limit)
            : db.query(
                "SELECT "
                    + COLUMNS
                    + " FROM spec_messages WHERE spec_id = ? AND id < ?"
                    + " ORDER BY id DESC LIMIT ?",
                MessageStore::map,
                specId,
                before,
                limit);
    var oldest = new ArrayList<>(newest);
    java.util.Collections.reverse(oldest);
    return List.copyOf(oldest);
  }

  public Optional<MessageRow> findById(String id) {
    return db.queryOne(
        "SELECT " + COLUMNS + " FROM spec_messages WHERE id = ?", MessageStore::map, id);
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
          requireReplyTarget(row);
          write(row, rev, null);
          changeLog.append(ENTITY, id, rev, row.author(), "sync", false, json);
          return new PushOutcome.Accepted(rev);
        });
  }

  private void write(MessageRow row, String rev, String baseRev) {
    db.execute(
        """
        INSERT INTO spec_messages
            (id, spec_id, author, body, reply_to, created_at, rev, base_rev)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET rev = excluded.rev, base_rev = excluded.base_rev""",
        row.id(),
        row.specId(),
        row.author(),
        row.body(),
        row.replyTo(),
        row.createdAt(),
        rev,
        baseRev);
  }

  private void requireReplyTarget(MessageRow row) {
    if (row.replyTo() != null
        && findById(row.replyTo())
            .filter(parent -> parent.specId().equals(row.specId()))
            .isEmpty()) {
      throw new IllegalArgumentException(
          "reply_to must reference a message on spec '" + row.specId() + "'");
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
        required(snapshot, "spec_id"),
        author,
        body,
        replyTo,
        required(snapshot, "created_at"),
        null,
        null);
  }

  private static Map<String, Object> snapshot(MessageRow row) {
    var map = new LinkedHashMap<String, Object>();
    map.put("spec_id", row.specId());
    map.put("author", row.author());
    map.put("body", row.body());
    if (row.replyTo() != null) {
      map.put("reply_to", row.replyTo());
    }
    map.put("created_at", row.createdAt());
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
        row.text(7));
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
