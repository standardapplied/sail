/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.BlobStore;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The receiving half of a content run, defined once for both ends of the wire: main reading an
 * upload's manifests and chunks, the node reading a pull's. Each end supplies how the next line is
 * read, because what a non-content line means differs by side, and this class owns everything after
 * it: {@code done} ends a run; an unexpected op, an unasked-for hash, a hash sent twice or a chunk
 * past the byte budget is {@code protocol}; a channel that ends inside a chunk is {@code
 * unreachable}; a chunk the store refuses is {@code protocol} and a store that fails is {@code
 * store}. Nothing but a {@link SyncTransportException} escapes, so a fix to the run lands on both
 * sides at once.
 */
final class ContentReceiver {

  /** One side's reading of the next content line of a run. */
  @FunctionalInterface
  interface Lines {
    SyncWire.Content next();
  }

  private final InputStream in;
  private final Lines lines;
  private final BlobStore into;
  private final String context;

  ContentReceiver(InputStream in, Lines lines, BlobStore into, String context) {
    this.in = in;
    this.lines = lines;
    this.into = into;
    this.context = context;
  }

  /**
   * Reads manifests until {@code done}: exactly the {@code expected} hashes, each once, each handed
   * to {@code admit} as it arrives so a side can bound what it holds before the next one is read.
   */
  Map<String, BlobStore.Manifest> manifests(
      Set<String> expected, Consumer<BlobStore.Manifest> admit) {
    var manifests = new LinkedHashMap<String, BlobStore.Manifest>();
    while (true) {
      var content = lines.next();
      if (content instanceof SyncWire.Done) break;
      if (!(content instanceof SyncWire.Manifest m))
        throw protocol("Expected manifest for " + context);
      var manifest = m.manifest();
      if (!expected.contains(manifest.hash())
          || manifests.putIfAbsent(manifest.hash(), manifest) != null)
        throw protocol("Unexpected blob manifest " + manifest.hash());
      admit.accept(manifest);
    }
    if (!manifests.keySet().equals(expected)) throw protocol("Missing manifests for " + context);
    return manifests;
  }

  /**
   * Reads chunks until {@code done}: only the {@code expected} hashes, each once, never more than
   * {@code remainingBytes} in all, every one verified and stored as it arrives. Returns the bytes
   * stored.
   */
  long chunks(Set<String> expected, long remainingBytes) {
    var pending = new LinkedHashSet<>(expected);
    var stored = 0L;
    while (true) {
      var content = lines.next();
      if (content instanceof SyncWire.Done) break;
      if (!(content instanceof SyncWire.Chunk chunk))
        throw protocol("Expected chunk for " + context);
      if (!pending.remove(chunk.hash())) throw protocol("Unexpected chunk " + chunk.hash());
      if (chunk.size() > remainingBytes - stored)
        throw protocol("Chunk " + chunk.hash() + " exceeds declared content size for " + context);
      store(chunk);
      stored += chunk.size();
    }
    if (!pending.isEmpty()) throw protocol("Missing chunks " + pending + " for " + context);
    return stored;
  }

  private void store(SyncWire.Chunk chunk) {
    byte[] bytes;
    try {
      bytes = SyncWire.readBytes(in, chunk.size());
    } catch (IOException e) {
      throw failed("unreachable", chunk, e);
    } catch (SyncTransportException e) {
      throw failed(e.kind(), chunk, e);
    }
    try {
      into.putChunk(chunk.hash(), bytes);
    } catch (IllegalArgumentException e) {
      throw protocol(e.getMessage());
    } catch (RuntimeException e) {
      throw failed("store", chunk, e);
    }
  }

  private SyncTransportException failed(String kind, SyncWire.Chunk chunk, Exception cause) {
    return new SyncTransportException(
        kind, context + ", chunk " + chunk.hash() + ": " + cause.getMessage(), cause);
  }

  private static SyncTransportException protocol(String message) {
    return new SyncTransportException("protocol", message, null);
  }
}
