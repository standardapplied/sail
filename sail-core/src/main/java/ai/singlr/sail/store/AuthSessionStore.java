/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Secrets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Login sessions for authenticated FDEs (distinct from {@code agent_sessions}). A session token is
 * returned in plaintext exactly once at creation and stored only as its SHA-256 hash, like API
 * tokens. {@link #validate} rejects (and prunes) expired sessions, so an expired token cannot be
 * used even before a sweep.
 */
public final class AuthSessionStore {

  private static final Base64.Encoder ID = Base64.getUrlEncoder().withoutPadding();

  private final Sqlite db;

  public AuthSessionStore(Sqlite db) {
    this.db = db;
  }

  /** A newly minted session; {@code token} is shown once and never stored in the clear. */
  public record CreatedSession(String token, String fdeId, Instant expiresAt) {}

  public record SessionInfo(String fdeId, Instant createdAt, Instant expiresAt) {}

  /** A session for {@code fdeId} that no passkey minted: a gateway's, or a test's. */
  public CreatedSession create(String fdeId, Duration ttl) {
    return insert(fdeId, null, ttl);
  }

  /**
   * A session for {@code fdeId} signed in with the passkey {@code credentialId}, which removing
   * that passkey ends ({@link #revokeMintedBy}); empty when the passkey is gone. The passkey is
   * looked up in the write transaction that inserts, so a removal that lands after a sign-in read
   * its passkey and before this mints none.
   */
  public Optional<CreatedSession> createSignedInWith(
      String fdeId, byte[] credentialId, Duration ttl) {
    var passkey = ID.encodeToString(credentialId);
    return db.transaction(
        () ->
            db.queryOne(
                    "SELECT 1 FROM webauthn_credentials WHERE credential_id = ?",
                    row -> row.integer(0),
                    passkey)
                .map(held -> insert(fdeId, passkey, ttl)));
  }

  private CreatedSession insert(String fdeId, String passkey, Duration ttl) {
    var token = generateToken();
    var now = DateTimeUtils.now();
    var expiresAt = now.plus(ttl);
    db.execute(
        "INSERT INTO sessions (token_hash, fde_id, credential_id, created_at, expires_at)"
            + " VALUES (?, ?, ?, ?, ?)",
        sha256(token),
        fdeId,
        passkey,
        now,
        expiresAt);
    return new CreatedSession(token, fdeId, expiresAt);
  }

  /**
   * The passkey the session of {@code token} was signed in with, its credential id as {@code
   * Passkeys.encodeId} writes it; empty for none or no session.
   */
  public Optional<String> passkeyOf(String token) {
    return db.queryOne(
            "SELECT credential_id FROM sessions WHERE token_hash = ?",
            row -> Optional.ofNullable(row.text(0)),
            sha256(token))
        .flatMap(found -> found);
  }

  /** Ends every session the passkey {@code credentialId} was signed in with; how many it ended. */
  public int revokeMintedBy(byte[] credentialId) {
    db.execute("DELETE FROM sessions WHERE credential_id = ?", ID.encodeToString(credentialId));
    return db.changes();
  }

  /** Resolves a live session, or empty if unknown or expired. Expired rows are pruned on lookup. */
  public Optional<SessionInfo> validate(String token) {
    return validateHash(sha256(token));
  }

  /**
   * As {@link #validate}, for the session whose token's hash is {@code hash}: the terminal host
   * keeps only a ticket's credential hash, and resolves it by that.
   */
  public Optional<SessionInfo> validateHash(String hash) {
    var result =
        db.queryOne(
            "SELECT fde_id, created_at, expires_at FROM sessions WHERE token_hash = ?",
            row -> new SessionInfo(row.text(0), row.instant(1), row.instant(2)),
            hash);
    if (result.isEmpty()) {
      return Optional.empty();
    }
    if (result.get().expiresAt().isBefore(DateTimeUtils.now())) {
      db.execute("DELETE FROM sessions WHERE token_hash = ?", hash);
      return Optional.empty();
    }
    db.execute(
        "UPDATE sessions SET last_used_at = ? WHERE token_hash = ?", DateTimeUtils.now(), hash);
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
    return Secrets.mint("sess");
  }

  private static String sha256(String input) {
    return Secrets.hash(input);
  }
}
