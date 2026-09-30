/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.authority.MessageAuthority;
import ai.singlr.sail.authority.WriteAuthority;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Append-only messages attached to rooms and journaled as independently synced records. */
public final class MessageStore implements ConflictResolver, SyncedStore {

  public static final int MAX_BODY_BYTES = 64 * 1024;
  private static final String ENTITY = "message";

  /**
   * Whether run {@code %1$s} belongs to the conversation named by {@code %2$s}: a run of the room
   * itself, or of a spec whose room it is. A spec born in a room posts there while its runs name
   * only the spec, so matching on the spec's id alone would miss them.
   */
  private static final String RUN_IN_CONVERSATION =
      """
      (%1$s.room_id = %2$s OR %1$s.spec_id = %2$s
          OR %1$s.spec_id IN (SELECT id FROM specs WHERE room_id = %2$s))""";

  /**
   * Whether run {@code %1$s}, on a node, is one main holds, with the spec it belongs to: main
   * places a run in a spec's conversation through its own copy of that spec.
   */
  private static final String RUN_ON_MAIN =
      """
      (coalesce(%1$s.base_rev, '') <> '' AND NOT EXISTS (SELECT 1 FROM specs s
          WHERE s.id = %1$s.spec_id AND coalesce(s.base_rev, '') = ''))""";

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
          journal(row, rev, ChangeLog.Entry.LOCAL, snapshot);
          return findById(id).orElseThrow();
        });
  }

  /** Who may post on this box, and as whom: the rule every door and main's commit decide by. */
  @Override
  public MessageAuthority authority() {
    return new MessageAuthority(db);
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

  /**
   * Up to {@code limit} messages message retention erases: posted before {@code cutoff}, and never
   * one a thread still needs — a parent a younger reply points at, directly or down its thread, an
   * agent's question still waiting for its answer, or the messages that question replies to.
   */
  public List<String> retiredBefore(Instant cutoff, int limit) {
    var open = YamlUtil.dumpJson(List.copyOf(openQuestions().values()));
    return db.query(
        """
        WITH RECURSIVE kept(id) AS (
            SELECT reply_to FROM room_messages
            WHERE (julianday(created_at) >= julianday(?1)
                OR id IN (SELECT value FROM json_each(?2)))
            AND reply_to IS NOT NULL
            UNION
            SELECT m.reply_to FROM room_messages m JOIN kept k ON m.id = k.id
            WHERE m.reply_to IS NOT NULL)
        SELECT id FROM room_messages
        WHERE julianday(created_at) < julianday(?1)
        AND id NOT IN (SELECT id FROM kept)
        AND id NOT IN (SELECT value FROM json_each(?2))
        ORDER BY id LIMIT ?3""",
        row -> row.text(0),
        cutoff.toString(),
        open,
        limit);
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
  public Set<String> latestWinsFields() {
    return Set.of();
  }

  @Override
  public Map<String, Object> currentForSync(String id) {
    return comparableSnapshot(id);
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

  /**
   * Messages this box posted that main has not acknowledged, oldest first, so a reply is offered
   * after the message it answers. Main decides an agent's post by its principal's run, in whatever
   * conversation it runs, and the pipeline's by a run of the same owner in the conversation, so
   * such a post waits, with the replies under it, until main holds what decides it: a run it names
   * has a change main has not taken, or the pipeline's conversation has a run of that owner only
   * this box holds. A post arriving first would be denied for good. Runs sync before messages, so
   * it normally goes later in the round.
   */
  public Set<String> dirtyIds() {
    return new LinkedHashSet<>(
        db.query(
            """
            WITH RECURSIVE
              pending AS (
                SELECT id, room_id, author, reply_to, rowid AS seq FROM room_messages
                WHERE base_rev IS NULL OR base_rev = '' OR rev <> base_rev),
              held(id) AS (
                SELECT p.id FROM pending p
                WHERE EXISTS (SELECT 1 FROM runs r
                    WHERE (NOT %2$s OR r.rev <> r.base_rev)
                      AND (r.principal = p.author OR EXISTS (SELECT 1 FROM run_principals rp
                          WHERE rp.run_id = r.id AND rp.principal = p.author)))
                  OR (p.author = ?1 AND EXISTS (SELECT 1 FROM runs r
                    WHERE %1$s AND NOT %2$s
                      AND NOT EXISTS (SELECT 1 FROM runs a
                          WHERE %3$s AND %4$s AND a.owner = r.owner)))
                UNION
                SELECT p.id FROM pending p JOIN held h ON p.reply_to = h.id)
            SELECT id FROM pending WHERE id NOT IN (SELECT id FROM held) ORDER BY seq"""
                .formatted(
                    RUN_IN_CONVERSATION.formatted("r", "p.room_id"),
                    RUN_ON_MAIN.formatted("r"),
                    RUN_IN_CONVERSATION.formatted("a", "p.room_id"),
                    RUN_ON_MAIN.formatted("a")),
            row -> row.text(0),
            SAIL_AUTHOR));
  }

  public Set<String> syncEntityIds() {
    return new LinkedHashSet<>(
        db.query(
            "SELECT entity_id FROM change_log WHERE entity_type = ?"
                + " GROUP BY entity_id ORDER BY MIN(seq)",
            row -> row.text(0),
            ENTITY));
  }

  /**
   * Adopts main's copy of a message. Main holding none ({@code snapshot} and {@code rev} both null)
   * is how a message main denied leaves this box: it goes with the replies this box posted under
   * it, which main cannot hold either, and every revision stays in the change log.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    if (snapshot == null && rev == null) {
      withdraw(id);
      return;
    }
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
            journal(row, rev, ChangeLog.Entry.SYNC, snapshot);
          }
        });
  }

  /**
   * Withdraws every post by one of {@code authors} that main has not taken, with the replies under
   * it: what a run main denied posted leaves this box with the run.
   */
  public void withdrawUnsynced(Collection<String> authors) {
    if (authors.isEmpty()) {
      return;
    }
    db.transaction(
        () ->
            db.query(
                    """
                    SELECT id FROM room_messages
                    WHERE (base_rev IS NULL OR base_rev = '')
                    AND author IN (SELECT value FROM json_each(?))""",
                    row -> row.text(0),
                    YamlUtil.dumpJson(List.copyOf(authors)))
                .forEach(this::withdraw));
  }

  private void withdraw(String id) {
    db.execute(
        """
        WITH RECURSIVE thread(id) AS (
            SELECT ?1
            UNION
            SELECT m.id FROM room_messages m JOIN thread t ON m.reply_to = t.id)
        DELETE FROM room_messages WHERE id IN (SELECT id FROM thread)""",
        id);
  }

  /**
   * How a message main holds leaves this box: erased with its room or by retention, never edited or
   * deleted on its own. A reply erased with it may go first; {@link Erasure} defers the reply
   * constraint to its commit.
   */
  @Override
  public void eraseRow(String id) {
    db.execute("DELETE FROM room_messages WHERE id = ?", id);
  }

  /**
   * Compare-and-set commit as main of a message a node posted. A message never changes, so only a
   * new one commits. One in a conversation main has never held is refused ({@link Unheld}), and one
   * replying to a message main does not hold is denied; {@code authority} then decides who may post
   * it, and as whom, before anything is written.
   */
  @Override
  public PushOutcome commitRevision(
      String id, Map<String, Object> snapshot, String expectedRev, WriteAuthority authority) {
    return db.transaction(
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
          var row = fromSnapshot(id, snapshot);
          requireDecidable(row);
          if (!holdsReplyTarget(row)) {
            return new PushOutcome.Denied("it replies to a message main does not hold", null, null);
          }
          var refusal = authority.decide(Actor.current(), id, null, snapshot);
          if (refusal.isPresent()) {
            return new PushOutcome.Denied(refusal.get().message(), null, null);
          }
          var json = YamlUtil.dumpJson(snapshot);
          var rev = Revisions.next(null, json);
          write(row, rev, null);
          journal(row, rev, ChangeLog.Entry.SYNC, snapshot);
          return new PushOutcome.Accepted(rev);
        });
  }

  /** The platform narrator: the review pipeline posts room verdicts under this author. */
  public static final String SAIL_AUTHOR = "sail";

  /**
   * Refuses to decide a post in a conversation main has never held ({@link SyncedStore.Unheld}):
   * its room or spec syncs before its messages, so the next round decides it, where a denial now
   * would remove a message whose room had simply not arrived. A conversation main held and lost is
   * decided like any other.
   */
  private void requireDecidable(MessageRow row) {
    if (!new RoomStore(db).holdsConversation(row.roomId())) {
      throw new Unheld(
          "main does not hold room '"
              + row.roomId()
              + "' yet; rooms sync before their messages, so the next round settles this");
    }
  }

  /**
   * Whether a run acting for {@code owner} is in conversation {@code roomId}: the evidence that the
   * owner's box ran the pipeline that narrates there as {@link #SAIL_AUTHOR}.
   */
  public boolean ranInConversation(String owner, String roomId) {
    return db.queryOne(
            "SELECT 1 FROM runs r WHERE r.owner = ?1 AND %s LIMIT 1"
                .formatted(RUN_IN_CONVERSATION.formatted("r", "?2")),
            row -> true,
            owner,
            roomId)
        .orElse(false);
  }

  /**
   * Messages are append-only, so the only resolution that can stand is main's: {@code chosen} must
   * be main's copy. The local row is replaced by it at main's rev, under the author main recorded,
   * and rebased onto it, exactly as a pull adopts it. Keeping mine would need main to rewrite a
   * message, which the wire refuses.
   */
  @Override
  public String resolveConflict(String id, Map<String, Object> chosen, MainVersion theirs) {
    var remote = theirs.snapshot();
    if (remote == null || !remote.equals(chosen)) {
      throw new IllegalArgumentException(
          "message '" + id + "' is append-only: main's copy stands; resolve it with --theirs");
    }
    Strings.requireNonBlank(theirs.rev(), "The revision of main's copy");
    Actor.run(Actor.main(theirs.author()), () -> adoptMainCopy(id, remote, theirs.rev()));
    return theirs.rev();
  }

  private void adoptMainCopy(String id, Map<String, Object> remote, String rev) {
    db.transaction(
        () -> {
          var row = fromSnapshot(id, remote);
          requireReplyTarget(row);
          replace(row, rev);
          journal(row, rev, ChangeLog.Entry.SYNC, remote);
          return null;
        });
  }

  /**
   * Journals {@code row} at {@code rev} under the author it names, as every box records a message:
   * who posted it, decided by {@link MessageAuthority} before it was written.
   */
  private void journal(MessageRow row, String rev, String origin, Map<String, Object> snapshot) {
    changeLog.appendAuthored(
        ENTITY, row.id(), rev, row.author(), origin, YamlUtil.dumpJson(snapshot));
  }

  private void replace(MessageRow row, String rev) {
    db.execute(
        """
        INSERT INTO room_messages
            (id, room_id, author, body, reply_to, created_at, rev, base_rev, question)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            room_id = excluded.room_id, author = excluded.author, body = excluded.body,
            reply_to = excluded.reply_to, created_at = excluded.created_at,
            rev = excluded.rev, base_rev = excluded.base_rev, question = excluded.question""",
        row.id(),
        row.roomId(),
        row.author(),
        row.body(),
        row.replyTo(),
        row.createdAt(),
        rev,
        rev,
        row.question() ? 1 : 0);
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
    if (!holdsReplyTarget(row)) {
      throw new IllegalArgumentException(
          "reply_to must reference a message in room '" + row.roomId() + "'");
    }
  }

  private boolean holdsReplyTarget(MessageRow row) {
    return row.replyTo() == null
        || findById(row.replyTo())
            .filter(parent -> parent.roomId().equals(row.roomId()))
            .isPresent();
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
  public static String roomIdOf(Map<String, Object> snapshot) {
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
