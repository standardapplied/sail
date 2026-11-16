/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import ai.singlr.sail.engine.AbstractIncusIT;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.IncusDeviceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A scoped node's round against a real Incus daemon: the container Sail launched for a project the
 * scope does not admit, carrying its project's key, is deleted, and one Sail did not launch is left
 * alone, though it carries Sail's API-socket device.
 */
class ScopeTeardownIT extends AbstractIncusIT {

  private static final String PROVISIONED = "sail-it-scope-gone";
  private static final String FOREIGN = "sail-it-scope-kept";

  @TempDir Path tempDir;

  @Test
  void aRoundDeletesTheContainerSailProvisionedForAProjectOutOfScopeAndNoOther() throws Exception {
    ensureIncusOrSkip();
    var socketDir = Files.createTempDirectory("sail-it-scope-socket");
    Files.setPosixFilePermissions(socketDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    try {
      launch(PROVISIONED, "-c", BoxInstances.PROJECT_KEY + "=" + PROVISIONED);
      launch(FOREIGN);
      new IncusDeviceManager(shell)
          .ensureEventSocket(FOREIGN, socketDir, Path.of("/var/lib/sail/run"));
      Files.createDirectories(tempDir.resolve("projects").resolve(PROVISIONED));

      try (var node = new ScopedNode(tempDir, "acme", shell)) {
        node.round();
      }

      var containers = new ContainerManager(shell);
      assertInstanceOf(ContainerState.NotCreated.class, containers.queryState(PROVISIONED));
      assertInstanceOf(ContainerState.Running.class, containers.queryState(FOREIGN));
      assertFalse(Files.exists(tempDir.resolve("projects").resolve(PROVISIONED)));
    } finally {
      deleteContainerQuietly(PROVISIONED);
      deleteContainerQuietly(FOREIGN);
      deleteRecursively(socketDir);
    }
  }
}
