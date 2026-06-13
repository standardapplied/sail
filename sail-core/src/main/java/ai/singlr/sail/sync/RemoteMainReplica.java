/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The {@link MainReplica} the {@link SyncEngine} drives when main is remote: it speaks {@link
 * SyncWire} over the SSH channel instead of touching a database, so the engine reconciles against a
 * box across the network with no change to its logic.
 *
 * <p>One {@link SyncWire.Fetch} pulls main's whole shared state up front; every read the engine
 * makes ({@link #entityIds}, {@link #current}, {@link #currentRev}) is then served from that
 * snapshot with no further round trip. Only {@link #commit} talks to main again, and each commit
 * carries back main's new high-water so {@link #maxSeq} — read once at the end of the round to set
 * the checkpoint — reflects this node's own pushes, exactly as the in-process engine sees it.
 */
public final class RemoteMainReplica implements MainReplica, AutoCloseable {

  private final Reader in;
  private final Writer out;

  private SyncWire.Fetched fetched;
  private long maxSeq;

  public RemoteMainReplica(Reader in, Writer out) {
    this.in = Objects.requireNonNull(in, "in");
    this.out = Objects.requireNonNull(out, "out");
  }

  @Override
  public String id() {
    return fetched().mainId();
  }

  @Override
  public Set<String> entityIds() {
    return fetched().entities().keySet();
  }

  @Override
  public Map<String, Object> current(String entityId) {
    var snapshot = fetched().entities().get(entityId);
    return snapshot == null ? null : snapshot.snapshot();
  }

  @Override
  public String currentRev(String entityId) {
    var snapshot = fetched().entities().get(entityId);
    return snapshot == null ? null : snapshot.rev();
  }

  @Override
  public long maxSeq() {
    fetched();
    return maxSeq;
  }

  @Override
  public CommitOutcome commit(String entityId, Map<String, Object> snapshot, String expectedRev) {
    var response = exchange(new SyncWire.Commit(entityId, snapshot, expectedRev));
    return switch (response) {
      case SyncWire.Committed committed -> {
        maxSeq = committed.maxSeq();
        yield new CommitOutcome.Accepted(committed.rev());
      }
      case SyncWire.Rejected rejected ->
          new CommitOutcome.Rejected(rejected.currentRev(), rejected.currentSnapshot());
      case SyncWire.Failed failed -> throw new SyncTransportException(failed.message());
      default -> throw new SyncTransportException("Unexpected response to commit: " + response);
    };
  }

  /** Pulls main's FDE roster over the same channel; the node mirrors it main-authoritatively. */
  public List<Map<String, Object>> fetchFdes() {
    var response = exchange(new SyncWire.FetchFdes());
    if (response instanceof SyncWire.Fdes roster) {
      return roster.fdes();
    }
    throw new SyncTransportException("Expected an fde roster, got: " + response);
  }

  @Override
  public void close() {
    try {
      out.write(SyncWire.encode(new SyncWire.Bye()));
      out.write('\n');
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private SyncWire.Fetched fetched() {
    if (fetched == null) {
      var response = exchange(new SyncWire.Fetch());
      if (!(response instanceof SyncWire.Fetched f)) {
        throw new SyncTransportException("Expected a fetch response, got: " + response);
      }
      fetched = f;
      maxSeq = f.maxSeq();
    }
    return fetched;
  }

  private SyncWire.Response exchange(SyncWire.Request request) {
    try {
      out.write(SyncWire.encode(request));
      out.write('\n');
      out.flush();
      var line = SyncWire.readFramed(in);
      if (line == null) {
        throw new SyncTransportException("Sync channel closed before main replied.");
      }
      return SyncWire.decodeResponse(line);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
