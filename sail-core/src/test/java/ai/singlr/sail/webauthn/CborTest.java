/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.webauthn;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CborTest {

  private static Object decode(String hex) {
    return Cbor.decode(HexFormat.of().parseHex(hex));
  }

  @Test
  void unsignedIntegers() {
    assertEquals(0L, decode("00"));
    assertEquals(23L, decode("17"));
    assertEquals(24L, decode("1818"));
    assertEquals(1000L, decode("1903e8"));
    assertEquals(1000000L, decode("1a000f4240"));
    assertEquals(4294967296L, decode("1b0000000100000000"));
  }

  @Test
  void negativeIntegers() {
    assertEquals(-1L, decode("20"));
    assertEquals(-10L, decode("29"));
    assertEquals(-100L, decode("3863"));
    assertEquals(-7L, decode("26")); // COSE ES256 alg label
  }

  @Test
  void byteAndTextStrings() {
    assertArrayEquals(new byte[] {}, (byte[]) decode("40"));
    assertArrayEquals(new byte[] {1, 2, 3, 4}, (byte[]) decode("4401020304"));
    assertEquals("", decode("60"));
    assertEquals("a", decode("6161"));
    assertEquals("abc", decode("63616263"));
    assertEquals("authData", decode("686175746844617461"));
  }

  @Test
  void arrays() {
    assertEquals(List.of(), decode("80"));
    assertEquals(List.of(1L, 2L, 3L), decode("83010203"));
  }

  @Test
  void maps() {
    assertEquals(Map.of(), decode("a0"));
    assertEquals(Map.of(1L, 2L, 3L, 4L), decode("a201020304"));
  }

  @Test
  void textKeyedMapLikeAttestationObject() {
    assertEquals(Map.of("fmt", "none"), decode("a163666d74646e6f6e65")); // {"fmt": "none"}
  }

  @Test
  void cose256KeyDecodesWithNegativeIntegerLabels() {
    var x = "1".repeat(64);
    var y = "2".repeat(64);
    var hex = "a5" + "0102" + "0326" + "2001" + "215820" + x + "225820" + y;
    @SuppressWarnings("unchecked")
    var key = (Map<Object, Object>) decode(hex);
    assertEquals(2L, key.get(1L)); // kty = EC2
    assertEquals(-7L, key.get(3L)); // alg = ES256
    assertEquals(1L, key.get(-1L)); // crv = P-256
    assertArrayEquals(HexFormat.of().parseHex(x), (byte[]) key.get(-2L));
    assertArrayEquals(HexFormat.of().parseHex(y), (byte[]) key.get(-3L));
  }

  @Test
  void positionTracksConsumedBytesForTrailingExtensions() {
    var reader = new Cbor(HexFormat.of().parseHex("a0a0")); // two empty maps back to back
    assertEquals(Map.of(), reader.readValue());
    assertEquals(1, reader.position());
    assertEquals(Map.of(), reader.readValue());
    assertEquals(2, reader.position());
  }

  @Test
  void rejectsTrailingBytes() {
    assertThrows(IllegalArgumentException.class, () -> decode("0000"));
  }

  @Test
  void rejectsTruncatedInput() {
    assertThrows(IllegalArgumentException.class, () -> decode("41")); // 1-byte string, no byte
    assertThrows(IllegalArgumentException.class, () -> decode("1903")); // 2-byte int, 1 byte
  }

  @Test
  void rejectsIndefiniteLength() {
    assertThrows(IllegalArgumentException.class, () -> decode("9f")); // indefinite array
    assertThrows(IllegalArgumentException.class, () -> decode("5f")); // indefinite byte string
  }

  @Test
  void rejectsUnsupportedMajorTypesAndSimpleValues() {
    assertThrows(IllegalArgumentException.class, () -> decode("f93c00")); // half-float
    assertThrows(IllegalArgumentException.class, () -> decode("c0")); // tag
  }

  @Test
  void rejectsDuplicateMapKeys() {
    assertThrows(IllegalArgumentException.class, () -> decode("a201000101")); // key 1 twice
  }

  @Test
  void rejectsIntegerExceedingLongRange() {
    // major type 0, 8-byte value 0xffffffffffffffff overflows a signed long
    assertThrows(IllegalArgumentException.class, () -> decode("1bffffffffffffffff"));
  }

  @Test
  void rejectsLengthExceedingIntRange() {
    // byte string with 8-byte length 0x80000000 (> Integer.MAX_VALUE)
    assertThrows(IllegalArgumentException.class, () -> decode("5b0000000080000000"));
  }

  @Test
  void rejectsArrayCountExceedingInput() {
    // array header declaring 0x7fffffff elements with no element bytes (allocation bomb)
    assertThrows(IllegalArgumentException.class, () -> decode("9a7fffffff"));
  }
}
