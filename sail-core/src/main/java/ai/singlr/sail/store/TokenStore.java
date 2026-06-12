/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * API token management. Tokens are SHA-256 hashed before storage. The plaintext is returned exactly
 * once at creation time.
 */
public final class TokenStore {

  private static final java.util.Set<String> ROLES = java.util.Set.of("admin", "member", "viewer");

  private final Sqlite db;

  public TokenStore(Sqlite db) {
    this.db = db;
  }

  public record TokenInfo(
      String name, String role, String createdAt, String lastUsedAt, String fdeHandle) {}

  public record CreatedToken(String name, String token, String role) {}

  public CreatedToken create(String name, String role) {
    return create(name, role, null);
  }

  /** Creates a token optionally owned by an FDE ({@code fdes.id}, or null for an unowned token). */
  public CreatedToken create(String name, String role, String fdeId) {
    if (!ROLES.contains(role)) {
      throw new IllegalArgumentException(
          "Invalid role: " + role + ". Must be one of " + ROLES + ".");
    }
    var token = generateToken();
    var hash = sha256(token);
    db.execute(
        "INSERT INTO api_tokens (token_hash, name, role, fde_id, created_at) VALUES (?, ?, ?, ?, ?)",
        hash,
        name,
        role,
        fdeId,
        Instant.now().toString());
    return new CreatedToken(name, token, role);
  }

  public Optional<TokenInfo> validate(String token) {
    var hash = sha256(token);
    var result = db.queryOne(SELECT + " WHERE t.token_hash = ?", TokenStore::map, hash);
    result.ifPresent(
        info ->
            db.execute(
                "UPDATE api_tokens SET last_used_at = ? WHERE token_hash = ?",
                Instant.now().toString(),
                hash));
    return result;
  }

  public List<TokenInfo> list() {
    return db.query(SELECT + " ORDER BY t.created_at", TokenStore::map);
  }

  private static final String SELECT =
      "SELECT t.name, t.role, t.created_at, t.last_used_at, f.handle"
          + " FROM api_tokens t LEFT JOIN fdes f ON t.fde_id = f.id";

  private static TokenInfo map(Sqlite.Row row) {
    return new TokenInfo(row.text(0), row.text(1), row.text(2), row.text(3), row.text(4));
  }

  public boolean revoke(String name) {
    db.execute("DELETE FROM api_tokens WHERE name = ?", name);
    return db.changes() > 0;
  }

  private static String generateToken() {
    var bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return "sail_" + HexFormat.of().formatHex(bytes);
  }

  static String sha256(String input) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }
}
