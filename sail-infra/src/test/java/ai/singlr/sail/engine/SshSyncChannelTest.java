/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SshSyncChannelTest {

  @Test
  void sshCommandTargetsTheGatewayAndRunsTheSyncServer() {
    var command = SshSyncChannel.sshCommand("sail@maindevbox", null);

    assertEquals("ssh", command.getFirst());
    assertEquals(List.of("sail", "_sync"), command.subList(command.size() - 2, command.size()));
    assertTrue(command.contains("sail@maindevbox"));
  }

  @Test
  void sshCommandForbidsPasswordAndKeyboardInteractiveAuth() {
    var command = SshSyncChannel.sshCommand("sail@host", null);

    assertTrue(command.contains("PasswordAuthentication=no"));
    assertTrue(command.contains("KbdInteractiveAuthentication=no"));
  }

  @Test
  void sshCommandWithoutAnIdentityOmitsTheKeyFlags() {
    var command = SshSyncChannel.sshCommand("sail@host", null);

    assertFalse(command.contains("-i"));
    assertFalse(command.contains("IdentitiesOnly=yes"));
  }

  @Test
  void sshCommandPinsTheManagedKeyWhenAnIdentityIsGiven() {
    var command = SshSyncChannel.sshCommand("sail@host", Path.of("/home/dev/.sail/sync_ed25519"));

    assertTrue(command.contains("IdentitiesOnly=yes"));
    var i = command.indexOf("-i");
    assertTrue(i > 0);
    assertEquals("/home/dev/.sail/sync_ed25519", command.get(i + 1));
    assertEquals(List.of("sail", "_sync"), command.subList(command.size() - 2, command.size()));
    assertTrue(command.indexOf("sail@host") < command.indexOf("sail"));
  }
}
