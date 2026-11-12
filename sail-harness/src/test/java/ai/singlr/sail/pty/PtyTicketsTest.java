/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A ticket admits one connection, briefly, for one thing: it is found only by the value issued,
 * spent by its first presentation, refused once older than its life, forgotten after twice that,
 * and never more are kept than the cap; what it admits is its action on its target alone.
 */
class PtyTicketsTest {

  private static final long LIFE = PtyMessage.TICKET_LIFE.toNanos();
  private static final PtyTickets.Ticket OPEN_ACME =
      new PtyTickets.Ticket(PtyMessage.TicketAction.OPEN, "acme", "tok-uday");
  private static final PtyTickets.Ticket ATTACH_LOUNGE =
      new PtyTickets.Ticket(PtyMessage.TicketAction.ATTACH, "lounge", "tok-uday");

  @Test
  void aTicketAdmitsWhatItWasIssuedForOnceAndThenIsUsed() throws IOException {
    var tickets = new PtyTickets(4);
    var value = tickets.issue(OPEN_ACME, 0);

    assertEquals(OPEN_ACME, tickets.redeem(value, LIFE));
    assertEquals(
        "This ticket was presented already, and a ticket admits one connection; ask the API for"
            + " a new one.",
        assertThrows(IOException.class, () -> tickets.redeem(value, LIFE)).getMessage());
  }

  @Test
  void aTicketPresentedPastItsLifeIsExpiredAndPastTwiceItIsForgotten() throws IOException {
    var tickets = new PtyTickets(4);
    var value = tickets.issue(OPEN_ACME, 0);

    assertEquals(
        "This ticket expired: a ticket is presented within 30 s of being issued; ask the API for"
            + " a new one.",
        assertThrows(IOException.class, () -> tickets.redeem(value, LIFE + 1)).getMessage());
    assertEquals(
        "This is not a ticket this host issued; ask the API for a new one.",
        assertThrows(IOException.class, () -> tickets.redeem(value, 2 * LIFE + 1)).getMessage());
  }

  @Test
  void onlyTheValueIssuedIsFoundAndEveryValueIsNew() throws IOException {
    var tickets = new PtyTickets(4);
    var first = tickets.issue(OPEN_ACME, 0);
    var second = tickets.issue(ATTACH_LOUNGE, 0);

    assertNotEquals(first, second);
    assertEquals(
        "This is not a ticket this host issued; ask the API for a new one.",
        assertThrows(IOException.class, () -> tickets.redeem(first + "0", 0)).getMessage());
    assertEquals(ATTACH_LOUNGE, tickets.redeem(second, 0));
    assertEquals(OPEN_ACME, tickets.redeem(first, 0));
  }

  @Test
  void noMoreThanTheCapAreKeptUntilOneIsForgotten() throws IOException {
    var tickets = new PtyTickets(2);
    tickets.issue(OPEN_ACME, 0);
    tickets.issue(OPEN_ACME, 0);

    assertEquals(
        "The host holds 2 tickets not yet forgotten; ask again once one has been presented or"
            + " expired.",
        assertThrows(IOException.class, () -> tickets.issue(OPEN_ACME, 2 * LIFE)).getMessage());
    tickets.issue(OPEN_ACME, 2 * LIFE + 1);
  }

  @Test
  void anOpenTicketCreatesOneSessionInItsProjectAndAttachesOnlyToIt() {
    var create = new PtyMessage.Create("lounge", List.of(), "/tmp", "acme", "", 80, 24);
    var elsewhere = new PtyMessage.Create("lounge", List.of(), "/tmp", "beta", "", 80, 24);
    var refusal =
        new PtyMessage.Err(
            "This connection's ticket is to open a session in project 'acme', and it admits"
                + " nothing else; ask the API for a ticket for that.");

    assertNull(OPEN_ACME.refusal(create, null));
    assertEquals(refusal, OPEN_ACME.refusal(create, "lounge"));
    assertEquals(refusal, OPEN_ACME.refusal(elsewhere, null));
    assertEquals(refusal, OPEN_ACME.refusal(new PtyMessage.Attach("lounge", true), null));
    assertNull(OPEN_ACME.refusal(new PtyMessage.Attach("lounge", true), "lounge"));
    assertEquals(refusal, OPEN_ACME.refusal(new PtyMessage.Attach("study", true), "lounge"));
  }

  @Test
  void anAttachTicketAttachesToItsSessionAndThenOnlyDrivesIt() {
    var refusal =
        new PtyMessage.Err(
            "This connection's ticket is to attach to session 'lounge', and it admits nothing"
                + " else; ask the API for a ticket for that.");

    assertNull(ATTACH_LOUNGE.refusal(new PtyMessage.Attach("lounge", false), null));
    assertEquals(refusal, ATTACH_LOUNGE.refusal(new PtyMessage.Attach("study", false), null));
    assertEquals(
        refusal,
        ATTACH_LOUNGE.refusal(
            new PtyMessage.Create("lounge", List.of(), "/tmp", "acme", "", 80, 24), null));
    for (var driving :
        List.of(
            new PtyMessage.Input(1, new byte[] {'a'}),
            new PtyMessage.Resize(80, 24),
            new PtyMessage.TakeWrite(),
            new PtyMessage.Detach())) {
      assertNull(ATTACH_LOUNGE.refusal(driving, null));
    }
    for (var other :
        List.<PtyMessage>of(
            new PtyMessage.ListSessions("", 16),
            new PtyMessage.Kill("lounge"),
            new PtyMessage.Yield("lounge", "x"),
            new PtyMessage.IssueTicket(PtyMessage.TicketAction.ATTACH, "lounge"),
            new PtyMessage.Hello("tok-uday"))) {
      assertEquals(refusal, ATTACH_LOUNGE.refusal(other, null));
    }
  }
}
