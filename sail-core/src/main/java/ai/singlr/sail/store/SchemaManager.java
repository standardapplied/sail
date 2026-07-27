/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.List;

/**
 * Manages SQLite schema versioning. Each migration runs inside a transaction. Migrations are
 * idempotent: re-running on a current database is a no-op.
 */
public final class SchemaManager {

  private static final List<String> MIGRATIONS =
      List.of(
          """
          CREATE TABLE IF NOT EXISTS schema_version (
              version INTEGER PRIMARY KEY,
              applied_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE IF NOT EXISTS specs (
              id TEXT PRIMARY KEY,
              title TEXT NOT NULL,
              status TEXT NOT NULL DEFAULT 'draft'
                  CHECK (status IN ('draft', 'pending', 'in_progress', 'review', 'done', 'archived')),
              assignee TEXT,
              agent TEXT,
              model TEXT,
              reasoning_effort TEXT
                  CHECK (reasoning_effort IS NULL OR reasoning_effort IN ('none', 'low', 'medium', 'high', 'xhigh')),
              branch TEXT,
              priority INTEGER NOT NULL DEFAULT 0,
              created_by TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE IF NOT EXISTS spec_dependencies (
              spec_id TEXT NOT NULL REFERENCES specs(id) ON DELETE CASCADE,
              depends_on TEXT NOT NULL REFERENCES specs(id),
              PRIMARY KEY (spec_id, depends_on)
          )""",
          """
          CREATE TABLE IF NOT EXISTS spec_repos (
              spec_id TEXT NOT NULL REFERENCES specs(id) ON DELETE CASCADE,
              repo TEXT NOT NULL,
              PRIMARY KEY (spec_id, repo)
          )""",
          """
          CREATE TABLE IF NOT EXISTS spec_content (
              spec_id TEXT PRIMARY KEY REFERENCES specs(id) ON DELETE CASCADE,
              body TEXT NOT NULL DEFAULT '',
              plan TEXT NOT NULL DEFAULT '',
              updated_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE IF NOT EXISTS spec_attachments (
              id TEXT PRIMARY KEY,
              spec_id TEXT NOT NULL REFERENCES specs(id) ON DELETE CASCADE,
              filename TEXT NOT NULL,
              content_type TEXT NOT NULL,
              size_bytes INTEGER NOT NULL,
              storage_path TEXT NOT NULL,
              created_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE IF NOT EXISTS events (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              timestamp TEXT NOT NULL,
              type TEXT NOT NULL,
              project TEXT,
              spec_id TEXT,
              agent TEXT,
              host TEXT NOT NULL,
              data TEXT NOT NULL DEFAULT '{}'
          )""",
          "CREATE INDEX IF NOT EXISTS idx_events_type ON events(type)",
          "CREATE INDEX IF NOT EXISTS idx_events_project ON events(project)",
          "CREATE INDEX IF NOT EXISTS idx_events_spec ON events(spec_id)",
          "CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events(timestamp DESC)",
          """
          CREATE TABLE IF NOT EXISTS api_tokens (
              token_hash TEXT PRIMARY KEY,
              name TEXT NOT NULL UNIQUE,
              role TEXT NOT NULL DEFAULT 'member'
                  CHECK (role IN ('admin', 'member')),
              created_at TEXT NOT NULL,
              last_used_at TEXT
          )""",
          """
          CREATE TABLE IF NOT EXISTS reviews (
              id TEXT PRIMARY KEY,
              spec_id TEXT NOT NULL REFERENCES specs(id),
              iteration INTEGER NOT NULL DEFAULT 1,
              status TEXT NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending', 'running', 'passed', 'failed', 'escalated')),
              created_at TEXT NOT NULL,
              completed_at TEXT
          )""",
          "CREATE INDEX IF NOT EXISTS idx_reviews_spec ON reviews(spec_id)",
          """
          CREATE TABLE IF NOT EXISTS review_stages (
              id TEXT PRIMARY KEY,
              review_id TEXT NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
              name TEXT NOT NULL,
              stage_type TEXT NOT NULL,
              status TEXT NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending', 'running', 'passed', 'failed', 'skipped')),
              reviewer TEXT,
              started_at TEXT,
              completed_at TEXT
          )""",
          "CREATE INDEX IF NOT EXISTS idx_review_stages_review ON review_stages(review_id)",
          """
          CREATE TABLE IF NOT EXISTS review_findings (
              id TEXT PRIMARY KEY,
              stage_id TEXT NOT NULL REFERENCES review_stages(id) ON DELETE CASCADE,
              severity TEXT NOT NULL,
              category TEXT NOT NULL,
              file TEXT,
              line_start INTEGER,
              line_end INTEGER,
              title TEXT NOT NULL,
              description TEXT NOT NULL,
              evidence TEXT,
              suggestion_before TEXT,
              suggestion_after TEXT,
              suggestion_rationale TEXT,
              confidence REAL NOT NULL DEFAULT 0.0,
              resolution TEXT NOT NULL DEFAULT 'OPEN'
                  CHECK (resolution IN ('OPEN', 'FIXED', 'DISMISSED'))
          )""",
          "CREATE INDEX IF NOT EXISTS idx_review_findings_stage ON review_findings(stage_id)",
          "CREATE INDEX IF NOT EXISTS idx_review_findings_severity ON review_findings(severity)",
          """
          CREATE TABLE IF NOT EXISTS agent_sessions (
              id TEXT PRIMARY KEY,
              project TEXT NOT NULL,
              spec_id TEXT,
              agent TEXT NOT NULL,
              branch TEXT,
              task TEXT,
              pid INTEGER,
              status TEXT NOT NULL DEFAULT 'running'
                  CHECK (status IN ('running', 'completed', 'stopped', 'failed')),
              started_at TEXT NOT NULL,
              completed_at TEXT
          )""",
          "CREATE INDEX IF NOT EXISTS idx_agent_sessions_project ON agent_sessions(project)",
          "CREATE INDEX IF NOT EXISTS idx_agent_sessions_spec ON agent_sessions(spec_id)",
          "ALTER TABLE specs ADD COLUMN project TEXT NOT NULL DEFAULT 'unassigned'",
          "CREATE INDEX IF NOT EXISTS idx_specs_project ON specs(project)",
          """
          CREATE TABLE IF NOT EXISTS data_migrations (
              name TEXT PRIMARY KEY,
              applied_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE api_tokens_new (
              token_hash TEXT PRIMARY KEY,
              name TEXT NOT NULL UNIQUE,
              role TEXT NOT NULL DEFAULT 'member'
                  CHECK (role IN ('admin', 'member', 'viewer')),
              created_at TEXT NOT NULL,
              last_used_at TEXT
          )""",
          """
          INSERT INTO api_tokens_new (token_hash, name, role, created_at, last_used_at)
              SELECT token_hash, name, role, created_at, last_used_at FROM api_tokens""",
          "DROP TABLE api_tokens",
          "ALTER TABLE api_tokens_new RENAME TO api_tokens",
          """
          CREATE TABLE IF NOT EXISTS fdes (
              id TEXT PRIMARY KEY,
              handle TEXT NOT NULL UNIQUE,
              display_name TEXT,
              email TEXT,
              status TEXT NOT NULL DEFAULT 'active'
                  CHECK (status IN ('active', 'disabled')),
              created_at TEXT NOT NULL
          )""",
          "ALTER TABLE api_tokens ADD COLUMN fde_id TEXT",
          "ALTER TABLE specs ADD COLUMN updated_by TEXT",
          "ALTER TABLE reviews ADD COLUMN decided_by TEXT",
          """
          CREATE TABLE IF NOT EXISTS webauthn_credentials (
              credential_id TEXT PRIMARY KEY,
              fde_id TEXT NOT NULL REFERENCES fdes(id) ON DELETE CASCADE,
              public_key_cose TEXT NOT NULL,
              cose_algorithm INTEGER NOT NULL,
              sign_count INTEGER NOT NULL DEFAULT 0,
              aaguid TEXT,
              backup_eligible INTEGER NOT NULL DEFAULT 0,
              backup_state INTEGER NOT NULL DEFAULT 0,
              label TEXT,
              created_at TEXT NOT NULL,
              last_used_at TEXT
          )""",
          "CREATE INDEX IF NOT EXISTS idx_webauthn_credentials_fde"
              + " ON webauthn_credentials(fde_id)",
          """
          CREATE TABLE IF NOT EXISTS sessions (
              token_hash TEXT PRIMARY KEY,
              fde_id TEXT NOT NULL REFERENCES fdes(id) ON DELETE CASCADE,
              created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL,
              last_used_at TEXT
          )""",
          """
          CREATE TABLE IF NOT EXISTS webauthn_challenges (
              id TEXT PRIMARY KEY,
              challenge TEXT NOT NULL,
              ceremony TEXT NOT NULL CHECK (ceremony IN ('register', 'assert')),
              fde_id TEXT REFERENCES fdes(id) ON DELETE CASCADE,
              created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE IF NOT EXISTS enrollment_tickets (
              token_hash TEXT PRIMARY KEY,
              fde_id TEXT NOT NULL REFERENCES fdes(id) ON DELETE CASCADE,
              created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL,
              consumed_at TEXT
          )""",
          "ALTER TABLE fdes ADD COLUMN role TEXT NOT NULL DEFAULT 'member'",
          """
          CREATE TABLE IF NOT EXISTS fde_ssh_keys (
              fingerprint TEXT PRIMARY KEY,
              fde_id TEXT NOT NULL REFERENCES fdes(id) ON DELETE CASCADE,
              public_key TEXT NOT NULL,
              comment TEXT,
              created_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE IF NOT EXISTS projects (
              name TEXT PRIMARY KEY,
              definition TEXT NOT NULL,
              created_by TEXT,
              created_at TEXT NOT NULL,
              updated_by TEXT,
              updated_at TEXT NOT NULL
          )""",
          "ALTER TABLE specs ADD COLUMN rev TEXT",
          """
          CREATE TABLE IF NOT EXISTS change_log (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              entity_type TEXT NOT NULL,
              entity_id TEXT NOT NULL,
              rev TEXT NOT NULL,
              actor TEXT,
              recorded_at TEXT NOT NULL,
              origin TEXT NOT NULL,
              deleted INTEGER NOT NULL DEFAULT 0,
              snapshot TEXT NOT NULL
          )""",
          "CREATE INDEX IF NOT EXISTS idx_change_log_entity"
              + " ON change_log(entity_type, entity_id, seq)",
          "ALTER TABLE specs ADD COLUMN base_rev TEXT",
          """
          CREATE TABLE IF NOT EXISTS sync_conflicts (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              entity_type TEXT NOT NULL,
              entity_id TEXT NOT NULL,
              base_snapshot TEXT,
              local_snapshot TEXT,
              remote_snapshot TEXT,
              fields TEXT NOT NULL,
              detected_at TEXT NOT NULL,
              status TEXT NOT NULL DEFAULT 'pending',
              resolved_rev TEXT
          )""",
          "CREATE INDEX IF NOT EXISTS idx_sync_conflicts_pending"
              + " ON sync_conflicts(status, entity_type, entity_id)",
          """
          CREATE TABLE IF NOT EXISTS sync_state (
              peer TEXT PRIMARY KEY,
              checkpoint INTEGER NOT NULL DEFAULT 0,
              updated_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE IF NOT EXISTS project_files (
              id TEXT PRIMARY KEY,
              project TEXT NOT NULL,
              path TEXT NOT NULL,
              content TEXT NOT NULL,
              rev TEXT,
              base_rev TEXT,
              updated_at TEXT NOT NULL,
              UNIQUE (project, path)
          )""",
          "ALTER TABLE api_tokens ADD COLUMN expires_at TEXT",
          "ALTER TABLE projects ADD COLUMN rev TEXT",
          "ALTER TABLE projects ADD COLUMN base_rev TEXT",
          """
          CREATE TABLE spec_dependencies_v2 (
              spec_id TEXT NOT NULL REFERENCES specs(id) ON DELETE CASCADE,
              depends_on TEXT NOT NULL,
              PRIMARY KEY (spec_id, depends_on)
          )""",
          "INSERT INTO spec_dependencies_v2 (spec_id, depends_on)"
              + " SELECT spec_id, depends_on FROM spec_dependencies",
          "DROP TABLE spec_dependencies",
          "ALTER TABLE spec_dependencies_v2 RENAME TO spec_dependencies",
          "ALTER TABLE agent_sessions ADD COLUMN exit_code INTEGER",
          "ALTER TABLE reviews ADD COLUMN superseded_at TEXT",
          "ALTER TABLE reviews ADD COLUMN error TEXT",
          "ALTER TABLE review_stages ADD COLUMN error TEXT",
          """
          CREATE TABLE IF NOT EXISTS spec_source_findings (
              spec_id TEXT NOT NULL REFERENCES specs(id) ON DELETE CASCADE,
              finding_id TEXT NOT NULL REFERENCES review_findings(id) ON DELETE CASCADE,
              PRIMARY KEY (spec_id, finding_id)
          )""",
          """
          CREATE TABLE IF NOT EXISTS slack_threads (
              project TEXT NOT NULL,
              spec_id TEXT NOT NULL,
              channel TEXT NOT NULL,
              thread_ts TEXT NOT NULL,
              created_at TEXT NOT NULL,
              PRIMARY KEY (project, spec_id)
          )""",
          """
          CREATE TABLE specs_v2 (
              id TEXT PRIMARY KEY,
              title TEXT NOT NULL,
              status TEXT NOT NULL DEFAULT 'draft'
                  CHECK (status IN ('draft', 'pending', 'in_progress', 'review', 'awaiting_merge',
                      'done', 'archived')),
              assignee TEXT,
              agent TEXT,
              model TEXT,
              reasoning_effort TEXT
                  CHECK (reasoning_effort IS NULL OR reasoning_effort IN ('none', 'low', 'medium', 'high', 'xhigh')),
              branch TEXT,
              priority INTEGER NOT NULL DEFAULT 0,
              created_by TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              project TEXT NOT NULL DEFAULT 'unassigned',
              updated_by TEXT,
              rev TEXT,
              base_rev TEXT
          )""",
          """
          INSERT INTO specs_v2 (id, title, status, assignee, agent, model, reasoning_effort,
                  branch, priority, created_by, created_at, updated_at, project, updated_by,
                  rev, base_rev)
              SELECT id, title, status, assignee, agent, model, reasoning_effort,
                  branch, priority, created_by, created_at, updated_at, project, updated_by,
                  rev, base_rev FROM specs""",
          "DROP TABLE specs",
          "ALTER TABLE specs_v2 RENAME TO specs",
          "CREATE INDEX IF NOT EXISTS idx_specs_project ON specs(project)",
          "ALTER TABLE agent_sessions ADD COLUMN watcher_pid INTEGER",
          "ALTER TABLE agent_sessions ADD COLUMN node TEXT",
          "ALTER TABLE agent_sessions ADD COLUMN role TEXT NOT NULL DEFAULT 'build'",
          "ALTER TABLE agent_sessions ADD COLUMN log_path TEXT",
          "ALTER TABLE agent_sessions ADD COLUMN rev TEXT",
          "ALTER TABLE agent_sessions ADD COLUMN base_rev TEXT",
          "ALTER TABLE agent_sessions RENAME TO runs",
          "CREATE INDEX IF NOT EXISTS idx_runs_project ON runs(project)",
          "CREATE INDEX IF NOT EXISTS idx_runs_spec ON runs(spec_id)",
          "DROP INDEX IF EXISTS idx_agent_sessions_project",
          "DROP INDEX IF EXISTS idx_agent_sessions_spec",
          "ALTER TABLE reviews ADD COLUMN rev TEXT",
          "ALTER TABLE reviews ADD COLUMN base_rev TEXT",
          "ALTER TABLE review_stages ADD COLUMN finding_counts TEXT",
          """
          CREATE TABLE reviews_v2 (
              id TEXT PRIMARY KEY,
              spec_id TEXT NOT NULL,
              iteration INTEGER NOT NULL DEFAULT 1,
              status TEXT NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending', 'running', 'passed', 'failed', 'escalated')),
              created_at TEXT NOT NULL,
              completed_at TEXT,
              decided_by TEXT,
              superseded_at TEXT,
              error TEXT,
              rev TEXT,
              base_rev TEXT
          )""",
          """
          INSERT INTO reviews_v2 (id, spec_id, iteration, status, created_at, completed_at,
              decided_by, superseded_at, error, rev, base_rev)
          SELECT id, spec_id, iteration, status, created_at, completed_at,
              decided_by, superseded_at, error, rev, base_rev FROM reviews""",
          "DROP TABLE reviews",
          "ALTER TABLE reviews_v2 RENAME TO reviews",
          "CREATE INDEX IF NOT EXISTS idx_reviews_spec ON reviews(spec_id)",
          "ALTER TABLE change_log ADD COLUMN peer TEXT",
          "ALTER TABLE runs ADD COLUMN unit TEXT",
          "ALTER TABLE runs ADD COLUMN repos TEXT",
          """
          CREATE TABLE specs_v3 (
              id TEXT PRIMARY KEY,
              title TEXT NOT NULL,
              status TEXT NOT NULL DEFAULT 'draft'
                  CHECK (status IN ('draft', 'pending', 'in_progress', 'review', 'awaiting_merge',
                      'done', 'cancelled', 'archived')),
              assignee TEXT,
              agent TEXT,
              model TEXT,
              reasoning_effort TEXT
                  CHECK (reasoning_effort IS NULL OR reasoning_effort IN ('none', 'low', 'medium', 'high', 'xhigh')),
              branch TEXT,
              priority INTEGER NOT NULL DEFAULT 0,
              created_by TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              project TEXT NOT NULL DEFAULT 'unassigned',
              updated_by TEXT,
              rev TEXT,
              base_rev TEXT
          )""",
          """
          INSERT INTO specs_v3 (id, title, status, assignee, agent, model, reasoning_effort,
                  branch, priority, created_by, created_at, updated_at, project, updated_by,
                  rev, base_rev)
              SELECT id, title, status, assignee, agent, model, reasoning_effort,
                  branch, priority, created_by, created_at, updated_at, project, updated_by,
                  rev, base_rev FROM specs""",
          "DROP TABLE specs",
          "ALTER TABLE specs_v3 RENAME TO specs",
          "CREATE INDEX IF NOT EXISTS idx_specs_project ON specs(project)",
          """
          CREATE TABLE runs_v2 (
              id TEXT PRIMARY KEY,
              project TEXT NOT NULL,
              spec_id TEXT,
              agent TEXT NOT NULL,
              branch TEXT,
              task TEXT,
              pid INTEGER,
              status TEXT NOT NULL DEFAULT 'running'
                  CHECK (status IN ('running', 'stopping', 'completed', 'stopped', 'failed')),
              started_at TEXT NOT NULL,
              completed_at TEXT,
              exit_code INTEGER,
              watcher_pid INTEGER,
              node TEXT,
              role TEXT NOT NULL DEFAULT 'build',
              log_path TEXT,
              rev TEXT,
              base_rev TEXT,
              unit TEXT,
              repos TEXT
          )""",
          """
          INSERT INTO runs_v2 (id, project, spec_id, agent, branch, task, pid, status,
                  started_at, completed_at, exit_code, watcher_pid, node, role, log_path,
                  rev, base_rev, unit, repos)
              SELECT id, project, spec_id, agent, branch, task, pid, status,
                  started_at, completed_at, exit_code, watcher_pid, node, role, log_path,
                  rev, base_rev, unit, repos FROM runs""",
          "DROP TABLE runs",
          "ALTER TABLE runs_v2 RENAME TO runs",
          "CREATE INDEX IF NOT EXISTS idx_runs_project ON runs(project)",
          "CREATE INDEX IF NOT EXISTS idx_runs_spec ON runs(spec_id)",
          "UPDATE specs SET project = 'unassigned' WHERE project IS NULL",
          """
          CREATE TABLE specs_v4 (
              id TEXT PRIMARY KEY,
              title TEXT NOT NULL,
              status TEXT NOT NULL DEFAULT 'draft'
                  CHECK (status IN ('draft', 'pending', 'in_progress', 'review', 'awaiting_merge',
                      'done', 'cancelled', 'archived')),
              assignee TEXT,
              agent TEXT,
              model TEXT,
              reasoning_effort TEXT
                  CHECK (reasoning_effort IS NULL OR reasoning_effort IN
                      ('none', 'low', 'medium', 'high', 'xhigh')),
              branch TEXT,
              priority INTEGER NOT NULL DEFAULT 0,
              created_by TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              project TEXT NOT NULL DEFAULT 'unassigned',
              updated_by TEXT,
              rev TEXT,
              base_rev TEXT
          )""",
          """
          INSERT INTO specs_v4 (id, title, status, assignee, agent, model, reasoning_effort,
                  branch, priority, created_by, created_at, updated_at, project, updated_by,
                  rev, base_rev)
              SELECT id, title, status, assignee, agent, model, reasoning_effort,
                  branch, priority, created_by, created_at, updated_at, project, updated_by,
                  rev, base_rev FROM specs""",
          "DROP TABLE specs",
          "ALTER TABLE specs_v4 RENAME TO specs",
          "CREATE INDEX IF NOT EXISTS idx_specs_project ON specs(project)",
          "UPDATE runs SET role = 'build' WHERE role IS NULL",
          """
          CREATE TABLE runs_v3 (
              id TEXT PRIMARY KEY,
              project TEXT NOT NULL,
              spec_id TEXT,
              agent TEXT NOT NULL,
              branch TEXT,
              task TEXT,
              pid INTEGER,
              status TEXT NOT NULL DEFAULT 'running'
                  CHECK (status IN ('running', 'stopping', 'completed', 'stopped', 'failed')),
              started_at TEXT NOT NULL,
              completed_at TEXT,
              exit_code INTEGER,
              watcher_pid INTEGER,
              node TEXT,
              role TEXT NOT NULL DEFAULT 'build'
                  CHECK (role IN ('build', 'review')),
              log_path TEXT,
              rev TEXT,
              base_rev TEXT,
              unit TEXT,
              repos TEXT
          )""",
          """
          INSERT INTO runs_v3 (id, project, spec_id, agent, branch, task, pid, status,
                  started_at, completed_at, exit_code, watcher_pid, node, role, log_path,
                  rev, base_rev, unit, repos)
              SELECT id, project, spec_id, agent, branch, task, pid, status,
                  started_at, completed_at, exit_code, watcher_pid, node, role, log_path,
                  rev, base_rev, unit, repos FROM runs""",
          "DROP TABLE runs",
          "ALTER TABLE runs_v3 RENAME TO runs",
          "CREATE INDEX IF NOT EXISTS idx_runs_project ON runs(project)",
          "CREATE INDEX IF NOT EXISTS idx_runs_spec ON runs(spec_id)",
          """
          DELETE FROM api_tokens
          WHERE fde_id IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM fdes WHERE fdes.id = api_tokens.fde_id)""",
          """
          CREATE TABLE api_tokens_v2 (
              token_hash TEXT PRIMARY KEY,
              name TEXT NOT NULL UNIQUE,
              role TEXT NOT NULL DEFAULT 'member'
                  CHECK (role IN ('admin', 'member', 'viewer')),
              fde_id TEXT REFERENCES fdes(id) ON DELETE CASCADE,
              created_at TEXT NOT NULL,
              last_used_at TEXT,
              expires_at TEXT
          )""",
          """
          INSERT INTO api_tokens_v2
                  (token_hash, name, role, fde_id, created_at, last_used_at, expires_at)
              SELECT token_hash, name, role, fde_id, created_at, last_used_at, expires_at
              FROM api_tokens""",
          "DROP TABLE api_tokens",
          "ALTER TABLE api_tokens_v2 RENAME TO api_tokens",
          """
          CREATE TABLE runs_v4 (
              id TEXT PRIMARY KEY,
              project TEXT NOT NULL,
              spec_id TEXT,
              agent TEXT NOT NULL,
              branch TEXT,
              task TEXT,
              pid INTEGER,
              status TEXT NOT NULL DEFAULT 'running'
                  CHECK (status IN ('running', 'stopping', 'completed', 'stopped', 'failed')),
              started_at TEXT NOT NULL,
              completed_at TEXT,
              exit_code INTEGER,
              watcher_pid INTEGER,
              node TEXT,
              role TEXT NOT NULL DEFAULT 'build'
                  CHECK (role IN ('build', 'adhoc', 'review')),
              log_path TEXT,
              rev TEXT,
              base_rev TEXT,
              unit TEXT,
              repos TEXT
          )""",
          """
          INSERT INTO runs_v4 (id, project, spec_id, agent, branch, task, pid, status,
                  started_at, completed_at, exit_code, watcher_pid, node, role, log_path,
                  rev, base_rev, unit, repos)
              SELECT id, project, spec_id, agent, branch, task, pid, status,
                  started_at, completed_at, exit_code, watcher_pid, node, role, log_path,
                  rev, base_rev, unit, repos FROM runs""",
          "DROP TABLE runs",
          "ALTER TABLE runs_v4 RENAME TO runs",
          "CREATE INDEX IF NOT EXISTS idx_runs_project ON runs(project)",
          "CREATE INDEX IF NOT EXISTS idx_runs_spec ON runs(spec_id)");

  /**
   * The last schema version whose {@code specs.status} CHECK predates {@code awaiting_merge}. The
   * five migrations after it rebuild the specs table to widen the constraint — SQLite cannot alter
   * a CHECK in place. The rebuild only works because {@link #migrate()} disables foreign-key
   * enforcement for the migration window: with it on, {@code DROP TABLE specs} would fire the
   * children's {@code ON DELETE CASCADE} and wipe spec content, repos, and dependencies.
   */
  static final int LAST_VERSION_WITH_NARROW_STATUS_CHECK = versionBefore("CREATE TABLE specs_v2");

  static final int LAST_VERSION_BEFORE_V1_FLOOR =
      versionBefore("UPDATE specs SET project = 'unassigned'");

  private static int versionBefore(String statementPrefix) {
    for (var i = 0; i < MIGRATIONS.size(); i++) {
      if (MIGRATIONS.get(i).startsWith(statementPrefix)) {
        return i;
      }
    }
    throw new IllegalStateException("No migration starts with: " + statementPrefix);
  }

  private final Sqlite db;

  public SchemaManager(Sqlite db) {
    this.db = db;
  }

  /**
   * Converges the schema to the current version. Refuses to carry a database across the 0.14.0 v1
   * data floor when the floor's data migration has not run: the floor's schema rebuilds repair
   * shapes structurally, but only {@code sail migrate} repairs the rows (node attribution, project
   * assignment, baseline journaling), and a schema-only crossing would bury that need forever —
   * afterwards nothing can tell a repaired database from a skipped one. Fresh databases (version 0)
   * and databases already at or above the floor pass untouched; the repair lane crosses via {@link
   * #migrateAll()}.
   */
  public void migrate() {
    var current = currentVersion();
    if (current > 0 && current <= LAST_VERSION_BEFORE_V1_FLOOR && !dataFloorStamped()) {
      throw new PreFloorException(
          "This database predates the 0.14.0 v1 data floor ("
              + LegacyDataMigration.NAME
              + " has not run). Run 'sail migrate' to carry it forward, then retry.");
    }
    migrateAll();
  }

  /**
   * Converges the schema without the floor guard — the lane for {@link MigrationRunner#applyAll},
   * which runs the data migrations immediately after and is therefore the only caller allowed to
   * carry a pre-floor database across.
   */
  void migrateAll() {
    migrateTo(MIGRATIONS.size());
  }

  private boolean dataFloorStamped() {
    try {
      return !db.query(
              "SELECT name FROM data_migrations WHERE name = ?",
              row -> row.text(0),
              LegacyDataMigration.NAME)
          .isEmpty();
    } catch (SqliteException e) {
      return false;
    }
  }

  /**
   * A database below the v1 floor whose data migration has not run; only 'sail migrate' may carry
   * it forward. Kept distinct so callers can surface the message without re-wrapping.
   */
  public static final class PreFloorException extends IllegalStateException {
    PreFloorException(String message) {
      super(message);
    }
  }

  /**
   * Applies pending migrations up to {@code targetVersion} (package-private so tests can stage a
   * database at a historical version). Foreign-key enforcement is suspended for the migration
   * window: table rebuilds must drop-and-recreate a parent table without firing the children's
   * {@code ON DELETE CASCADE}. Referential integrity is verified with {@code PRAGMA
   * foreign_key_check} before enforcement is restored — a violated rebuild fails loud, never
   * silently ships a corrupted database.
   */
  void migrateTo(int targetVersion) {
    db.execute(MIGRATIONS.getFirst());
    var current = currentVersion();
    if (current >= targetVersion) {
      return;
    }
    db.execute("PRAGMA foreign_keys = OFF");
    try {
      for (var i = current; i < targetVersion; i++) {
        var version = i + 1;
        var sql = MIGRATIONS.get(i);
        db.transaction(
            () -> {
              db.execute(sql);
              db.execute(
                  "INSERT INTO schema_version (version, applied_at) VALUES (?, datetime('now'))",
                  version);
            });
      }
      requireForeignKeysIntact();
    } finally {
      db.execute("PRAGMA foreign_keys = ON");
    }
  }

  private void requireForeignKeysIntact() {
    var violations =
        db.query("PRAGMA foreign_key_check", row -> row.text(0) + " row " + row.integer(1));
    if (!violations.isEmpty()) {
      throw new SqliteException(
          "Migration broke referential integrity: " + String.join(", ", violations), 0);
    }
  }

  public int currentVersion() {
    try {
      return db.queryOne("SELECT MAX(version) FROM schema_version", row -> (int) row.integer(0))
          .orElse(0);
    } catch (SqliteException e) {
      return 0;
    }
  }
}
