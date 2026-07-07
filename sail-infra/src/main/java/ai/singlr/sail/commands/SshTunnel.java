/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.SailPaths;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * A supervised {@code ssh -N -L} tunnel from the canonical control-plane port on this machine to
 * the same port on the box, so a browser passkey ceremony can run at the origin {@code
 * http://localhost:7070} the box allowlists. The local end must be the canonical port — the server
 * matches the ceremony origin exactly, so an ephemeral port can never validate — which is why a
 * busy port 7070 fails loud instead of falling back.
 *
 * <p>{@code BatchMode} keeps ssh from ever prompting (a missing key fails fast), {@code
 * ExitOnForwardFailure} turns a lost port race into a clean exit, and a health check against the
 * forwarded {@code /v1/health} proves the control plane is answering before any ceremony starts.
 * The tunnel rides the engineer's own SSH identity for {@code host} — the {@code sail} gateway
 * account's keys are {@code restrict}-ed and cannot forward ports.
 */
public final class SshTunnel implements AutoCloseable {

  public static final int PORT = 7070;
  public static final String ORIGIN = "http://localhost:" + PORT;

  private static final int HEALTH_ATTEMPTS = 20;
  private static final Duration HEALTH_INTERVAL = Duration.ofMillis(500);
  private static final Pattern PLAIN_HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@-]{0,252}");

  @FunctionalInterface
  interface Launcher {
    Process launch(List<String> command) throws IOException;
  }

  @FunctionalInterface
  interface HealthProbe {
    boolean healthy() throws IOException, InterruptedException;
  }

  private final String host;
  private final Process process;
  private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
  private final Thread drainer;

  private SshTunnel(String host, Process process) {
    this.host = host;
    this.process = process;
    this.drainer =
        Thread.startVirtualThread(
            () -> {
              try {
                process.getErrorStream().transferTo(stderr);
              } catch (IOException ignored) {
                return;
              }
            });
  }

  /** Opens the tunnel to {@code host} and blocks until the forwarded control plane is healthy. */
  public static SshTunnel open(String host) throws IOException, InterruptedException {
    return open(
        host,
        PORT,
        command -> new ProcessBuilder(command).start(),
        healthProbe(PORT),
        HEALTH_ATTEMPTS,
        HEALTH_INTERVAL);
  }

  static SshTunnel open(
      String host, int port, Launcher launcher, HealthProbe probe, int attempts, Duration interval)
      throws IOException, InterruptedException {
    requirePlainHost(host);
    requireFreePort(port);
    var tunnel = new SshTunnel(host, launcher.launch(command(host, port)));
    try {
      tunnel.awaitHealthy(probe, attempts, interval);
    } catch (Exception e) {
      tunnel.close();
      throw e;
    }
    return tunnel;
  }

  static List<String> command(String host, int port) {
    return List.of(
        "ssh",
        "-N",
        "-o",
        "BatchMode=yes",
        "-o",
        "ExitOnForwardFailure=yes",
        "-o",
        "ConnectTimeout=10",
        "-o",
        "ServerAliveInterval=15",
        "-o",
        "ServerAliveCountMax=2",
        "-L",
        "127.0.0.1:" + port + ":127.0.0.1:" + PORT,
        "--",
        host);
  }

  /** Throws with the box name and captured ssh stderr if the tunnel died mid-ceremony. */
  public void ensureAlive() {
    if (!process.isAlive()) {
      throw new IllegalStateException(
          "The SSH tunnel to " + host + " died mid-ceremony." + sshDiagnostics());
    }
  }

  @Override
  public void close() {
    process.destroy();
    try {
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }

  private void awaitHealthy(HealthProbe probe, int attempts, Duration interval)
      throws InterruptedException {
    for (var attempt = 0; attempt < attempts; attempt++) {
      if (!process.isAlive()) {
        throw new IllegalStateException(
            "The SSH tunnel to "
                + host
                + " exited before it became healthy."
                + sshDiagnostics()
                + "\n  Check that 'ssh "
                + host
                + "' signs in with your key alone — the tunnel never prompts.");
      }
      if (probeQuietly(probe)) {
        return;
      }
      Thread.sleep(interval);
    }
    throw new IllegalStateException(
        "The SSH tunnel to "
            + host
            + " is up, but "
            + ORIGIN
            + "/v1/health did not answer within "
            + interval.multipliedBy(attempts).toSeconds()
            + "s."
            + "\n  Is the control plane running on "
            + host
            + "? Install it there with: sudo sail host service install"
            + sshDiagnostics());
  }

  private static boolean probeQuietly(HealthProbe probe) throws InterruptedException {
    try {
      return probe.healthy();
    } catch (IOException e) {
      return false;
    }
  }

  private String sshDiagnostics() {
    try {
      drainer.join(Duration.ofMillis(500));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    var text = stderr.toString(StandardCharsets.UTF_8).strip();
    return text.isEmpty() ? "" : "\n  ssh said: " + text;
  }

  private static void requirePlainHost(String host) {
    if (host == null || !PLAIN_HOST.matcher(host).matches()) {
      throw new IllegalArgumentException(
          "Refusing to tunnel to '"
              + host
              + "' — the client host must be a plain hostname, IP, or SSH alias."
              + "\n  Fix 'host:' in "
              + SailPaths.clientConfigPath());
    }
  }

  private static void requireFreePort(int port) {
    try (var socket = new ServerSocket()) {
      socket.setReuseAddress(true);
      socket.bind(new InetSocketAddress("127.0.0.1", port));
    } catch (IOException e) {
      throw new IllegalStateException(
          "Local port "
              + port
              + " is already in use. The passkey ceremony must run at the canonical origin"
              + " http://localhost:"
              + port
              + " — the box allowlists that exact origin, so an ephemeral port cannot work."
              + "\n  Find the holder: lsof -nP -iTCP:"
              + port
              + " -sTCP:LISTEN"
              + "\n  Free the port and rerun.");
    }
  }

  private static HealthProbe healthProbe(int port) {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/health"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();
    return () -> {
      try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
      } catch (UncheckedIOException e) {
        return false;
      }
    };
  }
}
