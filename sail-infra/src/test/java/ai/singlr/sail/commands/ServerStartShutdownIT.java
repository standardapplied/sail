/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Boots the real control-plane server in a child JVM, sends it a genuine SIGTERM (what {@code
 * systemctl stop}/upgrade delivers), and asserts the shutdown hook ran and closed the server's
 * resources. The bug this guards: a {@code Type=simple} unit's SIGTERM does not unwind the stack,
 * so a try-with-resources never runs — only a registered hook drains in-flight work and closes the
 * database. Deterministic: it synchronizes on the server's "listening" line and on process exit
 * (bounded {@code poll}/{@code waitFor}), never on sleeps. Runs under the {@code integration}
 * profile; it needs a Unix JVM, not incus.
 */
class ServerStartShutdownIT {

  private static final String MAIN_CLASS = "ai.singlr.sail.Main";
  private static final String SENTINEL = "<<stream-closed>>";

  @Test
  void sigtermRunsTheShutdownHookAndClosesResources() throws Exception {
    var home =
        Files.createDirectories(Files.createTempDirectory("sail-shutdown-it").resolve("home"));

    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var process =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                "--enable-native-access=ALL-UNNAMED",
                "-Duser.home=" + home,
                MAIN_CLASS,
                "server",
                "start",
                "--host",
                "127.0.0.1",
                "--port",
                String.valueOf(freePort()))
            .redirectErrorStream(true)
            .start();
    process.getOutputStream().close();

    var lines = new LinkedBlockingQueue<String>();
    var reader = new Thread(() -> drainInto(process, lines), "server-output-reader");
    reader.setDaemon(true);
    reader.start();

    var transcript = new StringBuilder();
    try {
      var listening = awaitLine(lines, transcript, "Sail server listening", 60);
      assertTrue(listening, "server should start and announce it is listening:\n" + transcript);

      new ProcessBuilder("kill", "-TERM", String.valueOf(process.pid()))
          .inheritIO()
          .start()
          .waitFor();

      var cleaned = awaitLine(lines, transcript, "[shutdown] closed", 30);
      var exited = process.waitFor(30, TimeUnit.SECONDS);
      assertTrue(exited, "server must exit after SIGTERM:\n" + transcript);
      assertTrue(
          cleaned,
          "the shutdown hook must run on SIGTERM and close resources cleanly (exit="
              + process.exitValue()
              + "):\n"
              + transcript);
    } finally {
      process.destroyForcibly();
    }
  }

  private static boolean awaitLine(
      LinkedBlockingQueue<String> lines,
      StringBuilder transcript,
      String needle,
      int timeoutSeconds)
      throws InterruptedException {
    for (var line = lines.poll(timeoutSeconds, TimeUnit.SECONDS);
        line != null;
        line = lines.poll(timeoutSeconds, TimeUnit.SECONDS)) {
      if (line.equals(SENTINEL)) {
        return false;
      }
      transcript.append(line).append('\n');
      if (line.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private static void drainInto(Process process, LinkedBlockingQueue<String> lines) {
    try (var reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      for (var line = reader.readLine(); line != null; line = reader.readLine()) {
        lines.put(line);
      }
    } catch (IOException | InterruptedException ignored) {
    } finally {
      lines.add(SENTINEL);
    }
  }

  private static int freePort() throws IOException {
    try (var socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
