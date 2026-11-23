/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.ClosesWhatItOpens;
import ai.singlr.sail.engine.HostAccess;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyAdmissions;
import ai.singlr.sail.pty.PtyEvents;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtySessionHost;
import ai.singlr.sail.pty.PtyWire;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@EnabledOnOs(OS.LINUX)
@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SessionClientTest {

  @TempDir Path dir;

  @Test
  void verbsRoundTripAndErrorsSurfaceAsExceptions() throws Exception {
    try (var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new PtyIdentity.Person(new Actor("uday", Role.ADMIN, Actor.Lane.API)),
            new PtyAdmission() {
              @Override
              public void box(PtyIdentity.Person who) {}

              @Override
              public void project(String project, PtyIdentity.Person who) {}

              @Override
              public void room(String room, String project, PtyIdentity.Person who) {}
            },
            ai.singlr.sail.pty.PtyEvents.NONE,
            "0.0.0-test")) {
      host.start();
      try (var client = SessionClient.connect(dir.resolve("h.sock"))) {
        client.create("s1", List.of("sh", "-c", "read a"), "/tmp", "", "lounge", 80, 24);
        var listed = client.list();
        assertEquals(1, listed.size());
        assertEquals("s1", listed.getFirst().name());
        assertTrue(listed.getFirst().live());
        assertEquals("lounge", listed.getFirst().room(), "the room rides create to list");
        assertEquals(List.of("sh", "-c", "read a"), listed.getFirst().command());

        var dup =
            assertThrows(
                IOException.class,
                () -> client.create("s1", List.of("sh"), "/tmp", "", "", 80, 24));
        assertTrue(dup.getMessage().contains("already running"));

        client.kill("s1");
        assertEquals(0, client.list().size());
      }
    }
  }

  @Test
  void aBoxWithNoFdeIsToldTheFixAsItsReaderTypesIt() throws Exception {
    try (var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new HostAccess(null, new RoleRule(SyncConfig::unset, null)).identity(""),
            PtyAdmissions.NONE,
            PtyEvents.NONE,
            "0.0.0-test")) {
      host.start();
      var refused =
          assertThrows(IOException.class, () -> SessionClient.connect(dir.resolve("h.sock")));

      assertEquals(
          List.of(
              "This box has no FDE identity. Set one with 'sudo sail host config set sync-handle"
                  + " <handle>' or connect through the gateway."),
          CliCommand.lines(refused, OperatorCommand.SUDO));
      assertEquals(
          List.of(
              "This box has no FDE identity. Set one with 'sail host config set sync-handle"
                  + " <handle>' or connect through the gateway."),
          CliCommand.lines(refused, OperatorCommand.PLAIN));
    }
  }

  @Test
  void aRefusedRoomKeepsItsRemedyAcrossTheSocket() throws Exception {
    try (var host =
        new PtySessionHost(
            dir.resolve("h.sock"),
            dir.resolve("s"),
            64 * 1024,
            token -> new PtyIdentity.Person(new Actor("uday", Role.MEMBER, Actor.Lane.API)),
            new PtyAdmission() {
              @Override
              public void box(PtyIdentity.Person who) {}

              @Override
              public void project(String project, PtyIdentity.Person who) {}

              @Override
              public void room(String room, String project, PtyIdentity.Person who)
                  throws IOException {
                throw Remedied.io(
                    "Room " + room + " is not on this box yet.",
                    Remedy.of("Run '", Remedy.sail("sync"), "'."));
              }
            },
            PtyEvents.NONE,
            "0.0.0-test")) {
      host.start();
      try (var client = SessionClient.connect(dir.resolve("h.sock"))) {
        var refused =
            assertThrows(
                IOException.class,
                () -> client.create("s1", List.of("sh"), "/tmp", "", "lounge", 80, 24));

        assertEquals(
            List.of(
                "Refusing room for session 's1': Room lounge is not on this box yet. Run 'sudo"
                    + " sail sync'."),
            CliCommand.lines(refused, OperatorCommand.SUDO));
        assertEquals(
            List.of(
                "Refusing room for session 's1': Room lounge is not on this box yet. Run 'sail"
                    + " sync'."),
            CliCommand.lines(refused, OperatorCommand.PLAIN));
      }
    }
  }

  @Test
  void aMissingHostExplainsItselfInsteadOfStackTracing() {
    var error =
        assertThrows(IOException.class, () -> SessionClient.connect(dir.resolve("absent.sock")));
    assertTrue(error.getMessage().contains("session host"), error.getMessage());
  }

  /** What a stand-in host does with the one connection it takes. */
  @FunctionalInterface
  private interface Answer {
    void to(SocketChannel connection) throws IOException;
  }

  private static Stream<Arguments> hostsThatTurnTheClientAway() {
    Answer refuses =
        connection -> {
          PtyWire.handshake(connection, connection);
          PtyWire.read(connection);
          PtyWire.write(connection, new PtyMessage.Err("Session token is not valid."));
        };
    Answer answersSomethingElse =
        connection -> {
          PtyWire.handshake(connection, connection);
          PtyWire.read(connection);
          PtyWire.write(connection, new PtyMessage.Hello("not a welcome"));
        };
    Answer hangsUpAfterTheHandshake = connection -> PtyWire.handshake(connection, connection);
    Answer speaksAnotherProtocol = connection -> connection.write(ByteBuffer.wrap(new byte[64]));
    return Stream.of(
        Arguments.of("refuses the token", refuses),
        Arguments.of("answers the hello with something else", answersSomethingElse),
        Arguments.of("hangs up after the handshake", hangsUpAfterTheHandshake),
        Arguments.of("speaks another protocol", speaksAnotherProtocol));
  }

  /** However a host turns the client away after its socket opened, the socket is closed. */
  @ParameterizedTest(name = "a host that {0}")
  @MethodSource("hostsThatTurnTheClientAway")
  void aClientTurnedAwayLeavesNoSocketOpen(String what, Answer answer, @TempDir Path dir)
      throws Exception {
    var socket = dir.resolve("h.sock");
    try (var host = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      host.bind(UnixDomainSocketAddress.of(socket));
      var before = ClosesWhatItOpens.baseline();
      var answered =
          CompletableFuture.runAsync(
              () -> {
                try (var connection = host.accept()) {
                  answer.to(connection);
                } catch (IOException gone) {
                  var unused = gone;
                }
              });

      assertThrows(IOException.class, () -> SessionClient.connect(socket, "tok"), what);
      answered.get(10, TimeUnit.SECONDS);

      ClosesWhatItOpens.assertClosed("a client turned away by a host that " + what, before);
    }
  }
}
