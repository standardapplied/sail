/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.List;

/**
 * The v1 upgrade floor contract: the exact {@code sqlite_master} contents of a fully-migrated sail
 * 0.14.x database, captured verbatim (including the quoted names and appended-column artifacts its
 * rebuild-heavy migration chain left behind) before that chain was deleted. Tests stage a floor
 * database from this fixture to prove the one-step on-ramp into the v1 baseline; if the baseline
 * ever drifts from this shape, the schema-diff test fails and the floor has been broken.
 */
final class FloorSchema {

  private FloorSchema() {}

  private static final List<String> DDL =
      List.of(
          """
          CREATE TABLE schema_version (
              version INTEGER PRIMARY KEY,
              applied_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE "specs" (
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
          CREATE TABLE "spec_dependencies" (
              spec_id TEXT NOT NULL REFERENCES specs(id) ON DELETE CASCADE,
              depends_on TEXT NOT NULL,
              PRIMARY KEY (spec_id, depends_on)
          )""",
          """
          CREATE TABLE spec_repos (
              spec_id TEXT NOT NULL REFERENCES specs(id) ON DELETE CASCADE,
              repo TEXT NOT NULL,
              PRIMARY KEY (spec_id, repo)
          )""",
          """
          CREATE TABLE spec_content (
              spec_id TEXT PRIMARY KEY REFERENCES specs(id) ON DELETE CASCADE,
              body TEXT NOT NULL DEFAULT '',
              plan TEXT NOT NULL DEFAULT '',
              updated_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE spec_attachments (
              id TEXT PRIMARY KEY,
              spec_id TEXT NOT NULL REFERENCES specs(id) ON DELETE CASCADE,
              filename TEXT NOT NULL,
              content_type TEXT NOT NULL,
              size_bytes INTEGER NOT NULL,
              storage_path TEXT NOT NULL,
              created_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE events (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              timestamp TEXT NOT NULL,
              type TEXT NOT NULL,
              project TEXT,
              spec_id TEXT,
              agent TEXT,
              host TEXT NOT NULL,
              data TEXT NOT NULL DEFAULT '{}'
          )""",
          """
          CREATE TABLE fdes (
              id TEXT PRIMARY KEY,
              handle TEXT NOT NULL UNIQUE,
              display_name TEXT,
              email TEXT,
              status TEXT NOT NULL DEFAULT 'active'
                  CHECK (status IN ('active', 'disabled')),
              created_at TEXT NOT NULL
          , role TEXT NOT NULL DEFAULT 'member')""",
          """
          CREATE TABLE "api_tokens" (
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
          CREATE TABLE webauthn_credentials (
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
          """
          CREATE TABLE sessions (
              token_hash TEXT PRIMARY KEY,
              fde_id TEXT NOT NULL REFERENCES fdes(id) ON DELETE CASCADE,
              created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL,
              last_used_at TEXT
          )""",
          """
          CREATE TABLE webauthn_challenges (
              id TEXT PRIMARY KEY,
              challenge TEXT NOT NULL,
              ceremony TEXT NOT NULL CHECK (ceremony IN ('register', 'assert')),
              fde_id TEXT REFERENCES fdes(id) ON DELETE CASCADE,
              created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE enrollment_tickets (
              token_hash TEXT PRIMARY KEY,
              fde_id TEXT NOT NULL REFERENCES fdes(id) ON DELETE CASCADE,
              created_at TEXT NOT NULL,
              expires_at TEXT NOT NULL,
              consumed_at TEXT
          )""",
          """
          CREATE TABLE fde_ssh_keys (
              fingerprint TEXT PRIMARY KEY,
              fde_id TEXT NOT NULL REFERENCES fdes(id) ON DELETE CASCADE,
              public_key TEXT NOT NULL,
              comment TEXT,
              created_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE "reviews" (
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
          CREATE TABLE review_stages (
              id TEXT PRIMARY KEY,
              review_id TEXT NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
              name TEXT NOT NULL,
              stage_type TEXT NOT NULL,
              status TEXT NOT NULL DEFAULT 'pending'
                  CHECK (status IN ('pending', 'running', 'passed', 'failed', 'skipped')),
              reviewer TEXT,
              started_at TEXT,
              completed_at TEXT
          , error TEXT, finding_counts TEXT)""",
          """
          CREATE TABLE review_findings (
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
          """
          CREATE TABLE spec_source_findings (
              spec_id TEXT NOT NULL REFERENCES specs(id) ON DELETE CASCADE,
              finding_id TEXT NOT NULL REFERENCES review_findings(id) ON DELETE CASCADE,
              PRIMARY KEY (spec_id, finding_id)
          )""",
          """
          CREATE TABLE "runs" (
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
          , pid_ticks INTEGER)""",
          """
          CREATE TABLE projects (
              name TEXT PRIMARY KEY,
              definition TEXT NOT NULL,
              created_by TEXT,
              created_at TEXT NOT NULL,
              updated_by TEXT,
              updated_at TEXT NOT NULL
          , rev TEXT, base_rev TEXT)""",
          """
          CREATE TABLE project_files (
              id TEXT PRIMARY KEY,
              project TEXT NOT NULL,
              path TEXT NOT NULL,
              content TEXT NOT NULL,
              rev TEXT,
              base_rev TEXT,
              updated_at TEXT NOT NULL,
              UNIQUE (project, path)
          )""",
          """
          CREATE TABLE change_log (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              entity_type TEXT NOT NULL,
              entity_id TEXT NOT NULL,
              rev TEXT NOT NULL,
              actor TEXT,
              recorded_at TEXT NOT NULL,
              origin TEXT NOT NULL,
              deleted INTEGER NOT NULL DEFAULT 0,
              snapshot TEXT NOT NULL
          , peer TEXT)""",
          """
          CREATE TABLE sync_conflicts (
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
          """
          CREATE TABLE sync_state (
              peer TEXT PRIMARY KEY,
              checkpoint INTEGER NOT NULL DEFAULT 0,
              updated_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE slack_threads (
              project TEXT NOT NULL,
              spec_id TEXT NOT NULL,
              channel TEXT NOT NULL,
              thread_ts TEXT NOT NULL,
              created_at TEXT NOT NULL,
              PRIMARY KEY (project, spec_id)
          )""",
          """
          CREATE TABLE data_migrations (
              name TEXT PRIMARY KEY,
              applied_at TEXT NOT NULL
          )""",
          "CREATE INDEX idx_specs_project ON specs(project)",
          "CREATE INDEX idx_events_type ON events(type)",
          "CREATE INDEX idx_events_project ON events(project)",
          "CREATE INDEX idx_events_spec ON events(spec_id)",
          "CREATE INDEX idx_events_timestamp ON events(timestamp DESC)",
          "CREATE INDEX idx_webauthn_credentials_fde ON webauthn_credentials(fde_id)",
          "CREATE INDEX idx_reviews_spec ON reviews(spec_id)",
          "CREATE INDEX idx_review_stages_review ON review_stages(review_id)",
          "CREATE INDEX idx_review_findings_stage ON review_findings(stage_id)",
          "CREATE INDEX idx_review_findings_severity ON review_findings(severity)",
          "CREATE INDEX idx_runs_project ON runs(project)",
          "CREATE INDEX idx_runs_spec ON runs(spec_id)",
          "CREATE INDEX idx_change_log_entity ON change_log(entity_type, entity_id, seq)",
          "CREATE INDEX idx_sync_conflicts_pending"
              + " ON sync_conflicts(status, entity_type, entity_id)");

  static void stage(Sqlite db) {
    DDL.forEach(db::execute);
    for (var version = 1; version <= SchemaManager.FLOOR_VERSION; version++) {
      db.execute("INSERT INTO schema_version (version, applied_at) VALUES (?, 'staged')", version);
    }
  }
}
