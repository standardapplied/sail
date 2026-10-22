/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.ssh.SshPublicKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkstationIdentityTest {

  private static final String ED25519 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILDpT0mMcK sumesh@mac";

  @Test
  void identityFileFollowsTheRegisteredKeysAlgorithm() {
    assertEquals(
        "~/.ssh/id_ed25519",
        WorkstationIdentity.identityFileFor(Optional.of(SshPublicKey.parse(ED25519))));
    assertEquals(
        "~/.ssh/id_rsa",
        WorkstationIdentity.identityFileFor(
            Optional.of(new SshPublicKey("ssh-rsa", "", null, ""))));
  }

  @Test
  void identityFileDefaultsToEd25519WhenNoKeyIsRegistered() {
    assertEquals("~/.ssh/id_ed25519", WorkstationIdentity.identityFileFor(Optional.empty()));
  }

  @Test
  void registeredReadsAValidKeyFromDisk(@TempDir Path dir) throws Exception {
    var path = dir.resolve("workstation_key.pub");
    Files.writeString(path, ED25519 + "\n");

    var key = WorkstationIdentity.registeredAt(path);

    assertTrue(key.isPresent());
    assertEquals("ssh-ed25519", key.orElseThrow().type());
  }

  @Test
  void aKeyFileThatCannotBeReadIsRefusedNamingWhy(@TempDir Path dir) throws Exception {
    assumeFalse(SailPaths.isRoot());
    var unreadable = dir.resolve("unreadable.pub");
    Files.writeString(unreadable, ED25519 + "\n");
    Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"));
    try {
      var refused =
          assertThrows(
              IllegalStateException.class, () -> WorkstationIdentity.registeredAt(unreadable));

      assertEquals(
          "This box's workstation key ("
              + unreadable
              + ") could not be read (AccessDeniedException). Check that the account sail runs as"
              + " can read it.",
          refused.getMessage());
    } finally {
      Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("rw-------"));
    }
  }

  @Test
  void aKeyFileThatIsNotTextIsRefusedAsNoKeyNotAsUnreadable(@TempDir Path dir) throws Exception {
    var binary = dir.resolve("binary.pub");
    Files.write(binary, new byte[] {(byte) 0xff, (byte) 0xfe, 'x'});

    var refused =
        assertThrows(IllegalStateException.class, () -> WorkstationIdentity.registeredAt(binary));

    assertTrue(
        refused
            .getMessage()
            .startsWith("This box's workstation key (" + binary + ") is not an SSH public key: "),
        refused.getMessage());
  }

  @Test
  void registeredIsEmptyWhenMissing(@TempDir Path dir) {
    assertTrue(WorkstationIdentity.registeredAt(dir.resolve("absent.pub")).isEmpty());
  }

  @Test
  void aKeyFileThatHoldsNoKeyIsRefusedRatherThanReadAsNone(@TempDir Path dir) throws Exception {
    var garbage = dir.resolve("garbage.pub");
    Files.writeString(garbage, "not a key at all\n");

    var refused =
        assertThrows(IllegalStateException.class, () -> WorkstationIdentity.registeredAt(garbage));

    assertTrue(
        refused
            .getMessage()
            .startsWith(
                "This box's workstation key ("
                    + garbage
                    + ") is not an SSH public key: Unsupported"),
        refused.getMessage());
    assertEquals(
        Remedy.of("Register the key you connect with: ", WorkstationIdentity.SET_KEY),
        ((Remedied) refused).remedy());
    assertTrue(
        OperatorCommand.SUDO
            .render((Remedied) refused)
            .endsWith(
                " Register the key you connect with: sudo sail host config set ssh-public-key"),
        refused.getMessage());
    assertEquals(Optional.empty(), WorkstationIdentity.registeredAt(dir.resolve("none.pub")));
  }
}
