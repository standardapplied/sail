/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

/**
 * A server that answers one request with an SSE stream of {@code frames} and then either ends the
 * stream as HTTP ends it ({@code dropped} false) or closes the connection mid-stream, as a sail-api
 * that restarts under its followers does.
 */
final class StreamServer implements AutoCloseable {

  private final ServerSocket socket;
  private final Thread answering;

  private StreamServer(String frames, boolean dropped) throws IOException {
    socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
    answering = Thread.ofVirtual().start(() -> answer(frames, dropped));
  }

  static StreamServer ending(String frames) throws IOException {
    return new StreamServer(frames, false);
  }

  static StreamServer dropping(String frames) throws IOException {
    return new StreamServer(frames, true);
  }

  int port() {
    return socket.getLocalPort();
  }

  private void answer(String frames, boolean dropped) {
    try (var connection = socket.accept()) {
      var request =
          new BufferedReader(
              new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
      while (!request.readLine().isEmpty()) {}
      var body = frames.getBytes(StandardCharsets.UTF_8);
      var out = connection.getOutputStream();
      out.write(
          ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n"
                  + Integer.toHexString(body.length)
                  + "\r\n")
              .getBytes(StandardCharsets.UTF_8));
      out.write(body);
      out.write((dropped ? "\r\n" : "\r\n0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() throws Exception {
    socket.close();
    answering.join();
  }
}
