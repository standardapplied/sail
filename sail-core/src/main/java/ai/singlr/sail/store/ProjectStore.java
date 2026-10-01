/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.authority.WriteAuthority;
import ai.singlr.sail.authority.WriterAuthority;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.PersonalFields;
import ai.singlr.sail.identity.Actor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Project-definition catalog on SQLite. Each project's full descriptor (the canonical {@code
 * sail.yaml}) is stored verbatim as the {@code definition} blob, keyed by name, alongside the
 * attribution columns the board lists on. The catalog never looks inside a definition — it loads it
 * whole and hands it to the provisioner.
 *
 * <p>Every mutation journals the project's full post-state into the shared {@link ChangeLog} under
 * entity type {@code project} within one transaction, through the {@link RevisionJournal} every
 * mutable synced store shares — the same revision/CAS/conflict machinery {@link SpecStore} and
 * {@link FileStore} use — so a project created on main replicates to every box, with history and
 * bidirectional conflict resolution. Only the {@code definition} is comparable; attribution and
 * timestamps never cause a false conflict. Containers and run state are local and live elsewhere;
 * this table is only the definition every box agrees on.
 */
public final class ProjectStore implements ConflictResolver, SyncedStore {

  private static final String ENTITY = "project";
  private static final String BLOCKS_RESURRECTION = "_blocks_resurrection";
  private static final Map<String, Object> BLOCKING = Map.of(BLOCKS_RESURRECTION, true);

  private final Sqlite db;
  private final RevisionJournal journal;

  public ProjectStore(Sqlite db) {
    this.db = db;
    this.journal = new RevisionJournal(db, new ChangeLog(db), new ProjectSchema());
  }

  public record ProjectRow(
      String name,
      String definition,
      String createdBy,
      String createdAt,
      String updatedBy,
      String updatedAt) {}

  /**
   * Inserts the project or replaces its definition as the bound {@link Actor}, preserving the
   * original {@code created_by}/{@code created_at}, and journals it as a local edit the next sync
   * pushes. Idempotent in effect; re-applying the same definition still records a revision that
   * converges.
   *
   * <p>The definition is {@linkplain PersonalFields#redact redacted} first, so the catalogued,
   * synced state never carries this box's git identity or SSH keys — each box resolves those
   * locally at provision time. This is the one seam where a definition a human authored enters the
   * catalog; revisions arriving over sync are already redacted at their origin.
   */
  public void upsert(String name, String definition) {
    var canonical = PersonalFields.redact(definition);
    db.transaction(
        () -> {
          writeRow(name, canonical, author());
          journal.recordRevision(name, ChangeLog.Entry.LOCAL, false);
        });
  }

  /** Tombstones a project so the deletion propagates; a no-op if it is already absent. */
  public boolean delete(String name) {
    return db.transaction(
        () -> {
          if (findByName(name).isEmpty()) {
            return false;
          }
          journal.recordRevision(name, ChangeLog.Entry.LOCAL, true);
          eraseRow(name);
          return true;
        });
  }

  /**
   * Renames a project by tombstoning the old identity and creating the new one, each an ordinary
   * revision that peers reconcile through the normal sync engine — so both halves propagate and the
   * old name cannot be resurrected by a stale peer that still holds it. The old identity's
   * tombstone carries a resurrection block: it defeats a copy, or an unbased create, of the same
   * name on a box that never heard the name deleted, rather than losing to it (a plain delete does
   * not); a creation over a deletion a box heard is that box's own and is offered. Idempotent — a
   * no-op once {@code old} is gone; rejects a rename onto a name that is still live here.
   */
  public void rename(String old, String renamed, String newDefinition) {
    var canonical = PersonalFields.redact(newDefinition);
    db.transaction(
        () -> {
          if (findByName(old).isEmpty()) {
            return;
          }
          if (findByName(renamed).isPresent()) {
            throw new IllegalStateException("A project named '" + renamed + "' already exists.");
          }
          journal.recordTombstone(old, ChangeLog.Entry.LOCAL, BLOCKING);
          eraseRow(old);
          writeRow(renamed, canonical, author());
          journal.recordRevision(renamed, ChangeLog.Entry.LOCAL, false);
        });
  }

  public Optional<ProjectRow> findByName(String name) {
    return db.queryOne(SELECT + " WHERE name = ?", ProjectStore::map, name);
  }

  public List<ProjectRow> list() {
    return db.query(SELECT + " ORDER BY name", ProjectStore::map);
  }

  @Override
  public String entityType() {
    return ENTITY;
  }

  @Override
  public Map<String, Object> comparableSnapshot(String id) {
    return journal.comparableSnapshot(id);
  }

  @Override
  public Map<String, Object> currentForSync(String id) {
    return journal.currentForSync(id);
  }

  @Override
  public Optional<String> liveBase(String id) {
    return journal.liveBase(id);
  }

  @Override
  public Map<String, Object> comparableAtRev(String id, String rev) {
    return journal.comparableAtRev(id, rev);
  }

  @Override
  public String latestRev(String id) {
    return journal.latestRev(id);
  }

  @Override
  public String baseRevOf(String id) {
    return journal.baseRevOf(id);
  }

  @Override
  public Set<String> dirtyIds() {
    return journal.dirtyIds();
  }

  @Override
  public Set<String> syncEntityIds() {
    return journal.entityIds();
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
        upsert(row.name(), row.definition());
        changed++;
      }
    }
    return changed;
  }

  /**
   * Adopts main's authoritative state at its exact rev (no minting), as the new synced ancestor.
   */
  @Override
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    journal.applyRevision(id, snapshot, rev);
  }

  @Override
  public boolean acknowledge(String id, Map<String, Object> accepted, String rev) {
    return journal.acknowledge(id, accepted, rev);
  }

  @Override
  public Set<String> latestWinsFields() {
    return journal.latestWinsFields();
  }

  @Override
  public void eraseRow(String id) {
    journal.eraseRow(id);
  }

  /** Who may write projects on this box: any writer, as its doors and main's commit decide. */
  @Override
  public WriterAuthority authority() {
    return new WriterAuthority(db, "projects");
  }

  /** Compare-and-set commit as main through the shared {@link RevisionJournal#commitRevision}. */
  @Override
  public PushOutcome commitRevision(
      String id, Map<String, Object> snapshot, String expectedRev, WriteAuthority authority) {
    return journal.commitRevision(id, snapshot, expectedRev, authority);
  }

  /** Resolves an open conflict through the shared {@link RevisionJournal#resolveConflict}. */
  @Override
  public String resolveConflict(String id, Map<String, Object> chosen, MainVersion theirs) {
    return journal.resolveConflict(id, chosen, theirs);
  }

  /**
   * Journals the current state of {@code id}, a deletion when {@code deleted}, as {@code origin}.
   */
  String recordRevision(String id, String origin, boolean deleted) {
    return journal.recordRevision(id, origin, deleted);
  }

  private static String author() {
    return Actor.current().handle();
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

  /** The project's store-specific half of the shared {@link RevisionJournal} sync protocol. */
  private final class ProjectSchema implements EntitySchema {

    @Override
    public String entityType() {
      return ENTITY;
    }

    @Override
    public String table() {
      return "projects";
    }

    @Override
    public String key() {
      return "name";
    }

    @Override
    public boolean exists(String id) {
      return findByName(id).isPresent();
    }

    /**
     * Only the definition, the one work field: the author rides as the journal head's, so two boxes
     * mint the same rev for the same definition.
     */
    @Override
    public Map<String, Object> snapshotMap(String id) {
      return findByName(id).map(row -> comparable(row.definition())).orElse(null);
    }

    @Override
    public void apply(String id, Map<String, Object> snapshot) {
      writeRow(id, definitionOf(snapshot), Snapshots.actor(snapshot));
    }

    @Override
    public Map<String, Object> comparable(Map<String, Object> full) {
      return comparable(definitionOf(full));
    }

    @Override
    public void deleteRow(String id) {
      db.execute("DELETE FROM projects WHERE name = ?", id);
    }

    /** A rename's tombstone carries its resurrection block; a plain deletion carries nothing. */
    @Override
    public Map<String, Object> marks(Map<String, Object> snapshot) {
      return Boolean.TRUE.equals(snapshot.get(BLOCKS_RESURRECTION)) ? BLOCKING : Map.of();
    }

    private static Map<String, Object> comparable(String definition) {
      var map = new LinkedHashMap<String, Object>();
      map.put("definition", definition);
      return map;
    }
  }

  private static final String SELECT =
      "SELECT name, definition, created_by, created_at, updated_by, updated_at FROM projects";

  private static ProjectRow map(Sqlite.Row row) {
    return new ProjectRow(
        row.text(0), row.text(1), row.text(2), row.text(3), row.text(4), row.text(5));
  }
}
