/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.commands.SessionClient;
import ai.singlr.sail.common.Secrets;
import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtySessionHost;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workspace's terminal sessions listed through the API's door from a real terminal host whose
 * sessions run inside a real Incus container: each person lists their own, newest first, an admin
 * every one, and a workspace with none lists nothing; a session its workspace's stop ended is
 * listed ended, in the stop's words. The host is asked for each caller's own credential, as the API
 * asks it in production, and keeps what that caller could attach to.
 */
class WorkspaceSessionsIT extends AbstractIncusIT {

  @TempDir Path dir;

  @Test
  void eachPersonListsTheirOwnSessionsAndAnAdminEveryOneEndedOnesInTheStopsWords()
      throws Exception {
    ensureIncusOrSkip();
    var container = "sail-it-pty-list";
    var ana = new Actor("ana", Role.MEMBER, Actor.Lane.API);
    var ben = new Actor("ben", Role.MEMBER, Actor.Lane.API);
    var admin = new Actor("uday", Role.ADMIN, Actor.Lane.API);
    var people = Map.of("ana", ana, "ben", ben, "uday", admin);
    var socket = dir.resolve("h.sock");
    try {
      launch(container);
      var prepared =
          exec(
              container,
              List.of(
                  "bash", "-c", "mkdir -p /home/dev/workspace && chown -R 1000:1000 /home/dev"));
      assertTrue(prepared.ok(), "could not prepare the dev workspace: " + prepared.stderr());
      try (var host =
          new PtySessionHost(
              socket,
              dir.resolve("s"),
              64 * 1024,
              token -> new PtyIdentity.Person(token.isEmpty() ? admin : people.get(token)),
              new PtyAdmission() {
                @Override
                public void box(PtyIdentity.Person who) {}

                @Override
                public void project(String project, PtyIdentity.Person who) {}

                @Override
                public void room(String room, String project, PtyIdentity.Person who) {}
              },
              PtyEvents.NONE,
              "0.0.0-test",
              PtySessionHost.Handoff.NONE,
              PtySessionHost.Network.on(
                  new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                  hash ->
                      people.keySet().stream()
                          .filter(token -> Secrets.hash(token).equals(hash))
                          .findFirst()
                          .map(token -> new PtyIdentity.Person(people.get(token)))
                          .orElseThrow(() -> new IOException("The credential is not valid."))))) {
        host.start();
        try (var asAna = SessionClient.connect(socket, "ana");
            var asBen = SessionClient.connect(socket, "ben")) {
          asBen.create("app-ben", List.of("sleep", "600"), "/tmp", container, "", 80, 24);
          asAna.create("app-ana", List.of("sleep", "600"), "/tmp", container, "", 80, 24);
          asAna.create("app-ana2", List.of("sleep", "600"), "/tmp", container, "", 80, 24);
        }
        var door =
            new TerminalTickets(
                new TerminalTickets.Host() {
                  @Override
                  public PtyMessage ticket(
                      String credential,
                      PtyMessage.TicketAction action,
                      String target,
                      String workspace) {
                    return new PtyMessage.TicketRefused(
                        PtyMessage.TicketRefusal.UNAVAILABLE, "no tickets here");
                  }

                  @Override
                  public PtyMessage sessions(String credential) throws IOException {
                    return SessionClient.sessionsFor(socket, credential);
                  }
                },
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 7072));

        assertEquals(
            List.of("app-ana2", "app-ana"), names(door.sessions("ana", container, "main", null)));
        assertEquals(List.of("app-ben"), names(door.sessions("ben", container, "main", null)));
        assertEquals(
            List.of("app-ana2", "app-ana", "app-ben"),
            names(door.sessions("uday", container, "main", null)));
        assertEquals(List.of(), names(door.sessions("ana", container, "dock", null)));
        assertEquals(
            List.of(List.of("sleep", "600"), List.of("sleep", "600")),
            door.sessions("ana", container, "main", null).sessions().stream()
                .map(WorkspaceSessionsResponse.Session::command)
                .toList());

        var stopped =
            "Workspace main of "
                + container
                + " is being stopped by uday, which ends this terminal.";
        try (var dispatch =
            SessionClient.connect(
                socket, Files.readString(PtySessionHost.dispatchCredentialOf(socket)).strip())) {
          dispatch.yieldSession("app-ana", stopped);
        }
        var listed = door.sessions("ana", container, "main", null).sessions();
        assertEquals(
            List.of("app-ana2 live", "app-ana " + stopped),
            listed.stream()
                .map(s -> s.name() + " " + (s.live() ? "live" : s.endedReason()))
                .toList());
      }
    } finally {
      deleteContainerQuietly(container);
    }
  }

  private static List<String> names(WorkspaceSessionsResponse listed) {
    return listed.sessions().stream().map(WorkspaceSessionsResponse.Session::name).toList();
  }
}
