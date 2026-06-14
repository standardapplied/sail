/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.webauthn.Hashes;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Login sessions for authenticated FDEs (distinct from {@code agent_sessions}). A session token is
 * returned in plaintext exactly once at creation and stored only as its SHA-256 hash, like API
 * tokens. {@link #validate} rejects (and prunes) expired sessions, so an expired token cannot be
 * used even before a sweep.
 */
public final class AuthSessionStore {

  private final Sqlite db;

  public AuthSessionStore(Sqlite db) {
    this.db = db;
  }

  /** A newly minted session; {@code token} is shown once and never stored in the clear. */
  public record CreatedSession(String token, String fdeId, String expiresAt) {}

  public record SessionInfo(String fdeId, String createdAt, String expiresAt) {}

  public CreatedSession create(String fdeId, Duration ttl) {
    var token = generateToken();
    var now = DateTimeUtils.now();
    var expiresAt = now.plus(ttl).toString();
    db.execute(
        "INSERT INTO sessions (token_hash, fde_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
        sha256(token),
        fdeId,
        now.toString(),
        expiresAt);
    return new CreatedSession(token, fdeId, expiresAt);
  }

  /** Resolves a live session, or empty if unknown or expired. Expired rows are pruned on lookup. */
  public Optional<SessionInfo> validate(String token) {
    var hash = sha256(token);
    var result =
        db.queryOne(
            "SELECT fde_id, created_at, expires_at FROM sessions WHERE token_hash = ?",
            row -> new SessionInfo(row.text(0), row.text(1), row.text(2)),
            hash);
    if (result.isEmpty()) {
      return Optional.empty();
    }
    if (Instant.parse(result.get().expiresAt()).isBefore(DateTimeUtils.now())) {
      db.execute("DELETE FROM sessions WHERE token_hash = ?", hash);
      return Optional.empty();
    }
    db.execute(
        "UPDATE sessions SET last_used_at = ? WHERE token_hash = ?",
        DateTimeUtils.now().toString(),
        hash);
    return result;
  }

  public boolean revoke(String token) {
    db.execute("DELETE FROM sessions WHERE token_hash = ?", sha256(token));
    return db.changes() > 0;
  }

  public int revokeForFde(String fdeId) {
    db.execute("DELETE FROM sessions WHERE fde_id = ?", fdeId);
    return db.changes();
  }

  private static String generateToken() {
    var bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return "sess_" + HexFormat.of().formatHex(bytes);
  }

  private static String sha256(String input) {
    return HexFormat.of().formatHex(Hashes.sha256(input.getBytes(StandardCharsets.UTF_8)));
  }
}
