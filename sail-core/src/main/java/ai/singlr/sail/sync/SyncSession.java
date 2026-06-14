/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.io.Reader;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One node-to-main sync session over a channel: it hands out a {@link RemoteMainReplica} per entity
 * type (specs, then files) that the engine reconciles in turn, pulls the FDE roster, and ends the
 * session with {@link SyncWire.Bye} on {@link #close}. All exchanges share the one reader/writer
 * and run sequentially, so the typed replicas never interleave on the wire.
 */
public final class SyncSession implements AutoCloseable {

  private final Reader in;
  private final Writer out;

  public SyncSession(Reader in, Writer out) {
    this.in = Objects.requireNonNull(in, "in");
    this.out = Objects.requireNonNull(out, "out");
  }

  /** A remote view of main's entities of {@code entityType} for this session's channel. */
  public RemoteMainReplica replica(String entityType) {
    return new RemoteMainReplica(in, out, entityType);
  }

  /** Pulls main's FDE roster; the node mirrors it main-authoritatively. */
  public List<Map<String, Object>> fetchFdes() {
    var response = Rpc.exchange(in, out, new SyncWire.FetchFdes());
    if (response instanceof SyncWire.Fdes roster) {
      return roster.fdes();
    }
    throw new SyncTransportException("Expected an fde roster, got: " + response);
  }

  @Override
  public void close() {
    Rpc.send(out, new SyncWire.Bye());
  }
}
