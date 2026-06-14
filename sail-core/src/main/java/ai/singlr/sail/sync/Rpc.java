/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;

/**
 * The node's side of one framed request/response over a sync channel — shared by the typed {@link
 * RemoteMainReplica}s and the {@link SyncSession} that wraps them, so the encode/flush/read/decode
 * dance has a single definition. Sequential by contract: one exchange completes before the next
 * starts, so several typed replicas can ride the same reader/writer.
 */
final class Rpc {

  private Rpc() {}

  static SyncWire.Response exchange(Reader in, Writer out, SyncWire.Request request) {
    send(out, request);
    try {
      var line = SyncWire.readFramed(in);
      if (line == null) {
        throw new SyncTransportException("Sync channel closed before main replied.");
      }
      return SyncWire.decodeResponse(line);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void send(Writer out, SyncWire.Request request) {
    try {
      out.write(SyncWire.encode(request));
      out.write('\n');
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
