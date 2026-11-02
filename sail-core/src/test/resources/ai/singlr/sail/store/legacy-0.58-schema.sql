CREATE TABLE schema_version (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL
);
CREATE TABLE spec_repos (
    spec_id TEXT NOT NULL REFERENCES specs(id) ON DELETE CASCADE,
    repo TEXT NOT NULL,
    PRIMARY KEY (spec_id, repo)
);
CREATE TABLE spec_content (
    spec_id TEXT PRIMARY KEY REFERENCES specs(id) ON DELETE CASCADE,
    body TEXT NOT NULL DEFAULT '',
    plan TEXT NOT NULL DEFAULT '',
    updated_at TEXT NOT NULL
);
CREATE TABLE events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    timestamp TEXT NOT NULL,
    type TEXT NOT NULL,
    project TEXT,
    spec_id TEXT,
    agent TEXT,
    host TEXT NOT NULL,
    data TEXT NOT NULL DEFAULT '{}'
, publisher TEXT);
CREATE INDEX idx_events_type ON events(type);
CREATE INDEX idx_events_project ON events(project);
CREATE INDEX idx_events_spec ON events(spec_id);
CREATE INDEX idx_events_timestamp ON events(timestamp DESC);
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
, error TEXT, finding_counts TEXT);
CREATE INDEX idx_review_stages_review ON review_stages(review_id);
CREATE TABLE data_migrations (
    name TEXT PRIMARY KEY,
    applied_at TEXT NOT NULL
);
CREATE TABLE fdes (
    id TEXT PRIMARY KEY,
    handle TEXT NOT NULL UNIQUE,
    display_name TEXT,
    email TEXT,
    status TEXT NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'disabled')),
    created_at TEXT NOT NULL
, role TEXT NOT NULL DEFAULT 'member', scope TEXT NOT NULL DEFAULT 'all'
    CHECK (scope IN ('all', 'granted')));
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
);
CREATE INDEX idx_webauthn_credentials_fde ON webauthn_credentials(fde_id);
CREATE TABLE sessions (
    token_hash TEXT PRIMARY KEY,
    fde_id TEXT NOT NULL REFERENCES fdes(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    last_used_at TEXT
);
CREATE TABLE webauthn_challenges (
    id TEXT PRIMARY KEY,
    challenge TEXT NOT NULL,
    ceremony TEXT NOT NULL CHECK (ceremony IN ('register', 'assert')),
    fde_id TEXT REFERENCES fdes(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);
CREATE TABLE enrollment_tickets (
    token_hash TEXT PRIMARY KEY,
    fde_id TEXT NOT NULL REFERENCES fdes(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    consumed_at TEXT
);
CREATE TABLE fde_ssh_keys (
    fingerprint TEXT PRIMARY KEY,
    fde_id TEXT NOT NULL REFERENCES fdes(id) ON DELETE CASCADE,
    public_key TEXT NOT NULL,
    comment TEXT,
    created_at TEXT NOT NULL
);
CREATE TABLE projects (
    name TEXT PRIMARY KEY,
    definition TEXT NOT NULL,
    created_by TEXT,
    created_at TEXT NOT NULL,
    updated_by TEXT,
    updated_at TEXT NOT NULL
, rev TEXT, base_rev TEXT);
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
, peer TEXT, kind TEXT NOT NULL DEFAULT 'revision'
    CHECK (kind IN ('revision', 'tombstone', 'erasure')));
CREATE INDEX idx_change_log_entity ON change_log(entity_type, entity_id, seq);
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
, remote_rev TEXT, remote_author TEXT);
CREATE INDEX idx_sync_conflicts_pending ON sync_conflicts(status, entity_type, entity_id);
CREATE TABLE project_files (
    id TEXT PRIMARY KEY,
    project TEXT NOT NULL,
    path TEXT NOT NULL,
    rev TEXT,
    base_rev TEXT,
    updated_at TEXT NOT NULL, content_hash TEXT, size INTEGER, mode INTEGER NOT NULL DEFAULT 420, kind TEXT NOT NULL DEFAULT 'text',
    UNIQUE (project, path)
);
CREATE TABLE IF NOT EXISTS "spec_dependencies" (
    spec_id TEXT NOT NULL REFERENCES specs(id) ON DELETE CASCADE,
    depends_on TEXT NOT NULL,
    PRIMARY KEY (spec_id, depends_on)
);
CREATE TABLE slack_threads (
    project TEXT NOT NULL,
    spec_id TEXT NOT NULL,
    channel TEXT NOT NULL,
    thread_ts TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (project, spec_id)
);
CREATE TABLE IF NOT EXISTS "reviews" (
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
, findings_hash TEXT, waiting_on TEXT);
CREATE INDEX idx_reviews_spec ON reviews(spec_id);
CREATE TABLE IF NOT EXISTS "api_tokens" (
    token_hash TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    role TEXT NOT NULL DEFAULT 'member'
        CHECK (role IN ('admin', 'member', 'viewer')),
    fde_id TEXT REFERENCES fdes(id) ON DELETE CASCADE,
    created_at TEXT NOT NULL,
    last_used_at TEXT,
    expires_at TEXT
);
CREATE TABLE run_credentials (
    run_id TEXT PRIMARY KEY,
    credential_hash TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    expires_at TEXT
);
CREATE TABLE box_credential (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    handle TEXT NOT NULL,
    credential_hash TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL
);
CREATE TABLE run_principals (
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    principal TEXT NOT NULL,
    PRIMARY KEY (run_id, principal)
);
CREATE TABLE room_guard (
    run_id TEXT PRIMARY KEY REFERENCES runs(id) ON DELETE CASCADE,
    baseline TEXT NOT NULL
);
CREATE TABLE container_leases (
    project TEXT NOT NULL,
    node TEXT NOT NULL DEFAULT '',
    action TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (project, node)
);
CREATE TABLE IF NOT EXISTS "review_findings" (
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
, followup TEXT);
CREATE INDEX idx_review_findings_stage ON review_findings(stage_id);
CREATE INDEX idx_review_findings_severity ON review_findings(severity);
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
);
CREATE TABLE room_messages (
    id TEXT PRIMARY KEY,
    room_id TEXT NOT NULL,
    author TEXT NOT NULL,
    body TEXT NOT NULL,
    reply_to TEXT REFERENCES room_messages(id),
    created_at TEXT NOT NULL,
    rev TEXT NOT NULL,
    base_rev TEXT,
    question INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS "run_delivered_messages" (
    run_id TEXT NOT NULL REFERENCES runs(id) ON DELETE CASCADE,
    message_id TEXT NOT NULL REFERENCES room_messages(id) ON DELETE CASCADE,
    PRIMARY KEY (run_id, message_id)
);
CREATE INDEX idx_room_messages_page ON room_messages(room_id, id DESC);
CREATE TABLE IF NOT EXISTS "specs" (
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
    base_rev TEXT,
    room_id TEXT
, body_hash TEXT, plan_hash TEXT, archived_at TEXT, cancelled_at TEXT);
CREATE INDEX idx_specs_project ON specs(project);
CREATE TABLE sync_health (
    peer TEXT PRIMARY KEY,
    last_attempt_at TEXT,
    last_success_at TEXT,
    consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    last_error_kind TEXT CHECK (last_error_kind IN ('unreachable', 'refused', 'protocol', 'store')),
    last_error TEXT,
    last_report TEXT,
    state TEXT NOT NULL CHECK (state IN ('in_sync', 'syncing', 'stale')),
    stale_since TEXT
);
CREATE TABLE change_heads (
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    seq INTEGER NOT NULL, project TEXT,
    PRIMARY KEY (entity_type, entity_id)
);
CREATE INDEX idx_change_heads_seq ON change_heads(entity_type, seq);
CREATE TABLE IF NOT EXISTS "sync_state" (
    peer TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    checkpoint INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL, read_seq INTEGER, read_id TEXT, read_rev TEXT,
    PRIMARY KEY (peer, entity_type)
);
CREATE TABLE chunks (
    hash TEXT PRIMARY KEY,
    size INTEGER NOT NULL,
    bytes BLOB NOT NULL
);
CREATE TABLE blobs (
    hash TEXT PRIMARY KEY,
    size INTEGER NOT NULL,
    chunks TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX idx_change_log_file_version ON change_log (
    entity_id, json_extract(snapshot, '$.content_hash'), json_extract(snapshot, '$.mode')
) WHERE entity_type = 'file'
;
CREATE TRIGGER change_log_kind_from_deleted AFTER INSERT ON change_log
WHEN NEW.deleted = 1 AND NEW.kind = 'revision'
BEGIN
    UPDATE change_log SET kind = 'tombstone' WHERE seq = NEW.seq;
END;
CREATE TABLE erase_requests (
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    actor TEXT,
    requested_at TEXT NOT NULL,
    PRIMARY KEY (entity_type, entity_id)
);
CREATE INDEX idx_change_log_tombstones ON change_log(entity_type) WHERE kind = 'tombstone';
CREATE INDEX idx_room_messages_reply ON room_messages(reply_to);
CREATE INDEX idx_rooms_project ON rooms(project);
CREATE INDEX idx_specs_room ON specs(room_id);
CREATE INDEX idx_run_delivered_message ON run_delivered_messages(message_id);
CREATE TABLE fde_boxes (
    handle TEXT PRIMARY KEY,
    box_id TEXT NOT NULL,
    claimed_at TEXT NOT NULL
);
CREATE TABLE sync_offers (
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    offered TEXT,
    offered_from TEXT,
    PRIMARY KEY (entity_type, entity_id)
);
CREATE TABLE main_limits (file_max INTEGER NOT NULL);
CREATE TABLE materialized_files (
    id TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    mode INTEGER NOT NULL,
    undecided INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (id, content_hash, mode)
);
CREATE TABLE IF NOT EXISTS "runs" (
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
        CHECK (role IN ('build', 'adhoc', 'review', 'fix', 'room', 'room-full',
            'invite', 'invite-full')),
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
    last_activity_at TEXT,
    room_id TEXT,
    review_id TEXT,
    stop_source TEXT
);
CREATE INDEX idx_runs_project ON runs(project);
CREATE INDEX idx_runs_spec ON runs(spec_id);
CREATE INDEX idx_runs_room ON runs(room_id);
CREATE INDEX idx_runs_review ON runs(review_id);
CREATE TABLE fde_pairings (
    fde_id TEXT PRIMARY KEY REFERENCES fdes(id) ON DELETE CASCADE,
    public_key TEXT NOT NULL,
    token_name TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE project_grants (
    fde_id TEXT NOT NULL REFERENCES fdes(id) ON DELETE CASCADE,
    project TEXT NOT NULL,
    granted_at TEXT NOT NULL,
    PRIMARY KEY (fde_id, project)
);
CREATE TABLE sync_scope (
    kind TEXT NOT NULL CHECK (kind IN ('all', 'granted')),
    project TEXT
);
CREATE TRIGGER specs_status_since_insert AFTER INSERT ON specs
WHEN NEW.status IN ('archived', 'cancelled')
BEGIN
    UPDATE specs SET
        archived_at = CASE WHEN NEW.status = 'archived'
            THEN COALESCE(NEW.updated_at, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')) END,
        cancelled_at = CASE WHEN NEW.status = 'cancelled'
            THEN COALESCE(NEW.updated_at, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')) END
    WHERE id = NEW.id;
END;
CREATE TRIGGER specs_status_since_update AFTER UPDATE OF status ON specs
WHEN NEW.status IS NOT OLD.status
BEGIN
    UPDATE specs SET
        archived_at = CASE WHEN NEW.status = 'archived'
            THEN COALESCE(NEW.updated_at, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')) END,
        cancelled_at = CASE WHEN NEW.status = 'cancelled'
            THEN COALESCE(NEW.updated_at, strftime('%Y-%m-%dT%H:%M:%fZ', 'now')) END
    WHERE id = NEW.id;
END;
CREATE TABLE catalog(id,main);
CREATE TABLE compacted_entries (
    seq INTEGER PRIMARY KEY,
    entity_type TEXT NOT NULL,
    entity_id TEXT NOT NULL,
    rev TEXT NOT NULL
);
CREATE INDEX idx_compacted_entries_entity ON compacted_entries(entity_type, entity_id);
