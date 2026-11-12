/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.pty.PtyMessage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The ticket door keeps the terminal host's words and answers each kind of refusal with its own
 * status; a host it cannot reach, or one that answers no ticket, is the terminal's outage. The
 * end-to-end scenarios are {@code DirectTerminalTest}'s.
 */
class TerminalTicketsTest {

  private static final InetSocketAddress TERMINAL =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 7072);
  private static final Map<String, Object> ATTACH_LOUNGE =
      Map.of("action", "attach", "session", "lounge");

  @Test
  void eachKindOfRefusalIsItsOwnStatusInTheHostsWords() {
    var answered = new LinkedHashMap<String, String>();
    for (var kind : PtyMessage.TicketRefusal.values()) {
      var door =
          new TerminalTickets(
              (credential, action, target) -> new PtyMessage.TicketRefused(kind, "the host says"),
              TERMINAL);
      answered.put(
          kind.wire(),
          said(assertThrows(ApiException.class, () -> door.issue("tok", ATTACH_LOUNGE))));
    }

    assertEquals(
        Map.of(
            "invalid", "422 invalid_request the host says",
            "not_found", "404 not_found the host says",
            "forbidden", "403 forbidden the host says",
            "limited", "429 rate_limited the host says",
            "unavailable", "503 terminal_unavailable the host says"),
        answered);
  }

  @Test
  void aHostThatCannotBeAskedOrAnswersNoTicketIsTheTerminalsOutage() {
    var unreachable =
        new TerminalTickets(
            (credential, action, target) -> {
              throw new IOException("No session host at /run/pty.sock.");
            },
            TERMINAL);
    var astray = new TerminalTickets((credential, action, target) -> new PtyMessage.Ok(), TERMINAL);

    assertEquals(
        "503 terminal_unavailable No session host at /run/pty.sock.",
        said(assertThrows(ApiException.class, () -> unreachable.issue("tok", ATTACH_LOUNGE))));
    assertEquals(
        "503 terminal_unavailable The terminal host answered no ticket.",
        said(assertThrows(ApiException.class, () -> astray.issue("tok", ATTACH_LOUNGE))));
  }

  private static String said(ApiException refused) {
    return refused.status() + " " + refused.error().code() + " " + refused.error().message();
  }
}
