/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Secrets;
import ai.singlr.sail.common.Strings;
import java.util.Objects;
import java.util.Optional;

/**
 * The box's single ambient credential: the FDE identity every project container on this devbox
 * carries for sessions sail did not launch (an engineer's SSH shell, an IDE-spawned agent). One
 * row, hashed at rest; {@link #replace} mints a fresh plaintext and atomically retires the previous
 * one, so rotation is a single call and the old secret dies with it. Resolution is the inverse:
 * plaintext in, owning FDE handle out, empty for anything unknown.
 */
public final class BoxCredentialStore {

  private final Sqlite db;

  public BoxCredentialStore(Sqlite db) {
    this.db = Objects.requireNonNull(db, "db");
  }

  /** Mints and installs a new credential for {@code handle}, returning the plaintext once. */
  public String replace(String handle) {
    if (Strings.isBlank(handle)) {
      throw new IllegalArgumentException("box credential handle is required");
    }
    var credential = Secrets.mint("sailbox");
    db.execute(
        """
        INSERT INTO box_credential (id, handle, credential_hash, created_at)
        VALUES (1, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
            handle = excluded.handle,
            credential_hash = excluded.credential_hash,
            created_at = excluded.created_at""",
        handle.strip(),
        TokenStore.sha256(credential),
        DateTimeUtils.now().toString());
    return credential;
  }

  /** Resolves a plaintext credential to the box FDE handle, or empty when it does not match. */
  public Optional<String> resolve(String credential) {
    if (Strings.isBlank(credential)) {
      return Optional.empty();
    }
    return db.queryOne(
        "SELECT handle FROM box_credential WHERE credential_hash = ?",
        row -> row.text(0),
        TokenStore.sha256(credential));
  }
}
