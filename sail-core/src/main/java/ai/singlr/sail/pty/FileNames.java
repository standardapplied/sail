/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * A file's name or path is bytes, which a workspace's filesystem need not have written as UTF-8.
 * Held here as text that round-trips every byte: valid UTF-8 as itself, and each byte of an invalid
 * sequence as the lone low surrogate {@code U+DC00} plus that byte (as Python's {@code
 * surrogateescape} does), which no valid UTF-8 can decode to. On the wire the files frames carry a
 * name as its bytes ({@link #encode}), so a name a listing carried addresses that file exactly.
 */
public final class FileNames {

  private static final int ESCAPE = 0xDC00;

  private FileNames() {}

  /** {@code bytes} as a name, every byte kept. */
  public static String decode(byte[] bytes) {
    if (ascii(bytes)) {
      return new String(bytes, StandardCharsets.ISO_8859_1);
    }
    var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    var in = ByteBuffer.wrap(bytes);
    var out = CharBuffer.allocate(bytes.length);
    while (true) {
      var result = decoder.decode(in, out, true);
      if (!result.isError()) {
        break;
      }
      for (var i = 0; i < result.length(); i++) {
        out.put((char) (ESCAPE | (in.get() & 0xff)));
      }
    }
    decoder.flush(out);
    return out.flip().toString();
  }

  /**
   * Whether every byte is ASCII, as every field of a listing but a name, and most names, are: those
   * decode as themselves, with no decoder.
   */
  private static boolean ascii(byte[] bytes) {
    for (var b : bytes) {
      if (b < 0) {
        return false;
      }
    }
    return true;
  }

  /** The bytes {@code name} stands for: the inverse of {@link #decode}. */
  public static byte[] encode(String name) {
    var out = new ByteArrayOutputStream(name.length());
    var i = 0;
    while (i < name.length()) {
      var c = name.charAt(i);
      if (escaped(name, i)) {
        out.write(c & 0xff);
        i++;
        continue;
      }
      var end = i + Character.charCount(name.codePointAt(i));
      out.writeBytes(name.substring(i, end).getBytes(StandardCharsets.UTF_8));
      i = end;
    }
    return out.toByteArray();
  }

  /** {@code name} as a person reads it: each byte that was no UTF-8 shown as {@code U+FFFD}. */
  public static String shown(String name) {
    var shown = new StringBuilder(name.length());
    for (var i = 0; i < name.length(); i++) {
      shown.append(escaped(name, i) ? '�' : name.charAt(i));
    }
    return shown.toString();
  }

  /** Whether the char at {@code i} is an escaped byte: a low surrogate no high one precedes. */
  private static boolean escaped(String name, int i) {
    var c = name.charAt(i);
    return c >= ESCAPE + 0x80
        && c <= ESCAPE + 0xff
        && (i == 0 || !Character.isHighSurrogate(name.charAt(i - 1)));
  }
}
