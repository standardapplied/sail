/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Shared project files on SQLite: arbitrary workspace files (configs, scripts, docs) that every FDE
 * on a project should have, replicated through the same sync engine as specs. One row per file
 * keyed by {@code (project, path)} — so two FDEs touching different files never conflict, only
 * edits to the same file do. Content is referenced by its verified SHA-256 hash, and the relative
 * {@code path} preserves the folder structure when the tree is materialized back to disk.
 *
 * <p>Each mutation journals the file's full post-state into the shared {@link ChangeLog} under
 * entity type {@code file} within one transaction — the same revision/CAS/conflict machinery {@link
 * SpecStore} uses — so files get history, restore, and bidirectional conflict resolution for free.
 */
public final class FileStore implements ConflictResolver, SyncedStore {

  private static final String ENTITY = "file";

  private final Sqlite db;
  private final ChangeLog changeLog;
  private final BlobStore blobs;
  private final RevisionJournal journal;

  public FileStore(Sqlite db) {
    this.db = db;
    this.blobs = new BlobStore(db);
    this.changeLog = new ChangeLog(db);
    this.journal = new RevisionJournal(db, changeLog, new FileSchema());
  }

  public record FileRow(
      String project, String path, String contentHash, long size, int mode, String kind) {
    public FileRow {
      if (contentHash == null)
        throw new IllegalStateException(
            "File " + project + "/" + path + " content migration is incomplete; run sail migrate");
      BlobStore.requireHash(contentHash);
      if (size < 0
          || mode < 0
          || mode > 0777
          || (mode & 0400) == 0
          || !("text".equals(kind) || "binary".equals(kind))) {
        throw new IllegalArgumentException(
            "Invalid file metadata for " + project + "/" + path + " (mode must be owner-readable)");
      }
    }
  }

  public BlobStore blobs() {
    return blobs;
  }

  public InputStream open(FileRow row) {
    return blobs.open(row.contentHash());
  }

  public void put(String project, String path, InputStream input, int mode) {
    try (var scope = blobs.retain()) {
      ingest(project, path, input, mode);
    }
  }

  private void ingest(String project, String path, InputStream input, int mode) {
    var hash = blobs.put(input);
    put(new FileRow(project, path, hash, blobs.manifest(hash).size(), mode, kind(blobs, hash)));
  }

  static String kind(BlobStore blobs, String hash) {
    try (var stream = blobs.open(hash)) {
      for (var i = 0; i < 8192; i++) {
        var value = stream.read();
        if (value == -1) break;
        if (value == 0) return "binary";
      }
      return "text";
    } catch (java.io.IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The change-log entity id for a file: its project and relative path. */
  public static String idOf(String project, String path) {
    return project + "/" + path;
  }

  /** Stores or replaces a file's content as a local edit. */
  public void put(FileRow row) {
    db.transaction(
        () -> {
          writeRow(row);
          journal.recordRevision(idOf(row.project(), row.path()), "local", false);
        });
  }

  /** Tombstones a file so the deletion propagates; a no-op if it is already absent. */
  public boolean delete(String project, String path) {
    return db.transaction(
        () -> {
          var row = findRow(idOf(project, path)).orElse(null);
          if (row == null) {
            return false;
          }
          journal.recordRevision(idOf(project, path), "local", true);
          db.execute("DELETE FROM project_files WHERE id = ?", idOf(project, path));
          return true;
        });
  }

  /**
   * Moves every shared file from project {@code old} to {@code renamed} when a project is renamed
   * locally, keeping each file's relative path. A file's change-log identity is its project and
   * path, so the move is journaled as a tombstone under the old id and a fresh revision under the
   * new one: both reach every peer as ordinary changes. Idempotent.
   */
  public void reproject(String old, String renamed) {
    if (old.equals(renamed)) {
      return;
    }
    db.transaction(
        () -> {
          for (var file : list(old)) {
            delete(old, file.path());
            put(
                new FileRow(
                    renamed,
                    file.path(),
                    file.contentHash(),
                    file.size(),
                    file.mode(),
                    file.kind()));
          }
        });
  }

  public Optional<FileRow> find(String project, String path) {
    return findRow(idOf(project, path));
  }

  /** Every current file of a project, ordered by path. */
  public List<FileRow> list(String project) {
    return db.query(
        "SELECT project, path, content_hash, size, mode, kind FROM project_files WHERE project = ? ORDER BY path",
        FileStore::mapRow,
        project);
  }

  @Override
  public String entityType() {
    return ENTITY;
  }

  @Override
  public Set<String> contentFields() {
    return Set.of("content_hash");
  }

  @Override
  public Set<String> liveContentHashes() {
    var hashes =
        new LinkedHashSet<>(db.query("SELECT content_hash FROM project_files", row -> row.text(0)));
    hashes.remove(null);
    return hashes;
  }

  public Map<String, Object> comparableSnapshot(String id) {
    return journal.comparableSnapshot(id);
  }

  /**
   * Whether {@code project} was pruned: its files are erased everywhere, and none is added again.
   */
  public boolean projectPruned(String project) {
    return changeLog.isErased(Erasure.PROJECT, project);
  }

  /**
   * Every file id this box has touched for a project, tombstoned ones included, erased ones not.
   */
  public List<String> idsForProject(String project) {
    return db.query(
        MATERIALIZABLE + " AND h.entity_id LIKE ?", row -> row.text(0), ENTITY, project + "/%");
  }

  /**
   * Projects this box has any file for, current or tombstoned — drives materialization. An erased
   * file is not one: it left no history to tell a copy this box wrote from one a person edited, so
   * whatever of it is on disk stays as it is, with the project's container.
   */
  public LinkedHashSet<String> projectsWithFiles() {
    var projects = new LinkedHashSet<String>();
    for (var id : db.query(MATERIALIZABLE, row -> row.text(0), ENTITY)) {
      projects.add(id.substring(0, id.indexOf('/')));
    }
    return projects;
  }

  private static final String MATERIALIZABLE =
      """
      SELECT h.entity_id FROM change_heads h JOIN change_log l ON l.seq = h.seq
      WHERE h.entity_type = ? AND l.kind <> 'erasure'""";

  /**
   * Whether any retained revision of the file records a mode for this content — history written
   * since modes are journaled, as opposed to the mode-less revisions the content migration left.
   */
  public boolean recordsModeFor(String id, String hash) {
    return db.queryOne(
            """
            SELECT 1 FROM change_log WHERE entity_type = 'file' AND entity_id = ?
                AND json_extract(snapshot, '$.content_hash') = ?
                AND json_extract(snapshot, '$.mode') IS NOT NULL LIMIT 1
            """,
            row -> row.integer(0),
            id,
            hash)
        .isPresent();
  }

  /**
   * Whether this content-and-mode pair is a recorded version of the file. A revision the content
   * migration converted recorded no mode — the old materializer wrote whatever the box's umask gave
   * — so it matches its content at any mode.
   */
  public boolean isKnownVersion(String id, String hash, int mode) {
    return db.queryOne(
            """
            SELECT 1 FROM change_log WHERE entity_type = 'file' AND entity_id = ?
                AND json_extract(snapshot, '$.content_hash') = ?
                AND (json_extract(snapshot, '$.mode') = ?
                     OR json_extract(snapshot, '$.mode') IS NULL) LIMIT 1
            """,
            row -> row.integer(0),
            id,
            hash,
            mode)
        .isPresent();
  }

  public Map<String, Object> comparableAtRev(String id, String rev) {
    return journal.comparableAtRev(id, rev);
  }

  public String latestRev(String id) {
    return journal.latestRev(id);
  }

  public String baseRevOf(String id) {
    return journal.baseRevOf(id);
  }

  public LinkedHashSet<String> syncEntityIds() {
    return new LinkedHashSet<>(journal.entityIds());
  }

  public Set<String> dirtyIds() {
    return journal.dirtyIds();
  }

  /**
   * Adopts main's authoritative state at its exact rev (no minting), as the new synced ancestor.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    journal.applyRevision(id, snapshot, rev);
  }

  @Override
  public void eraseRow(String id) {
    journal.eraseRow(id);
  }

  /** Compare-and-set commit as main: accepts only if {@code expectedRev} still matches. */
  public PushOutcome commitRevision(String id, Map<String, Object> snapshot, String expectedRev) {
    return journal.commitRevision(id, snapshot, expectedRev);
  }

  /**
   * Resolves an open file conflict locally: rebases the row onto main's conflicting content {@code
   * remote} as the new merge base — so the next sync can never re-raise the same conflict — then
   * writes {@code chosen} as the resolved state. Take-theirs ({@code chosen} equals {@code remote})
   * simply adopts main's value; keep-mine writes a forward local edit the next sync pushes. A
   * {@code null} side is a deletion. Every state stays in the {@link ChangeLog}, so no choice loses
   * work.
   */
  @Override
  public String resolveConflict(String id, Map<String, Object> chosen, Map<String, Object> remote) {
    return journal.resolveConflict(id, chosen, remote);
  }

  private void writeRow(FileRow row) {
    blobs.requireHeld(row.contentHash());
    if (blobs.manifest(row.contentHash()).size() != row.size())
      throw new IllegalArgumentException("File size does not match blob " + row.contentHash());
    db.execute(
        "INSERT INTO project_files (id, project, path, content_hash, size, mode, kind, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT(id) DO UPDATE SET content_hash = excluded.content_hash, size = excluded.size, mode = excluded.mode, kind = excluded.kind, updated_at = excluded.updated_at",
        idOf(row.project(), row.path()),
        row.project(),
        row.path(),
        row.contentHash(),
        row.size(),
        row.mode(),
        row.kind(),
        DateTimeUtils.now().toString());
  }

  private FileRow rowFrom(String id, Map<String, Object> snapshot) {
    var slash = id.indexOf('/');
    if (slash <= 0 || slash == id.length() - 1)
      throw new IllegalArgumentException("Invalid file id: " + id);
    var hash = Snapshots.text(snapshot, "content_hash");
    blobs.requireHeld(hash);
    var permission = snapshot.get("mode");
    if (!(permission instanceof Long || permission instanceof Integer)
        || ((Number) permission).longValue() < 0
        || ((Number) permission).longValue() > 0777)
      throw new IllegalArgumentException("Invalid file mode for " + id);
    return new FileRow(
        id.substring(0, slash),
        id.substring(slash + 1),
        hash,
        blobs.manifest(hash).size(),
        ((Number) permission).intValue(),
        Snapshots.text(snapshot, "kind"));
  }

  private Optional<FileRow> findRow(String id) {
    return db.queryOne(
        "SELECT project, path, content_hash, size, mode, kind FROM project_files WHERE id = ?",
        FileStore::mapRow,
        id);
  }

  private static FileRow mapRow(Sqlite.Row row) {
    return new FileRow(
        row.text(0), row.text(1), row.text(2), row.integer(3), (int) row.integer(4), row.text(5));
  }

  private static Map<String, Object> comparable(Map<String, Object> snapshot) {
    var map = new LinkedHashMap<String, Object>();
    for (var field : List.of("content_hash", "mode", "kind")) map.put(field, snapshot.get(field));
    return map;
  }

  /** The file's store-specific half of the shared {@link RevisionJournal} sync protocol. */
  private final class FileSchema implements EntitySchema {

    @Override
    public String entityType() {
      return ENTITY;
    }

    @Override
    public String table() {
      return "project_files";
    }

    @Override
    public boolean exists(String id) {
      return findRow(id).isPresent();
    }

    @Override
    public Map<String, Object> snapshotMap(String id) {
      return findRow(id)
          .map(
              row -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("project", row.project());
                map.put("path", row.path());
                map.put("content_hash", row.contentHash());
                map.put("mode", row.mode());
                map.put("kind", row.kind());
                return (Map<String, Object>) map;
              })
          .orElse(null);
    }

    @Override
    public void apply(String id, Map<String, Object> snapshot) {
      writeRow(rowFrom(id, snapshot));
    }

    @Override
    public Map<String, Object> comparable(Map<String, Object> full) {
      return FileStore.comparable(full);
    }

    @Override
    public void deleteRow(String id) {
      db.execute("DELETE FROM project_files WHERE id = ?", id);
    }
  }
}
