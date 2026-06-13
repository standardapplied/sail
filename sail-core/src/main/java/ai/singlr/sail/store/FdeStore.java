/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.ssh.SshPublicKey;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Forward Deployed Engineers — the human principals that own API tokens and are attributed on the
 * specs, reviews, and events they act on. The handle is the stable, human-facing identity (used as
 * a spec assignee); the surrogate id is the foreign key tokens and other records point at.
 */
public final class FdeStore {

  private final Sqlite db;

  public FdeStore(Sqlite db) {
    this.db = db;
  }

  public static final String DEFAULT_ROLE = "member";
  private static final Set<String> ROLES = Set.of("admin", "member", "viewer");
  private static final Set<String> STATUSES = Set.of("active", "disabled");

  public record Fde(
      String id,
      String handle,
      String displayName,
      String email,
      String role,
      String status,
      String createdAt) {}

  /** Creates an FDE with the default {@code member} role. */
  public Fde add(String handle, String displayName, String email) {
    return add(handle, displayName, email, DEFAULT_ROLE);
  }

  /**
   * Creates an FDE with a generated id and {@code active} status. Throws if the handle is taken or
   * the role is not one of {@code admin}, {@code member}, {@code viewer}.
   */
  public Fde add(String handle, String displayName, String email, String role) {
    ai.singlr.sail.engine.NameValidator.requireValidFdeHandle(handle);
    if (!ROLES.contains(role)) {
      throw new IllegalArgumentException(
          "Invalid role: " + role + ". Must be one of " + ROLES + ".");
    }
    var fde =
        new Fde(generateId(), handle, displayName, email, role, "active", Instant.now().toString());
    db.execute(
        "INSERT INTO fdes (id, handle, display_name, email, role, status, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        fde.id(),
        fde.handle(),
        fde.displayName(),
        fde.email(),
        fde.role(),
        fde.status(),
        fde.createdAt());
    return fde;
  }

  /**
   * Mirrors an identity from the main devbox into this box's roster, keyed by the stable {@code
   * handle}: a new handle is inserted, an existing one has its display name, email, role, and
   * status updated while its local surrogate id — and the tokens, keys, and sessions that reference
   * it — are preserved. This is the one-way, main-authoritative pull; nodes never push identities
   * back. Revocation propagates as a {@code disabled} status (which the gateway already refuses),
   * never as a destructive delete. Role and status are validated, so a malformed roster entry is
   * rejected rather than written with a bad authorization.
   */
  public void replicate(
      String handle,
      String displayName,
      String email,
      String role,
      String status,
      String createdAt) {
    ai.singlr.sail.engine.NameValidator.requireValidFdeHandle(handle);
    if (!ROLES.contains(role)) {
      throw new IllegalArgumentException(
          "Invalid role: " + role + ". Must be one of " + ROLES + ".");
    }
    if (!STATUSES.contains(status)) {
      throw new IllegalArgumentException(
          "Invalid status: " + status + ". Must be one of " + STATUSES + ".");
    }
    db.execute(
        "INSERT INTO fdes (id, handle, display_name, email, role, status, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)"
            + " ON CONFLICT(handle) DO UPDATE SET display_name = excluded.display_name,"
            + " email = excluded.email, role = excluded.role, status = excluded.status",
        generateId(),
        handle,
        displayName,
        email,
        role,
        status,
        createdAt == null || createdAt.isBlank() ? Instant.now().toString() : createdAt);
  }

  public Optional<Fde> byHandle(String handle) {
    return db.queryOne(SELECT + " WHERE handle = ?", FdeStore::map, handle);
  }

  public Optional<Fde> byId(String id) {
    return db.queryOne(SELECT + " WHERE id = ?", FdeStore::map, id);
  }

  public List<Fde> list() {
    return db.query(SELECT + " ORDER BY handle", FdeStore::map);
  }

  /** Returns the number of FDEs that are active admins — the principals who can administer. */
  public long activeAdminCount() {
    return db.queryOne(
            "SELECT COUNT(*) FROM fdes WHERE role = 'admin' AND status = 'active'",
            row -> row.integer(0))
        .orElse(0L);
  }

  /**
   * Creates an FDE and registers its SSH key in one transaction, so a rejected key (already
   * registered to someone else) rolls the FDE back instead of leaving a half-onboarded principal.
   */
  public Fde addWithKey(
      String handle, String displayName, String email, String role, SshPublicKey key) {
    return db.transaction(
        () -> {
          var fde = add(handle, displayName, email, role);
          new FdeSshKeyStore(db).add(fde.id(), key);
          return fde;
        });
  }

  /**
   * Removes an FDE and every credential that authenticates as it. SSH keys, sessions, passkeys, and
   * enrollment tickets cascade via their foreign keys; owned API tokens are deleted explicitly
   * because {@code api_tokens.fde_id} predates the cascade constraints. Spec attribution ({@code
   * created_by}/{@code updated_by}) stores the handle as historical text and is untouched.
   *
   * <p>Refuses to remove the last active admin. The guard runs inside the delete transaction so two
   * concurrent removals cannot both pass it and leave the host with no administrator.
   */
  public void remove(String fdeId) {
    db.transaction(
        () -> {
          var fde =
              byId(fdeId)
                  .orElseThrow(() -> new IllegalArgumentException("No FDE with id " + fdeId + "."));
          if ("admin".equals(fde.role())
              && "active".equals(fde.status())
              && activeAdminCount() <= 1) {
            throw new IllegalStateException(
                "'"
                    + fde.handle()
                    + "' is the last active admin FDE. Add another admin first:"
                    + " sail fde add <handle> --role admin");
          }
          db.execute("DELETE FROM api_tokens WHERE fde_id = ?", fdeId);
          db.execute("DELETE FROM fdes WHERE id = ?", fdeId);
        });
  }

  private static final String SELECT =
      "SELECT id, handle, display_name, email, role, status, created_at FROM fdes";

  private static Fde map(Sqlite.Row row) {
    return new Fde(
        row.text(0), row.text(1), row.text(2), row.text(3), row.text(4), row.text(5), row.text(6));
  }

  private static String generateId() {
    var bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return "fde_" + HexFormat.of().formatHex(bytes);
  }
}
