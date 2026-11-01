/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The one live Mast pairing an FDE has on this box: the public key the box let into its operator's
 * login and the name of the token it minted, which is what revoking the pairing needs. The private
 * key and the token went out once in the connect code and are stored nowhere. Box-local like {@code
 * fde_ssh_keys} and {@code api_tokens}, and removed with its FDE.
 */
public final class FdePairingStore {

  private final Sqlite db;

  public FdePairingStore(Sqlite db) {
    this.db = db;
  }

  public record Pairing(String fdeId, String publicKey, String tokenName, Instant createdAt) {}

  /** Records {@code fdeId}'s pairing. Throws if it already has one: a pairing is revoked first. */
  public void put(String fdeId, String publicKey, String tokenName) {
    db.execute(
        "INSERT INTO fde_pairings (fde_id, public_key, token_name, created_at)"
            + " VALUES (?, ?, ?, ?)",
        fdeId,
        publicKey,
        tokenName,
        DateTimeUtils.now());
  }

  public Optional<Pairing> find(String fdeId) {
    return db.queryOne(
        "SELECT fde_id, public_key, token_name, created_at FROM fde_pairings WHERE fde_id = ?",
        row -> new Pairing(row.text(0), row.text(1), row.text(2), row.instant(3)),
        fdeId);
  }

  /** The handles of the FDEs paired on this box, in order. */
  public List<String> handles() {
    return db.query(
        "SELECT f.handle FROM fde_pairings p JOIN fdes f ON f.id = p.fde_id ORDER BY f.handle",
        row -> row.text(0));
  }

  public boolean remove(String fdeId) {
    db.execute("DELETE FROM fde_pairings WHERE fde_id = ?", fdeId);
    return db.changes() > 0;
  }
}
