/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConnectCommandTest {

  @Test
  void snippetUsesTheProjectsContainerUserNotAHardcodedDev() {
    var snippet =
        ConnectCommand.connectSnippet(
            "10.0.0.1", "uday", "acme", "10.1.1.5", "engineer", "~/.ssh/id_ed25519");

    assertTrue(snippet.contains("User engineer"), "container ssh user from the definition");
    assertTrue(snippet.contains("zed ssh://engineer@acme/home/engineer/workspace"));
    assertFalse(snippet.contains("dev@"), "no hardcoded dev user");
  }

  @Test
  void snippetPointsIdentityFileAtTheRegisteredKey() {
    var snippet =
        ConnectCommand.connectSnippet(
            "10.0.0.1", "uday", "acme", "10.1.1.5", "engineer", "~/.ssh/id_rsa");

    assertTrue(snippet.contains("IdentityFile ~/.ssh/id_rsa"), "uses the derived identity file");
    assertFalse(snippet.contains("id_ed25519"), "no hardcoded ed25519 when another key is set");
  }

  @Test
  void jsonReportsTheResolvedContainerUserAndKeyStatus() {
    var map =
        ConnectCommand.connectJson(
            "acme", "10.0.0.1", "uday", "10.1.1.5", "engineer", "~/.ssh/id_ed25519", false);
    assertEquals("engineer", map.get("container_user"));
    assertEquals("acme", map.get("project"));
    assertEquals("~/.ssh/id_ed25519", map.get("identity_file"));
    assertEquals(false, map.get("workstation_key_set"));
  }
}
