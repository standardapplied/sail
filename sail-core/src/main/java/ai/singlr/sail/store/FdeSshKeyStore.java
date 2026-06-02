/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.ssh.SshPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The SSH keys an FDE authenticates the terminal with — the registry that maps an inbound SSH key,
 * by its OpenSSH {@code SHA256:} fingerprint, to the principal acting on the CLI. The full {@code
 * authorized_keys} line is retained so the host can render the {@code sail} user's authorized_keys
 * from this table; the fingerprint is the stable identity used for lookup and revocation. Deleting
 * a row revokes the key everywhere.
 */
public final class FdeSshKeyStore {

  private static final String SELECT =
      "SELECT k.fingerprint, k.fde_id, f.handle, k.public_key, k.comment, k.created_at"
          + " FROM fde_ssh_keys k JOIN fdes f ON f.id = k.fde_id";

  private final Sqlite db;

  public FdeSshKeyStore(Sqlite db) {
    this.db = db;
  }

  public record SshKeyInfo(
      String fingerprint,
      String fdeId,
      String fdeHandle,
      String publicKey,
      String comment,
      String createdAt) {}

  /** Registers {@code key} to {@code fdeId}. Throws if the fingerprint is already registered. */
  public void add(String fdeId, SshPublicKey key) {
    db.execute(
        "INSERT INTO fde_ssh_keys (fingerprint, fde_id, public_key, comment, created_at)"
            + " VALUES (?, ?, ?, ?, ?)",
        key.fingerprint(),
        fdeId,
        key.line(),
        key.comment(),
        Instant.now().toString());
  }

  public Optional<SshKeyInfo> findByFingerprint(String fingerprint) {
    return db.queryOne(SELECT + " WHERE k.fingerprint = ?", FdeSshKeyStore::map, fingerprint);
  }

  public List<SshKeyInfo> list() {
    return db.query(SELECT + " ORDER BY f.handle, k.created_at", FdeSshKeyStore::map);
  }

  public List<SshKeyInfo> listForFde(String fdeId) {
    return db.query(
        SELECT + " WHERE k.fde_id = ? ORDER BY k.created_at", FdeSshKeyStore::map, fdeId);
  }

  public boolean remove(String fingerprint) {
    db.execute("DELETE FROM fde_ssh_keys WHERE fingerprint = ?", fingerprint);
    return db.changes() > 0;
  }

  private static SshKeyInfo map(Sqlite.Row row) {
    return new SshKeyInfo(
        row.text(0), row.text(1), row.text(2), row.text(3), row.text(4), row.text(5));
  }
}
