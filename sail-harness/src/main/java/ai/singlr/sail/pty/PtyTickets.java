/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.common.Secrets;
import ai.singlr.sail.webauthn.Hashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * The tickets a host issued: each a record that the host decided its action on its target for the
 * credential that asked, never a credential of its own. A ticket is kept only as its hash, found by
 * comparing every kept hash in constant time, admits one connection, and must be presented within
 * {@link PtyMessage#TICKET_LIFE}. A used or expired ticket is remembered for as long again, so its
 * presenter hears which it was; after that it is forgotten like one never issued. At most {@code
 * cap} are kept at once, so no caller makes the host hold more.
 */
final class PtyTickets {

  private static final long LIFE = PtyMessage.TICKET_LIFE.toNanos();

  /**
   * What a presented ticket admits: {@code action} on {@code target}, as {@code token}'s holder.
   */
  record Ticket(PtyMessage.TicketAction action, String target, String token) {

    /**
     * Why {@code message} lies outside this ticket, on a connection that opened {@code opened}
     * (null for none), or null when it does not: an open ticket creates one session in its project
     * and attaches to that session; an attach ticket attaches to its session; either then writes,
     * resizes, takes the write token and detaches, and does nothing else.
     */
    PtyMessage.Err refusal(PtyMessage message, String opened) {
      var opens = action == PtyMessage.TicketAction.OPEN;
      var covered =
          switch (message) {
            case PtyMessage.Create m -> opens && opened == null && target.equals(m.project());
            case PtyMessage.Attach m -> m.session().equals(opens ? opened : target);
            case PtyMessage.Input m -> true;
            case PtyMessage.Resize m -> true;
            case PtyMessage.TakeWrite m -> true;
            case PtyMessage.Detach m -> true;
            default -> false;
          };
      return covered
          ? null
          : new PtyMessage.Err(
              "This connection's ticket is to "
                  + (opens
                      ? "open a session in project '" + target + "'"
                      : "attach to session '" + target + "'")
                  + ", and it admits nothing else; ask the API for a ticket for that.");
    }
  }

  private record Issued(byte[] hash, Ticket ticket, long issuedAt, boolean used) {}

  private final int cap;
  private final List<Issued> issued = new ArrayList<>();

  PtyTickets(int cap) {
    this.cap = cap;
  }

  /** A new ticket for {@code ticket}, issued at {@code now}; refused when {@code cap} are kept. */
  synchronized String issue(Ticket ticket, long now) throws IOException {
    forget(now);
    if (issued.size() >= cap) {
      throw new IOException(
          "The host holds "
              + cap
              + " tickets not yet forgotten; ask again once one has been presented or expired.");
    }
    var value = Secrets.mint("tkt");
    issued.add(new Issued(hash(value), ticket, now, false));
    return value;
  }

  /**
   * What {@code presented} admits at {@code now}, spending it; refused, saying why, when it is
   * unknown, used or expired.
   */
  synchronized Ticket redeem(String presented, long now) throws IOException {
    forget(now);
    var wanted = hash(presented);
    var index = -1;
    for (var i = 0; i < issued.size(); i++) {
      if (MessageDigest.isEqual(issued.get(i).hash(), wanted)) {
        index = i;
      }
    }
    if (index < 0) {
      throw new IOException("This is not a ticket this host issued; ask the API for a new one.");
    }
    var found = issued.get(index);
    if (found.used()) {
      throw new IOException(
          "This ticket was presented already, and a ticket admits one connection; ask the API for"
              + " a new one.");
    }
    if (now - found.issuedAt() > LIFE) {
      throw new IOException(
          "This ticket expired: a ticket is presented within "
              + PtyMessage.TICKET_LIFE.toSeconds()
              + " s of being issued; ask the API for a new one.");
    }
    issued.set(index, new Issued(found.hash(), found.ticket(), found.issuedAt(), true));
    return found.ticket();
  }

  private void forget(long now) {
    issued.removeIf(entry -> now - entry.issuedAt() > 2 * LIFE);
  }

  private static byte[] hash(String value) {
    return Hashes.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
