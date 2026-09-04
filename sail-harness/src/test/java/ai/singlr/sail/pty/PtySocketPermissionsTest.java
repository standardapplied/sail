/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class PtySocketPermissionsTest {

  @TempDir Path dir;

  /**
   * The pty socket is the identity boundary's only door: a blank-token connection resolves to the
   * box owner, so any local process that can open the socket would act as the owner. The socket and
   * the directory holding it must therefore be owner-only — no group or other access.
   */
  @Test
  void theSocketAndItsDirectoryAreOwnerOnly() throws IOException {
    var sockDir = dir.resolve("run");
    var socket = sockDir.resolve("pty.sock");
    try (var host =
        new PtySessionHost(
            socket,
            dir.resolve("sessions"),
            64 * 1024,
            token -> new PtyIdentity("uday", true),
            PtyRooms.NONE,
            PtyEvents.NONE,
            "0.0.0-test")) {
      host.start();

      assertGroupAndOtherDenied(Files.getPosixFilePermissions(socket), "socket");
      assertGroupAndOtherDenied(Files.getPosixFilePermissions(sockDir), "socket directory");
      var credential = PtySessionHost.dispatchCredentialOf(socket);
      assertGroupAndOtherDenied(Files.getPosixFilePermissions(credential), "dispatch credential");
      assertEquals(64, Files.readString(credential).length(), "256 bits of hex");
    }
    assertFalse(
        Files.exists(PtySessionHost.dispatchCredentialOf(socket)),
        "a closed host leaves no credential behind");
  }

  private static void assertGroupAndOtherDenied(Set<PosixFilePermission> perms, String what) {
    for (var perm : PosixFilePermission.values()) {
      if (perm.name().startsWith("GROUP") || perm.name().startsWith("OTHERS")) {
        assertFalse(perms.contains(perm), what + " must not grant " + perm);
      }
    }
    assertTrue(perms.contains(PosixFilePermission.OWNER_READ), what + " stays owner-readable");
  }
}
