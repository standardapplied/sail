/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.identity.Actor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Every stored instant is an integer millisecond: each instant column of every table is declared
 * {@code INTEGER}, every row written through a store, a trigger or a default holds one, and order
 * and expiry compare those integers.
 */
@ActingAs(value = Actor.Lane.CLI, handle = "uday")
class InstantsStoredAsMillisTest {

  private static final Pattern INSTANT_COLUMN = Pattern.compile(".*_at|timestamp|stale_since");
  private static final Instant PINNED = Instant.parse("2026-10-20T19:09:51.123456789Z");

  private Sqlite db;
  private Clock clock;

  @BeforeEach
  void setUp() {
    clock = DateTimeUtils.clock;
    db = Sqlite.openMemory();
    new SchemaManager(db).migrate();
    HeldProjects.hold(db, "proj");
  }

  @AfterEach
  void tearDown() {
    DateTimeUtils.clock = clock;
    db.close();
  }

  private record Column(String table, String name) {}

  private List<Column> instantColumns() {
    var columns = new ArrayList<Column>();
    for (var table :
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            row -> row.text(0))) {
      for (var column :
          db.query("SELECT name FROM pragma_table_info(?)", row -> row.text(0), table)) {
        if (INSTANT_COLUMN.matcher(column).matches()) {
          columns.add(new Column(table, column));
        }
      }
    }
    return columns;
  }

  @Test
  void everyInstantColumnOfEveryTableIsDeclaredAnInteger() {
    var columns = instantColumns();

    assertEquals(true, columns.size() > 40, columns.toString());
    for (var column : columns) {
      assertEquals(
          "INTEGER",
          db.queryOne(
                  "SELECT type FROM pragma_table_info(?) WHERE name = ?",
                  row -> row.text(0),
                  column.table(),
                  column.name())
              .orElseThrow(),
          column.toString());
    }
  }

  @Test
  void whatTheStoresTriggersAndDefaultsWriteIsAnIntegerMillisecondOfTheClock() {
    DateTimeUtils.clock = Clock.fixed(PINNED, ZoneOffset.UTC);
    var specs = new SpecStore(db);
    specs.create(spec("archived"));
    specs.updateStatus("archived", SpecStatus.ARCHIVED);
    new MessageStore(db).append("archived", "uday", "hi", null);
    new SlackThreadStore(db).save("proj", "archived", "C1", "1.2");
    new TokenStore(db).create("ci", "member", null, Duration.ofHours(1));
    new EventStore(db)
        .insert(
            new EventStore.EventRow(0, DateTimeUtils.now(), "t", "proj", null, null, "h", "{}"));
    var runs = new RunStore(db);
    var run = DateTimeUtils.newId().toString();
    runs.create(run, "proj", "archived", "box", "build", "claude", "b", "t", null, null, "/l", "u");
    runs.stampActivity(run, Duration.ZERO);
    runs.complete(run, "completed", 0);
    var reviews = new ReviewStore(db);
    var review = reviews.createReview("archived", 1);
    reviews.createStage(review, "tests", "test");
    new RoomStore(db)
        .create(
            new RoomStore.RoomRow(
                "lounge", "proj", "Lounge", null, null, null, "uday", null, null, "uday"));
    new SyncHealth(db).begin("main", DateTimeUtils.now());
    new SyncState(db).advance("main", "spec", 1L);

    var texts = new ArrayList<String>();
    for (var column : instantColumns()) {
      texts.addAll(
          db.query(
              "SELECT '%s.%s' FROM %s WHERE typeof(%s) NOT IN ('integer', 'null')"
                  .formatted(column.table(), column.name(), column.table(), column.name()),
              row -> row.text(0)));
    }
    assertEquals(List.of(), texts, "no instant is stored as text");
    var millis = PINNED.toEpochMilli();
    assertEquals(
        List.of(millis, millis + 1, millis + 1),
        db.queryOne(
                "SELECT created_at, updated_at, archived_at FROM specs WHERE id = 'archived'",
                row -> List.of(row.integer(0), row.integer(1), row.integer(2)))
            .orElseThrow());
    assertEquals(
        millis,
        db.queryOne("SELECT created_at FROM slack_threads", row -> row.integer(0)).orElseThrow());
  }

  @Test
  void aClockFinerThanAMillisecondIsReadBackAtTheMillisecondItWasStoredAt() {
    DateTimeUtils.clock = Clock.fixed(PINNED, ZoneOffset.UTC);
    var specs = new SpecStore(db);

    specs.create(spec("fine"));

    var stored = specs.findById("fine").orElseThrow().createdAt();
    assertEquals(Instant.parse("2026-10-20T19:09:51.123Z"), stored);
    assertEquals("2026-10-20T19:09:51.123Z", DateTimeUtils.format(stored));
  }

  @Test
  void anExpiryOneMillisecondPastIsSweptAndOneMillisecondAheadIsKeptInEveryTable() {
    var now = Instant.parse("2026-10-20T19:09:39.656Z");
    DateTimeUtils.clock = Clock.fixed(now, ZoneOffset.UTC);
    var fde = new FdeStore(db);
    fde.add("ada", "Ada", "ada@x.dev", "member");
    var id = fde.byHandle("ada").orElseThrow().id();
    for (var sign : List.of("past", "ahead")) {
      var expiry = sign.equals("past") ? now.minusMillis(1) : now.plusMillis(1);
      db.execute(
          "INSERT INTO sessions (token_hash, fde_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
          sign,
          id,
          now,
          expiry);
      db.execute(
          "INSERT INTO webauthn_challenges (id, challenge, ceremony, fde_id, created_at,"
              + " expires_at) VALUES (?, 'c', 'assert', ?, ?, ?)",
          sign,
          id,
          now,
          expiry);
      db.execute(
          "INSERT INTO enrollment_tickets (token_hash, fde_id, created_at, expires_at)"
              + " VALUES (?, ?, ?, ?)",
          sign,
          id,
          now,
          expiry);
      db.execute(
          "INSERT INTO api_tokens (token_hash, name, role, created_at, expires_at)"
              + " VALUES (?, ?, 'member', ?, ?)",
          sign,
          sign,
          now,
          expiry);
      db.execute(
          "INSERT INTO run_credentials (run_id, credential_hash, created_at, expires_at)"
              + " VALUES (?, ?, ?, ?)",
          sign,
          sign,
          now,
          expiry);
    }

    assertEquals(5, ExpiredRowSweeper.sweep(db));

    for (var table :
        List.of(
            "sessions",
            "webauthn_challenges",
            "enrollment_tickets",
            "api_tokens",
            "run_credentials")) {
      assertEquals(
          List.of(now.plusMillis(1)),
          db.query("SELECT expires_at FROM " + table, row -> row.instant(0)),
          table);
    }
  }

  @Test
  void instantsAcrossASecondBoundaryOrderAsTime() {
    var specs = new SpecStore(db);
    DateTimeUtils.clock = Clock.fixed(Instant.parse("2026-10-20T19:09:51.999Z"), ZoneOffset.UTC);
    specs.create(spec("zz-first"));
    DateTimeUtils.clock = Clock.fixed(Instant.parse("2026-10-20T19:09:52.000Z"), ZoneOffset.UTC);
    specs.create(spec("aa-second"));

    assertEquals(
        List.of("zz-first", "aa-second"),
        db.query("SELECT id FROM specs ORDER BY created_at", row -> row.text(0)));
  }

  @Test
  void twoLocalWritesInOneMillisecondAreTwoVersions() {
    var at = Instant.parse("2026-10-20T19:09:51.123Z");
    DateTimeUtils.clock = Clock.fixed(at, ZoneOffset.UTC);
    var specs = new SpecStore(db);
    specs.create(spec("busy"));

    specs.updateStatus("busy", SpecStatus.IN_PROGRESS);
    var first = specs.findById("busy").orElseThrow().updatedAt();
    specs.updateStatus("busy", SpecStatus.REVIEW);
    var second = specs.findById("busy").orElseThrow().updatedAt();

    assertEquals(at.plusMillis(1), first);
    assertEquals(at.plusMillis(2), second);
  }

  @Test
  void retentionRetiresTheMessageBeforeTheCutoffAndKeepsTheOneAfter() {
    DateTimeUtils.clock = Clock.fixed(Instant.parse("2026-10-20T00:00:00Z"), ZoneOffset.UTC);
    var messages = new MessageStore(db);
    var older = messages.append("room", "uday", "older", null);
    DateTimeUtils.clock = Clock.fixed(Instant.parse("2026-10-20T00:00:02Z"), ZoneOffset.UTC);
    messages.append("room", "uday", "newer", null);

    assertEquals(
        List.of(older.id()), messages.retiredBefore(Instant.parse("2026-10-20T00:00:01Z"), 10));
  }

  private static SpecStore.SpecRow spec(String id) {
    return new SpecStore.SpecRow(
        id,
        "proj",
        id,
        SpecStatus.PENDING,
        null,
        null,
        null,
        null,
        null,
        0,
        "uday",
        null,
        null,
        "uday",
        List.of(),
        List.of());
  }
}
