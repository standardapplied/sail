/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

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

  static final byte[] MAGIC = "SAILPTY1".getBytes(StandardCharsets.US_ASCII);
  static final int MAX_FRAME = 1 << 20;

  private PtyWire() {}

  /** Sends the magic; the peer must echo it back. Throws on any mismatch. */
  public static void handshake(ReadableByteChannel in, WritableByteChannel out) throws IOException {
    writeFully(out, ByteBuffer.wrap(MAGIC));
    var peer = ByteBuffer.allocate(MAGIC.length);
    readFully(in, peer);
    if (!ByteBuffer.wrap(MAGIC).equals(peer.flip())) {
      throw new IOException("Peer is not speaking sail pty protocol v1; refusing the connection.");
    }
  }

  public static void write(WritableByteChannel out, PtyMessage message) throws IOException {
    var payload = encode(message);
    var frame = ByteBuffer.allocate(4 + payload.length);
    frame.putInt(payload.length).put(payload).flip();
    writeFully(out, frame);
  }

  public static PtyMessage read(ReadableByteChannel in) throws IOException {
    var lengthBuf = ByteBuffer.allocate(4);
    readFully(in, lengthBuf);
    var length = lengthBuf.flip().getInt();
    if (length < 1 || length > MAX_FRAME) {
      throw new IOException("Refusing pty frame of " + length + " bytes; the cap is 1 MiB.");
    }
    var payload = ByteBuffer.allocate(length);
    readFully(in, payload);
    payload.flip();
    return decode(payload);
  }

  private static byte[] encode(PtyMessage message) {
    var out = new Writer();
    switch (message) {
      case PtyMessage.Hello m -> out.type(9).string(m.token());
      case PtyMessage.Create m -> {
        out.type(1).string(m.session()).stringList(m.command()).string(m.cwd()).string(m.project());
        out.buffer.putInt(m.cols()).putInt(m.rows());
      }
      case PtyMessage.Attach m -> {
        out.type(2).string(m.session());
        out.buffer.put((byte) (m.write() ? 1 : 0));
      }
      case PtyMessage.Input m -> {
        out.type(3);
        out.buffer.putLong(m.seq());
        out.bytes(m.bytes());
      }
      case PtyMessage.Resize m -> {
        out.type(4);
        out.buffer.putInt(m.cols()).putInt(m.rows());
      }
      case PtyMessage.TakeWrite m -> out.type(5);
      case PtyMessage.Detach m -> out.type(6);
      case PtyMessage.ListSessions m -> out.type(7);
      case PtyMessage.Kill m -> out.type(8).string(m.session());
      case PtyMessage.Output m -> {
        out.type(20);
        out.buffer.putLong(m.lastInputSeq());
        out.bytes(m.bytes());
      }
      case PtyMessage.ReplayBegin m -> {
        out.type(21);
        out.buffer.put((byte) (m.safe() ? 1 : 0));
      }
      case PtyMessage.ReplayEnd m -> out.type(22);
      case PtyMessage.WriterChanged m -> out.type(23).string(m.fde());
      case PtyMessage.Resized m -> {
        out.type(24);
        out.buffer.putInt(m.cols()).putInt(m.rows());
      }
      case PtyMessage.Paused m -> out.type(25);
      case PtyMessage.Continued m -> out.type(26);
      case PtyMessage.SessionEnded m -> out.type(27).string(m.reason());
      case PtyMessage.SessionInfo m -> encodeInfo(out.type(28), m);
      case PtyMessage.Sessions m -> {
        out.type(29);
        out.buffer.putInt(m.sessions().size());
        for (var info : m.sessions()) {
          encodeInfo(out, info);
        }
      }
      case PtyMessage.Ok m -> out.type(30);
      case PtyMessage.Err m -> out.type(31).string(m.message());
    }
    return out.finish();
  }

  private static Writer encodeInfo(Writer out, PtyMessage.SessionInfo info) {
    out.string(info.name());
    out.buffer.put((byte) (info.live() ? 1 : 0)).putInt(info.attached());
    return out.string(info.writerFde());
  }

  private static PtyMessage decode(ByteBuffer in) throws IOException {
    var type = in.get();
    return switch (type) {
      case 9 -> new PtyMessage.Hello(string(in));
      case 1 ->
          new PtyMessage.Create(
              string(in), stringList(in), string(in), string(in), in.getInt(), in.getInt());
      case 2 -> new PtyMessage.Attach(string(in), in.get() == 1);
      case 3 -> new PtyMessage.Input(in.getLong(), bytes(in));
      case 4 -> new PtyMessage.Resize(in.getInt(), in.getInt());
      case 5 -> new PtyMessage.TakeWrite();
      case 6 -> new PtyMessage.Detach();
      case 7 -> new PtyMessage.ListSessions();
      case 8 -> new PtyMessage.Kill(string(in));
      case 20 -> new PtyMessage.Output(in.getLong(), bytes(in));
      case 21 -> new PtyMessage.ReplayBegin(in.get() == 1);
      case 22 -> new PtyMessage.ReplayEnd();
      case 23 -> new PtyMessage.WriterChanged(string(in));
      case 24 -> new PtyMessage.Resized(in.getInt(), in.getInt());
      case 25 -> new PtyMessage.Paused();
      case 26 -> new PtyMessage.Continued();
      case 27 -> new PtyMessage.SessionEnded(string(in));
      case 28 -> decodeInfo(in);
      case 29 -> {
        var count = in.getInt();
        var sessions = new ArrayList<PtyMessage.SessionInfo>(count);
        for (var i = 0; i < count; i++) {
          sessions.add(decodeInfo(in));
        }
        yield new PtyMessage.Sessions(List.copyOf(sessions));
      }
      case 30 -> new PtyMessage.Ok();
      case 31 -> new PtyMessage.Err(string(in));
      default -> throw new IOException("Unknown pty frame type " + type + ".");
    };
  }

  private static PtyMessage.SessionInfo decodeInfo(ByteBuffer in) {
    return new PtyMessage.SessionInfo(string(in), in.get() == 1, in.getInt(), string(in));
  }

  private static String string(ByteBuffer in) {
    var bytes = bytes(in);
    return bytes.length == 0 ? "" : new String(bytes, StandardCharsets.UTF_8);
  }

  private static byte[] bytes(ByteBuffer in) {
    var length = in.getInt();
    var bytes = new byte[length];
    in.get(bytes);
    return bytes;
  }

  private static List<String> stringList(ByteBuffer in) {
    var count = in.getInt();
    var values = new ArrayList<String>(count);
    for (var i = 0; i < count; i++) {
      values.add(string(in));
    }
    return List.copyOf(values);
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

  private static final class Writer {
    private ByteBuffer buffer = ByteBuffer.allocate(512);

    Writer type(int type) {
      ensure(1);
      buffer.put((byte) type);
      return this;
    }

    Writer string(String value) {
      bytes(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
      return this;
    }

    Writer stringList(List<String> values) {
      ensure(4);
      buffer.putInt(values.size());
      values.forEach(this::string);
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
