/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.SessionYield;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyAdmissions;
import ai.singlr.sail.pty.PtyChildShim;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.pty.PtyWire;
import ai.singlr.sail.pty.SdNotify;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PtyHostYieldTest {

  private static final PtyIdentity.Resolver RESOLVER =
      token ->
          switch (token) {
            case "" -> new PtyIdentity.Person(new Actor("box", Role.MEMBER, Actor.Lane.API));
            case "tok-mady" ->
                new PtyIdentity.Person(new Actor("mady", Role.MEMBER, Actor.Lane.API));
            default -> throw new IOException("Session token is not valid or has expired.");
          };

  @TempDir Path dir;

  @Test
  void endsOnlyTheNamedLiveSessionsAndAMissingHostHasNothingToEnd() throws Exception {
    assertDoesNotThrow(
        () ->
            new PtyHostYield(dir.resolve("absent.sock"))
                .end(List.of("resume-1"), new SessionYield.Ending("r", "")),
        "no socket means no host and no sessions");

    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            RESOLVER,
            PtyAdmissions.NONE,
            PtyEvents.NONE)) {
      try (var client = SessionClient.connect(dir.resolve("h.sock"), "")) {
        client.create("resume-1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
        client.create("shell", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      }

      new PtyHostYield(dir.resolve("h.sock"))
          .end(
              List.of("resume-1", "resume-ghost"),
              new SessionYield.Ending(
                  "yielded to dispatch 2", "Run 2 took over its conversation."));

      try (var client = SessionClient.connect(dir.resolve("h.sock"), "")) {
        assertEquals(
            List.of("resume-1 Run 2 took over its conversation.", "shell live"),
            client.list().stream()
                .map(info -> info.name() + " " + (info.live() ? "live" : info.endedReason()))
                .toList(),
            "the named live session ended, the unnamed one lives, the absent one is skipped");
      }
      assertEquals(2, host.sessionCount(), "the ended one is kept until its retention passes");
    }
  }

  /**
   * A resume session opened through the gateway belongs to the FDE who attached, not to the box
   * owner the API would be. The seam speaks as the host's dispatch authority, so the claim ends
   * every displaced session whoever opened it — the box owner's, another FDE's, all in one pass.
   */
  @Test
  void theDispatchAuthorityEndsSessionsWhoeverOpenedThem() throws Exception {
    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            RESOLVER,
            PtyAdmissions.NONE,
            PtyEvents.NONE)) {
      try (var mady = SessionClient.connect(dir.resolve("h.sock"), "tok-mady")) {
        mady.create("resume-1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      }
      try (var box = SessionClient.connect(dir.resolve("h.sock"), "")) {
        box.create("resume-2", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
        assertThrows(
            IOException.class,
            () -> box.yieldSession("resume-1", "r"),
            "an FDE, even the box owner, holds no yield authority");
      }

      var yield = new PtyHostYield(dir.resolve("h.sock"));
      yield.end(List.of("resume-1", "resume-2"), new SessionYield.Ending("r", ""));

      assertEquals(
          List.of(false, false),
          List.of(yield.live("resume-1"), yield.live("resume-2")),
          "both ended, mady's included");
    }
  }

  /**
   * A live member asks whether a terminal holds the conversation it would resume. The answer covers
   * every FDE's sessions, since the terminal may be anyone's, and a box with no host holds none.
   */
  @Test
  void aSessionIsLiveWhoeverOpenedItAndNoHostMeansNoSession() throws Exception {
    assertFalse(new PtyHostYield(dir.resolve("absent.sock")).live("resume-1"));

    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            RESOLVER,
            PtyAdmissions.NONE,
            PtyEvents.NONE)) {
      try (var mady = SessionClient.connect(dir.resolve("h.sock"), "tok-mady")) {
        mady.create("resume-1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      }
      var yield = new PtyHostYield(dir.resolve("h.sock"));

      assertTrue(yield.live("resume-1"), "another FDE's terminal still holds the conversation");
      assertFalse(yield.live("resume-2"));

      yield.end(List.of("resume-1"), new SessionYield.Ending("r", ""));

      assertFalse(yield.live("resume-1"), "a session that ended holds nothing");
    }
  }

  @Test
  void aHostThatMintedNoCredentialIsAnErrorNotASilentSkip() throws Exception {
    try (var host =
        PtyHostCommand.startHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            RESOLVER,
            PtyAdmissions.NONE,
            PtyEvents.NONE)) {
      try (var mady = SessionClient.connect(dir.resolve("h.sock"), "tok-mady")) {
        mady.create("resume-1", List.of("sh", "-c", "read a"), "/tmp", "", "", 80, 24);
      }
      Files.delete(PtySessionHost.dispatchCredentialOf(dir.resolve("h.sock")));

      var failed =
          assertThrows(
              IOException.class,
              () ->
                  new PtyHostYield(dir.resolve("h.sock"))
                      .end(List.of("resume-1"), new SessionYield.Ending("r", "")));
      assertTrue(failed.getMessage().contains("dispatch credential"), failed.getMessage());
      assertEquals(1, host.sessionCount(), "the session it could not end is reported, not hidden");
    }
  }

  /**
   * Review 1's final F-M2, in one frame: what a stop or a forced delete of workspace {@code try} of
   * acme ends is the live sessions in that workspace alone: not one in acme's own container, in
   * acme's other workspace, or in another project's workspace of the same name. A missing host has
   * nothing to end.
   */
  @Test
  void aWorkspacesEndIsTheLiveSessionsInThatWorkspaceAlone() throws Exception {
    assertDoesNotThrow(
        () ->
            new PtyHostYield(dir.resolve("absent.sock"))
                .endIn("acme", "try", new SessionYield.Ending("r", "")));
    var socket = dir.resolve("h.sock");
    try (var host =
        new PtySessionHost(
            socket,
            dir.resolve("s"),
            64 * 1024,
            RESOLVER,
            new PtyAdmission() {
              @Override
              public void box(PtyIdentity.Person who) {}

              @Override
              public void project(String project, PtyIdentity.Person who) {}

              @Override
              public void workspace(String project, String workspace, PtyIdentity.Person who) {}

              @Override
              public void room(String room, String project, PtyIdentity.Person who) {}
            },
            PtyEvents.NONE,
            "0.0.0-test",
            new PtySessionHost.Handoff(
                SdNotify.NONE,
                Map.of(),
                exit ->
                    Stream.concat(
                            PtyChildShim.onClassPath(exit).stream(),
                            Stream.of("sh", "-c", "read a", "--"))
                        .toList()),
            PtySessionHost.Network.NONE)) {
      host.start();
      create(socket, "in-try", "acme", "try");
      create(socket, "in-container", "acme", "");
      create(socket, "in-spare", "acme", "spare");
      create(socket, "beta-try", "beta", "try");

      new PtyHostYield(socket)
          .endIn(
              "acme",
              "try",
              new SessionYield.Ending(
                  "Workspace try of acme is being stopped by ada, which ends this terminal.",
                  "Workspace try of acme was stopped by ada."));

      try (var client = SessionClient.connect(socket, "")) {
        assertEquals(
            List.of(
                "beta-try live",
                "in-container live",
                "in-spare live",
                "in-try Workspace try of acme was stopped by ada."),
            client.list().stream()
                .map(info -> info.name() + " " + (info.live() ? "live" : info.endedReason()))
                .sorted()
                .toList());
      }
    }
  }

  /** Opens session {@code name} in workspace {@code workspace} of {@code project}, as the box. */
  private static void create(Path socket, String name, String project, String workspace)
      throws IOException {
    try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      channel.connect(UnixDomainSocketAddress.of(socket));
      PtyWire.handshake(channel, channel);
      PtyWire.write(channel, new PtyMessage.Hello(""));
      assertInstanceOf(PtyMessage.Welcome.class, PtyWire.read(channel));
      PtyWire.write(
          channel, new PtyMessage.Create(name, List.of(), "/tmp", project, "", 80, 24, workspace));
      assertInstanceOf(PtyMessage.Ok.class, PtyWire.read(channel));
    }
  }
}
