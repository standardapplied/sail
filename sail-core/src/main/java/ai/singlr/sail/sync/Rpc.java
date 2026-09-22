/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The node's side of one framed request/response over a sync channel, so the encode/flush/read/
 * decode dance has a single definition for every session flavour. Sequential by contract: one
 * exchange completes before the next starts, so several typed replicas can ride the same
 * reader/writer.
 */
final class Rpc {

  private Rpc() {}

  static SyncWire.Response exchange(InputStream in, OutputStream out, SyncWire.Request request) {
    var context = SyncWire.context(request);
    var line = exchange(in, out, SyncWire.encode(request), context);
    try {
      return SyncWire.decodeResponse(line);
    } catch (RuntimeException e) {
      throw new SyncTransportException("protocol", context + ": " + e.getMessage(), e);
    }
  }

  /** Sends one encoded line and returns main's raw answer, or fails naming {@code context}. */
  static String exchange(InputStream in, OutputStream out, String line, String context) {
    try {
      send(out, line);
      var answer = SyncWire.readFramed(in);
      if (answer == null) {
        throw new SyncTransportException(
            "unreachable", "Sync channel closed before main replied.", null);
      }
      return answer;
    } catch (IOException | UncheckedIOException e) {
      throw new UncheckedIOException(new IOException(context + ": " + e.getMessage(), e));
    } catch (SyncTransportException e) {
      throw new SyncTransportException(e.kind(), context + ": " + e.getMessage(), e);
    }
  }

  static SyncWire.Response receive(InputStream in, String context) {
    try {
      var line = SyncWire.readLine(in);
      if (line == null)
        throw new SyncTransportException("unreachable", context + ": channel closed", null);
      return SyncWire.decodeResponse(line);
    } catch (IOException e) {
      throw new SyncTransportException("unreachable", context + ": " + e.getMessage(), e);
    } catch (SyncTransportException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new SyncTransportException("protocol", context + ": " + e.getMessage(), e);
    }
  }

  static void send(OutputStream out, String line) {
    try {
      out.write(line.getBytes(StandardCharsets.UTF_8));
      out.write('\n');
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
