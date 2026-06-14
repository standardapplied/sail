/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlatformDetectorTest {

  @Test
  void platformSuffixLinux() {
    assertEquals("linux-amd64", PlatformDetector.platformSuffix("Linux", "amd64"));
  }

  @Test
  void platformSuffixMacAppleSilicon() {
    assertEquals("darwin-arm64", PlatformDetector.platformSuffix("Mac OS X", "aarch64"));
  }

  @Test
  void platformSuffixMacIntel() {
    assertEquals("darwin-amd64", PlatformDetector.platformSuffix("Mac OS X", "x86_64"));
  }

  @Test
  void platformSuffixDarwinArm64() {
    assertEquals("darwin-arm64", PlatformDetector.platformSuffix("Darwin", "arm64"));
  }

  @Test
  void platformSuffixUnknownDefaultsToLinux() {
    assertEquals("linux-amd64", PlatformDetector.platformSuffix("FreeBSD", "amd64"));
  }

  @Test
  void isValidBinaryElfOnLinux() {
    var elf = new byte[] {0x7f, 'E', 'L', 'F', 0, 0, 0, 0};

    assertTrue(PlatformDetector.isValidBinary(elf, "Linux"));
  }

  @Test
  void isValidBinaryRejectsNonElfOnLinux() {
    var notElf = new byte[] {0, 0, 0, 0};

    assertFalse(PlatformDetector.isValidBinary(notElf, "Linux"));
  }

  @Test
  void isValidBinaryMachO64OnMac() {
    var machO = new byte[] {(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe, 0, 0};

    assertTrue(PlatformDetector.isValidBinary(machO, "Mac OS X"));
  }

  @Test
  void isValidBinaryMachOUniversalOnMac() {
    var fat = new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0};

    assertTrue(PlatformDetector.isValidBinary(fat, "Mac OS X"));
  }

  @Test
  void isValidBinaryRejectsElfOnMac() {
    var elf = new byte[] {0x7f, 'E', 'L', 'F', 0, 0};

    assertFalse(PlatformDetector.isValidBinary(elf, "Mac OS X"));
  }

  @Test
  void isValidBinaryRejectsMachOOnLinux() {
    var machO = new byte[] {(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe, 0};

    assertFalse(PlatformDetector.isValidBinary(machO, "Linux"));
  }

  @Test
  void isValidBinaryRejectsNull() {
    assertFalse(PlatformDetector.isValidBinary(null, "Linux"));
  }

  @Test
  void isValidBinaryRejectsTooShort() {
    assertFalse(PlatformDetector.isValidBinary(new byte[] {0x7f, 'E'}, "Linux"));
  }

  @Test
  void isValidBinaryRejectsEmpty() {
    assertFalse(PlatformDetector.isValidBinary(new byte[0], "Linux"));
  }
}
