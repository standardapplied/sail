/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Holds the challenge issued at the start of a WebAuthn ceremony until the matching finish call. A
 * challenge is single-use: {@link #consume} deletes the row whenever it is found — valid, expired,
 * or for the wrong ceremony — so a challenge can never be replayed and a stale handle cannot be
 * probed. Expired challenges are also rejected (and pruned) on lookup, so timeout is enforced even
 * before a sweep. The opaque handle returned by {@link #issue} is what the client echoes back; the
 * challenge bytes themselves never leave the server except inside the ceremony options.
 */
public final class PendingChallengeStore {

  private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder B64URL_DEC = Base64.getUrlDecoder();

  private final Sqlite db;

  public PendingChallengeStore(Sqlite db) {
    this.db = db;
  }

  /** A retained ceremony challenge; {@code fdeId} is the target FDE for registration, else null. */
  public record PendingChallenge(String id, byte[] challenge, String ceremony, String fdeId) {}

  /**
   * Issues a challenge for {@code ceremony}, returning the opaque handle the client echoes back.
   */
  public String issue(String ceremony, byte[] challenge, String fdeId, Duration ttl) {
    var id = generateId();
    var now = DateTimeUtils.now();
    db.execute(
        "INSERT INTO webauthn_challenges (id, challenge, ceremony, fde_id, created_at, expires_at)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        id,
        B64URL.encodeToString(challenge),
        ceremony,
        fdeId,
        now.toString(),
        now.plus(ttl).toString());
    return id;
  }

  /**
   * Resolves and removes a live challenge for {@code id}. Returns empty when the handle is unknown,
   * has expired, or was issued for a different ceremony; in every found case the row is deleted.
   */
  public Optional<PendingChallenge> consume(String id, String ceremony) {
    var row =
        db.queryOne(
            "SELECT id, challenge, ceremony, fde_id, expires_at FROM webauthn_challenges"
                + " WHERE id = ?",
            r ->
                new Resolved(
                    new PendingChallenge(
                        r.text(0), B64URL_DEC.decode(r.text(1)), r.text(2), r.text(3)),
                    r.text(4)),
            id);
    if (row.isEmpty()) {
      return Optional.empty();
    }
    db.execute("DELETE FROM webauthn_challenges WHERE id = ?", id);
    var resolved = row.get();
    if (!ceremony.equals(resolved.challenge().ceremony())
        || Instant.parse(resolved.expiresAt()).isBefore(DateTimeUtils.now())) {
      return Optional.empty();
    }
    return Optional.of(resolved.challenge());
  }

  private record Resolved(PendingChallenge challenge, String expiresAt) {}

  private static String generateId() {
    var bytes = new byte[16];
    new SecureRandom().nextBytes(bytes);
    return "wac_" + HexFormat.of().formatHex(bytes);
  }
}
