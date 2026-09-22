/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.sync.SyncWire;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
    dropLegacyContent(db);
    return new Report(
        count,
        0,
        0,
        List.of(
            "Migrated " + count + " entities; stored " + (bytes(db) - before) + " content bytes"));
  }

  /**
   * Drops the base64 column once, and only once every row has its hash: the drop is the one step
   * that cannot be redone, so a row the loop did not see — written by a process this one raced —
   * stops it, and the next run finishes the job.
   */
  static void dropLegacyContent(Sqlite db) {
    db.transaction(
        () -> {
          if (!hasContentColumn(db)) return null;
          var unhashed =
              db.queryOne(
                      "SELECT count(*) FROM project_files WHERE content_hash IS NULL",
                      r -> r.integer(0))
                  .orElseThrow();
          if (unhashed > 0) {
            throw new IllegalStateException(
                unhashed + " shared file(s) still carry no content hash; run 'sail migrate' again");
          }
          db.execute("ALTER TABLE project_files DROP COLUMN content");
          return null;
        });
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
              r -> Arrays.asList(r.text(0), r.text(1)),
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
              "SELECT content, path FROM project_files WHERE id = ? AND content_hash IS NULL",
              r -> Arrays.asList(r.text(0), r.text(1)),
              id);
      if (content.isPresent()) {
        var hash = legacyFile(blobs, content.get().get(0));
        db.execute(
            "UPDATE project_files SET content_hash = ?, size = ?, kind = ?, mode = ? WHERE id = ?",
            hash,
            blobs.manifest(hash).size(),
            FileStore.kind(blobs, hash),
            legacyMode(content.get().get(1)),
            id);
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
      var migrated = snapshot(blobs, type, revision.snapshot(), false);
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
      var base = snapshot(blobs, type, conflict.base(), true);
      var local = snapshot(blobs, type, conflict.local(), true);
      var remote = snapshot(blobs, type, conflict.remote(), true);
      var encodedFields =
          SyncConflicts.encodeFields(
              SyncConflicts.decodeFields(conflict.fields()).stream()
                  .map(
                      field ->
                          switch (field) {
                            case "body", "plan", "content" -> field + "_hash";
                            default -> field;
                          })
                  .toList());
      if (!Objects.equals(base, conflict.base())
          || !Objects.equals(local, conflict.local())
          || !Objects.equals(remote, conflict.remote())
          || !encodedFields.equals(conflict.fields())) {
        db.execute(
            "UPDATE sync_conflicts SET base_snapshot = ?, local_snapshot = ?, remote_snapshot = ?, fields = ? WHERE id = ?",
            base,
            local,
            remote,
            encodedFields,
            conflict.id());
        changed = true;
      }
    }
    return changed;
  }

  private record Revision(long seq, String snapshot) {}

  private record Conflict(long id, String base, String local, String remote, String fields) {}

  /**
   * A snapshot with its content moved into the blob store. A legacy file snapshot gets no {@code
   * mode}: the old materializer wrote whatever the box's umask gave, so the mode of a copy on disk
   * was never recorded, and {@link FileStore#isKnownVersion} treats a revision without one as
   * matching any. The live row and an open conflict's sides carry {@link #legacyMode} instead,
   * because both are applied to disk.
   */
  private static String snapshot(BlobStore blobs, String type, String json, boolean applied) {
    if (json == null) return null;
    var original = YamlUtil.parseJsonLine(json, SyncWire.MAX_FRAME);
    var value = new LinkedHashMap<>(original);
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
      value.putIfAbsent("kind", FileStore.kind(blobs, hash));
      if (applied) value.putIfAbsent("mode", legacyMode(Objects.toString(value.get("path"), "")));
    }
    return value.equals(original) ? json : YamlUtil.dumpJson(value);
  }

  /** The mode the old rule gave a shared file on every box: executable for a script, else 0644. */
  static int legacyMode(String path) {
    return path.toLowerCase(Locale.ROOT).endsWith(".sh") ? 0755 : 0644;
  }

  private static String legacyFile(BlobStore blobs, String encoded) {
    try (var input =
        Base64.getDecoder()
            .wrap(new ByteArrayInputStream(encoded.getBytes(StandardCharsets.US_ASCII)))) {
      return blobs.put(input);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot migrate legacy file content", e);
    }
  }
}
