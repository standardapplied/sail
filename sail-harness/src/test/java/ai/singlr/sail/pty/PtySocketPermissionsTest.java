/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            token -> new PtyIdentity.Person(new Actor("uday", Role.ADMIN, Actor.Lane.API)),
            PtyAdmissions.NONE,
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

  /**
   * A host that cannot make its socket's directory owner-only does not start: {@code /tmp}, the
   * system's, whose mode no account but root may change, and which root's run would change, so it
   * runs as no other. The host is not closed, which would delete the dispatch credential's name in
   * a directory that is not its own. An account's own directory always yields to its chmod, so no
   * directory of the test's could stand in.
   */
  @Test
  void aHostThatCannotRestrictItsSocketsDirectoryDoesNotStart() {
    assumeFalse(SailPaths.isRoot(), "root may change /tmp's mode, which this test must not do");
    var socket = Path.of("/tmp", "sail-" + Ids.newId() + ".sock");
    var host =
        new PtySessionHost(
            socket,
            dir.resolve("sessions"),
            64 * 1024,
            token -> new PtyIdentity.Person(new Actor("uday", Role.ADMIN, Actor.Lane.API)),
            PtyAdmissions.NONE,
            PtyEvents.NONE,
            "0.0.0-test");

    assertThrows(IOException.class, host::start);
    assertFalse(Files.exists(socket), "nothing was bound");
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
