/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.pty.PtyMessage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
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
              (credential, action, target, workspace) ->
                  new PtyMessage.TicketRefused(kind, "the host says"),
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
            (credential, action, target, workspace) -> {
              throw new IOException("No session host at /run/pty.sock.");
            },
            TERMINAL);
    var astray =
        new TerminalTickets(
            (credential, action, target, workspace) -> new PtyMessage.Ok(), TERMINAL);

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

  /**
   * R16 at the API: an open names its workspace, passed to the host as asked ({@code main} the
   * project's own container); an invalid one is refused before the host is asked.
   */
  @Test
  void anOpenNamesItsWorkspaceAndAnInvalidOneIsRefusedFirst() {
    var asked = new java.util.ArrayList<String>();
    var door =
        new TerminalTickets(
            (credential, action, target, workspace) -> {
              asked.add(target + "/" + workspace);
              return new PtyMessage.Ticket("tkt");
            },
            TERMINAL);

    door.issue("cred", Map.of("action", "open", "project", "acme", "workspace", "try"));
    door.issue("cred", Map.of("action", "open", "project", "acme", "workspace", "main"));
    door.issue("cred", Map.of("action", "open", "project", "acme"));
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                door.issue(
                    "cred", Map.of("action", "open", "project", "acme", "workspace", "Bad Name")));

    assertEquals(List.of("acme/try", "acme/main", "acme/"), asked);
    assertTrue(refused.getMessage().startsWith("Invalid workspace name: 'Bad Name'."));
  }
}
