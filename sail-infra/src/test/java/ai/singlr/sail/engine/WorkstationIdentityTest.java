/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.ssh.SshPublicKey;
import java.nio.file.Files;
import java.nio.file.Path;
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
  void registeredIsEmptyWhenMissingOrUnparseable(@TempDir Path dir) throws Exception {
    assertTrue(WorkstationIdentity.registeredAt(dir.resolve("absent.pub")).isEmpty());
    var garbage = dir.resolve("garbage.pub");
    Files.writeString(garbage, "not a key at all\n");
    assertTrue(WorkstationIdentity.registeredAt(garbage).isEmpty());
  }
}
