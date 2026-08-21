/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.YamlUtil;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared project files on SQLite: arbitrary workspace files (configs, scripts, docs) that every FDE
 * on a project should have, replicated through the same sync engine as specs. One row per file
 * keyed by {@code (project, path)} — so two FDEs touching different files never conflict, only
 * edits to the same file do. Content is stored verbatim as text (callers base64 binary), and the
 * relative {@code path} preserves the folder structure when the tree is materialized back to disk.
 *
 * <p>Each mutation journals the file's full post-state into the shared {@link ChangeLog} under
 * entity type {@code file} within one transaction — the same revision/CAS/conflict machinery {@link
 * SpecStore} uses — so files get history, restore, and bidirectional conflict resolution for free.
 */
public final class FileStore implements ConflictResolver, SyncedStore {

  private static final String ENTITY = "file";

  private final Sqlite db;
  private final ChangeLog changeLog;
  private final RevisionJournal journal;

  public FileStore(Sqlite db) {
    this.db = db;
    this.changeLog = new ChangeLog(db);
    this.journal = new RevisionJournal(db, changeLog, new FileSchema());
  }

  public record FileRow(String project, String path, String content) {}

  /** The change-log entity id for a file: its project and relative path. */
  public static String idOf(String project, String path) {
    return project + "/" + path;
  }

  /** Stores or replaces a file's content as a local edit. */
  public void put(String project, String path, String content) {
    var row = new FileRow(project, path, content);
    db.transaction(
        () -> {
          writeRow(row);
          journal.recordRevision(idOf(project, path), "local", false);
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
   * Re-keys every shared file and its change-log history from project {@code old} to {@code
   * renamed} when a project is renamed locally, keeping each file's relative path. Idempotent.
   */
  public void reproject(String old, String renamed) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE project_files SET id = ? || substr(id, ?), project = ? WHERE project = ?",
              renamed,
              old.length() + 1,
              renamed,
              old);
          db.execute(
              "UPDATE change_log SET entity_id = ? || substr(entity_id, ?)"
                  + " WHERE entity_type = ? AND entity_id LIKE ?",
              renamed,
              old.length() + 1,
              ENTITY,
              old + "/%");
        });
  }

  public Optional<FileRow> find(String project, String path) {
    return findRow(idOf(project, path));
  }

  /** Every current file of a project, ordered by path. */
  public List<FileRow> list(String project) {
    return db.query(
        "SELECT project, path, content FROM project_files WHERE project = ? ORDER BY path",
        FileStore::mapRow,
        project);
  }

  @Override
  public String entityType() {
    return ENTITY;
  }

  public Map<String, Object> comparableSnapshot(String id) {
    return journal.comparableSnapshot(id);
  }

  /** Every file id this box has touched for a project, including tombstoned ones. */
  public List<String> idsForProject(String project) {
    return db.query(
        "SELECT DISTINCT entity_id FROM change_log WHERE entity_type = ? AND entity_id LIKE ?",
        row -> row.text(0),
        ENTITY,
        project + "/%");
  }

  /** Projects this box has any file for, current or tombstoned — drives materialization. */
  public LinkedHashSet<String> projectsWithFiles() {
    var projects = new LinkedHashSet<String>();
    for (var id : syncEntityIds()) {
      projects.add(id.substring(0, id.indexOf('/')));
    }
    return projects;
  }

  /**
   * Whether {@code content} matches any revision this box has ever recorded for the file — i.e. a
   * copy this box itself wrote to disk. Lets materialization tell a stale copy it may safely
   * refresh from one a human edited locally, which it must never clobber.
   */
  public boolean isKnownContent(String id, String content) {
    return changeLog.history(ENTITY, id).stream()
        .map(e -> YamlUtil.parseMap(e.snapshot()).get("content"))
        .anyMatch(c -> Objects.equals(c, content));
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

  /**
   * Adopts main's authoritative state at its exact rev (no minting), as the new synced ancestor.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    journal.applyRevision(id, snapshot, rev);
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
    db.execute(
        "INSERT INTO project_files (id, project, path, content, updated_at) VALUES (?, ?, ?, ?, ?)"
            + " ON CONFLICT(id) DO UPDATE SET content = excluded.content,"
            + " updated_at = excluded.updated_at",
        idOf(row.project(), row.path()),
        row.project(),
        row.path(),
        row.content(),
        DateTimeUtils.now().toString());
  }

  private static FileRow rowFrom(String id, Map<String, Object> snapshot) {
    var slash = id.indexOf('/');
    var content = snapshot.get("content");
    return new FileRow(
        id.substring(0, slash),
        id.substring(slash + 1),
        content == null ? null : content.toString());
  }

  private Optional<FileRow> findRow(String id) {
    return db.queryOne(
        "SELECT project, path, content FROM project_files WHERE id = ?", FileStore::mapRow, id);
  }

  private static FileRow mapRow(Sqlite.Row row) {
    return new FileRow(row.text(0), row.text(1), row.text(2));
  }

  private static Map<String, Object> comparable(String content) {
    var map = new LinkedHashMap<String, Object>();
    map.put("content", content);
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
                map.put("content", row.content());
                return (Map<String, Object>) map;
              })
          .orElse(null);
    }

    @Override
    public String author(String id) {
      return null;
    }

    @Override
    public void apply(String id, Map<String, Object> snapshot) {
      writeRow(rowFrom(id, snapshot));
    }

    @Override
    public Map<String, Object> comparable(Map<String, Object> full) {
      return FileStore.comparable(Snapshots.text(full, "content"));
    }

    @Override
    public void deleteRow(String id) {
      db.execute("DELETE FROM project_files WHERE id = ?", id);
    }
  }
}
