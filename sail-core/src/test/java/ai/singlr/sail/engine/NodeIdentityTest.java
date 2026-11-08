/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * NodeIdentity reads the shared {@code host.yaml}; the test asserts the total, environment-tolerant
 * contract: a config is always returned (unset when no file is present) and {@code handle()} is
 * exactly that config's handle.
 */
class NodeIdentityTest {

  @Test
  void configIsAlwaysPresentAndHandleMirrorsIt() {
    var config = NodeIdentity.config();

    assertNotNull(config);
    assertEquals(config.handle(), NodeIdentity.handle());
  }

  @Test
  void aBoxNarratesSlackWhenItIsMainOrStandaloneAndNeverOnAFileItCannotReadWhole(@TempDir Path dir)
      throws IOException {
    var hostYaml = dir.resolve("host.yaml");
    assertTrue(NodeIdentity.narrates(hostYaml), "a box with no host.yaml was never made a node");

    Files.writeString(hostYaml, "");
    assertFalse(NodeIdentity.narrates(hostYaml), "an empty file is one being written");

    Files.writeString(hostYaml, "sync: [half");
    assertFalse(NodeIdentity.narrates(hostYaml), "a torn file posts nothing");

    Files.writeString(hostYaml, "sync:\n  role: node\n  main: sail@main\n  handle: eng\n");
    assertFalse(NodeIdentity.narrates(hostYaml), "a node's work is announced by main");

    Files.writeString(hostYaml, "sync:\n  role: main\n  handle: uday\n");
    assertTrue(NodeIdentity.narrates(hostYaml));
  }
}
