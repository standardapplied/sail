/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;

/**
 * A fixed-capacity ring of raw session bytes, journaled to one file so history survives a host
 * restart — the durability tmux never had. The layout is a small header (magic, version, capacity,
 * total bytes ever written, safe watermark) followed by the ring region; the write position is
 * always {@code totalWritten % capacity}.
 *
 * <p>The journal stores bytes and one number; it never inspects the stream. The session host owns
 * safety: it feeds the same bytes to a {@link TermBoundary} and calls {@link #markSafe()} at clean
 * points, and {@link #tail(int)} starts replay at the newest safe watermark still inside the window
 * — falling back to the raw window start, flagged unsafe, when history has overwritten it.
 */
public final class RingJournal implements AutoCloseable {

  private static final long MAGIC = 0x5341494C52494E47L;
  private static final int VERSION = 1;
  private static final int HEADER_BYTES = 40;

  /** One replayable tail: bytes from {@code startOffset} of the stream, safe or best-effort. */
  public record Tail(long startOffset, boolean safe, byte[] bytes) {}

  private final FileChannel channel;
  private final long capacity;
  private long totalWritten;

  /**
   * The oldest safe replay start still inside the ring; the one checkpoint that survives a restart.
   */
  private long safeWatermark;

  /** Safe replay starts inside the ring, oldest first, at least a stride apart. */
  private final ArrayDeque<Long> checkpoints = new ArrayDeque<>();

  private RingJournal(FileChannel channel, long capacity, long totalWritten, long safeWatermark) {
    this.channel = channel;
    this.capacity = capacity;
    this.totalWritten = totalWritten;
    this.safeWatermark = safeWatermark;
    if (safeWatermark >= windowStart()) {
      checkpoints.add(safeWatermark);
    }
  }

  /** Checkpoints are spaced so the ring never holds more than a few dozen of them. */
  private long checkpointStride() {
    return Math.max(1, capacity / 64);
  }

  /** The oldest offset still in the ring. */
  private long windowStart() {
    return totalWritten - Math.min(totalWritten, capacity);
  }

  /** Opens or creates the journal at {@code path}; an existing file must match the capacity. */
  public static RingJournal open(Path path, long capacity) throws IOException {
    if (capacity <= 0) {
      throw new IllegalArgumentException("Ring capacity must be positive, got " + capacity + ".");
    }
    var channel =
        FileChannel.open(
            path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    if (channel.size() == 0) {
      var journal = new RingJournal(channel, capacity, 0, 0);
      journal.writeHeader();
      return journal;
    }
    var header = ByteBuffer.allocate(HEADER_BYTES);
    channel.read(header, 0);
    header.flip();
    if (header.remaining() < HEADER_BYTES || header.getLong() != MAGIC) {
      channel.close();
      throw new IOException(
          "Not a sail ring journal: " + path + ". Remove the file to start a fresh history.");
    }
    var version = header.getInt();
    if (version != VERSION) {
      channel.close();
      throw new IOException(
          "Ring journal "
              + path
              + " is version "
              + version
              + "; this build reads "
              + VERSION
              + ".");
    }
    var storedCapacity = header.getLong();
    if (storedCapacity != capacity) {
      channel.close();
      throw new IOException(
          "Ring journal "
              + path
              + " has capacity "
              + storedCapacity
              + ", not "
              + capacity
              + ". Remove the file to resize.");
    }
    return new RingJournal(channel, capacity, header.getLong(), header.getLong());
  }

  /** Appends {@code buf[0..len)} to the ring and persists the header. */
  public void append(byte[] buf, int len) throws IOException {
    var offset = 0;
    while (offset < len) {
      var position = totalWritten % capacity;
      var chunk = (int) Math.min(len - offset, capacity - position);
      channel.write(ByteBuffer.wrap(buf, offset, chunk), HEADER_BYTES + position);
      totalWritten += chunk;
      offset += chunk;
    }
    writeHeader();
  }

  /**
   * Records that the stream is at a safe replay boundary right now — but keeps the OLDEST safe
   * start still worth replaying: the stored mark only advances once it has fallen below {@code
   * keepFloor} (the replay budget's edge). Advancing eagerly would mean "nothing to replay" after
   * every quiet moment; a late attacher wants history, starting clean.
   */
  /**
   * Records that the stream is at a safe replay boundary right now. The journal keeps every such
   * boundary still inside the ring (a stride apart, so the set stays small); a replay of any width
   * then starts at the oldest boundary inside its window, so a late attacher gets the most history
   * the ring can offer, and never a screen that begins mid-sequence.
   */
  public void markSafe() throws IOException {
    var boundary = totalWritten;
    var start = windowStart();
    while (!checkpoints.isEmpty() && checkpoints.peekFirst() < start) {
      checkpoints.pollFirst();
    }
    if (checkpoints.isEmpty() || boundary - checkpoints.peekLast() >= checkpointStride()) {
      checkpoints.addLast(boundary);
    }
    var oldest = checkpoints.peekFirst();
    if (oldest != safeWatermark) {
      safeWatermark = oldest;
      writeHeader();
    }
  }

  /**
   * The newest at-most-{@code maxBytes} of history, starting at the oldest safe boundary inside
   * that window when there is one; otherwise from the window start, flagged unsafe so the client
   * clears its screen before applying.
   */
  public Tail tail(int maxBytes) throws IOException {
    var from = Math.max(windowStart(), totalWritten - maxBytes);
    var safe = false;
    for (var checkpoint : checkpoints) {
      if (checkpoint >= from) {
        from = checkpoint;
        safe = true;
        break;
      }
    }
    var length = (int) (totalWritten - from);
    var bytes = new byte[length];
    var read = 0;
    while (read < length) {
      var position = (from + read) % capacity;
      var chunk = (int) Math.min(length - read, capacity - position);
      var slice = ByteBuffer.wrap(bytes, read, chunk);
      channel.read(slice, HEADER_BYTES + position);
      read += chunk;
    }
    return new Tail(from, safe, bytes);
  }

  public long capacity() {
    return capacity;
  }

  public long totalWritten() {
    return totalWritten;
  }

  private void writeHeader() throws IOException {
    var header = ByteBuffer.allocate(HEADER_BYTES);
    header
        .putLong(MAGIC)
        .putInt(VERSION)
        .putLong(capacity)
        .putLong(totalWritten)
        .putLong(safeWatermark);
    header.flip();
    channel.write(header, 0);
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
