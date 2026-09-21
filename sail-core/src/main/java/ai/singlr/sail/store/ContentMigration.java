/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.config.YamlUtil;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

/** Resumable conversion of live content, retained revisions, and every conflict side. */
public final class ContentMigration implements DataMigration {
  public static final String NAME = "content-addressed-blobs-v1";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean resumable() {
    return true;
  }

  @Override
  public Report apply(Sqlite db, ProjectRegistry projects, Prompter prompter) {
    var blobs = new BlobStore(db);
    var count = 0;
    var before = bytes(db);
    for (var type : List.of("spec", "file")) {
      var table = type.equals("spec") ? "specs" : "project_files";
      var ids =
          db.query(
              "SELECT id FROM "
                  + table
                  + " UNION SELECT entity_id FROM change_log WHERE entity_type = ? UNION SELECT entity_id FROM sync_conflicts WHERE entity_type = ?",
              r -> r.text(0),
              type,
              type);
      for (var id : ids) {
        var changed = db.transaction(() -> migrateEntity(db, blobs, type, id));
        if (changed) count++;
      }
    }
    db.transaction(
        () -> {
          if (hasContentColumn(db)) db.execute("ALTER TABLE project_files DROP COLUMN content");
        });
    return new Report(
        count,
        0,
        0,
        List.of(
            "Migrated " + count + " entities; stored " + (bytes(db) - before) + " content bytes"));
  }

  private static long bytes(Sqlite db) {
    return db.queryOne("SELECT COALESCE(SUM(size), 0) FROM chunks", r -> r.integer(0))
        .orElseThrow();
  }

  private static boolean hasContentColumn(Sqlite db) {
    return db.queryOne(
            "SELECT 1 FROM pragma_table_info('project_files') WHERE name = 'content'",
            r -> r.integer(0))
        .isPresent();
  }

  private static boolean migrateEntity(Sqlite db, BlobStore blobs, String type, String id) {
    var changed = false;
    if (type.equals("spec")) {
      var content =
          db.queryOne(
              "SELECT c.body, c.plan FROM specs s LEFT JOIN spec_content c ON c.spec_id = s.id WHERE s.id = ? AND (s.body_hash IS NULL OR s.plan_hash IS NULL)",
              r -> java.util.Arrays.asList(r.text(0), r.text(1)),
              id);
      if (content.isPresent()) {
        db.execute(
            "UPDATE specs SET body_hash = ?, plan_hash = ? WHERE id = ?",
            blobs.putText(content.get().get(0)),
            blobs.putText(content.get().get(1)),
            id);
        changed = true;
      }
    } else if (hasContentColumn(db)) {
      var content =
          db.queryOne(
              "SELECT content FROM project_files WHERE id = ? AND content_hash IS NULL",
              r -> r.text(0),
              id);
      if (content.isPresent()) {
        var hash = legacyFile(blobs, content.get());
        db.execute(
            "UPDATE project_files SET content_hash = ?, size = ?, kind = ? WHERE id = ?",
            hash,
            blobs.manifest(hash).size(),
            kind(blobs, hash),
            id);
        known(db, id, hash);
        changed = true;
      }
    }
    var after = 0L;
    while (true) {
      var revisions =
          db.query(
              "SELECT seq, snapshot FROM change_log WHERE entity_type = ? AND entity_id = ? AND seq > ? ORDER BY seq LIMIT 1",
              r -> new Revision(r.integer(0), r.text(1)),
              type,
              id,
              after);
      if (revisions.isEmpty()) break;
      var revision = revisions.getFirst();
      var migrated = snapshot(db, blobs, type, id, revision.snapshot());
      if (!migrated.equals(revision.snapshot())) {
        db.execute("UPDATE change_log SET snapshot = ? WHERE seq = ?", migrated, revision.seq());
        changed = true;
      }
      after = revision.seq();
    }
    for (var conflict :
        db.query(
            "SELECT id, base_snapshot, local_snapshot, remote_snapshot, fields FROM sync_conflicts WHERE entity_type = ? AND entity_id = ?",
            r -> new Conflict(r.integer(0), r.text(1), r.text(2), r.text(3), r.text(4)),
            type,
            id)) {
      var base = snapshot(db, blobs, type, id, conflict.base());
      var local = snapshot(db, blobs, type, id, conflict.local());
      var remote = snapshot(db, blobs, type, id, conflict.remote());
      var fields =
          YamlUtil.parseStringList(conflict.fields()).stream()
              .map(
                  field ->
                      switch (field) {
                        case "body", "plan", "content" -> field + "_hash";
                        default -> field;
                      })
              .toList();
      db.execute(
          "UPDATE sync_conflicts SET base_snapshot = ?, local_snapshot = ?, remote_snapshot = ?, fields = ? WHERE id = ?",
          base,
          local,
          remote,
          YamlUtil.dumpJson(fields),
          conflict.id());
    }
    return changed;
  }

  private record Revision(long seq, String snapshot) {}

  private record Conflict(long id, String base, String local, String remote, String fields) {}

  private static String snapshot(Sqlite db, BlobStore blobs, String type, String id, String json) {
    if (json == null) return null;
    var value =
        new LinkedHashMap<>(YamlUtil.parseJsonLine(json, ai.singlr.sail.sync.SyncWire.MAX_FRAME));
    if (type.equals("spec")) {
      for (var field : List.of("body", "plan")) {
        if (value.containsKey(field)) {
          var text = value.remove(field);
          value.putIfAbsent(field + "_hash", blobs.putText(text == null ? "" : text.toString()));
        }
      }
    } else if (value.containsKey("content")) {
      var content = value.remove("content");
      var hash = legacyFile(blobs, content == null ? "" : content.toString());
      value.putIfAbsent("content_hash", hash);
      value.putIfAbsent("mode", 0644);
      value.putIfAbsent("kind", kind(blobs, hash));
      known(db, id, hash);
    }
    return value.equals(YamlUtil.parseJsonLine(json, ai.singlr.sail.sync.SyncWire.MAX_FRAME))
        ? json
        : YamlUtil.dumpJson(value);
  }

  private static String legacyFile(BlobStore blobs, String encoded) {
    try (var input =
        java.util.Base64.getDecoder()
            .wrap(new ByteArrayInputStream(encoded.getBytes(StandardCharsets.US_ASCII)))) {
      return blobs.put(input);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException("Cannot migrate legacy file content", e);
    }
  }

  private static String kind(BlobStore blobs, String hash) {
    try (var input = blobs.open(hash)) {
      for (var i = 0; i < 8192; i++) {
        var value = input.read();
        if (value == 0) return "binary";
        if (value == -1) break;
      }
      return "text";
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static void known(Sqlite db, String id, String hash) {
    db.execute("INSERT OR IGNORE INTO known_content (entity_id, hash) VALUES (?, ?)", id, hash);
  }
}
