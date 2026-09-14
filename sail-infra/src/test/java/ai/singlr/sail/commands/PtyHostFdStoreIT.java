/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyWire;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The claim itself, against the real thing: systemd's file descriptor store, the production unit
 * text, the real host. A restart and a {@code SIGKILL} of the host leave the session alive with its
 * history, its keyboard and its exit status; a stop ends it loudly and empties the store. Needs a
 * systemd user manager, not incus.
 */
class PtyHostFdStoreIT {

  private static final List<String> SCRIPT =
      List.of("sh", "-c", "echo ready; read a; echo got:$a; exit 7");

  @Test
  void aRestartOrACrashOfTheHostNeverEndsASessionAndAStopEndsItLoudly() throws Exception {
    UserUnitFixture.ensureSystemdOrSkip();
    try (var fixture = UserUnitFixture.start()) {
      String boot;
      String instance;
      try (var client = fixture.connect()) {
        boot = client.hostBootId();
        client.create("s1", SCRIPT, "/tmp", "", "", 80, 24);
        instance = named(client.list(), "s1").instanceId();
        var channel = client.attach("s1", true);
        prologue(channel);
        awaitText(channel, "ready");
        assertEquals("1", fixture.show("NFileDescriptorStore"), "the master is in the store");
      }

      fixture.systemctl("restart", fixture.unit);
      try (var client = fixture.connect()) {
        assertEquals(boot, client.hostBootId(), "an adopting host keeps the boot id");
        var s1 = named(client.list(), "s1");
        assertTrue(s1.live());
        assertEquals(instance, s1.instanceId());
        var channel = client.attach("s1", true);
        prologue(channel);
        awaitText(channel, "ready");
        PtyWire.write(channel, new PtyMessage.Input(1, "hello\n".getBytes(StandardCharsets.UTF_8)));
        awaitText(channel, "got:hello");
        assertEquals("exited(7)", awaitEnd(channel).reason(), "the status crossed the restart");
        UserUnitFixture.await(
            () -> countOf(fixture, "NFileDescriptorStore").equals("0"),
            "the store to let the ended session go");
      }

      String crashed;
      try (var client = fixture.connect()) {
        client.create("s2", List.of("sh", "-c", "echo up; read a; exit 0"), "/tmp", "", "", 80, 24);
        crashed = named(client.list(), "s2").instanceId();
        prologue(client.attach("s2", false));
        assertEquals("1", fixture.show("NFileDescriptorStore"));
      }
      fixture.systemctl("kill", "--signal=SIGKILL", "--kill-whom=main", fixture.unit);
      try (var client = fixture.connect()) {
        assertEquals(boot, client.hostBootId(), "a crash is a restart too");
        var s2 = named(client.list(), "s2");
        assertTrue(s2.live());
        assertEquals(crashed, s2.instanceId());
        assertEquals("1", fixture.show("NFileDescriptorStore"), "the store survived the crash");
        var channel = client.attach("s2", true);
        prologue(channel);
        awaitText(channel, "up");
        PtyWire.write(channel, new PtyMessage.Input(1, "\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals("exited(0)", awaitEnd(channel).reason());
      }

      try (var client = fixture.connect()) {
        client.create("s3", List.of("sh", "-c", "echo three; read a"), "/tmp", "", "", 80, 24);
        var channel = client.attach("s3", true);
        prologue(channel);
        awaitText(channel, "three");
        fixture.systemctl("stop", fixture.unit);
        var seen = awaitText(channel, "[sail: session ended");
        assertTrue(seen.contains(PtyHostCommand.STOPPED_REASON), seen);
        assertEquals(PtyHostCommand.STOPPED_REASON, awaitEnd(channel).reason(), "loud, not EOF");
      }
      UserUnitFixture.await(
          () -> fixture.processesUnderTmp().isEmpty(), "the stopped host's children to be gone");
      fixture.systemctl("start", fixture.unit);
      try (var client = fixture.connect()) {
        assertNotEquals(boot, client.hostBootId(), "nothing adopted: a new boot");
        assertTrue(client.list().isEmpty());
        assertEquals("0", fixture.show("NFileDescriptorStore"));
      }
    }
  }

  private static String countOf(UserUnitFixture fixture, String property) {
    try {
      return fixture.show(property);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  private static PtyMessage.SessionInfo named(List<PtyMessage.SessionInfo> listing, String name) {
    return listing.stream()
        .filter(info -> info.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no session '" + name + "' in " + listing));
  }

  private static void prologue(SocketChannel channel) throws IOException {
    assertInstanceOf(PtyMessage.Resized.class, PtyWire.read(channel));
    assertInstanceOf(PtyMessage.ReplayBegin.class, PtyWire.read(channel));
  }

  private static String awaitText(SocketChannel channel, String marker) throws IOException {
    var seen = new StringBuilder();
    while (!seen.toString().contains(marker)) {
      var message = PtyWire.read(channel);
      if (message instanceof PtyMessage.Output(var seq, var bytes)) {
        seen.append(new String(bytes, StandardCharsets.UTF_8));
      }
      if (message instanceof PtyMessage.SessionEnded(var reason)) {
        throw new AssertionError("ended (" + reason + ") before '" + marker + "': " + seen);
      }
    }
    return seen.toString();
  }

  private static PtyMessage.SessionEnded awaitEnd(SocketChannel channel) throws IOException {
    while (true) {
      if (PtyWire.read(channel) instanceof PtyMessage.SessionEnded ended) {
        return ended;
      }
    }
  }
}
