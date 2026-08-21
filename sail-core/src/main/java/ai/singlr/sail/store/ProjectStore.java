/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.PersonalFields;
import ai.singlr.sail.config.YamlUtil;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Project-definition catalog on SQLite. Each project's full descriptor (the canonical {@code
 * sail.yaml}) is stored verbatim as the {@code definition} blob, keyed by name, alongside the
 * attribution columns the board lists on. The catalog never looks inside a definition — it loads it
 * whole and hands it to the provisioner.
 *
 * <p>Every mutation journals the project's full post-state into the shared {@link ChangeLog} under
 * entity type {@code project} within one transaction — the same revision/CAS/conflict machinery
 * {@link SpecStore} and {@link FileStore} use — so a project created on main replicates to every
 * box, with history and bidirectional conflict resolution. Only the {@code definition} is
 * comparable; attribution and timestamps never cause a false conflict. Containers and run state are
 * local and live elsewhere; this table is only the definition every box agrees on.
 */
public final class ProjectStore implements ConflictResolver, SyncedStore {

  private static final String ENTITY = "project";
  private static final String BLOCKS_RESURRECTION = "_blocks_resurrection";

  private final Sqlite db;
  private final ChangeLog changeLog;

  public ProjectStore(Sqlite db) {
    this.db = db;
    this.changeLog = new ChangeLog(db);
  }

  public record ProjectRow(
      String name,
      String definition,
      String createdBy,
      String createdAt,
      String updatedBy,
      String updatedAt) {}

  /**
   * Inserts the project or replaces its definition, preserving the original {@code
   * created_by}/{@code created_at}, and journals it as a local edit the next sync pushes.
   * Idempotent in effect; re-applying the same definition still records a revision that converges.
   *
   * <p>The definition is {@linkplain PersonalFields#redact redacted} first, so the catalogued,
   * synced state never carries this box's git identity or SSH keys — each box resolves those
   * locally at provision time. This is the one seam where a definition a human authored enters the
   * catalog; revisions arriving over sync are already redacted at their origin.
   */
  public void upsert(String name, String definition, String actor) {
    var canonical = PersonalFields.redact(definition);
    db.transaction(
        () -> {
          writeRow(name, canonical, actor);
          recordRevision(name, canonical, null, "local", false, false);
        });
  }

  /** Tombstones a project so the deletion propagates; a no-op if it is already absent. */
  public boolean delete(String name) {
    return db.transaction(
        () -> {
          var existing = findByName(name).orElse(null);
          if (existing == null) {
            return false;
          }
          recordRevision(name, existing.definition(), null, "local", true, false);
          db.execute("DELETE FROM projects WHERE name = ?", name);
          return true;
        });
  }

  /**
   * Renames a project by tombstoning the old identity and creating the new one, each an ordinary
   * revision that peers reconcile through the normal sync engine — so both halves propagate and the
   * old name cannot be resurrected by a stale peer that still holds it. The old identity's
   * tombstone carries a resurrection block: it defeats an unbased create of the same name rather
   * than losing to it (a plain delete does not). Idempotent — a no-op once {@code old} is gone;
   * rejects a rename onto a name that is still live here.
   */
  public void rename(String old, String renamed, String newDefinition) {
    var canonical = PersonalFields.redact(newDefinition);
    db.transaction(
        () -> {
          var existing = findByName(old).orElse(null);
          if (existing == null) {
            return;
          }
          if (findByName(renamed).isPresent()) {
            throw new IllegalStateException("A project named '" + renamed + "' already exists.");
          }
          recordRevision(old, existing.definition(), null, "local", true, false, true);
          db.execute("DELETE FROM projects WHERE name = ?", old);
          writeRow(renamed, canonical, existing.updatedBy());
          recordRevision(renamed, canonical, null, "local", false, false);
        });
  }

  public Optional<ProjectRow> findByName(String name) {
    return db.queryOne(SELECT + " WHERE name = ?", ProjectStore::map, name);
  }

  public List<ProjectRow> list() {
    return db.query(SELECT + " ORDER BY name", ProjectStore::map);
  }

  // ── Sync roles (mirrors FileStore): the database is the replicated source of truth ──

  @Override
  public String entityType() {
    return ENTITY;
  }

  public Map<String, Object> comparableSnapshot(String id) {
    return findByName(id).map(row -> comparable(row.definition(), row.updatedBy())).orElse(null);
  }

  /**
   * As {@link #comparableSnapshot}, but a deleted id this box holds a rename resurrection-block for
   * reports the blocking marker rather than absence, so a stale peer still holding the old name
   * adopts the deletion instead of pushing its surviving copy back.
   */
  @Override
  public Map<String, Object> currentForSync(String id) {
    var current = comparableSnapshot(id);
    return current != null || !blocksResurrection(id) ? current : blocksResurrectionMarker();
  }

  /** Reads the resurrection-block marker back as a deletion; otherwise a normal adopt. */
  @Override
  public void adoptForSync(String id, Map<String, Object> snapshot, String rev) {
    applyRevision(id, isBlocksResurrectionMarker(snapshot) ? null : snapshot, rev);
  }

  public Map<String, Object> comparableAtRev(String id, String rev) {
    if (Strings.isBlank(rev)) {
      return null;
    }
    return changeLog
        .at(ENTITY, id, rev)
        .map(e -> comparable((String) YamlUtil.parseMap(e.snapshot()).get("definition")))
        .orElse(null);
  }

  public String latestRev(String id) {
    var history = changeLog.history(ENTITY, id);
    return history.isEmpty() ? null : history.getLast().rev();
  }

  public String baseRevOf(String id) {
    if (findByName(id).isPresent()) {
      return rawBaseRev(id);
    }
    var tombstone = changeLog.history(ENTITY, id);
    if (tombstone.isEmpty()) {
      return null;
    }
    var baseRev = YamlUtil.parseMap(tombstone.getLast().snapshot()).get("_base_rev");
    return baseRev == null ? null : baseRev.toString();
  }

  public Set<String> syncEntityIds() {
    return new LinkedHashSet<>(
        db.query(
            "SELECT DISTINCT entity_id FROM change_log WHERE entity_type = ?",
            row -> row.text(0),
            ENTITY));
  }

  /**
   * Rewrites every catalogued definition to its {@linkplain PersonalFields#redact redacted} form,
   * so a catalog written before this brick — carrying one box's git identity and SSH keys — is
   * scrubbed and the placeholder form propagates on the next sync. A no-op for definitions already
   * redacted; returns how many it changed. Because redaction is deterministic, a node that runs
   * this against its main-derived rows reaches the same content main does and the two converge
   * without conflict.
   */
  public int canonicalizeDefinitions() {
    var changed = 0;
    for (var row : list()) {
      if (!PersonalFields.redact(row.definition()).equals(row.definition())) {
        upsert(row.name(), row.definition(), row.updatedBy());
        changed++;
      }
    }
    return changed;
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
            var definition = definitionOf(snapshot);
            writeRow(id, definition, Snapshots.actor(snapshot));
            recordRevision(id, definition, rev, "sync", false, true);
          }
        });
  }

  /** Compare-and-set commit as main: accepts only if {@code expectedRev} still matches. */
  public PushOutcome commitRevision(String id, Map<String, Object> snapshot, String expectedRev) {
    return db.immediateTransaction(
        () -> {
          if (!Objects.equals(latestRev(id), expectedRev)) {
            var current = comparableSnapshot(id);
            if (current == null && blocksResurrection(id)) {
              current = blocksResurrectionMarker();
            }
            return new PushOutcome.Stale(latestRev(id), current);
          }
          var blocks = isBlocksResurrectionMarker(snapshot);
          if (snapshot == null || blocks) {
            var present = findByName(id).orElse(null);
            if (present == null && !blocks) {
              return new PushOutcome.Accepted(latestRev(id));
            }
            var rev =
                recordRevision(
                    id,
                    present == null ? null : present.definition(),
                    null,
                    "sync",
                    true,
                    false,
                    blocks);
            if (present != null) {
              db.execute("DELETE FROM projects WHERE name = ?", id);
            }
            return new PushOutcome.Accepted(rev);
          }
          var definition = definitionOf(snapshot);
          writeRow(id, definition, Snapshots.actor(snapshot));
          return new PushOutcome.Accepted(
              recordRevision(id, definition, null, "sync", false, false));
        });
  }

  /**
   * Resolves an open project conflict locally: rebases the row onto main's conflicting definition
   * {@code remote} as the new merge base — so the next sync can never re-raise the same conflict —
   * then writes {@code chosen} as the resolved state. Take-theirs simply adopts main's value;
   * keep-mine writes a forward local edit the next sync pushes. A {@code null} side is a deletion.
   * Every state stays in the {@link ChangeLog}, so no choice loses work.
   */
  @Override
  public String resolveConflict(String id, Map<String, Object> chosen, Map<String, Object> remote) {
    return db.transaction(
        () -> {
          var baseRev = adoptBase(id, remote);
          if (Objects.equals(definitionOf(chosen), definitionOf(remote))) {
            return baseRev;
          }
          return writeChosen(id, chosen);
        });
  }

  private String adoptBase(String id, Map<String, Object> remote) {
    if (remote == null) {
      return adoptBaseDeletion(id);
    }
    var definition = definitionOf(remote);
    writeRow(id, definition, Snapshots.actor(remote));
    return recordRevision(id, definition, null, "sync", false, true);
  }

  private String adoptBaseDeletion(String id) {
    var present = findByName(id).orElse(null);
    if (present == null) {
      var rev = Revisions.next(currentRev(id), "{}");
      changeLog.append(ENTITY, id, rev, null, "sync", true, "{}");
      return rev;
    }
    var rev = recordRevision(id, present.definition(), null, "sync", true, false);
    db.execute("DELETE FROM projects WHERE name = ?", id);
    return rev;
  }

  private String writeChosen(String id, Map<String, Object> chosen) {
    if (chosen == null) {
      var present = findByName(id).orElse(null);
      if (present == null) {
        return latestRev(id);
      }
      var rev = recordRevision(id, present.definition(), null, "resolve", true, false);
      db.execute("DELETE FROM projects WHERE name = ?", id);
      return rev;
    }
    var definition = definitionOf(chosen);
    writeRow(id, definition, "resolve");
    return recordRevision(id, definition, null, "resolve", false, false);
  }

  private void adoptDeletion(String id, String rev) {
    var present = findByName(id).orElse(null);
    if (present == null) {
      changeLog.append(ENTITY, id, rev, null, "sync", true, "{}");
      return;
    }
    recordRevision(id, present.definition(), rev, "sync", true, false);
    db.execute("DELETE FROM projects WHERE name = ?", id);
  }

  String recordRevision(
      String id,
      String definition,
      String explicitRev,
      String origin,
      boolean deleted,
      boolean setBaseRev) {
    return recordRevision(id, definition, explicitRev, origin, deleted, setBaseRev, false);
  }

  /**
   * Whether this box holds a resurrection-blocking tombstone for {@code id}: a rename recorded its
   * old identity's deletion so a stale peer still holding the name adopts the deletion rather than
   * pushing its surviving copy back. Only a rename's tombstone blocks; a plain delete does not.
   */
  public boolean blocksResurrection(String id) {
    var history = changeLog.history(ENTITY, id);
    return !history.isEmpty()
        && history.getLast().deleted()
        && Boolean.TRUE.equals(
            YamlUtil.parseMap(history.getLast().snapshot()).get(BLOCKS_RESURRECTION));
  }

  /** The snapshot the sync engine reads as "this identity is authoritatively, blockingly gone". */
  public static Map<String, Object> blocksResurrectionMarker() {
    return Map.of(BLOCKS_RESURRECTION, true);
  }

  public static boolean isBlocksResurrectionMarker(Map<String, Object> snapshot) {
    return snapshot != null && Boolean.TRUE.equals(snapshot.get(BLOCKS_RESURRECTION));
  }

  private String recordRevision(
      String id,
      String definition,
      String explicitRev,
      String origin,
      boolean deleted,
      boolean setBaseRev,
      boolean blocksResurrection) {
    var map = new LinkedHashMap<String, Object>();
    map.put("definition", definition);
    if (deleted) {
      map.put("_base_rev", rawBaseRev(id));
      if (blocksResurrection) {
        map.put(BLOCKS_RESURRECTION, true);
      }
    }
    var snapshot = YamlUtil.dumpJson(map);
    var rev = explicitRev != null ? explicitRev : Revisions.next(currentRev(id), snapshot);
    if (!deleted) {
      if (setBaseRev) {
        db.execute("UPDATE projects SET rev = ?, base_rev = ? WHERE name = ?", rev, rev, id);
      } else {
        db.execute("UPDATE projects SET rev = ? WHERE name = ?", rev, id);
      }
    }
    changeLog.append(ENTITY, id, rev, null, origin, deleted, snapshot);
    return rev;
  }

  private void writeRow(String name, String definition, String actor) {
    var now = DateTimeUtils.now().toString();
    db.execute(
        "INSERT INTO projects (name, definition, created_by, created_at, updated_by, updated_at)"
            + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(name) DO UPDATE SET"
            + " definition = excluded.definition, updated_by = excluded.updated_by,"
            + " updated_at = excluded.updated_at",
        name,
        definition,
        actor,
        now,
        actor,
        now);
  }

  private static String definitionOf(Map<String, Object> snapshot) {
    if (snapshot == null) {
      return null;
    }
    var definition = snapshot.get("definition");
    return definition == null ? null : definition.toString();
  }

  /**
   * The author carried in a synced snapshot. The {@code sync} default remains because the retained
   * versioned 0.14 migration can journal a project row that predates attribution. Read on the
   * receiving side so a current snapshot is attributed to the engineer who actually edited it.
   */
  private String rawBaseRev(String id) {
    var value =
        db.queryOne(
                "SELECT COALESCE(base_rev, '') FROM projects WHERE name = ?",
                row -> row.text(0),
                id)
            .orElse("");
    return value.isBlank() ? null : value;
  }

  private String currentRev(String id) {
    return db.queryOne(
            "SELECT COALESCE(rev, '') FROM projects WHERE name = ?", row -> row.text(0), id)
        .orElse("");
  }

  /**
   * The base/historical comparable carries only the work field. {@link ConflictDetector} ignores
   * reserved {@code _}-prefixed keys, and the merge base's author is never needed, so the stored
   * snapshot — and therefore the content-addressed revision — stays {@code {definition}} and two
   * boxes still mint the same rev for the same definition.
   */
  private static Map<String, Object> comparable(String definition) {
    var map = new LinkedHashMap<String, Object>();
    map.put("definition", definition);
    return map;
  }

  /**
   * The transmitted comparable additionally carries {@code _actor} so the receiving box can
   * attribute the synced row to its real author. It rides outside the work fields, so it never
   * counts toward a conflict and never enters the revision hash.
   */
  private static Map<String, Object> comparable(String definition, String actor) {
    var map = comparable(definition);
    if (actor != null) {
      map.put(Snapshots.ACTOR, actor);
    }
    return map;
  }

  private static final String SELECT =
      "SELECT name, definition, created_by, created_at, updated_by, updated_at FROM projects";

  private static ProjectRow map(Sqlite.Row row) {
    return new ProjectRow(
        row.text(0), row.text(1), row.text(2), row.text(3), row.text(4), row.text(5));
  }
}
