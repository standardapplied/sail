/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Converts the database Sail 0.58.0 made into this release's, in place, keeping what the fleet
 * carries across and nothing else. One hop of one release: it exists to carry the internal fleet to
 * 0.59 and is deleted once every box runs it.
 *
 * <p>Every box keeps its own rows — which database it is, the roster, keys, grants, tokens,
 * passkeys, sessions, pairings, its credential and scope, and the shared-file versions it wrote.
 * Main, or a box that syncs with nobody, also keeps its projects, their shared files, the open
 * specs, their rooms and those rooms' messages, each written through its store's adoption, the path
 * a node fills its database by, so the rows and the journal are what this release writes. A node
 * keeps nothing synced: its first round pulls what main kept. Everything else — closed specs, runs,
 * reviews, events, the old journal — is dropped.
 *
 * <p>It converts within one transaction on the database file itself: every other process sees
 * either the 0.58 database or this release's, and a 0.58 process opening it afterwards is refused
 * by its own schema check. The state converted is first copied beside it as the backup.
 */
public final class LegacyConversion {

  /** The schema version Sail 0.58.0 stamps: the one database this converts. */
  public static final int VERSION = 264;

  private static final String LEGACY = "legacy_";

  private static final String OPEN =
      "('draft', 'pending', 'in_progress', 'review', 'awaiting_merge')";

  private static final List<String> BOX_LOCAL =
      List.of(
          "catalog",
          "fdes",
          "fde_ssh_keys",
          "fde_boxes",
          "fde_pairings",
          "project_grants",
          "api_tokens",
          "webauthn_credentials",
          "sessions",
          "box_credential",
          "sync_scope",
          "main_limits",
          "enrollment_tickets",
          "materialized_files");

  private static final DateTimeFormatter SQLITE_TIME =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

  private final Path path;
  private final SyncConfig sync;
  private final Host host;

  private LegacyConversion(Path path, SyncConfig sync, Host host) {
    this.path = path;
    this.sync = sync;
    this.host = host;
  }

  /** What the box around the database says about who may still write to it. */
  public interface Host {
    /**
     * Whether {@code unit}, the systemd unit a 0.58 run was launched as in {@code project}'s
     * container, still runs; true whenever that cannot be told.
     */
    boolean running(String project, String unit);

    /**
     * Refuses while another process holds the database open. Asked last, under the conversion's
     * write lock, where a 0.58 writer waiting for that lock still holds the file.
     */
    void requireNoOtherHolder();
  }

  /** What one conversion kept and dropped, of each kind, and where the state it converted is. */
  public record Report(
      Path backup,
      boolean node,
      Map<String, Long> kept,
      Map<String, Long> dropped,
      Optional<String> vacuumFailure) {

    Report vacuumed(Optional<String> failure) {
      return new Report(backup, node, kept, dropped, failure);
    }
  }

  /**
   * The conversion of the database at {@code db} on {@code host}, empty unless Sail 0.58.0 made it.
   * The box's role is read from {@code hostYaml} now: none is a box that syncs with nobody. One
   * that cannot be read refuses, as does one that names no main while the database records the main
   * it syncs with: a node read as main would keep what it never synced, and a main read as a node
   * would drop everything it holds.
   */
  public static Optional<LegacyConversion> at(Path db, Path hostYaml, Host host) {
    if (!isLegacy(db)) {
      return Optional.empty();
    }
    var sync = sync(hostYaml);
    var main = syncsWith(db);
    if (!sync.isNode() && main.isPresent()) {
      throw Remedied.state(
          hostYaml
              + " names no main for this box, yet its database syncs with main "
              + main.get()
              + ", so it is not converted: as main it would keep what it never synced.",
          Remedy.of(
              "Put this node's host.yaml at " + hostYaml + ", then run '",
              Remedy.sail("migrate"),
              "' again."));
    }
    return Optional.of(new LegacyConversion(db, sync, host));
  }

  private static Optional<String> syncsWith(Path db) {
    try (var sqlite = Sqlite.open(db)) {
      return sqlite.queryOne(
          "SELECT main FROM catalog WHERE main IS NOT NULL AND main <> ''", row -> row.text(0));
    }
  }

  /** Whether the database at {@code db} is one Sail 0.58.0 made, as yet unconverted. */
  public static boolean isLegacy(Path db) {
    return Files.exists(db) && version(db) == VERSION;
  }

  private static int version(Path db) {
    try (var sqlite = Sqlite.open(db)) {
      return sqlite
          .queryOne("SELECT MAX(version) FROM schema_version", row -> (int) row.integer(0))
          .orElse(0);
    } catch (SqliteException e) {
      return 0;
    }
  }

  private static SyncConfig sync(Path hostYaml) {
    if (!Files.exists(hostYaml)) {
      return SyncConfig.unset();
    }
    try {
      var declared = YamlUtil.parseFile(hostYaml);
      if (declared.isEmpty()) {
        throw new IllegalArgumentException("it is empty");
      }
      return HostYaml.fromMap(declared).sync();
    } catch (IOException | RuntimeException e) {
      throw Remedied.state(
          "Cannot tell whether this box is main or a node: "
              + hostYaml
              + " cannot be read ("
              + e.getMessage()
              + "), so its 0.58 database is not converted.",
          Remedy.of("Restore " + hostYaml + ", then run '", Remedy.sail("migrate"), "' again."));
    }
  }

  /** Whether this box is a node, which keeps nothing synced. */
  public boolean node() {
    return sync.isNode();
  }

  /** The file the state this converts is copied to before anything changes. */
  public Path backup() {
    return path.resolveSibling(path.getFileName() + ".0.58.bak");
  }

  /**
   * Refuses, changing nothing, a conversion that must wait: one whose backup is already there, or
   * one with a build or a review running on this box — a run of this box's whose unit still runs,
   * or that names none. A run of another node is only a row here, and one whose unit has stopped is
   * a row its watcher never closed.
   */
  public void requireConvertible() {
    try (var db = Sqlite.open(path)) {
      requireConvertible(db);
    }
  }

  private void requireConvertible(Sqlite db) {
    requireNoBackup();
    requireNoRunHere(db);
  }

  private void requireNoBackup() {
    if (Files.exists(backup())) {
      throw Remedied.state(
          "A backup of a 0.58 database is already at "
              + backup()
              + ": another migrate may be converting it now, or an earlier one left it.",
          Remedy.of(
              "When no other migrate runs, move it aside, then run '",
              Remedy.sail("migrate"),
              "' again."));
    }
  }

  private void requireNoRunHere(Sqlite db) {
    var running =
        db
            .query(
                "SELECT id, project, unit FROM runs WHERE status IN ('running', 'stopping')"
                    + " AND (node IS NULL OR node = ?) ORDER BY started_at",
                row ->
                    List.of(row.text(0), row.text(1), Objects.requireNonNullElse(row.text(2), "")),
                sync.handle())
            .stream()
            .filter(run -> run.get(2).isEmpty() || host.running(run.get(1), run.get(2)))
            .map(run -> run.get(0) + " (" + run.get(1) + ")")
            .toList();
    if (!running.isEmpty()) {
      throw Remedied.state(
          "A run is still going on this box, so its 0.58 database is not converted yet: "
              + String.join(", ", running)
              + ".",
          Remedy.of(
              "Wait for it to finish, or stop it as the dev user in its project's container with '",
              Remedy.program("systemctl", "--user", "stop", "sail-agent-<run>"),
              "', then run '",
              Remedy.sail("migrate"),
              "' again."));
    }
  }

  /**
   * Converts the database: copies it to {@link #backup()}, then rewrites it in one transaction as
   * this release's and reclaims the pages the dropped rows held. Empty when another {@code migrate}
   * converted it first. A failure before the commit leaves the 0.58 database as it was and takes
   * away the backup it made, so the conversion can be run again.
   */
  public Optional<Report> convert() {
    requireNoBackup();
    try (var db = Sqlite.open(path)) {
      db.execute("PRAGMA foreign_keys = OFF");
      return Actor.call(Actor.system(), () -> db.transaction(() -> rewrite(db)))
          .map(report -> report.vacuumed(vacuum(db)));
    }
  }

  private Optional<Report> rewrite(Sqlite db) {
    if (new SchemaManager(db).currentVersion() != VERSION) {
      return Optional.empty();
    }
    requireNoRunHere(db);
    backUp();
    try {
      setAside(db);
      new SchemaManager(db).createBaseline();
      BOX_LOCAL.forEach(table -> copy(db, table, ""));
      var kept = node() ? Map.<String, Long>of() : carrySynced(db);
      var report = report(db, kept);
      dropLegacy(db);
      requireForeignKeys(db);
      host.requireNoOtherHolder();
      return Optional.of(report);
    } catch (RuntimeException e) {
      forgetBackup(e);
      throw e;
    }
  }

  private void forgetBackup(RuntimeException failure) {
    try {
      Files.deleteIfExists(backup());
    } catch (IOException e) {
      failure.addSuppressed(e);
    }
  }

  /**
   * Copies the database as it is converted to {@link #backup()}, readable by its owner alone: it
   * holds every credential hash the box holds. The file is made first, empty, so the copy lands in
   * it with those permissions, and the copy is on disk before the database changes. A failure after
   * the file is made takes it away again.
   */
  private void backUp() {
    try {
      Files.createFile(
          backup(),
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (IOException e) {
      throw new UncheckedIOException("Could not create the backup " + backup(), e);
    }
    try {
      try (var copy = Sqlite.open(path)) {
        copy.execute("VACUUM INTO ?", backup().toString());
      }
      flush(backup(), StandardOpenOption.WRITE);
      flush(backup().toAbsolutePath().getParent(), StandardOpenOption.READ);
    } catch (RuntimeException e) {
      forgetBackup(e);
      throw e;
    }
  }

  private static void flush(Path file, StandardOpenOption mode) {
    try (var channel = FileChannel.open(file, mode)) {
      channel.force(true);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not write " + file + " to disk", e);
    }
  }

  private static void setAside(Sqlite db) {
    for (var object :
        db.query(
            "SELECT type, name FROM sqlite_master WHERE type IN ('index', 'trigger', 'view')"
                + " AND name NOT LIKE 'sqlite_%'",
            row -> List.of(row.text(0), row.text(1)))) {
      db.execute("DROP " + object.get(0).toUpperCase() + " " + quoted(object.get(1)));
    }
    for (var table : tables(db)) {
      db.execute("ALTER TABLE " + quoted(table) + " RENAME TO " + quoted(LEGACY + table));
    }
  }

  private static List<String> tables(Sqlite db) {
    return db.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
            + " ORDER BY name",
        row -> row.text(0));
  }

  private static void dropLegacy(Sqlite db) {
    for (var table : tables(db)) {
      if (table.startsWith(LEGACY)) {
        db.execute("DROP TABLE " + quoted(table));
      }
    }
  }

  private static void requireForeignKeys(Sqlite db) {
    var broken = db.query("PRAGMA foreign_key_check", row -> row.text(0));
    if (!broken.isEmpty()) {
      throw new IllegalStateException(
          "The converted database breaks its foreign keys in " + new LinkedHashSet<>(broken));
    }
  }

  private static Optional<String> vacuum(Sqlite db) {
    try {
      db.execute("VACUUM");
      return Optional.empty();
    } catch (SqliteException e) {
      return Optional.of(e.getMessage());
    }
  }

  /**
   * Copies the rows of the legacy {@code table} that {@code where} admits into this release's, each
   * column the two share, a time stored as text rewritten as its epoch millis.
   */
  private static void copy(Sqlite db, String table, String where) {
    var legacy = columns(db, LEGACY + table);
    var current = columns(db, table);
    var shared = current.keySet().stream().filter(legacy::containsKey).toList();
    var list = String.join(", ", shared.stream().map(LegacyConversion::quoted).toList());
    db.execute("DELETE FROM " + quoted(table));
    db.execute(
        "INSERT INTO "
            + quoted(table)
            + " ("
            + list
            + ") SELECT "
            + list
            + " FROM "
            + quoted(LEGACY + table)
            + " "
            + where);
    shared.stream()
        .filter(
            column -> "INTEGER".equals(current.get(column)) && "TEXT".equals(legacy.get(column)))
        .forEach(column -> toMillis(db, table, column));
  }

  private static Map<String, String> columns(Sqlite db, String table) {
    var columns = new LinkedHashMap<String, String>();
    db.query(
            "SELECT name, upper(type) FROM pragma_table_info(?)",
            row -> Map.entry(row.text(0), row.text(1)),
            table)
        .forEach(column -> columns.put(column.getKey(), column.getValue()));
    return columns;
  }

  private static void toMillis(Sqlite db, String table, String column) {
    var times =
        db.query(
            "SELECT rowid, "
                + quoted(column)
                + " FROM "
                + quoted(table)
                + " WHERE typeof("
                + quoted(column)
                + ") = 'text'",
            row -> Map.entry(row.integer(0), row.text(1)));
    for (var time : times) {
      db.execute(
          "UPDATE " + quoted(table) + " SET " + quoted(column) + " = ? WHERE rowid = ?",
          millis(time.getValue(), table + "." + column),
          time.getKey());
    }
  }

  /**
   * The epoch millis of a time 0.58 stored as text — an ISO-8601 instant, or SQLite's {@code
   * YYYY-MM-DD HH:MM:SS} in UTC — truncated to the millisecond as {@link Instant#toEpochMilli} is.
   */
  static long millis(String time, String where) {
    try {
      return Instant.parse(time).toEpochMilli();
    } catch (DateTimeParseException iso) {
      try {
        return LocalDateTime.parse(time, SQLITE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli();
      } catch (DateTimeParseException sqlite) {
        throw new IllegalStateException(where + " holds '" + time + "', which is not a time.");
      }
    }
  }

  private static String quoted(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  /**
   * {@code full}, a row as its store projects it, as main sends it: with the reserved keys naming
   * its author, creator and times ({@link Snapshots}), which a store adopting it writes from.
   */
  private static Map<String, Object> wire(Map<String, Object> full) {
    var wire = new LinkedHashMap<>(full);
    if (full.get("updated_by") != null) {
      wire.put(Snapshots.ACTOR, full.get("updated_by"));
    }
    wire.put(Snapshots.CREATOR, full.get("created_by"));
    return Snapshots.timed(wire, full);
  }

  private record Revision(String id, Map<String, Object> snapshot, String rev, String author) {}

  /**
   * Keeps what main carries across: the blobs and slack threads the kept rows name, then each kept
   * entity adopted in the order a node adopts them, then a tombstone for every project the legacy
   * journal deleted, so nothing that was deleted, the demo among them, comes back.
   */
  private Map<String, Long> carrySynced(Sqlite db) {
    var open =
        new LinkedHashSet<>(
            db.query(
                "SELECT id FROM legacy_specs WHERE status IN " + OPEN + " ORDER BY id",
                row -> row.text(0)));
    copyContent(db);
    copy(
        db,
        "slack_threads",
        "WHERE spec_id IN (SELECT id FROM legacy_specs WHERE status IN " + OPEN + ")");
    var keptSpecs = adopt(new SpecStore(db), specs(db, open));
    var keptRooms = adopt(new RoomStore(db), rooms(db));
    var keptFiles = adopt(new FileStore(db), files(db));
    keepFileTimes(db);
    var keptProjects = adopt(new ProjectStore(db), projects(db));
    var keptMessages = adopt(new MessageStore(db), messages(db));
    adopt(new ProjectStore(db), deletedProjects(db));
    var kept = new LinkedHashMap<String, Long>();
    kept.put("projects", keptProjects);
    kept.put("files", keptFiles);
    kept.put("specs", keptSpecs);
    kept.put("rooms", keptRooms);
    kept.put("messages", keptMessages);
    return kept;
  }

  private static void copyContent(Sqlite db) {
    var named =
        "SELECT body_hash FROM legacy_specs WHERE status IN "
            + OPEN
            + " UNION SELECT plan_hash FROM legacy_specs WHERE status IN "
            + OPEN
            + " UNION SELECT content_hash FROM legacy_project_files";
    copy(db, "blobs", "WHERE hash IN (" + named + ")");
    copy(
        db,
        "chunks",
        "WHERE hash IN (SELECT c.value FROM legacy_blobs b, json_each(b.chunks) c"
            + " WHERE b.hash IN ("
            + named
            + "))");
  }

  private static long adopt(SyncedStore store, List<Revision> revisions) {
    for (var revision : revisions) {
      Actor.run(
          Actor.main(revision.author()),
          () -> store.adoptForSync(revision.id(), revision.snapshot(), revision.rev()));
    }
    return revisions.size();
  }

  private static String author(Sqlite db, String type, String id) {
    return db.queryOne(
            "SELECT actor FROM legacy_change_log WHERE entity_type = ? AND entity_id = ?"
                + " ORDER BY seq DESC LIMIT 1",
            row -> Optional.ofNullable(row.text(0)),
            type,
            id)
        .flatMap(actor -> actor)
        .orElse(null);
  }

  private static List<Revision> specs(Sqlite db, Set<String> kept) {
    return db.query(
        """
        SELECT id, project, title, status, assignee, agent, model, reasoning_effort, branch,
            priority, created_by, created_at, updated_by, updated_at, room_id, body_hash,
            plan_hash, rev
        FROM legacy_specs WHERE status IN %s ORDER BY id"""
            .formatted(OPEN),
        row -> {
          var id = row.text(0);
          var snapshot = new LinkedHashMap<String, Object>();
          snapshot.put("id", id);
          snapshot.put("project", row.text(1));
          snapshot.put("title", row.text(2));
          snapshot.put("status", row.text(3));
          snapshot.put("assignee", row.text(4));
          snapshot.put("agent", row.text(5));
          snapshot.put("model", row.text(6));
          snapshot.put("reasoning_effort", row.text(7));
          snapshot.put("branch", row.text(8));
          snapshot.put("priority", row.integer(9));
          snapshot.put("created_by", row.text(10));
          snapshot.put("created_at", millis(row.text(11), "specs.created_at"));
          snapshot.put("updated_by", row.text(12));
          snapshot.put("updated_at", millis(row.text(13), "specs.updated_at"));
          snapshot.put(
              "depends_on",
              db
                  .query(
                      "SELECT depends_on FROM legacy_spec_dependencies WHERE spec_id = ?"
                          + " ORDER BY depends_on",
                      dependency -> dependency.text(0),
                      id)
                  .stream()
                  .filter(kept::contains)
                  .toList());
          snapshot.put(
              "repos",
              db.query(
                  "SELECT repo FROM legacy_spec_repos WHERE spec_id = ? ORDER BY repo",
                  repo -> repo.text(0),
                  id));
          snapshot.put("room_id", row.text(14));
          snapshot.put("body_hash", row.text(15));
          snapshot.put("plan_hash", row.text(16));
          return new Revision(id, wire(snapshot), row.text(17), author(db, "spec", id));
        });
  }

  private static List<Revision> rooms(Sqlite db) {
    return db.query(
        """
        SELECT id, project, title, assignee, wake, roster, created_by, created_at, updated_by,
            updated_at, rev
        FROM legacy_rooms
        WHERE id IN (SELECT COALESCE(room_id, id) FROM legacy_specs WHERE status IN %s)
        ORDER BY id"""
            .formatted(OPEN),
        row -> {
          var id = row.text(0);
          var snapshot = new LinkedHashMap<String, Object>();
          snapshot.put("id", id);
          snapshot.put("project", row.text(1));
          snapshot.put("title", row.text(2));
          snapshot.put("assignee", row.text(3));
          snapshot.put("wake", row.text(4));
          snapshot.put("roster", row.text(5));
          snapshot.put("created_by", row.text(6));
          snapshot.put("created_at", millis(row.text(7), "rooms.created_at"));
          snapshot.put("updated_by", row.text(8));
          snapshot.put("updated_at", millis(row.text(9), "rooms.updated_at"));
          return new Revision(id, wire(snapshot), row.text(10), author(db, "room", id));
        });
  }

  private static List<Revision> files(Sqlite db) {
    return db.query(
        "SELECT id, project, path, content_hash, mode, kind, rev FROM legacy_project_files"
            + " ORDER BY id",
        row -> {
          var id = row.text(0);
          var snapshot = new LinkedHashMap<String, Object>();
          snapshot.put("project", row.text(1));
          snapshot.put("path", row.text(2));
          snapshot.put("content_hash", row.text(3));
          snapshot.put("mode", row.integer(4));
          snapshot.put("kind", row.text(5));
          return new Revision(id, snapshot, row.text(6), author(db, "file", id));
        });
  }

  /**
   * Gives each kept shared file back the time 0.58 last changed it, which adopting a file does not
   * carry: it stamps the time the box wrote it, and the conversion writes none of the content.
   */
  private static void keepFileTimes(Sqlite db) {
    for (var file :
        db.query(
            "SELECT id, updated_at FROM legacy_project_files",
            row -> Map.entry(row.text(0), row.text(1)))) {
      db.execute(
          "UPDATE project_files SET updated_at = ? WHERE id = ?",
          millis(file.getValue(), "project_files.updated_at"),
          file.getKey());
    }
  }

  private static List<Revision> projects(Sqlite db) {
    return db.query(
        "SELECT name, definition, created_by, created_at, updated_by, updated_at, rev"
            + " FROM legacy_projects ORDER BY name",
        row -> {
          var name = row.text(0);
          var snapshot = new LinkedHashMap<String, Object>();
          snapshot.put("name", name);
          ProjectDefinitionJson.sections(definition(name, row.text(1)))
              .forEach(
                  (section, json) -> {
                    if (json != null) {
                      snapshot.put(section, json);
                    }
                  });
          snapshot.put("created_by", row.text(2));
          snapshot.put("created_at", millis(row.text(3), "projects.created_at"));
          snapshot.put("updated_by", row.text(4));
          snapshot.put("updated_at", millis(row.text(5), "projects.updated_at"));
          return new Revision(name, wire(snapshot), row.text(6), author(db, "project", name));
        });
  }

  /**
   * The 0.58 catalog definition of {@code name} as this release reads it: without the {@code git}
   * and {@code ssh} blocks this release no longer has, and with the review pipeline moved out of
   * {@code agent} into its own section. Checked whole, as every write is; one this release refuses
   * fails the conversion, naming the project.
   */
  static ProjectDefinition definition(String name, String yaml) {
    try {
      var document = new LinkedHashMap<>(YamlUtil.parseMap(yaml));
      document.remove("git");
      document.remove("ssh");
      if (document.get("agent") instanceof Map<?, ?> declared) {
        var agent = new LinkedHashMap<Object, Object>(declared);
        var pipeline = agent.remove("review_pipeline");
        if (pipeline != null) {
          if (document.containsKey("pipeline")) {
            throw new IllegalArgumentException(
                "it declares both agent.review_pipeline and pipeline");
          }
          document.put("pipeline", pipeline);
        }
        document.put("agent", agent);
      }
      return ProjectDefinitionJson.validated(
          ProjectDefinitionJson.read(YamlUtil.dumpJson(document)));
    } catch (RuntimeException e) {
      throw Remedied.state(
          "Project '"
              + name
              + "' cannot be converted: "
              + e.getMessage()
              + ". Nothing was changed.",
          Remedy.of(
              "Fix its definition on 0.58 with '",
              Remedy.sail("project", "edit", name),
              "', then run '",
              Remedy.sail("migrate"),
              "' again."));
    }
  }

  private static List<Revision> messages(Sqlite db) {
    return db.query(
        """
        SELECT m.id, m.room_id, m.author, m.body, m.reply_to, m.created_at, m.question, m.rev
        FROM legacy_room_messages m
        WHERE m.room_id IN (SELECT COALESCE(room_id, id) FROM legacy_specs WHERE status IN %s)
        ORDER BY m.rowid"""
            .formatted(OPEN),
        row -> {
          var snapshot = new LinkedHashMap<String, Object>();
          snapshot.put("room_id", row.text(1));
          snapshot.put("author", row.text(2));
          snapshot.put("body", row.text(3));
          if (!row.isNull(4)) {
            snapshot.put("reply_to", row.text(4));
          }
          snapshot.put("created_at", millis(row.text(5), "room_messages.created_at"));
          if (row.integer(6) != 0) {
            snapshot.put("question", true);
          }
          return new Revision(row.text(0), snapshot, row.text(7), row.text(2));
        });
  }

  private static List<Revision> deletedProjects(Sqlite db) {
    return db.query(
        """
        SELECT l.entity_id, l.rev, l.actor FROM legacy_change_heads h
        JOIN legacy_change_log l ON l.seq = h.seq
        WHERE h.entity_type = 'project' AND l.kind <> 'revision'
            AND h.entity_id NOT IN (SELECT name FROM legacy_projects)
        ORDER BY l.entity_id""",
        row -> new Revision(row.text(0), null, row.text(1), row.text(2)));
  }

  private Report report(Sqlite db, Map<String, Long> kept) {
    Function<String, Long> legacy =
        table ->
            db.queryOne("SELECT COUNT(*) FROM " + quoted(LEGACY + table), row -> row.integer(0))
                .orElse(0L);
    var dropped = new LinkedHashMap<String, Long>();
    dropped.put("projects", legacy.apply("projects") - kept.getOrDefault("projects", 0L));
    dropped.put("files", legacy.apply("project_files") - kept.getOrDefault("files", 0L));
    dropped.put("specs", legacy.apply("specs") - kept.getOrDefault("specs", 0L));
    dropped.put("rooms", legacy.apply("rooms") - kept.getOrDefault("rooms", 0L));
    dropped.put("messages", legacy.apply("room_messages") - kept.getOrDefault("messages", 0L));
    dropped.put("runs", legacy.apply("runs"));
    dropped.put("reviews", legacy.apply("reviews"));
    dropped.put("events", legacy.apply("events"));
    return new Report(
        backup(),
        node(),
        Collections.unmodifiableMap(new LinkedHashMap<>(kept)),
        Collections.unmodifiableMap(dropped),
        Optional.empty());
  }
}
