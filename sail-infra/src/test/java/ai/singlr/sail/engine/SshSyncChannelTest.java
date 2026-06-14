/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SshSyncChannelTest {

  @Test
  void sshCommandTargetsTheGatewayAndRunsTheSyncServer() {
    var command = SshSyncChannel.sshCommand("sail@maindevbox");

    assertEquals("ssh", command.getFirst());
    assertEquals(List.of("sail", "_sync"), command.subList(command.size() - 2, command.size()));
    assertTrue(command.contains("sail@maindevbox"));
  }

  @Test
  void sshCommandForbidsPasswordAndKeyboardInteractiveAuth() {
    var command = SshSyncChannel.sshCommand("sail@host");

    assertTrue(command.contains("PasswordAuthentication=no"));
    assertTrue(command.contains("KbdInteractiveAuthentication=no"));
  }
}
