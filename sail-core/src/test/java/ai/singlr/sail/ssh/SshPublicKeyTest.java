/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.ssh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SshPublicKeyTest {

  @Test
  void parsesTypeBlobCommentAndFingerprint() {
    var line = TestSshKeys.ed25519("uday", "uday@laptop");
    var key = SshPublicKey.parse(line);
    assertEquals("ssh-ed25519", key.type());
    assertEquals("uday@laptop", key.comment());
    assertTrue(key.fingerprint().startsWith("SHA256:"));
    assertEquals(line, key.line());
  }

  @Test
  void fingerprintIsStableAndDistinctPerKey() {
    var a = SshPublicKey.parse(TestSshKeys.ed25519("a", null));
    var a2 = SshPublicKey.parse(TestSshKeys.ed25519("a", "different-comment"));
    var b = SshPublicKey.parse(TestSshKeys.ed25519("b", null));
    assertEquals(
        a.fingerprint(), a2.fingerprint(), "fingerprint depends on the key, not the comment");
    assertNotEquals(a.fingerprint(), b.fingerprint());
  }

  @Test
  void commentlessLineOmitsTrailingSpace() {
    var key = SshPublicKey.parse(TestSshKeys.ed25519("k", null));
    assertNull(key.comment());
    assertFalse(key.line().endsWith(" "));
  }

  @Test
  void rejectsEmptyAndMalformed() {
    assertThrows(IllegalArgumentException.class, () -> SshPublicKey.parse(""));
    assertThrows(IllegalArgumentException.class, () -> SshPublicKey.parse("   "));
    assertThrows(IllegalArgumentException.class, () -> SshPublicKey.parse("ssh-ed25519"));
    assertThrows(IllegalArgumentException.class, () -> SshPublicKey.parse((String) null));
  }

  @Test
  void rejectsUnsupportedType() {
    assertThrows(
        IllegalArgumentException.class, () -> SshPublicKey.parse("ssh-dss AAAAB3Nz comment"));
  }

  @Test
  void rejectsInvalidBase64() {
    assertThrows(
        IllegalArgumentException.class, () -> SshPublicKey.parse("ssh-ed25519 not!base64"));
  }

  @Test
  void rejectsBlobWhoseEmbeddedTypeMismatches() {
    // valid base64, valid key, but relabeled with a different (supported) type
    var realEd25519Blob = SshPublicKey.parse(TestSshKeys.ed25519("x", null)).blob();
    assertThrows(
        IllegalArgumentException.class,
        () -> SshPublicKey.parse("ssh-rsa " + realEd25519Blob + " spoofed"));
  }
}
