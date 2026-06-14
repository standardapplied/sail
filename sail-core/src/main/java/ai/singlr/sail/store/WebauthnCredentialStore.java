/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.webauthn.RegisteredCredential;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Persists the passkeys (WebAuthn credentials) an FDE has registered. Binary fields — the
 * credential id, the COSE public key, and the AAGUID — are stored base64-encoded since the SQLite
 * layer binds only text and integers. The credential id is the lookup key at assertion time; {@code
 * sign_count} is updated after each successful assertion for clone detection.
 */
public final class WebauthnCredentialStore {

  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder URL_DEC = Base64.getUrlDecoder();
  private static final Base64.Encoder STD = Base64.getEncoder();
  private static final Base64.Decoder STD_DEC = Base64.getDecoder();

  private static final String SELECT =
      "SELECT credential_id, fde_id, public_key_cose, cose_algorithm, sign_count, aaguid,"
          + " backup_eligible, backup_state, label, created_at, last_used_at FROM webauthn_credentials";

  private final Sqlite db;

  public WebauthnCredentialStore(Sqlite db) {
    this.db = db;
  }

  public record Credential(
      byte[] credentialId,
      String fdeId,
      byte[] publicKeyCose,
      long coseAlgorithm,
      long signCount,
      byte[] aaguid,
      boolean backupEligible,
      boolean backupState,
      String label,
      String createdAt,
      String lastUsedAt) {}

  /** Persists a freshly registered credential for {@code fdeId} with an optional friendly label. */
  public void save(RegisteredCredential credential, String fdeId, String label) {
    db.execute(
        "INSERT INTO webauthn_credentials (credential_id, fde_id, public_key_cose, cose_algorithm,"
            + " sign_count, aaguid, backup_eligible, backup_state, label, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        URL.encodeToString(credential.credentialId()),
        fdeId,
        STD.encodeToString(credential.publicKeyCose()),
        credential.coseAlgorithm(),
        credential.signCount(),
        credential.aaguid() == null ? null : STD.encodeToString(credential.aaguid()),
        credential.backupEligible() ? 1 : 0,
        credential.backupState() ? 1 : 0,
        label,
        DateTimeUtils.now().toString());
  }

  public Optional<Credential> findByCredentialId(byte[] credentialId) {
    return db.queryOne(
        SELECT + " WHERE credential_id = ?",
        WebauthnCredentialStore::map,
        URL.encodeToString(credentialId));
  }

  public List<Credential> listForFde(String fdeId) {
    return db.query(
        SELECT + " WHERE fde_id = ? ORDER BY created_at", WebauthnCredentialStore::map, fdeId);
  }

  /** Records a successful assertion: advances the signature counter and stamps last-used. */
  public void recordUse(byte[] credentialId, long signCount) {
    db.execute(
        "UPDATE webauthn_credentials SET sign_count = ?, last_used_at = ? WHERE credential_id = ?",
        signCount,
        DateTimeUtils.now().toString(),
        URL.encodeToString(credentialId));
  }

  private static Credential map(Sqlite.Row row) {
    var aaguid = row.text(5);
    return new Credential(
        URL_DEC.decode(row.text(0)),
        row.text(1),
        STD_DEC.decode(row.text(2)),
        row.integer(3),
        row.integer(4),
        aaguid == null ? null : STD_DEC.decode(aaguid),
        row.integer(6) != 0,
        row.integer(7) != 0,
        row.text(8),
        row.text(9),
        row.text(10));
  }
}
