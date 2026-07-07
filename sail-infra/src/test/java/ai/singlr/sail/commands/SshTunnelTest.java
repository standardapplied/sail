/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SshTunnelTest {

  private static final Duration FAST = Duration.ofMillis(1);

  private static int freePort() throws Exception {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  @Test
  void commandNeverPromptsAndFailsOnALostPortRace() {
    var command = SshTunnel.command("devbox", 7070);
    assertEquals("ssh", command.getFirst());
    assertTrue(command.containsAll(List.of("-N", "BatchMode=yes", "ExitOnForwardFailure=yes")));
    assertTrue(command.contains("127.0.0.1:7070:127.0.0.1:7070"));
    assertEquals("devbox", command.getLast());
    assertEquals("--", command.get(command.size() - 2));
  }

  @Test
  void openBecomesHealthyThenClosesTheTunnelWithTheCommand() throws Exception {
    var ssh = new FakeSshProcess("");
    try (var tunnel = SshTunnel.open("devbox", freePort(), cmd -> ssh, () -> true, 3, FAST)) {
      assertDoesNotThrow(tunnel::ensureAlive);
    }
    assertFalse(ssh.isAlive());
  }

  @Test
  void openFailsLoudWhenTheCanonicalPortIsBusy() throws Exception {
    try (var holder = new ServerSocket()) {
      holder.setReuseAddress(true);
      holder.bind(new InetSocketAddress("127.0.0.1", 0));
      var port = holder.getLocalPort();
      var launched = new AtomicInteger();
      var error =
          assertThrows(
              IllegalStateException.class,
              () ->
                  SshTunnel.open(
                      "devbox",
                      port,
                      cmd -> {
                        launched.incrementAndGet();
                        return new FakeSshProcess("");
                      },
                      () -> true,
                      3,
                      FAST));
      assertTrue(error.getMessage().contains("already in use"));
      assertTrue(error.getMessage().contains("lsof -nP -iTCP:" + port));
      assertEquals(0, launched.get());
    }
  }

  @Test
  void openSurfacesSshStderrWhenSshDiesBeforeBecomingHealthy() throws Exception {
    var ssh = new FakeSshProcess("Permission denied (publickey).");
    ssh.kill();
    var port = freePort();
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> SshTunnel.open("devbox", port, cmd -> ssh, () -> false, 3, FAST));
    assertTrue(error.getMessage().contains("devbox"));
    assertTrue(error.getMessage().contains("Permission denied (publickey)."));
    assertTrue(error.getMessage().contains("signs in with your key alone"));
  }

  @Test
  void openFailsWithGuidanceWhenTheHealthCheckTimesOut() throws Exception {
    var ssh = new FakeSshProcess("");
    var port = freePort();
    var error =
        assertThrows(
            IllegalStateException.class,
            () -> SshTunnel.open("devbox", port, cmd -> ssh, () -> false, 3, FAST));
    assertTrue(error.getMessage().contains("/v1/health did not answer"));
    assertTrue(error.getMessage().contains("devbox"));
    assertTrue(error.getMessage().contains("sail host service install"));
    assertFalse(ssh.isAlive());
  }

  @Test
  void ensureAliveNamesTheBoxAndSurfacesSshStderrWhenTheTunnelDiesMidCeremony() throws Exception {
    var ssh = new FakeSshProcess("client_loop: send disconnect: Broken pipe");
    var tunnel = SshTunnel.open("devbox", freePort(), cmd -> ssh, () -> true, 3, FAST);
    ssh.kill();
    var error = assertThrows(IllegalStateException.class, tunnel::ensureAlive);
    assertTrue(error.getMessage().contains("died mid-ceremony"));
    assertTrue(error.getMessage().contains("devbox"));
    assertTrue(error.getMessage().contains("Broken pipe"));
    tunnel.close();
  }

  @Test
  void refusesHostsThatCouldSmuggleSshOptions() throws Exception {
    var port = freePort();
    for (var host : new String[] {"-oProxyCommand=evil", "", null, "box name", "box;rm"}) {
      var error =
          assertThrows(
              IllegalArgumentException.class,
              () -> SshTunnel.open(host, port, cmd -> new FakeSshProcess(""), () -> true, 3, FAST));
      assertTrue(error.getMessage().contains("plain hostname"));
    }
  }

  private static final class FakeSshProcess extends Process {

    private final InputStream stderr;
    private volatile boolean alive = true;

    FakeSshProcess(String stderrText) {
      this.stderr = new ByteArrayInputStream(stderrText.getBytes(StandardCharsets.UTF_8));
    }

    void kill() {
      alive = false;
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return stderr;
    }

    @Override
    public int waitFor() {
      return 1;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
      return !alive;
    }

    @Override
    public int exitValue() {
      if (alive) {
        throw new IllegalThreadStateException();
      }
      return 1;
    }

    @Override
    public void destroy() {
      alive = false;
    }

    @Override
    public Process destroyForcibly() {
      alive = false;
      return this;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }
  }
}
