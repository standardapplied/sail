/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A database as Sail 0.58.0 left it: its schema, as main's database held it on 2026-11-02, stamped
 * {@link LegacyConversion#VERSION}, and rows written the way 0.58 wrote them — times as ISO-8601
 * text, project definitions as YAML, every synced row with its journal entry — for the conversion
 * tests to convert.
 */
public final class LegacyDatabase implements AutoCloseable {

  public static final String AT = "2026-10-01T09:30:15.123456789Z";
  public static final String LATER = "2026-10-02T10:00:00Z";

  /** A box with no unit of a run still running and no other process holding the database. */
  public static final LegacyConversion.Host QUIET =
      new LegacyConversion.Host() {
        @Override
        public boolean running(String project, String unit) {
          return false;
        }

        @Override
        public void requireNoOtherHolder() {}
      };

  public final Sqlite db;

  private LegacyDatabase(Sqlite db) {
    this.db = db;
  }

  /** A 0.58 database at {@code file}, holding its schema and its catalog id {@code catalog}. */
  public static LegacyDatabase create(Path file, String catalog) {
    var db = Sqlite.open(file);
    statements().forEach(db::execute);
    db.execute(
        "INSERT INTO schema_version (version, applied_at) VALUES (1, '2026-05-28 05:29:11')");
    db.execute(
        "INSERT INTO schema_version (version, applied_at) VALUES (?, '2026-10-27 16:14:34')",
        LegacyConversion.VERSION);
    db.execute("INSERT INTO catalog (id, main) VALUES (?, NULL)", catalog);
    return new LegacyDatabase(db);
  }

  private static List<String> statements() {
    try (var schema = LegacyDatabase.class.getResourceAsStream("legacy-0.58-schema.sql")) {
      var text = new String(Objects.requireNonNull(schema).readAllBytes(), StandardCharsets.UTF_8);
      var statements = new ArrayList<String>();
      var statement = new StringBuilder();
      for (var line : text.split("\n")) {
        statement.append(line).append('\n');
        var trigger = statement.toString().startsWith("CREATE TRIGGER");
        if (line.endsWith(";") && (!trigger || line.strip().equals("END;"))) {
          statements.add(statement.toString());
          statement.setLength(0);
        }
      }
      return statements;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Records that this database syncs with the main whose catalog id is {@code main}. */
  public void syncsWith(String main) {
    db.execute("UPDATE catalog SET main = ?", main);
  }

  /** {@code content} as a one-chunk blob, as 0.58 stored one; its hash. */
  public String blob(byte[] content) {
    var hash = BlobStore.hash(new ByteArrayInputStream(content));
    var chunks = content.length == 0 ? "[]" : "[\"" + hash + "\"]";
    db.execute(
        "INSERT OR IGNORE INTO blobs (hash, size, chunks, created_at) VALUES (?, ?, ?,"
            + " '2026-09-22 18:38:05')",
        hash,
        content.length,
        chunks);
    if (content.length > 0) {
      db.execute(
          "INSERT OR IGNORE INTO chunks (hash, size, bytes) VALUES (?, ?, ?)",
          hash,
          content.length,
          content);
    }
    return hash;
  }

  public String blob(String text) {
    return blob(text.getBytes(StandardCharsets.UTF_8));
  }

  /** Appends a journal entry of {@code kind} for an entity and makes it the entity's head. */
  public void journal(String type, String id, String rev, String actor, String kind) {
    db.execute(
        """
        INSERT INTO change_log (entity_type, entity_id, rev, actor, recorded_at, origin, deleted,
            snapshot, kind)
        VALUES (?, ?, ?, ?, ?, 'local', ?, '{}', ?)""",
        type,
        id,
        rev,
        actor,
        AT,
        "revision".equals(kind) ? 0 : 1,
        kind);
    db.execute(
        """
        INSERT INTO change_heads (entity_type, entity_id, seq) VALUES (?, ?, last_insert_rowid())
        ON CONFLICT (entity_type, entity_id) DO UPDATE SET seq = excluded.seq""",
        type,
        id);
  }

  public void project(String name, String yaml, String createdBy, String author) {
    db.execute(
        """
        INSERT INTO projects (name, definition, created_by, created_at, updated_by, updated_at,
            rev)
        VALUES (?, ?, ?, ?, ?, ?, ?)""",
        name,
        yaml,
        createdBy,
        AT,
        author,
        LATER,
        "3-" + name);
    journal("project", name, "3-" + name, author, "revision");
  }

  /** A spec in {@code status} with its room, body and plan, depending on {@code dependsOn}. */
  public void spec(String id, String status, String project, List<String> dependsOn) {
    var body = "# " + id + "\n\nThe body of " + id + ".";
    db.execute(
        """
        INSERT INTO specs (id, title, status, assignee, agent, model, reasoning_effort, branch,
            priority, created_by, created_at, updated_at, project, updated_by, rev, room_id,
            body_hash, plan_hash)
        VALUES (?, ?, ?, 'uday', 'claude-code', 'claude-opus-5-5', 'high', ?, 2, 'uday', ?, ?, ?,
            'mady', ?, ?, ?, ?)""",
        id,
        "Title of " + id,
        status,
        "agent/" + id,
        AT,
        LATER,
        project,
        "5-" + id,
        id,
        blob(body),
        blob(""));
    db.execute(
        "INSERT INTO spec_content (spec_id, body, plan, updated_at) VALUES (?, ?, '', ?)",
        id,
        body,
        LATER);
    db.execute("INSERT INTO spec_repos (spec_id, repo) VALUES (?, 'sail')", id);
    for (var dependency : dependsOn) {
      db.execute(
          "INSERT INTO spec_dependencies (spec_id, depends_on) VALUES (?, ?)", id, dependency);
    }
    journal("spec", id, "5-" + id, "uday", "revision");
    room(id, project);
  }

  public void room(String id, String project) {
    db.execute(
        """
        INSERT INTO rooms (id, project, title, assignee, wake, roster, created_by, created_at,
            updated_at, updated_by, rev)
        VALUES (?, ?, ?, 'uday', 'mention', NULL, 'uday', ?, ?, 'mady', ?)""",
        id,
        project,
        "Room of " + id,
        AT,
        LATER,
        "2-" + id);
    journal("room", id, "2-" + id, "uday", "revision");
  }

  public void message(String id, String roomId, String replyTo, boolean question) {
    db.execute(
        """
        INSERT INTO room_messages (id, room_id, author, body, reply_to, created_at, rev, question)
        VALUES (?, ?, 'rajesh', ?, ?, ?, ?, ?)""",
        id,
        roomId,
        "Message " + id,
        replyTo,
        AT,
        "1-" + id,
        question ? 1 : 0);
    journal("message", id, "1-" + id, "rajesh", "revision");
  }

  public void file(String project, String path, byte[] content) {
    var id = project + "/" + path;
    db.execute(
        """
        INSERT INTO project_files (id, project, path, rev, updated_at, content_hash, size, mode,
            kind)
        VALUES (?, ?, ?, ?, ?, ?, ?, 493, 'text')""",
        id,
        project,
        path,
        "4-" + id,
        LATER,
        blob(content),
        content.length);
    db.execute(
        "INSERT INTO materialized_files (id, content_hash, mode) VALUES (?, ?, 493)",
        id,
        BlobStore.hash(new ByteArrayInputStream(content)));
    journal("file", id, "4-" + id, null, "revision");
  }

  public void run(String id, String project, String specId, String status, String node) {
    db.execute(
        """
        INSERT INTO runs (id, project, spec_id, agent, status, started_at, node, rev)
        VALUES (?, ?, ?, 'claude-code', ?, ?, ?, ?)""",
        id,
        project,
        specId,
        status,
        AT,
        node,
        "1-" + id);
    journal("run", id, "1-" + id, node, "revision");
  }

  public void review(String id, String specId) {
    db.execute(
        "INSERT INTO reviews (id, spec_id, status, created_at, rev) VALUES (?, ?, 'passed', ?, ?)",
        id,
        specId,
        AT,
        "1-" + id);
    db.execute(
        """
        INSERT INTO review_stages (id, review_id, name, stage_type, status, started_at,
            completed_at, finding_counts)
        VALUES (?, ?, 'codeandsecurity', 'agent', 'passed', ?, ?, '{}')""",
        id + "-stage",
        id,
        AT,
        LATER);
    journal("review", id, "1-" + id, "uday", "revision");
  }

  public void event(String type, String specId) {
    db.execute(
        "INSERT INTO events (timestamp, type, project, spec_id, host, data) VALUES (?, ?, 'sail',"
            + " ?, 'main', '{}')",
        AT,
        type,
        specId);
  }

  /** An FDE with an SSH key, a passkey, a web session, an API token and a Mast pairing. */
  public void fde(String id, String handle) {
    db.execute(
        "INSERT INTO fdes (id, handle, display_name, created_at, role) VALUES (?, ?, ?, ?, 'admin')",
        id,
        handle,
        "FDE " + handle,
        AT);
    db.execute(
        "INSERT INTO fde_ssh_keys (fingerprint, fde_id, public_key, created_at) VALUES (?, ?,"
            + " 'ssh-ed25519 AAAA', ?)",
        "SHA256:" + handle,
        id,
        AT);
    db.execute(
        """
        INSERT INTO webauthn_credentials (credential_id, fde_id, public_key_cose, cose_algorithm,
            created_at, last_used_at)
        VALUES (?, ?, 'cose', -7, ?, ?)""",
        "cred-" + handle,
        id,
        AT,
        LATER);
    db.execute(
        "INSERT INTO sessions (token_hash, fde_id, created_at, expires_at, last_used_at) VALUES"
            + " (?, ?, ?, '2026-12-01T00:00:00Z', ?)",
        "session-" + handle,
        id,
        AT,
        LATER);
    db.execute(
        "INSERT INTO api_tokens (token_hash, name, role, fde_id, created_at) VALUES (?, ?,"
            + " 'admin', ?, ?)",
        "token-" + handle,
        "token of " + handle,
        id,
        AT);
    db.execute(
        "INSERT INTO fde_pairings (fde_id, public_key, token_name, created_at) VALUES (?,"
            + " 'ssh-ed25519 BBBB', ?, ?)",
        id,
        "token of " + handle,
        AT);
    db.execute(
        "INSERT INTO fde_boxes (handle, box_id, claimed_at) VALUES (?, 'box-of-' || ?, ?)",
        handle,
        handle,
        AT);
  }

  public void slackThread(String project, String specId) {
    db.execute(
        "INSERT INTO slack_threads (project, spec_id, channel, thread_ts, created_at) VALUES (?, ?,"
            + " 'C0BELHHT26A', '1730000000.000100', '2026-09-22 18:38:05')",
        project,
        specId);
  }

  public void boxCredential(String handle) {
    db.execute(
        "INSERT INTO box_credential (id, handle, credential_hash, created_at) VALUES (1, ?, ?, ?)",
        handle,
        "credential-" + handle,
        AT);
  }

  @Override
  public void close() {
    db.close();
  }
}
