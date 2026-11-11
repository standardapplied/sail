/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.auth;

import ai.singlr.sail.store.EnrollmentTicketStore;
import ai.singlr.sail.store.FdeStore;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Default {@link EnrollmentTickets} over the {@link EnrollmentTicketStore}, resolving FDE handles
 * to the surrogate ids the store records. Tickets live for {@link #TTL} — long enough to complete a
 * browser enrollment, short enough to limit exposure of an unused ticket. A ticket is neither
 * minted nor honoured for a person the roster disables ({@link ActivePerson}).
 */
public final class EnrollmentService implements EnrollmentTickets {

  static final Duration TTL = Duration.ofMinutes(15);

  private final EnrollmentTicketStore tickets;
  private final FdeStore fdes;

  public EnrollmentService(EnrollmentTicketStore tickets, FdeStore fdes) {
    this.tickets = Objects.requireNonNull(tickets, "tickets");
    this.fdes = Objects.requireNonNull(fdes, "fdes");
  }

  @Override
  public Ticket issue(String fdeHandle) {
    var fde = ActivePerson.named(fdes, fdeHandle);
    var created = tickets.issue(fde.id(), TTL);
    return new Ticket(created.ticket(), fde.handle(), created.expiresAt());
  }

  @Override
  public Optional<String> authorize(String ticket) {
    return tickets
        .validate(ticket)
        .map(enrolls -> ActivePerson.named(fdes, enrolls.fdeHandle()).handle());
  }

  @Override
  public boolean consume(String ticket) {
    return tickets.consume(ticket);
  }
}
