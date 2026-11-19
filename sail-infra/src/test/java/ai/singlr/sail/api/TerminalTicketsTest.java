/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyMessage;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
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
   * A workspace's sessions are the host's listing kept to that project and workspace ({@code main}
   * the project's own container), newest first (by name among equals), and to the sessions the
   * caller may attach to by the host's own rule: their own, every one for an admin. A host it
   * cannot reach is the terminal's outage.
   */
  @Test
  void aWorkspacesSessionsAreThoseOfItTheCallerMayAttachTo() {
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
              public List<PtyMessage.SessionInfo> sessions() {
                return List.of(
                    info("app-a", "acme", "try", false, "ana", 2_000),
                    info("app-b", "acme", "try", true, "ana", 3_000),
                    info("app-c", "acme", "", true, "ana", 1_000),
                    info("app-d", "other", "try", true, "ana", 1_000),
                    info("app-e", "acme", "try", true, "ben", 1_000),
                    info("app-f", "acme", "try", true, "ana", 3_000));
              }
            },
            TERMINAL);
    var ana = new Actor("ana", Role.MEMBER, Actor.Lane.API);
    var admin = new Actor("uday", Role.ADMIN, Actor.Lane.API);

    assertEquals(List.of("app-b", "app-f", "app-a"), names(door.sessions(ana, "acme", "try")));
    assertEquals(
        Arrays.asList(null, null, "exited(0)"),
        door.sessions(ana, "acme", "try").sessions().stream()
            .map(WorkspaceSessionsResponse.Session::endedReason)
            .toList());
    assertEquals(
        Instant.ofEpochMilli(2_000),
        door.sessions(ana, "acme", "try").sessions().getLast().createdAt());
    assertEquals(List.of("app-c"), names(door.sessions(ana, "acme", "main")));
    assertEquals(
        List.of("app-b", "app-f", "app-a", "app-e"), names(door.sessions(admin, "acme", "try")));
    assertEquals(
        List.of("app-e"),
        names(door.sessions(new Actor("ben", Role.MEMBER, Actor.Lane.API), "acme", "try")));
    assertEquals(
        List.of(),
        names(door.sessions(new Actor("cy", Role.MEMBER, Actor.Lane.API), "acme", "try")));
    assertEquals(
        "503 terminal_unavailable This terminal host lists no sessions here.",
        said(
            assertThrows(
                ApiException.class,
                () ->
                    new TerminalTickets(
                            (credential, action, target, workspace) -> new PtyMessage.Ticket("t"),
                            TERMINAL)
                        .sessions(ana, "acme", "try"))));
    assertThrows(IllegalArgumentException.class, () -> door.sessions(ana, "acme", "Bad Name"));
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
