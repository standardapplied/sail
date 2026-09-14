/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.pty.PtyWire;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The FDE-facing claim: the shell inside a real container — reached through the real {@code incus
 * exec} client — is the same process before and after the host restarts, still takes keystrokes,
 * and its history is continuous. And when the host stops for good, closing the master is the kill
 * switch: the shell is gone and no {@code incus exec} client outlives its session.
 */
class PtyHostFdStoreContainerIT extends AbstractIncusIT {

  @Test
  void theShellInTheContainerSurvivesTheHostRestartAndDiesWithTheStop() throws Exception {
    ensureIncusOrSkip();
    UserUnitFixture.ensureSystemdOrSkip();
    var container = "sail-it-pty-store";
    try (var fixture = UserUnitFixture.start()) {
      launch(container);
      var prepared =
          exec(
              container,
              List.of(
                  "bash", "-c", "mkdir -p /home/dev/workspace && chown -R 1000:1000 /home/dev"));
      assertTrue(prepared.ok(), prepared.stderr());

      String boot;
      String instance;
      String shellPid;
      try (var client = fixture.connect()) {
        boot = client.hostBootId();
        client.create("c1", List.of(), "/tmp", container, "", 80, 24);
        instance = client.list().getFirst().instanceId();
        var channel = client.attach("c1", true);
        prologue(channel);
        PtyWire.write(
            channel,
            new PtyMessage.Input(1, "echo marker-before\n".getBytes(StandardCharsets.UTF_8)));
        awaitText(channel, "marker-before");
        shellPid = bashPids(container);
        assertTrue(shellPid.matches("\\d+"), "exactly one bash in the container: " + shellPid);
      }

      fixture.systemctl("restart", fixture.unit);
      try (var client = fixture.connect()) {
        assertEquals(boot, client.hostBootId());
        assertEquals(instance, client.list().getFirst().instanceId());
        assertEquals(shellPid, bashPids(container), "the same shell process is still alive");
        var channel = client.attach("c1", true);
        prologue(channel);
        assertTrue(
            awaitText(channel, "marker-before").contains("marker-before"), "continuous ring");
        PtyWire.write(
            channel,
            new PtyMessage.Input(
                2, "echo marker-after-$(hostname)\n".getBytes(StandardCharsets.UTF_8)));
        awaitText(channel, "marker-after-" + container);
      }

      fixture.systemctl("stop", fixture.unit);
      UserUnitFixture.await(() -> bashPids(container).isEmpty(), "the shell to die of the hangup");
      UserUnitFixture.await(
          () ->
              ProcessHandle.allProcesses()
                  .map(handle -> handle.info().commandLine().orElse(""))
                  .noneMatch(line -> line.contains("incus exec " + container)),
          "no incus exec client to outlive its session");
    } finally {
      deleteContainerQuietly(container);
    }
  }

  private String bashPids(String container) {
    try {
      var result = exec(container, List.of("pgrep", "-x", "bash"));
      return result.stdout().strip().replace("\n", ",");
    } catch (Exception e) {
      throw new AssertionError(e);
    }
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
}
