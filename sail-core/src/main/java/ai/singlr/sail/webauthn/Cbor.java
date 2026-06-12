/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal CBOR (RFC 8949) decoder covering exactly the definite-length subset WebAuthn uses: the
 * attestation object (a text-keyed map) and COSE keys (integer-keyed maps). Decodes major types 0
 * (unsigned int), 1 (negative int), 2 (byte string), 3 (text string), 4 (array), and 5 (map);
 * everything else — tags, floats, indefinite-length, truncated input — is rejected, so malformed
 * authenticator data fails closed rather than being silently misread.
 *
 * <p>Decoded values map to: {@code Long}, {@code byte[]}, {@code String}, {@code List<Object>},
 * {@code Map<Object, Object>} (keys are {@code Long} or {@code String}). The reader tracks its
 * position so callers parsing fixed-layout structures (e.g. authenticator data, where the COSE key
 * is followed by optional extensions) know where decoding ended.
 */
public final class Cbor {

  private final byte[] data;
  private int pos;

  public Cbor(byte[] data) {
    this.data = data;
  }

  /** Decodes a single complete CBOR item and asserts no trailing bytes remain. */
  public static Object decode(byte[] data) {
    var reader = new Cbor(data);
    var value = reader.readValue();
    if (reader.pos != data.length) {
      throw new IllegalArgumentException("Trailing bytes after CBOR item");
    }
    return value;
  }

  /** The index one past the last byte consumed so far. */
  public int position() {
    return pos;
  }

  /** Reads one CBOR data item, advancing the position. */
  public Object readValue() {
    var initial = readByte();
    var majorType = (initial >> 5) & 0x07;
    var info = initial & 0x1f;
    return switch (majorType) {
      case 0 -> readUnsigned(info);
      case 1 -> -1L - readUnsigned(info);
      case 2 -> readBytes(lengthOf(info));
      case 3 -> new String(readBytes(lengthOf(info)), StandardCharsets.UTF_8);
      case 4 -> readArray(lengthOf(info));
      case 5 -> readMap(lengthOf(info));
      default -> throw new IllegalArgumentException("Unsupported CBOR major type: " + majorType);
    };
  }

  private long readUnsigned(int info) {
    return switch (info) {
      case 24 -> readByte() & 0xffL;
      case 25 -> readUInt(2);
      case 26 -> readUInt(4);
      case 27 -> readUInt(8);
      default -> {
        if (info < 24) {
          yield info;
        }
        throw new IllegalArgumentException("Unsupported CBOR additional info: " + info);
      }
    };
  }

  private int lengthOf(int info) {
    // readUnsigned never yields a negative value (8-byte overflow is rejected there), so only the
    // upper bound can fail here.
    var length = readUnsigned(info);
    if (length > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("CBOR length out of range: " + length);
    }
    return (int) length;
  }

  private long readUInt(int byteCount) {
    var value = 0L;
    for (var i = 0; i < byteCount; i++) {
      value = (value << 8) | (readByte() & 0xffL);
    }
    if (value < 0) {
      throw new IllegalArgumentException("CBOR integer exceeds supported range");
    }
    return value;
  }

  private byte[] readBytes(int length) {
    if (length > data.length - pos) {
      throw new IllegalArgumentException("CBOR byte string overruns input");
    }
    var out = new byte[length];
    System.arraycopy(data, pos, out, 0, length);
    pos += length;
    return out;
  }

  private List<Object> readArray(int count) {
    if (count > data.length - pos) {
      throw new IllegalArgumentException("CBOR array count exceeds remaining input");
    }
    var list = new ArrayList<>(count);
    for (var i = 0; i < count; i++) {
      list.add(readValue());
    }
    return list;
  }

  private Map<Object, Object> readMap(int count) {
    var map = new LinkedHashMap<Object, Object>();
    for (var i = 0; i < count; i++) {
      var key = readValue();
      if (map.put(key, readValue()) != null) {
        throw new IllegalArgumentException("Duplicate CBOR map key: " + key);
      }
    }
    return map;
  }

  private int readByte() {
    if (pos >= data.length) {
      throw new IllegalArgumentException("Unexpected end of CBOR input");
    }
    return data[pos++] & 0xff;
  }
}
