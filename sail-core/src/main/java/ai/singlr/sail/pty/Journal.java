/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;

/**
 * The session's byte history: the append-only sink the gather thread drains a pty into and the
 * source a replay reads back. {@link RingJournal} is the one production implementation; the seam
 * exists so a session's failure paths — an append that throws on a full disk, a read that throws on
 * a corrupt file — are testable without a real disk fault.
 */
public interface Journal extends AutoCloseable {

  /** One replayable tail: bytes from {@code startOffset} of the stream, safe or best-effort. */
  record Tail(long startOffset, boolean safe, byte[] bytes) {}

  /** Appends {@code buf[0..len)} to the history. */
  void append(byte[] buf, int len) throws IOException;

  /** Records that the stream is at a safe replay boundary right now. */
  void markSafe() throws IOException;

  /** The newest at-most-{@code maxBytes} of history, starting at a safe boundary when one fits. */
  Tail tail(int maxBytes) throws IOException;

  /** The fixed capacity of the history window. */
  long capacity();

  /** Total bytes ever appended — the child's progress, independent of any reader. */
  long totalWritten();

  @Override
  void close() throws IOException;
}
