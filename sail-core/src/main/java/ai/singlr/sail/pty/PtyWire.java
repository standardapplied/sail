/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import ai.singlr.sail.common.Remedy;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The framing for {@link PtyMessage} over a byte channel: an 8-byte magic handshake carrying the
 * protocol version, then length-prefixed frames ({@code int length, byte type, payload}). Frames
 * are capped at 1 MiB — anything larger is a protocol violation, refused loudly (the tmux lesson:
 * version and framing skew must fail the handshake, never limp).
 */
public final class PtyWire {

  static final byte[] MAGIC = "SAILPTY7".getBytes(StandardCharsets.US_ASCII);
  static final int MAX_FRAME = 1 << 20;

  private PtyWire() {}

  /** Sends the magic; the peer must echo it back. Throws on any mismatch. */
  public static void handshake(ReadableByteChannel in, WritableByteChannel out) throws IOException {
    writeFully(out, ByteBuffer.wrap(MAGIC));
    var peer = ByteBuffer.allocate(MAGIC.length);
    readFully(in, peer);
    if (!ByteBuffer.wrap(MAGIC).equals(peer.flip())) {
      throw new IOException("Peer is not speaking sail pty protocol v7; refusing the connection.");
    }
  }

  public static void write(WritableByteChannel out, PtyMessage message) throws IOException {
    var payload = encode(message);
    if (payload.length > MAX_FRAME) {
      throw new IOException(
          "Refusing to send a pty frame of " + payload.length + " bytes; the cap is 1 MiB.");
    }
    var frame = ByteBuffer.allocate(4 + payload.length);
    frame.putInt(payload.length).put(payload).flip();
    writeFully(out, frame);
  }

  /**
   * The bytes {@code strings} occupy on the wire — the count and every length prefix included, not
   * merely the characters — so a cap measured here bounds the frame that carries them.
   */
  public static int wireSize(List<String> strings) {
    var size = 4;
    for (var value : strings) {
      size += 4 + value.getBytes(StandardCharsets.UTF_8).length;
    }
    return size;
  }

  public static PtyMessage read(ReadableByteChannel in) throws IOException {
    return read(in, MAX_FRAME);
  }

  /**
   * Reads one frame of at most {@code cap} payload bytes, refusing a longer one before allocating
   * for it: a peer that has proven nothing yet makes the reader hold no more than {@code cap}.
   */
  public static PtyMessage read(ReadableByteChannel in, int cap) throws IOException {
    var lengthBuf = ByteBuffer.allocate(4);
    readFully(in, lengthBuf);
    var length = lengthBuf.flip().getInt();
    if (length < 1 || length > cap) {
      throw new IOException(
          "Refusing pty frame of " + length + " bytes; the cap is " + cap + " bytes.");
    }
    var payload = ByteBuffer.allocate(length);
    readFully(in, payload);
    payload.flip();
    return decode(payload);
  }

  private static byte[] encode(PtyMessage message) {
    var out = new Writer(sizeHint(message));
    switch (message) {
      case PtyMessage.Hello m -> out.type(9).string(m.token());
      case PtyMessage.Create m -> {
        out.type(1)
            .string(m.session())
            .stringList(m.command())
            .string(m.cwd())
            .string(m.project())
            .string(m.room());
        out.i32(m.cols()).i32(m.rows());
        encodeAgent(out.string(m.workspace()), m.agent());
      }
      case PtyMessage.Attach m -> {
        out.type(2).string(m.session());
        out.bool(m.write()).string(m.client()).bool(m.takes());
      }
      case PtyMessage.Input m -> {
        out.type(3);
        out.i64(m.seq());
        out.bytes(m.bytes());
      }
      case PtyMessage.Resize m -> {
        out.type(4);
        out.i32(m.cols()).i32(m.rows());
      }
      case PtyMessage.TakeWrite m -> out.type(5);
      case PtyMessage.Detach m -> out.type(6);
      case PtyMessage.ListSessions m -> {
        out.type(7).string(m.after());
        out.i32(m.limit()).string(m.credential());
      }
      case PtyMessage.Kill m -> out.type(8).string(m.session());
      case PtyMessage.Yield m ->
          out.type(10).string(m.session()).string(m.reason()).string(m.record());
      case PtyMessage.IssueTicket m ->
          out.type(11)
              .string(m.action().wire())
              .string(m.target())
              .string(m.credential())
              .string(m.workspace());
      case PtyMessage.PutBegin m -> {
        out.type(12).i32(m.id()).string(m.name());
        out.i64(m.size());
      }
      case PtyMessage.PutChunk m -> {
        out.type(13).i32(m.id()).i64(m.offset());
        out.bytes(m.bytes());
      }
      case PtyMessage.PutEnd m -> out.type(14).i32(m.id()).bytes(m.sha256());
      case PtyMessage.PutCancel m -> out.type(15).i32(m.id());
      case PtyMessage.PutAck m -> out.type(35).i32(m.id()).i64(m.received());
      case PtyMessage.PutStored m -> out.type(36).i32(m.id()).string(m.path());
      case PtyMessage.PutRefused m ->
          out.type(37).i32(m.id()).string(m.kind().wire()).i64(m.limit());
      case PtyMessage.Ticket m -> out.type(33).string(m.ticket());
      case PtyMessage.TicketRefused m ->
          encodeRemedy(out.type(34).string(m.kind().wire()).string(m.reason()), m.remedy());
      case PtyMessage.Output m -> {
        out.type(20);
        out.i64(m.lastInputSeq());
        out.bytes(m.bytes());
      }
      case PtyMessage.ReplayBegin m -> {
        out.type(21);
        out.bool(m.safe());
      }
      case PtyMessage.ReplayEnd m -> out.type(22);
      case PtyMessage.WriterChanged m -> out.type(23).string(m.fde()).bool(m.yours());
      case PtyMessage.Resized m -> {
        out.type(24);
        out.i32(m.cols()).i32(m.rows());
      }
      case PtyMessage.Paused m -> out.type(25);
      case PtyMessage.Continued m -> out.type(26);
      case PtyMessage.SessionEnded m -> out.type(27).string(m.reason());
      case PtyMessage.SessionInfo m -> encodePlace(encodeInfo(out.type(28), m), m);
      case PtyMessage.Sessions m -> {
        out.type(29);
        out.i32(m.sessions().size());
        for (var info : m.sessions()) {
          encodeInfo(out, info);
        }
        out.string(m.next());
        out.i32(m.sessions().size());
        for (var info : m.sessions()) {
          encodePlace(out, info);
        }
      }
      case PtyMessage.Ok m -> out.type(30);
      case PtyMessage.Err m -> {
        encodeRemedy(out.type(31).string(m.reason()), m.remedy());
        out.string(m.code());
      }
      case PtyMessage.Welcome m -> out.type(32).string(m.hostBootId());
      case PtyMessage.YieldWorkspace m ->
          out.type(16)
              .string(m.project())
              .string(m.workspace())
              .string(m.reason())
              .string(m.record());
      case PtyMessage.FilesList m -> out.type(40).i32(m.id()).name(m.path());
      case PtyMessage.FilesStat m -> out.type(41).i32(m.id()).name(m.path());
      case PtyMessage.FilesRead m ->
          out.type(42).i32(m.id()).name(m.path()).i64(m.offset()).i64(m.length());
      case PtyMessage.FilesCancel m -> out.type(43).i32(m.id());
      case PtyMessage.FilesWatch m -> {
        out.type(44).i32(m.paths().size());
        m.paths().forEach(watched -> out.name(watched.path()).string(watched.version()));
      }
      case PtyMessage.FilesListing m -> {
        out.type(50).i32(m.id()).string(m.version()).i32(m.entries().size());
        m.entries().forEach(entry -> encodeEntry(out, entry));
        out.bool(m.more()).bool(m.truncated());
      }
      case PtyMessage.FilesEntry m ->
          encodeEntry(out.type(51).i32(m.id()), m.entry()).string(m.version());
      case PtyMessage.FilesData m -> {
        out.type(52).i32(m.id()).string(m.version());
        out.bytes(m.bytes());
        out.bool(m.eof());
      }
      case PtyMessage.FilesChanged m -> {
        out.type(53).i32(m.paths().size());
        m.paths().forEach(out::name);
      }
      case PtyMessage.FilesTick m -> out.type(54);
      case PtyMessage.FilesRefused m ->
          out.type(55).i32(m.id()).string(m.kind().wire()).string(m.message());
    }
    return out.finish();
  }

  private static Writer encodeEntry(Writer out, PtyMessage.FileEntry entry) {
    out.name(entry.name()).string(entry.kind().wire()).string(entry.target().wire());
    return out.i64(entry.size()).i64(entry.mtimeNanos()).i32(entry.mode());
  }

  private static PtyMessage.FileEntry entry(ByteBuffer in) throws IOException {
    return new PtyMessage.FileEntry(
        name(in),
        PtyMessage.FileKind.of(string(in)),
        PtyMessage.FileKind.of(string(in)),
        in.getLong(),
        in.getLong(),
        in.getInt());
  }

  private static PtyMessage.FilesWatch watch(ByteBuffer in) throws IOException {
    var count = bounded(in);
    var paths = new ArrayList<PtyMessage.Watched>(count);
    for (var i = 0; i < count; i++) {
      paths.add(new PtyMessage.Watched(name(in), string(in)));
    }
    return new PtyMessage.FilesWatch(paths);
  }

  private static PtyMessage.FilesListing listing(ByteBuffer in) throws IOException {
    var id = in.getInt();
    var version = string(in);
    var count = bounded(in);
    var entries = new ArrayList<PtyMessage.FileEntry>(count);
    for (var i = 0; i < count; i++) {
      entries.add(entry(in));
    }
    return new PtyMessage.FilesListing(id, version, entries, in.get() == 1, in.get() == 1);
  }

  /**
   * A starting buffer size that fits {@code message} without a realloc for the frames whose length
   * we know up front — {@code Output}, the hot path, above all, where doubling from 512 would cost
   * a handful of copies per 64 KiB frame per subscriber. Everything small keeps the modest default.
   */
  private static int sizeHint(PtyMessage message) {
    return switch (message) {
      case PtyMessage.Output(var seq, var bytes) -> bytes.length + 16;
      case PtyMessage.PutChunk(var id, var offset, var bytes) -> bytes.length + 24;
      case PtyMessage.FilesData(var id, var version, var bytes, var eof) ->
          bytes.length + version.length() + 24;
      case PtyMessage.SessionEnded(var reason) -> reason.length() + 16;
      default -> 512;
    };
  }

  /**
   * A refusal's remedy, after its reason: each part as words ({@code 0, text}) or a command ({@code
   * 1, runs, verb, args}).
   */
  private static void encodeRemedy(Writer out, Remedy remedy) {
    out.i32(remedy.parts().size());
    for (var part : remedy.parts()) {
      switch (part) {
        case Remedy.Words words -> out.bool(false).string(words.text());
        case Remedy.Command command ->
            out.bool(true)
                .string(command.runs().name())
                .string(command.verb())
                .stringList(command.args());
      }
    }
  }

  private static PtyMessage.Err decodeErr(ByteBuffer in) throws IOException {
    return new PtyMessage.Err(string(in), remedy(in), string(in));
  }

  /** A refusal's remedy as {@link #encodeRemedy} writes it: its parts, each words or a command. */
  private static Remedy remedy(ByteBuffer in) throws IOException {
    var count = bounded(in);
    var parts = new ArrayList<Remedy.Part>(count);
    for (var i = 0; i < count; i++) {
      var kind = in.get();
      parts.add(
          switch (kind) {
            case 0 -> new Remedy.Words(string(in));
            case 1 -> command(in);
            default ->
                throw new IOException("Corrupt pty frame: a refusal part of kind " + kind + ".");
          });
    }
    return new Remedy(parts);
  }

  private static Remedy.Command command(ByteBuffer in) throws IOException {
    var runs = string(in);
    var verb = string(in);
    var args = stringList(in);
    try {
      return new Remedy.Command(Remedy.Runs.valueOf(runs), verb, args);
    } catch (IllegalArgumentException corrupt) {
      throw new IOException(
          "Corrupt pty frame: a refusal names a command '" + verb + "' run by '" + runs + "'.");
    }
  }

  private static Writer encodeInfo(Writer out, PtyMessage.SessionInfo info) {
    out.string(info.name()).string(info.instanceId());
    out.bool(info.live()).i32(info.attached());
    return out.string(info.writerFde()).string(info.room()).stringList(info.command());
  }

  /**
   * Where a session runs, who opened it and when, and why it ended: its project, its workspace, its
   * opener, its creation in epoch milliseconds, its ended reason, blank while it lives, and the
   * harness it runs.
   */
  private static Writer encodePlace(Writer out, PtyMessage.SessionInfo info) {
    out.string(info.project()).string(info.workspace()).string(info.opener());
    return encodeAgent(out.i64(info.createdAtMillis()).string(info.endedReason()), info.agent());
  }

  /** The harness a session runs, its mode and whether it signs in inside: blank for a command. */
  private static Writer encodeAgent(Writer out, PtyMessage.Agent agent) {
    return out.string(agent.harness()).string(agent.mode()).bool(agent.inside());
  }

  private static PtyMessage.Agent agent(ByteBuffer in) throws IOException {
    return new PtyMessage.Agent(string(in), string(in), in.get() == 1);
  }

  private static PtyMessage decode(ByteBuffer in) throws IOException {
    var type = in.get();
    return switch (type) {
      case 9 -> new PtyMessage.Hello(string(in));
      case 1 ->
          new PtyMessage.Create(
              string(in),
              stringList(in),
              string(in),
              string(in),
              string(in),
              in.getInt(),
              in.getInt(),
              string(in),
              agent(in));
      case 2 -> new PtyMessage.Attach(string(in), in.get() == 1, string(in), in.get() == 1);
      case 3 -> new PtyMessage.Input(in.getLong(), bytes(in));
      case 4 -> new PtyMessage.Resize(in.getInt(), in.getInt());
      case 5 -> new PtyMessage.TakeWrite();
      case 6 -> new PtyMessage.Detach();
      case 7 -> new PtyMessage.ListSessions(string(in), in.getInt(), string(in));
      case 8 -> new PtyMessage.Kill(string(in));
      case 10 -> new PtyMessage.Yield(string(in), string(in), string(in));
      case 11 ->
          new PtyMessage.IssueTicket(
              PtyMessage.TicketAction.of(string(in)), string(in), string(in), string(in));
      case 12 -> new PtyMessage.PutBegin(in.getInt(), string(in), in.getLong());
      case 13 -> new PtyMessage.PutChunk(in.getInt(), in.getLong(), bytes(in));
      case 14 -> new PtyMessage.PutEnd(in.getInt(), bytes(in));
      case 15 -> new PtyMessage.PutCancel(in.getInt());
      case 35 -> new PtyMessage.PutAck(in.getInt(), in.getLong());
      case 36 -> new PtyMessage.PutStored(in.getInt(), string(in));
      case 37 ->
          new PtyMessage.PutRefused(
              in.getInt(), PtyMessage.PutRefusal.of(string(in)), in.getLong());
      case 33 -> new PtyMessage.Ticket(string(in));
      case 34 ->
          new PtyMessage.TicketRefused(
              PtyMessage.TicketRefusal.of(string(in)), string(in), remedy(in));
      case 20 -> new PtyMessage.Output(in.getLong(), bytes(in));
      case 21 -> new PtyMessage.ReplayBegin(in.get() == 1);
      case 22 -> new PtyMessage.ReplayEnd();
      case 23 -> new PtyMessage.WriterChanged(string(in), in.get() == 1);
      case 24 -> new PtyMessage.Resized(in.getInt(), in.getInt());
      case 25 -> new PtyMessage.Paused();
      case 26 -> new PtyMessage.Continued();
      case 27 -> new PtyMessage.SessionEnded(string(in));
      case 28 -> placed(decodeInfo(in), in);
      case 29 -> {
        var count = bounded(in);
        var sessions = new ArrayList<PtyMessage.SessionInfo>(count);
        for (var i = 0; i < count; i++) {
          sessions.add(decodeInfo(in));
        }
        var next = string(in);
        var placed = in.getInt();
        if (placed != count) {
          throw new IOException(
              "Corrupt pty frame: a page places " + placed + " sessions but holds " + count + ".");
        }
        for (var i = 0; i < count; i++) {
          sessions.set(i, placed(sessions.get(i), in));
        }
        yield new PtyMessage.Sessions(List.copyOf(sessions), next);
      }
      case 30 -> new PtyMessage.Ok();
      case 31 -> decodeErr(in);
      case 32 -> new PtyMessage.Welcome(string(in));
      case 16 -> new PtyMessage.YieldWorkspace(string(in), string(in), string(in), string(in));
      case 40 -> new PtyMessage.FilesList(in.getInt(), name(in));
      case 41 -> new PtyMessage.FilesStat(in.getInt(), name(in));
      case 42 -> new PtyMessage.FilesRead(in.getInt(), name(in), in.getLong(), in.getLong());
      case 43 -> new PtyMessage.FilesCancel(in.getInt());
      case 44 -> watch(in);
      case 50 -> listing(in);
      case 51 -> new PtyMessage.FilesEntry(in.getInt(), entry(in), string(in));
      case 52 -> new PtyMessage.FilesData(in.getInt(), string(in), bytes(in), in.get() == 1);
      case 53 -> new PtyMessage.FilesChanged(names(in));
      case 54 -> new PtyMessage.FilesTick();
      case 55 ->
          new PtyMessage.FilesRefused(
              in.getInt(), PtyMessage.FilesRefusal.of(string(in)), string(in));
      default -> throw new IOException("Unknown pty frame type " + type + ".");
    };
  }

  /** {@code info} with what {@link #encodePlace} wrote for it. */
  private static PtyMessage.SessionInfo placed(PtyMessage.SessionInfo info, ByteBuffer in)
      throws IOException {
    return info.placed(string(in), string(in), string(in))
        .lived(in.getLong(), string(in))
        .running(agent(in));
  }

  private static PtyMessage.SessionInfo decodeInfo(ByteBuffer in) throws IOException {
    return new PtyMessage.SessionInfo(
        string(in), string(in), in.get() == 1, in.getInt(), string(in), string(in), stringList(in));
  }

  private static String string(ByteBuffer in) throws IOException {
    var bytes = bytes(in);
    return bytes.length == 0 ? "" : new String(bytes, StandardCharsets.UTF_8);
  }

  private static byte[] bytes(ByteBuffer in) throws IOException {
    var bytes = new byte[bounded(in)];
    in.get(bytes);
    return bytes;
  }

  /** A file's name or path, its bytes kept whatever they are ({@link FileNames}). */
  private static String name(ByteBuffer in) throws IOException {
    return FileNames.decode(bytes(in));
  }

  private static List<String> names(ByteBuffer in) throws IOException {
    var count = bounded(in);
    var values = new ArrayList<String>(count);
    for (var i = 0; i < count; i++) {
      values.add(name(in));
    }
    return List.copyOf(values);
  }

  private static List<String> stringList(ByteBuffer in) throws IOException {
    var count = bounded(in);
    var values = new ArrayList<String>(count);
    for (var i = 0; i < count; i++) {
      values.add(string(in));
    }
    return List.copyOf(values);
  }

  /**
   * A length or element count read from within a frame, validated before it sizes any allocation: a
   * corrupt inner field can never exceed what the (already-capped) frame still holds, so a tiny
   * frame can never drive a giant array. Fails loud rather than throwing an unchecked {@code
   * NegativeArraySizeException} or {@code OutOfMemoryError} past the {@code read} contract.
   */
  private static int bounded(ByteBuffer in) throws IOException {
    var value = in.getInt();
    if (value < 0 || value > in.remaining()) {
      throw new IOException(
          "Corrupt pty frame: a field claims "
              + value
              + " bytes but only "
              + in.remaining()
              + " remain.");
    }
    return value;
  }

  private static void readFully(ReadableByteChannel in, ByteBuffer buf) throws IOException {
    while (buf.hasRemaining()) {
      if (in.read(buf) < 0) {
        throw new EOFException("pty channel closed mid-frame");
      }
    }
  }

  private static void writeFully(WritableByteChannel out, ByteBuffer buf) throws IOException {
    while (buf.hasRemaining()) {
      out.write(buf);
    }
  }

  /**
   * A growing buffer: every write reserves its room first, so no field can overflow whatever the
   * size hint guessed — an overflow here is a RuntimeException the host answers with a generic
   * refusal, and a listing that overflowed by a few bytes refused the same way on every retry.
   */
  private static final class Writer {
    private ByteBuffer buffer;

    Writer(int initialCapacity) {
      buffer = ByteBuffer.allocate(Math.max(16, initialCapacity));
    }

    Writer type(int type) {
      ensure(1);
      buffer.put((byte) type);
      return this;
    }

    Writer string(String value) {
      bytes(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
      return this;
    }

    /** A file's name or path as its bytes ({@link FileNames}). */
    Writer name(String value) {
      bytes(FileNames.encode(value));
      return this;
    }

    Writer stringList(List<String> values) {
      i32(values.size());
      values.forEach(this::string);
      return this;
    }

    Writer bool(boolean value) {
      ensure(1);
      buffer.put((byte) (value ? 1 : 0));
      return this;
    }

    Writer i32(int value) {
      ensure(4);
      buffer.putInt(value);
      return this;
    }

    Writer i64(long value) {
      ensure(8);
      buffer.putLong(value);
      return this;
    }

    void bytes(byte[] value) {
      ensure(4 + value.length);
      buffer.putInt(value.length).put(value);
    }

    private void ensure(int more) {
      if (buffer.remaining() < more) {
        var grown = ByteBuffer.allocate(Math.max(buffer.capacity() * 2, buffer.position() + more));
        buffer.flip();
        grown.put(buffer);
        buffer = grown;
      }
    }

    byte[] finish() {
      var out = new byte[buffer.position()];
      buffer.flip().get(out);
      return out;
    }
  }
}
