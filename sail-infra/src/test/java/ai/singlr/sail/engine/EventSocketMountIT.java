/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validates the control-plane socket's bind mount against a <em>real</em> incus daemon. Proves the
 * exact property {@code /run} could not guarantee: a host directory bind-mounted to an off-{@code
 * /run} container path is reachable inside the guest and <em>stays</em> reachable across a
 * container restart, deterministically. Self-cleaning: the throwaway container and host directory
 * are always removed.
 */
class EventSocketMountIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-event-socket";
  private static final Path CONTAINER_DIR = Path.of("/var/lib/sail/run");

  @Test
  void mountIsReachableInsideAndSurvivesRestart() throws Exception {
    ensureIncusOrSkip();

    var hostDir = Files.createTempDirectory("sail-it-event-socket");
    Files.writeString(hostDir.resolve("marker"), "ok");
    Files.setPosixFilePermissions(hostDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    try {
      launch(CONTAINER);

      new IncusDeviceManager(shell).ensureEventSocket(CONTAINER, hostDir, CONTAINER_DIR);

      assertTrue(
          markerReachable(),
          "the bind-mounted marker must be reachable inside the container off /run");

      var restart = shell.exec(List.of("incus", "restart", CONTAINER));
      assertTrue(restart.ok(), "container restart failed: " + restart.stderr());

      assertTrue(
          markerReachable(),
          "the mount must survive a container restart deterministically — the /run failure mode");
    } finally {
      deleteContainerQuietly(CONTAINER);
      deleteRecursively(hostDir);
    }
  }

  private boolean markerReachable() throws Exception {
    return exec(CONTAINER, List.of("test", "-f", CONTAINER_DIR.resolve("marker").toString())).ok();
  }
}
