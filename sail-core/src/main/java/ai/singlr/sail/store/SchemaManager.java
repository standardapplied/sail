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
          )""");

  private final Sqlite db;

  public SchemaManager(Sqlite db) {
    this.db = db;
  }

  public void migrate() {
    db.execute(MIGRATIONS.getFirst());
    var current = currentVersion();
    for (var i = current; i < MIGRATIONS.size(); i++) {
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
