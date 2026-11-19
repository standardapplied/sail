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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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

  /**
   * A workspace's sessions are the host's listing for the caller's own credential, which the host
   * decides as it decides that caller's attach: kept to that project and workspace ({@code main}
   * the project's own container), to sessions made since the workspace's instance was (none of an
   * earlier one of its name), newest first (by name among equals). The host's refusal of a
   * credential is said in its words and status, a host closing is the terminal's outage, and so is
   * a host it cannot reach.
   */
  @Test
  void aWorkspacesSessionsAreTheHostsListingForTheCallersOwnCredential() {
    var asked = new ArrayList<String>();
    var door =
        new TerminalTickets(
            new TerminalTickets.Host() {
              @Override
              public PtyMessage ticket(
                  String credential,
                  PtyMessage.TicketAction action,
                  String target,
                  String workspace) {
                return new PtyMessage.Ticket("tkt");
              }

              @Override
              public PtyMessage sessions(String credential) {
                asked.add(credential);
                return switch (credential) {
                  case "machine" ->
                      new PtyMessage.TicketRefused(
                          PtyMessage.TicketRefusal.FORBIDDEN, "This token acts for no one.");
                  case "closing" -> new PtyMessage.Err("The pty host is shutting down.");
                  case "odd" -> new PtyMessage.Ok();
                  default ->
                      new PtyMessage.Sessions(
                          List.of(
                              info("app-a", "acme", "try", false, "ana", 2_000),
                              info("app-b", "acme", "try", true, "ana", 3_000),
                              info("app-c", "acme", "", true, "ana", 1_000),
                              info("app-d", "other", "try", true, "ana", 1_000),
                              info("app-f", "acme", "try", true, "ana", 3_000),
                              info("app-old", "acme", "try", false, "ana", 500),
                              info("app-early", "acme", "try", true, "ana", 400)),
                          "");
                };
              }
            },
            TERMINAL);
    var since = Instant.ofEpochMilli(1_000);

    assertEquals(
        List.of("app-b", "app-f", "app-a", "app-early"),
        names(door.sessions("tok-ana", "acme", "try", since)),
        "an ended session older than the workspace is an earlier one's; a live one is this one's,"
            + " however the wall clock stepped");
    assertEquals(List.of("tok-ana"), asked);
    assertEquals(
        Arrays.asList(null, null, "exited(0)", null),
        door.sessions("tok-ana", "acme", "try", since).sessions().stream()
            .map(WorkspaceSessionsResponse.Session::endedReason)
            .toList());
    assertEquals(
        Instant.ofEpochMilli(2_000),
        door.sessions("tok-ana", "acme", "try", since).sessions().get(2).createdAt());
    assertEquals(List.of("app-c"), names(door.sessions("tok-ana", "acme", "main", since)));
    assertEquals(
        List.of("app-b", "app-f", "app-a", "app-old", "app-early"),
        names(door.sessions("tok-ana", "acme", "try", null)),
        "a workspace whose start is not known keeps every session of its name");
    assertEquals(
        "403 forbidden This token acts for no one.",
        said(
            assertThrows(
                ApiException.class, () -> door.sessions("machine", "acme", "try", since))));
    assertEquals(
        "503 terminal_unavailable The pty host is shutting down.",
        said(
            assertThrows(
                ApiException.class, () -> door.sessions("closing", "acme", "try", since))));
    assertEquals(
        "503 terminal_unavailable The terminal host listed no sessions.",
        said(assertThrows(ApiException.class, () -> door.sessions("odd", "acme", "try", since))));
    assertEquals(
        "503 terminal_unavailable This terminal host lists no sessions here.",
        said(
            assertThrows(
                ApiException.class,
                () ->
                    new TerminalTickets(
                            (credential, action, target, workspace) -> new PtyMessage.Ticket("t"),
                            TERMINAL)
                        .sessions("tok-ana", "acme", "try", since))));
    assertThrows(
        IllegalArgumentException.class, () -> door.sessions("tok-ana", "acme", "Bad Name", since));
  }

  private static List<String> names(WorkspaceSessionsResponse listed) {
    return listed.sessions().stream().map(s -> s.name()).toList();
  }

  private static PtyMessage.SessionInfo info(
      String name, String project, String workspace, boolean live, String opener, long created) {
    return new PtyMessage.SessionInfo(name, "i-" + name, live, 0, "", "", List.of())
        .placed(project, workspace, opener)
        .lived(created, live ? "" : "exited(0)");
  }
}
