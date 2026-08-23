/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.List;

/**
 * Manages SQLite schema versioning against the v1 baseline. A fresh database gets the current
 * schema in one shot; a database at or above the published 0.14 floor (schema v{@value
 * #FLOOR_VERSION}, the version every released 0.14.x binary reaches) rides the on-ramp — the
 * post-floor migrations that never shipped in a 0.14.x release — to the baseline; anything below
 * the floor is refused with the remedy, never silently replayed. The pre-1.0 migration chain lives
 * in git history only.
 *
 * <p>Policy: migrations are append-only within a major version, and baselining like this happens
 * only at a major version with a published floor. The floor must be a version a released binary can
 * actually reach — a floor no release can produce strands the fleet behind an impossible remedy.
 * See ARCHITECTURE.md.
 */
public final class SchemaManager {

  /** The schema version a fully-migrated released sail 0.14.x database carries: the v1 floor. */
  static final int FLOOR_VERSION = 118;

  /**
   * The migrations between the floor and the baseline: appended after 0.14.1 shipped, never
   * released, so a floor database must replay them here. Verbatim from the deleted chain; a
   * database mid-ramp (a development box) resumes at its recorded version.
   */
  static final List<String> ON_RAMP =
      List.of(
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
          "CREATE INDEX IF NOT EXISTS idx_runs_spec ON runs(spec_id)",
          "ALTER TABLE runs ADD COLUMN pid_ticks INTEGER");

  /** The v1 baseline version: the floor plus the on-ramp it is structurally equivalent to. */
  static final int V1_VERSION = FLOOR_VERSION + ON_RAMP.size() + 1;

  /**
   * The post-baseline migration chain: append-only within the 1.x major, one statement per version,
   * versions continuing from {@value #V1_VERSION}. A migration's version is its <em>list index</em>
   * ({@link #postBaselineFrom} applies {@code MIGRATIONS.get(version - V1_VERSION - 1)}), so an
   * entry's position is its identity across releases: inserting one in the middle re-points every
   * later version at different SQL, and a box stamped by an earlier release then runs a statement
   * against the wrong schema — the {@code run_principals} incident, where a v0.20.0 box ran {@code
   * INSERT INTO run_principals} at the index its {@code CREATE} used to hold. <b>Only ever append;
   * never reorder, edit, or remove an entry.</b> Each addition ships with a test that seeds a
   * database in a prior release's shape and migrates it forward — a fresh-database test cannot
   * catch a mid-list insertion, because it never replays the earlier version→SQL mapping. The
   * {@code run_credentials} table is local-only secret material (per-run credential hashes), and
   * {@code run_delivered_messages} (delivery bookkeeping), {@code room_guard} (the room commit
   * guard's launch baseline, kept host-side so the guarded agent can never reach it), and {@code
   * container_leases} (a box's own exclusive-container-operation claims) are local-only as well —
   * none of the four ever joins a sync snapshot.
   */
  /**
   * One-time sweep of the review-loop convergence gap: findings a spec shipped below the gate were
   * left {@code OPEN} forever (nothing resolved a passed review's own residue). Closes every such
   * finding — {@code OPEN} on the non-superseded passed review of a {@code done} spec — as {@code
   * SHIPPED}, the same terminal state {@code ReviewStore.resolveShippedFindings} now writes on the
   * {@code → done} transition going forward. Named so the migration test runs this exact statement.
   */
  static final String BACKFILL_SHIPPED_RESIDUE =
      """
      UPDATE review_findings SET resolution = 'SHIPPED',
              resolution_evidence = 'shipped below the review gate'
          WHERE resolution = 'OPEN'
          AND stage_id IN (
              SELECT s.id FROM review_stages s
              JOIN reviews r ON r.id = s.review_id
              JOIN specs sp ON sp.id = r.spec_id
              WHERE r.status = 'passed'
              AND r.superseded_at IS NULL
              AND sp.status = 'done')""";

  static final List<String> MIGRATIONS =
      List.of(
          "ALTER TABLE runs ADD COLUMN principal TEXT",
          "ALTER TABLE runs ADD COLUMN owner TEXT",
          """
          CREATE TABLE run_credentials (
              run_id TEXT PRIMARY KEY,
              credential_hash TEXT NOT NULL UNIQUE,
              created_at TEXT NOT NULL,
              expires_at TEXT
          )""",
          """
          CREATE TABLE spec_messages (
              id TEXT PRIMARY KEY,
              spec_id TEXT NOT NULL,
              author TEXT NOT NULL,
              body TEXT NOT NULL,
              reply_to TEXT REFERENCES spec_messages(id),
              created_at TEXT NOT NULL,
              rev TEXT NOT NULL,
              base_rev TEXT
          )""",
          "CREATE INDEX idx_spec_messages_page ON spec_messages(spec_id, id DESC)",
          """
          CREATE TABLE box_credential (
              id INTEGER PRIMARY KEY CHECK (id = 1),
              handle TEXT NOT NULL,
              credential_hash TEXT NOT NULL UNIQUE,
              created_at TEXT NOT NULL
          )""",
          """
          CREATE TABLE review_findings_v2 (
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
                  CHECK (resolution IN ('OPEN', 'FIXED', 'DISMISSED', 'DISPUTED')),
              resolution_evidence TEXT,
              carried_from TEXT
          )""",
          """
          INSERT INTO review_findings_v2 (id, stage_id, severity, category, file,
                  line_start, line_end, title, description, evidence, suggestion_before,
                  suggestion_after, suggestion_rationale, confidence, resolution)
              SELECT id, stage_id, severity, category, file,
                  line_start, line_end, title, description, evidence, suggestion_before,
                  suggestion_after, suggestion_rationale, confidence, resolution
              FROM review_findings""",
          "DROP TABLE review_findings",
          "ALTER TABLE review_findings_v2 RENAME TO review_findings",
          "CREATE INDEX idx_review_findings_stage ON review_findings(stage_id)",
          "CREATE INDEX idx_review_findings_severity ON review_findings(severity)",
          """
          CREATE TABLE run_delivered_messages (
              run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
              message_id TEXT NOT NULL REFERENCES spec_messages(id) ON DELETE CASCADE,
              PRIMARY KEY (run_id, message_id)
          )""",
          """
          CREATE TABLE run_principals (
              run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
              principal TEXT NOT NULL,
              PRIMARY KEY (run_id, principal)
          )""",
          "INSERT INTO run_principals SELECT id, principal FROM runs WHERE principal IS NOT NULL",
          "ALTER TABLE review_findings ADD COLUMN carry_evidence TEXT",
          "ALTER TABLE runs ADD COLUMN session_id TEXT",
          "ALTER TABLE runs ADD COLUMN session_source TEXT",
          "ALTER TABLE runs ADD COLUMN transcript_path TEXT",
          """
          CREATE TABLE runs_v5 (
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
                  CHECK (role IN ('build', 'adhoc', 'review', 'room')),
              log_path TEXT,
              rev TEXT,
              base_rev TEXT,
              unit TEXT,
              repos TEXT,
              pid_ticks INTEGER,
              principal TEXT,
              owner TEXT,
              session_id TEXT,
              session_source TEXT,
              transcript_path TEXT
          )""",
          """
          INSERT INTO runs_v5 (id, project, spec_id, agent, branch, task, pid, status,
                  started_at, completed_at, exit_code, watcher_pid, node, role, log_path,
                  rev, base_rev, unit, repos, pid_ticks, principal, owner, session_id,
                  session_source, transcript_path)
              SELECT id, project, spec_id, agent, branch, task, pid, status,
                  started_at, completed_at, exit_code, watcher_pid, node, role, log_path,
                  rev, base_rev, unit, repos, pid_ticks, principal, owner, session_id,
                  session_source, transcript_path FROM runs""",
          "DROP TABLE runs",
          "ALTER TABLE runs_v5 RENAME TO runs",
          "CREATE INDEX IF NOT EXISTS idx_runs_project ON runs(project)",
          "CREATE INDEX IF NOT EXISTS idx_runs_spec ON runs(spec_id)",
          "ALTER TABLE specs ADD COLUMN wake TEXT"
              + " CHECK (wake IS NULL OR wake IN ('on', 'mention', 'off'))",
          """
          CREATE TABLE room_guard (
              run_id TEXT PRIMARY KEY REFERENCES runs(id) ON DELETE CASCADE,
              baseline TEXT NOT NULL
          )""",
          "ALTER TABLE runs ADD COLUMN last_activity_at TEXT",
          "ALTER TABLE spec_messages ADD COLUMN question INTEGER NOT NULL DEFAULT 0",
          """
          CREATE TABLE runs_v6 (
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
                  CHECK (role IN ('build', 'adhoc', 'review', 'room', 'invite', 'invite-full')),
              log_path TEXT,
              rev TEXT,
              base_rev TEXT,
              unit TEXT,
              repos TEXT,
              pid_ticks INTEGER,
              principal TEXT,
              owner TEXT,
              session_id TEXT,
              session_source TEXT,
              transcript_path TEXT,
              last_activity_at TEXT
          )""",
          """
          INSERT INTO runs_v6 (id, project, spec_id, agent, branch, task, pid, status,
                  started_at, completed_at, exit_code, watcher_pid, node, role, log_path,
                  rev, base_rev, unit, repos, pid_ticks, principal, owner, session_id,
                  session_source, transcript_path, last_activity_at)
              SELECT id, project, spec_id, agent, branch, task, pid, status,
                  started_at, completed_at, exit_code, watcher_pid, node, role, log_path,
                  rev, base_rev, unit, repos, pid_ticks, principal, owner, session_id,
                  session_source, transcript_path, last_activity_at FROM runs""",
          "DROP TABLE runs",
          "ALTER TABLE runs_v6 RENAME TO runs",
          "CREATE INDEX IF NOT EXISTS idx_runs_project ON runs(project)",
          "CREATE INDEX IF NOT EXISTS idx_runs_spec ON runs(spec_id)",
          """
          CREATE TABLE container_leases (
              project TEXT NOT NULL,
              node TEXT NOT NULL DEFAULT '',
              action TEXT NOT NULL,
              created_at TEXT NOT NULL,
              PRIMARY KEY (project, node)
          )""",
          "ALTER TABLE specs ADD COLUMN engagement TEXT",
          """
          CREATE TABLE runs_v7 (
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
                  CHECK (role IN ('build', 'adhoc', 'review', 'room', 'room-full', 'invite',
                      'invite-full')),
              log_path TEXT,
              rev TEXT,
              base_rev TEXT,
              unit TEXT,
              repos TEXT,
              pid_ticks INTEGER,
              principal TEXT,
              owner TEXT,
              session_id TEXT,
              session_source TEXT,
              transcript_path TEXT,
              last_activity_at TEXT
          )""",
          """
          INSERT INTO runs_v7 (id, project, spec_id, agent, branch, task, pid, status,
                  started_at, completed_at, exit_code, watcher_pid, node, role, log_path,
                  rev, base_rev, unit, repos, pid_ticks, principal, owner, session_id,
                  session_source, transcript_path, last_activity_at)
              SELECT id, project, spec_id, agent, branch, task, pid, status,
                  started_at, completed_at, exit_code, watcher_pid, node, role, log_path,
                  rev, base_rev, unit, repos, pid_ticks, principal, owner, session_id,
                  session_source, transcript_path, last_activity_at FROM runs""",
          "DROP TABLE runs",
          "ALTER TABLE runs_v7 RENAME TO runs",
          "CREATE INDEX IF NOT EXISTS idx_runs_project ON runs(project)",
          "CREATE INDEX IF NOT EXISTS idx_runs_spec ON runs(spec_id)",
          """
          CREATE TABLE review_findings_v3 (
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
                  CHECK (resolution IN ('OPEN', 'FIXED', 'DISMISSED', 'DISPUTED', 'SHIPPED')),
              resolution_evidence TEXT,
              carried_from TEXT,
              carry_evidence TEXT
          )""",
          """
          INSERT INTO review_findings_v3 (id, stage_id, severity, category, file,
                  line_start, line_end, title, description, evidence, suggestion_before,
                  suggestion_after, suggestion_rationale, confidence, resolution,
                  resolution_evidence, carried_from, carry_evidence)
              SELECT id, stage_id, severity, category, file,
                  line_start, line_end, title, description, evidence, suggestion_before,
                  suggestion_after, suggestion_rationale, confidence, resolution,
                  resolution_evidence, carried_from, carry_evidence
              FROM review_findings""",
          "DROP TABLE review_findings",
          "ALTER TABLE review_findings_v3 RENAME TO review_findings",
          "CREATE INDEX idx_review_findings_stage ON review_findings(stage_id)",
          "CREATE INDEX idx_review_findings_severity ON review_findings(severity)",
          BACKFILL_SHIPPED_RESIDUE,
          """
          CREATE TABLE rooms (
              id TEXT PRIMARY KEY,
              project TEXT,
              title TEXT NOT NULL,
              assignee TEXT,
              wake TEXT CHECK (wake IN ('on', 'mention', 'off')),
              roster TEXT,
              created_by TEXT,
              created_at TEXT NOT NULL,
              updated_at TEXT NOT NULL,
              updated_by TEXT,
              rev TEXT,
              base_rev TEXT
          )""");

  /** The schema version this binary converges every database to. */
  static final int CURRENT_VERSION = V1_VERSION + MIGRATIONS.size();

  private static final String SCHEMA_VERSION_TABLE =
      """
      CREATE TABLE IF NOT EXISTS schema_version (
          version INTEGER PRIMARY KEY,
          applied_at TEXT NOT NULL
      )""";

  private static final List<String> BASELINE =
      List.of(
          """
          CREATE TABLE specs (
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
          "CREATE INDEX idx_specs_project ON specs(project)",
          """
          CREATE TABLE spec_dependencies (
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
          "CREATE INDEX idx_events_type ON events(type)",
          "CREATE INDEX idx_events_project ON events(project)",
          "CREATE INDEX idx_events_spec ON events(spec_id)",
          "CREATE INDEX idx_events_timestamp ON events(timestamp DESC)",
          """
          CREATE TABLE fdes (
              id TEXT PRIMARY KEY,
              handle TEXT NOT NULL UNIQUE,
              display_name TEXT,
              email TEXT,
              status TEXT NOT NULL DEFAULT 'active'
                  CHECK (status IN ('active', 'disabled')),
              created_at TEXT NOT NULL,
              role TEXT NOT NULL DEFAULT 'member'
          )""",
          """
          CREATE TABLE api_tokens (
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
          "CREATE INDEX idx_webauthn_credentials_fde ON webauthn_credentials(fde_id)",
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
          CREATE TABLE reviews (
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
          "CREATE INDEX idx_reviews_spec ON reviews(spec_id)",
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
              completed_at TEXT,
              error TEXT,
              finding_counts TEXT
          )""",
          "CREATE INDEX idx_review_stages_review ON review_stages(review_id)",
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
          "CREATE INDEX idx_review_findings_stage ON review_findings(stage_id)",
          "CREATE INDEX idx_review_findings_severity ON review_findings(severity)",
          """
          CREATE TABLE spec_source_findings (
              spec_id TEXT NOT NULL REFERENCES specs(id) ON DELETE CASCADE,
              finding_id TEXT NOT NULL REFERENCES review_findings(id) ON DELETE CASCADE,
              PRIMARY KEY (spec_id, finding_id)
          )""",
          """
          CREATE TABLE runs (
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
              repos TEXT,
              pid_ticks INTEGER
          )""",
          "CREATE INDEX idx_runs_project ON runs(project)",
          "CREATE INDEX idx_runs_spec ON runs(spec_id)",
          """
          CREATE TABLE projects (
              name TEXT PRIMARY KEY,
              definition TEXT NOT NULL,
              created_by TEXT,
              created_at TEXT NOT NULL,
              updated_by TEXT,
              updated_at TEXT NOT NULL,
              rev TEXT,
              base_rev TEXT
          )""",
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
              snapshot TEXT NOT NULL,
              peer TEXT
          )""",
          "CREATE INDEX idx_change_log_entity ON change_log(entity_type, entity_id, seq)",
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
          "CREATE INDEX idx_sync_conflicts_pending"
              + " ON sync_conflicts(status, entity_type, entity_id)",
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
          )""");

  private final Sqlite db;

  public SchemaManager(Sqlite db) {
    this.db = db;
  }

  /**
   * Converges the schema to this binary's current version. A fresh database gets the v1 baseline
   * plus the post-baseline chain directly; a database at or past the 0.14 floor rides the on-ramp
   * remainder to the baseline (a released 0.14.x database replays the never-released post-floor
   * migrations; a development box mid-ramp resumes where its version left off) and then the
   * post-baseline chain, applied incrementally from its recorded version; a database below the
   * floor raises {@link PreFloorException} with the remedy, because this release no longer carries
   * the pre-1.0 migration chain; a database newer than this binary raises {@link
   * IllegalStateException}, because an older binary must not operate on a schema it does not
   * understand. Idempotent: re-running on a current database is a no-op.
   */
  public void migrate() {
    db.execute(SCHEMA_VERSION_TABLE);
    var current = currentVersion();
    if (current > CURRENT_VERSION) {
      throw new IllegalStateException(
          "Database schema v"
              + current
              + " is newer than this Sail binary supports (v"
              + CURRENT_VERSION
              + "). Upgrade Sail before opening this database.");
    }
    if (current == CURRENT_VERSION) {
      return;
    }
    if (current == 0) {
      db.transaction(
          () -> {
            BASELINE.forEach(db::execute);
            MIGRATIONS.forEach(db::execute);
            stamp(CURRENT_VERSION);
          });
      return;
    }
    if (current < FLOOR_VERSION) {
      throw new PreFloorException(
          "This database is schema v"
              + current
              + ", below the v1 schema floor (schema v"
              + FLOOR_VERSION
              + "), and this release does not carry pre-floor migrations."
              + " Install sail 0.14.x, run 'sail migrate', then upgrade to this release and"
              + " retry.");
    }
    if (current < V1_VERSION) {
      onRampFrom(current);
    }
    postBaselineFrom(currentVersion());
  }

  /**
   * Applies the post-baseline remainder incrementally: each migration commits with its own version
   * stamp, so an interruption resumes exactly where it left off and a database written by an older
   * 1.x binary replays only the entries it has not seen. Foreign-key enforcement is suspended for
   * the window, exactly as on the on-ramp: the chain contains a table rebuild whose {@code DROP
   * TABLE} would otherwise fire an implicit delete and cascade child rows away. Integrity is
   * verified before enforcement is restored.
   */
  private void postBaselineFrom(int current) {
    if (current >= CURRENT_VERSION) {
      return;
    }
    db.execute("PRAGMA foreign_keys = OFF");
    try {
      for (var next = current + 1; next <= CURRENT_VERSION; next++) {
        var version = next;
        var statement = MIGRATIONS.get(version - V1_VERSION - 1);
        db.transaction(
            () -> {
              db.execute(statement);
              stamp(version);
            });
      }
      requireForeignKeysIntact();
    } finally {
      db.execute("PRAGMA foreign_keys = ON");
    }
  }

  /**
   * Applies the on-ramp remainder from {@code current} and stamps the baseline version, atomically.
   * Foreign-key enforcement is suspended for the window because the on-ramp contains a table
   * rebuild that must drop-and-recreate a parent without firing child cascades; integrity is
   * verified before enforcement is restored, so a violated rebuild fails loud rather than shipping
   * a corrupted database.
   */
  private void onRampFrom(int current) {
    db.execute("PRAGMA foreign_keys = OFF");
    try {
      db.transaction(
          () -> {
            ON_RAMP.subList(current - FLOOR_VERSION, ON_RAMP.size()).forEach(db::execute);
            stamp(V1_VERSION);
          });
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

  private void stamp(int version) {
    db.execute(
        "INSERT INTO schema_version (version, applied_at) VALUES (?, datetime('now'))", version);
  }

  /**
   * A database below the v1 schema floor: only a sail 0.14.x binary can carry it forward. Kept
   * distinct so callers can surface the message without re-wrapping.
   */
  public static final class PreFloorException extends IllegalStateException {
    PreFloorException(String message) {
      super(message);
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
