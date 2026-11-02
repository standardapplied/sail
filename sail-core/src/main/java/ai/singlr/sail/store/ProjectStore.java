/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.authority.ProjectAuthority;
import ai.singlr.sail.authority.Refusal;
import ai.singlr.sail.authority.WriteAuthority;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.identity.Actor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Project-definition catalog on SQLite. Each project's {@link ProjectDefinition} is stored as its
 * JSON document ({@link ProjectDefinitionJson}), keyed by name, alongside the attribution columns
 * the board lists on. Every definition is checked whole before it is held, and each of its sections
 * is a field of its own in the comparison, so two boxes editing different sections never conflict.
 *
 * <p>Every mutation journals the project's full post-state into the shared {@link ChangeLog} under
 * entity type {@code project} within one transaction, through the {@link RevisionJournal} every
 * mutable synced store shares — the same revision/CAS/conflict machinery {@link SpecStore} and
 * {@link FileStore} use — so a project created on main replicates to every box, with history and
 * bidirectional conflict resolution. Only the sections are comparable; attribution and timestamps
 * never cause a false conflict. Containers and run state are local and live elsewhere; this table
 * is only the definition every box agrees on.
 */
public final class ProjectStore implements ConflictResolver, SyncedStore {

  private static final String ENTITY = "project";
  private static final String NAME = "name";
  private static final String BLOCKS_RESURRECTION = "_blocks_resurrection";
  private static final Map<String, Object> BLOCKING = Map.of(BLOCKS_RESURRECTION, true);

  private final Sqlite db;
  private final RevisionJournal journal;

  public ProjectStore(Sqlite db) {
    this.db = db;
    this.journal = new RevisionJournal(db, new ChangeLog(db), new ProjectSchema(), this::authority);
  }

  /**
   * A catalogued project: its definition's JSON {@code document} and who made and last changed it.
   */
  public record ProjectRow(
      String name,
      String document,
      String createdBy,
      Instant createdAt,
      String updatedBy,
      Instant updatedAt) {

    /** The definition the row holds, read whole; a row that holds none readable is refused. */
    public ProjectDefinition definition() {
      return ProjectDefinitionJson.read(document);
    }
  }

  /** A create or rename refused because a project by that name is already catalogued. */
  public static final class NameTaken extends IllegalStateException {
    NameTaken(String name) {
      super("A project named '" + name + "' already exists.");
    }
  }

  /** A section write refused because the section is no longer the one its writer last read. */
  public static final class StaleSection extends RuntimeException {
    StaleSection(String name, Section section) {
      super(
          "The "
              + section.key()
              + " section of project '"
              + name
              + "' changed since it was read. Read it again, then write it.");
    }
  }

  /**
   * Inserts the project or replaces its definition as the bound {@link Actor}, preserving the
   * original {@code created_by}/{@code created_at}, and journals it as a local edit the next sync
   * pushes. The definition is checked whole first. Re-applying the same definition still records a
   * revision that converges.
   */
  public void upsert(ProjectDefinition definition) {
    var document = ProjectDefinitionJson.write(ProjectDefinitionJson.validated(definition));
    db.transaction(
        () -> {
          writeRow(definition.name(), document, author(), null, null, DateTimeUtils.now());
          journal.recordRevision(definition.name(), ChangeLog.Entry.LOCAL, false);
        });
  }

  /**
   * Creates the project {@code definition} names as the bound {@link Actor}; refused when one by
   * that name is already catalogued.
   */
  public ProjectRow create(ProjectDefinition definition) {
    var document = ProjectDefinitionJson.write(ProjectDefinitionJson.validated(definition));
    var name = definition.name();
    return db.transaction(
        () -> {
          journal.admit(name, Map.of());
          if (findByName(name).isPresent()) {
            throw new NameTaken(name);
          }
          writeRow(name, document, author(), null, null, DateTimeUtils.now());
          journal.recordRevision(name, ChangeLog.Entry.LOCAL, false);
          return findByName(name).orElseThrow();
        });
  }

  /**
   * Writes {@code json}, the JSON value of {@code section} ({@code null} unsets it), into project
   * {@code name}'s definition as the bound {@link Actor}: checked whole, one revision. When {@code
   * expected} names the section's tag ({@link #tagOf}) its writer last read, a section changed
   * since is refused as {@link StaleSection}, the project left as it is; null writes whatever it
   * holds.
   */
  public ProjectRow writeSection(String name, Section section, String json, String expected) {
    var values = new LinkedHashMap<Section, String>();
    values.put(section, json);
    var tags = new LinkedHashMap<Section, String>();
    if (expected != null) {
      tags.put(section, expected);
    }
    return writeSections(name, values, tags);
  }

  /**
   * Writes each section {@code values} names, as {@link #writeSection} writes one, all in one
   * revision or none: a section whose tag {@code expected} names and the project no longer holds
   * refuses the whole write, and one that leaves every section as it was records nothing.
   */
  public ProjectRow writeSections(
      String name, Map<Section, String> values, Map<Section, String> expected) {
    return db.transaction(
        () -> {
          var held =
              findByName(name)
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Project '" + name + "' is not in the catalog."));
          var definition = held.definition();
          for (var tag : expected.entrySet()) {
            if (!tag.getValue()
                .equals(tagOf(ProjectDefinitionJson.section(definition, tag.getKey())))) {
              throw new StaleSection(name, tag.getKey());
            }
          }
          var next = definition;
          for (var value : values.entrySet()) {
            next = ProjectDefinitionJson.withSection(next, value.getKey(), value.getValue());
          }
          if (next.equals(definition)) {
            return held;
          }
          writeRow(
              name, ProjectDefinitionJson.write(next), author(), null, null, DateTimeUtils.now());
          journal.recordRevision(name, ChangeLog.Entry.LOCAL, false);
          return findByName(name).orElseThrow();
        });
  }

  /**
   * The tag of a section's JSON value as a project holds it: what a writer names to write only over
   * the section it read. Equal values have equal tags; an unset section has one too.
   */
  public static String tagOf(String sectionJson) {
    return Revisions.shortHash(sectionJson == null ? "null" : sectionJson);
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
  public void rename(String old, String renamed, ProjectDefinition newDefinition) {
    var canonical = ProjectDefinitionJson.write(ProjectDefinitionJson.validated(newDefinition));
    db.transaction(
        () -> {
          if (findByName(old).isEmpty()) {
            return;
          }
          if (findByName(renamed).isPresent()) {
            throw new NameTaken(renamed);
          }
          journal.recordTombstone(old, ChangeLog.Entry.LOCAL, BLOCKING);
          eraseRow(old);
          writeRow(renamed, canonical, author(), null, null, DateTimeUtils.now());
          journal.recordRevision(renamed, ChangeLog.Entry.LOCAL, false);
        });
  }

  public Optional<ProjectRow> findByName(String name) {
    return db.queryOne(SELECT + " WHERE name = ?", ProjectStore::map, name);
  }

  public List<ProjectRow> list() {
    return db.query(SELECT + " ORDER BY name", ProjectStore::map);
  }

  /**
   * Why {@code actor} may not put a row this box files under {@code heldProject} — null when its
   * head is no live revision — into {@code nextProject}: a box takes a write only into a project
   * its catalog holds within the scope main last named for it ({@link SyncScope}), so no round ever
   * takes back work it accepted; a project the catalog holds beyond that scope — applied here, or
   * kept while a run ends — leaves with the next round. A row staying in its project is never
   * refused, whatever this box holds of it. Said as this box's own refusal, or, when {@code
   * onMain}, as main's answer to a node: naming the projects {@code actor} may see of those granted
   * and, on a node main scoped, the grant that unblocks it. Empty when it may.
   */
  public Optional<Refusal> refusesInto(
      Actor actor, String heldProject, String nextProject, boolean onMain) {
    if (Strings.isBlank(nextProject) || nextProject.equals(heldProject)) {
      return Optional.empty();
    }
    var granted = new SyncScope(db).held();
    if (findByName(nextProject).isPresent() && granted.admits(nextProject)) {
      return Optional.empty();
    }
    var visible =
        list().stream()
            .map(ProjectRow::name)
            .filter(actor.scope()::admits)
            .filter(granted::admits)
            .toList();
    var scoped = !granted.unrestricted();
    var handle = Strings.isBlank(actor.actingFde()) ? "<handle>" : actor.actingFde();
    return Refusal.of(
        Refusal.Kind.UNHELD_PROJECT,
        "Project '"
            + nextProject
            + (onMain
                ? "' isn't on main. Projects there: "
                : "' isn't on this box. Projects here: ")
            + (visible.isEmpty() ? "none" : String.join(", ", visible))
            + ".",
        scoped ? ungranted(handle, nextProject) : Remedy.NONE);
  }

  /**
   * What a node main scoped does about {@code project}, beyond what main last granted {@code
   * handle}: a grant made since reaches it with the next sync, which only main knows of, so both
   * are named.
   */
  public static Remedy ungranted(String handle, String project) {
    return Remedy.of(
        "If an admin granted it since your last sync, run: ",
        Remedy.sail("sync"),
        ". Otherwise ask an admin to grant it on main: ",
        Remedy.sail("fde grant", handle, project),
        ".");
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

  /** Who may write projects on this box: any writer, and a rename an admin alone. */
  @Override
  public ProjectAuthority authority() {
    return new ProjectAuthority(db);
  }

  /** Whether {@code deletion}, a tombstone's marks, is a rename's: it blocks the old name. */
  public static boolean renames(Map<String, Object> deletion) {
    return deletion != null && Boolean.TRUE.equals(deletion.get(BLOCKS_RESURRECTION));
  }

  /**
   * Why the bound {@link Actor} may not write {@code next} as this box's own revision of project
   * {@code name}; empty if it may. The journal's own decision ({@link RevisionJournal#decide}), for
   * a door to ask before a side effect the write would follow.
   */
  public Optional<Refusal> decide(String name, Map<String, Object> next) {
    return journal.decide(name, next);
  }

  /**
   * Why the bound {@link Actor} may not rename project {@code name}; empty if it may: the journal's
   * decision of the deletion a rename records first, for a door to ask before it stops or renames
   * the project's container.
   */
  public Optional<Refusal> decideRename(String name) {
    return journal.decide(name, BLOCKING);
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

  /**
   * Writes project {@code name} as {@code actor} at {@code updatedAt}. A new row is created by
   * {@code creator} at {@code createdAt}, or by {@code actor} at {@code updatedAt} when either
   * names none; a row already here keeps its creator and creation time unless {@code creator} and
   * {@code createdAt} name them, as a revision from main does.
   */
  private void writeRow(
      String name,
      String definition,
      String actor,
      String creator,
      Instant createdAt,
      Instant updatedAt) {
    db.execute(
        "INSERT INTO projects (name, definition, created_by, created_at, updated_by, updated_at)"
            + " VALUES (?, ?, COALESCE(?, ?), COALESCE(?, ?), ?, ?) ON CONFLICT(name) DO UPDATE SET"
            + " definition = excluded.definition, updated_by = excluded.updated_by,"
            + " updated_at = excluded.updated_at,"
            + " created_by = COALESCE(?, projects.created_by),"
            + " created_at = COALESCE(?, projects.created_at)",
        name,
        definition,
        creator,
        actor,
        createdAt,
        updatedAt,
        actor,
        updatedAt,
        creator,
        createdAt);
  }

  /** The definition document of project {@code name} whose sections {@code snapshot} carries. */
  private static String documentOf(String name, Map<String, Object> snapshot) {
    var sections = new LinkedHashMap<String, String>();
    for (var section : Section.values()) {
      sections.put(section.key(), Snapshots.text(snapshot, section.key()));
    }
    return ProjectDefinitionJson.write(ProjectDefinitionJson.fromSections(name, sections));
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
     * The definition's sections, each a work field, who created the project and when, and when it
     * was last revised; the author rides as the journal head's.
     */
    @Override
    public Map<String, Object> snapshotMap(String id) {
      return findByName(id)
          .map(
              row -> {
                var map = sections(row.document());
                map.put("created_by", row.createdBy());
                map.put("created_at", Snapshots.millis(row.createdAt()));
                map.put("updated_at", Snapshots.millis(row.updatedAt()));
                return map;
              })
          .orElse(null);
    }

    /**
     * Writes the definition at the revision's times ({@link Snapshots#timed}), by the creator main
     * names when this box adopts main's revision ({@link Snapshots#adoptedCreator}).
     */
    @Override
    public void apply(String id, Map<String, Object> snapshot) {
      var held = findByName(id).map(ProjectRow::createdBy).orElse(null);
      writeRow(
          id,
          documentOf(id, snapshot),
          Snapshots.actor(snapshot),
          Snapshots.adoptedCreator(snapshot, held),
          Snapshots.instant(snapshot, Snapshots.CREATED_AT),
          Snapshots.timeOr(snapshot, Snapshots.UPDATED_AT, DateTimeUtils.now()));
    }

    @Override
    public Map<String, Object> comparable(Map<String, Object> full) {
      var comparable = new LinkedHashMap<String, Object>();
      comparable.put(NAME, full.get(NAME));
      for (var section : Section.values()) {
        if (full.get(section.key()) != null) {
          comparable.put(section.key(), full.get(section.key()));
        }
      }
      var creator = full.get("created_by");
      if (creator != null) {
        comparable.put(Snapshots.CREATOR, creator);
      }
      return Snapshots.timed(comparable, full);
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

    /**
     * The project's name and each section it sets, its JSON value; an unset one is absent. The name
     * is a work field so a live project never compares equal to its deletion, which carries none:
     * the journal reads a missing field as null ({@link RevisionJournal}), and a project that sets
     * no section would otherwise be the same content as its own tombstone.
     */
    private static LinkedHashMap<String, Object> sections(String document) {
      var definition = ProjectDefinitionJson.read(document);
      var map = new LinkedHashMap<String, Object>();
      map.put(NAME, definition.name());
      ProjectDefinitionJson.sections(definition)
          .forEach(
              (key, json) -> {
                if (json != null) {
                  map.put(key, json);
                }
              });
      return map;
    }
  }

  private static final String SELECT =
      "SELECT name, definition, created_by, created_at, updated_by, updated_at FROM projects";

  private static ProjectRow map(Sqlite.Row row) {
    return new ProjectRow(
        row.text(0), row.text(1), row.text(2), row.instant(3), row.text(4), row.instant(5));
  }
}
