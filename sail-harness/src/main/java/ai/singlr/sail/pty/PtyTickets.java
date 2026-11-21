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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The tickets a host issued: each a record that the host decided its action on its target for the
 * credential that asked, never a credential of its own, and naming that credential only by its
 * hash. A ticket is kept only as its own hash, found by comparing every kept hash in constant time,
 * admits one connection, and must be presented within {@link PtyMessage#TICKET_LIFE}. At most
 * {@code cap} tickets not yet presented are kept, and at most {@link #PER_HOLDER} for any one
 * credential, so one caller cannot keep the rest from a ticket. A spent or expired ticket is
 * remembered apart, for its life again and within its own bound, only so its presenter hears which
 * it was.
 */
final class PtyTickets {

  private static final long LIFE = PtyMessage.TICKET_LIFE.toNanos();

  /**
   * What a presented ticket admits: {@code action} on {@code target}, in {@code workspace} of it
   * for an open (blank for the project's own container), for the credential whose hash is {@code
   * holder}.
   */
  record Ticket(PtyMessage.TicketAction action, String target, String holder, String workspace)
      implements PtySessionHost.Bound {

    Ticket {
      workspace = Objects.toString(workspace, "");
    }

    /** A ticket to the project's own container, or to attach to a session. */
    Ticket(PtyMessage.TicketAction action, String target, String holder) {
      this(action, target, holder, "");
    }

    @Override
    public String toString() {
      return "Ticket[action=" + action + ", target=" + target + "]";
    }

    /**
     * Why {@code message} lies outside this ticket, on a connection that opened {@code opened} and
     * holds the write token of {@code writing} (null for none), or null when it does not: an open
     * ticket creates one session in its project and attaches to that session; an attach ticket
     * attaches to its session; either then writes, resizes, takes the write token, uploads into the
     * session it attached and detaches, ends its own session while it holds that session's write
     * token, and does nothing else. A files ticket lists, stats, reads, watches, saves (its bytes
     * sent as an upload's are), makes, moves and removes the files of its workspace, says what
     * changed in it since {@code HEAD} and one file's change, and does nothing else.
     */
    @Override
    public PtyMessage.Err refusal(PtyMessage message, String opened, String writing) {
      if (action == PtyMessage.TicketAction.FILES) {
        return message instanceof PtyMessage.FilesList
                || message instanceof PtyMessage.FilesStat
                || message instanceof PtyMessage.FilesRead
                || message instanceof PtyMessage.FilesCancel
                || message instanceof PtyMessage.FilesWatch
                || message instanceof PtyMessage.FilesWrite
                || message instanceof PtyMessage.FilesCreate
                || message instanceof PtyMessage.FilesMove
                || message instanceof PtyMessage.FilesRemove
                || message instanceof PtyMessage.FilesChanges
                || message instanceof PtyMessage.FilesDiff
                || message instanceof PtyMessage.PutChunk
                || message instanceof PtyMessage.PutEnd
                || message instanceof PtyMessage.PutCancel
            ? null
            : new PtyMessage.Err(
                "This connection's ticket is to read and change the files of workspace '"
                    + workspace
                    + "' of project '"
                    + target
                    + "', and it admits nothing else; ask the API for a ticket for that.");
      }
      var opens = action == PtyMessage.TicketAction.OPEN;
      var own = opens ? opened : target;
      if (message instanceof PtyMessage.Kill m
          && m.session().equals(own)
          && !m.session().equals(writing)) {
        return new PtyMessage.Err(NOT_THE_WRITER);
      }
      var covered =
          switch (message) {
            case PtyMessage.Create m ->
                opens
                    && opened == null
                    && target.equals(m.project())
                    && workspace.equals(m.workspace());
            case PtyMessage.Attach m -> m.session().equals(own);
            case PtyMessage.Kill m -> m.session().equals(own) && m.session().equals(writing);
            case PtyMessage.Input m -> true;
            case PtyMessage.Resize m -> true;
            case PtyMessage.TakeWrite m -> true;
            case PtyMessage.Detach m -> true;
            case PtyMessage.PutBegin m -> true;
            case PtyMessage.PutChunk m -> true;
            case PtyMessage.PutEnd m -> true;
            case PtyMessage.PutCancel m -> true;
            default -> false;
          };
      return covered
          ? null
          : new PtyMessage.Err(
              "This connection's ticket is to "
                  + (opens
                      ? "open a session in "
                          + (workspace.isBlank()
                              ? "project '" + target + "'"
                              : "workspace '" + workspace + "' of project '" + target + "'")
                      : "attach to session '" + target + "'")
                  + ", and it admits nothing else; ask the API for a ticket for that.");
    }
  }

  /**
   * Why a connection's Kill of its own session is refused: it does not hold the session's write
   * token, which another connection may, or none once the session ended.
   */
  static final String NOT_THE_WRITER =
      "This connection does not hold the terminal's keyboard, so it was not ended; someone else"
          + " may hold it.";

  private record Issued(byte[] hash, Ticket ticket, long issuedAt) {}

  private record Spent(byte[] hash, long issuedAt, boolean used) {}

  /** How many tickets not yet presented one credential may hold. */
  static final int PER_HOLDER = 8;

  private final int cap;
  private final List<Issued> outstanding = new ArrayList<>();
  private final ArrayDeque<Spent> spent = new ArrayDeque<>();

  PtyTickets(int cap) {
    this.cap = cap;
  }

  /**
   * A new ticket for {@code ticket}, issued at {@code now}; refused when the host holds {@code cap}
   * tickets not yet presented, or the ticket's credential holds {@link #PER_HOLDER}.
   */
  synchronized String issue(Ticket ticket, long now) throws IOException {
    expire(now);
    if (outstanding.size() >= cap) {
      throw new IOException(
          "The host holds "
              + cap
              + " tickets not yet presented; ask again once some have been presented or expired.");
    }
    if (outstanding.stream().filter(held -> held.ticket().holder().equals(ticket.holder())).count()
        >= PER_HOLDER) {
      throw new IOException(
          "This credential holds "
              + PER_HOLDER
              + " tickets not yet presented; present one or let it expire, then ask again.");
    }
    var value = Secrets.mint("tkt");
    outstanding.add(new Issued(hash(value), ticket, now));
    return value;
  }

  /**
   * What {@code presented} admits at {@code now}, spending it; refused, saying why, when it is
   * unknown, used or expired.
   */
  synchronized Ticket redeem(String presented, long now) throws IOException {
    expire(now);
    var wanted = hash(presented);
    Issued found = null;
    for (var held : outstanding) {
      if (MessageDigest.isEqual(held.hash(), wanted)) {
        found = held;
      }
    }
    Spent gone = null;
    for (var forgotten : spent) {
      if (MessageDigest.isEqual(forgotten.hash(), wanted)) {
        gone = forgotten;
      }
    }
    if (found != null) {
      outstanding.remove(found);
      remember(new Spent(found.hash(), found.issuedAt(), true));
      return found.ticket();
    }
    if (gone == null) {
      throw new IOException("This is not a ticket this host issued; ask the API for a new one.");
    }
    if (gone.used()) {
      throw new IOException(
          "This ticket was presented already, and a ticket admits one connection; ask the API for"
              + " a new one.");
    }
    throw new IOException(
        "This ticket expired: a ticket is presented within "
            + PtyMessage.TICKET_LIFE.toSeconds()
            + " s of being issued; ask the API for a new one.");
  }

  /** Moves every ticket past its life to the spent, and forgets the spent past twice it. */
  private void expire(long now) {
    for (var held : List.copyOf(outstanding)) {
      if (now - held.issuedAt() > LIFE) {
        outstanding.remove(held);
        remember(new Spent(held.hash(), held.issuedAt(), false));
      }
    }
    spent.removeIf(forgotten -> now - forgotten.issuedAt() > 2 * LIFE);
  }

  private void remember(Spent ticket) {
    spent.addLast(ticket);
    while (spent.size() > 4 * cap) {
      spent.removeFirst();
    }
  }

  private static byte[] hash(String value) {
    return Hashes.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
