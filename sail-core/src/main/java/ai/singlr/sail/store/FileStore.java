/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
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
public final class FileStore implements ConflictResolver {

  private static final String ENTITY = "file";

  private final Sqlite db;
  private final ChangeLog changeLog;

  public FileStore(Sqlite db) {
    this.db = db;
    this.changeLog = new ChangeLog(db);
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
          recordRevision(row, null, "local", false, false);
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
          recordRevision(row, null, "local", true, false);
          db.execute("DELETE FROM project_files WHERE id = ?", idOf(project, path));
          return true;
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

  public Map<String, Object> comparableSnapshot(String id) {
    return findRow(id).map(row -> comparable(row.content())).orElse(null);
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
    if (Strings.isBlank(rev)) {
      return null;
    }
    return changeLog
        .at(ENTITY, id, rev)
        .map(e -> comparable((String) YamlUtil.parseMap(e.snapshot()).get("content")))
        .orElse(null);
  }

  public String latestRev(String id) {
    var history = changeLog.history(ENTITY, id);
    return history.isEmpty() ? null : history.getLast().rev();
  }

  public String baseRevOf(String id) {
    if (findRow(id).isPresent()) {
      return rawBaseRev(id);
    }
    var tombstone = changeLog.history(ENTITY, id);
    if (tombstone.isEmpty()) {
      return null;
    }
    var baseRev = YamlUtil.parseMap(tombstone.getLast().snapshot()).get("_base_rev");
    return baseRev == null ? null : baseRev.toString();
  }

  public LinkedHashSet<String> syncEntityIds() {
    return new LinkedHashSet<>(
        db.query(
            "SELECT DISTINCT entity_id FROM change_log WHERE entity_type = ?",
            row -> row.text(0),
            ENTITY));
  }

  /**
   * Adopts main's authoritative state at its exact rev (no minting), as the new synced ancestor.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    db.transaction(
        () -> {
          if (snapshot == null) {
            adoptDeletion(id, rev);
          } else {
            var row = rowFrom(id, snapshot);
            writeRow(row);
            recordRevision(row, rev, "sync", false, true);
          }
        });
  }

  /** Compare-and-set commit as main: accepts only if {@code expectedRev} still matches. */
  public PushOutcome commitRevision(String id, Map<String, Object> snapshot, String expectedRev) {
    return db.transaction(
        () -> {
          if (!Objects.equals(latestRev(id), expectedRev)) {
            return new PushOutcome.Stale(latestRev(id), comparableSnapshot(id));
          }
          if (snapshot == null) {
            var present = findRow(id).orElse(null);
            if (present == null) {
              return new PushOutcome.Accepted(latestRev(id));
            }
            var rev = recordRevision(present, null, "sync", true, false);
            db.execute("DELETE FROM project_files WHERE id = ?", id);
            return new PushOutcome.Accepted(rev);
          }
          var row = rowFrom(id, snapshot);
          writeRow(row);
          return new PushOutcome.Accepted(recordRevision(row, null, "sync", false, false));
        });
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
    return db.transaction(
        () -> {
          var baseRev = adoptBase(id, remote);
          if (Objects.equals(contentOf(chosen), contentOf(remote))) {
            return baseRev;
          }
          return writeChosen(id, chosen);
        });
  }

  private String adoptBase(String id, Map<String, Object> remote) {
    if (remote == null) {
      return adoptBaseDeletion(id);
    }
    var row = rowFrom(id, remote);
    writeRow(row);
    return recordRevision(row, null, "sync", false, true);
  }

  private String adoptBaseDeletion(String id) {
    var present = findRow(id).orElse(null);
    if (present == null) {
      var rev = Revisions.next(currentRev(id), "{}");
      changeLog.append(ENTITY, id, rev, null, "sync", true, "{}");
      return rev;
    }
    var rev = recordRevision(present, null, "sync", true, false);
    db.execute("DELETE FROM project_files WHERE id = ?", id);
    return rev;
  }

  private String writeChosen(String id, Map<String, Object> chosen) {
    if (chosen == null) {
      var present = findRow(id).orElse(null);
      if (present == null) {
        return latestRev(id);
      }
      var rev = recordRevision(present, null, "resolve", true, false);
      db.execute("DELETE FROM project_files WHERE id = ?", id);
      return rev;
    }
    var row = rowFrom(id, chosen);
    writeRow(row);
    return recordRevision(row, null, "resolve", false, false);
  }

  private static String contentOf(Map<String, Object> snapshot) {
    return snapshot == null ? null : (String) snapshot.get("content");
  }

  private void adoptDeletion(String id, String rev) {
    var present = findRow(id).orElse(null);
    if (present == null) {
      changeLog.append(ENTITY, id, rev, null, "sync", true, "{}");
      return;
    }
    recordRevision(present, rev, "sync", true, false);
    db.execute("DELETE FROM project_files WHERE id = ?", id);
  }

  private String recordRevision(
      FileRow row, String explicitRev, String origin, boolean deleted, boolean setBaseRev) {
    var id = idOf(row.project(), row.path());
    var map = new LinkedHashMap<String, Object>();
    map.put("project", row.project());
    map.put("path", row.path());
    map.put("content", row.content());
    if (deleted) {
      map.put("_base_rev", rawBaseRev(id));
    }
    var snapshot = YamlUtil.dumpJson(map);
    var rev = explicitRev != null ? explicitRev : Revisions.next(currentRev(id), snapshot);
    if (!deleted) {
      if (setBaseRev) {
        db.execute("UPDATE project_files SET rev = ?, base_rev = ? WHERE id = ?", rev, rev, id);
      } else {
        db.execute("UPDATE project_files SET rev = ? WHERE id = ?", rev, id);
      }
    }
    changeLog.append(ENTITY, id, rev, null, origin, deleted, snapshot);
    return rev;
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

  private String rawBaseRev(String id) {
    var value =
        db.queryOne(
                "SELECT COALESCE(base_rev, '') FROM project_files WHERE id = ?",
                row -> row.text(0),
                id)
            .orElse("");
    return value.isBlank() ? null : value;
  }

  private String currentRev(String id) {
    return db.queryOne(
            "SELECT COALESCE(rev, '') FROM project_files WHERE id = ?", row -> row.text(0), id)
        .orElse("");
  }

  private static Map<String, Object> comparable(String content) {
    var map = new LinkedHashMap<String, Object>();
    map.put("content", content);
    return map;
  }
}
