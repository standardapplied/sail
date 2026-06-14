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
 * One-time tickets that authorize a single passkey enrollment for one FDE. A browser running {@code
 * navigator.credentials.create()} cannot carry an operator's API token, so an admin (or the host
 * operator) mints a short-lived ticket and hands it to the enrollment page; the page presents it to
 * the register endpoints in place of an admin credential. Like API tokens and sessions, the ticket
 * is returned in plaintext once and stored only as its SHA-256 hash. It is {@link #validate live}
 * until used or expired, and {@link #consume consumed} exactly once on a successful registration.
 */
public final class EnrollmentTicketStore {

  private final Sqlite db;

  public EnrollmentTicketStore(Sqlite db) {
    this.db = db;
  }

  /** A freshly minted ticket; {@code ticket} is shown once and never stored in the clear. */
  public record CreatedTicket(String ticket, String fdeId, String expiresAt) {}

  /** A live ticket resolved to the FDE it enrolls. */
  public record TicketInfo(String fdeId, String fdeHandle, String expiresAt) {}

  public CreatedTicket issue(String fdeId, Duration ttl) {
    var ticket = generateTicket();
    var now = DateTimeUtils.now();
    var expiresAt = now.plus(ttl).toString();
    db.execute(
        "INSERT INTO enrollment_tickets (token_hash, fde_id, created_at, expires_at)"
            + " VALUES (?, ?, ?, ?)",
        sha256(ticket),
        fdeId,
        now.toString(),
        expiresAt);
    return new CreatedTicket(ticket, fdeId, expiresAt);
  }

  /** Resolves a live (unconsumed, unexpired) ticket to its FDE, pruning it if expired. */
  public Optional<TicketInfo> validate(String ticket) {
    var hash = sha256(ticket);
    var info =
        db.queryOne(
            "SELECT t.fde_id, f.handle, t.expires_at FROM enrollment_tickets t"
                + " JOIN fdes f ON f.id = t.fde_id"
                + " WHERE t.token_hash = ? AND t.consumed_at IS NULL",
            row -> new TicketInfo(row.text(0), row.text(1), row.text(2)),
            hash);
    if (info.isEmpty()) {
      return Optional.empty();
    }
    if (Instant.parse(info.get().expiresAt()).isBefore(DateTimeUtils.now())) {
      db.execute("DELETE FROM enrollment_tickets WHERE token_hash = ?", hash);
      return Optional.empty();
    }
    return info;
  }

  /**
   * Atomically marks a ticket used and reports whether THIS call was the one that consumed it. The
   * {@code UPDATE ... WHERE consumed_at IS NULL RETURNING} runs as a single statement, so two
   * concurrent callers cannot both observe the ticket as live — exactly one gets the returned row.
   * Callers must treat {@code true} as the authorization to proceed (consume before acting), not
   * consume after a separate liveness check.
   */
  public boolean consume(String ticket) {
    return db.queryOne(
            "UPDATE enrollment_tickets SET consumed_at = ?"
                + " WHERE token_hash = ? AND consumed_at IS NULL"
                + " RETURNING token_hash",
            row -> row.text(0),
            DateTimeUtils.now().toString(),
            sha256(ticket))
        .isPresent();
  }

  private static String generateTicket() {
    var bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return "enr_" + HexFormat.of().formatHex(bytes);
  }

  private static String sha256(String input) {
    return HexFormat.of().formatHex(Hashes.sha256(input.getBytes(StandardCharsets.UTF_8)));
  }
}
