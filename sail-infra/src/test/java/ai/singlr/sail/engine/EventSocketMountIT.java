/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validates the control-plane socket's bind mount against a <em>real</em> incus daemon — the
 * boundary no unit test can reach and the source of this project's hardest bugs. Proves the exact
 * property {@code /run} could not guarantee: a host directory bind-mounted to an off-{@code /run}
 * container path is reachable inside the guest and <em>stays</em> reachable across a container
 * restart, deterministically.
 *
 * <p>Skipped (not failed) wherever the incus daemon is absent — local dev runs inside an
 * unprivileged container with no nested daemon, so this only executes in the {@code integration} CI
 * lane on an Ubuntu runner. Self-cleaning: the throwaway container and host directory are always
 * removed.
 */
class EventSocketMountIT {

  private static final String CONTAINER = "sail-it-event-socket";
  private static final String IMAGE = "images:alpine/3.20";
  private static final Path CONTAINER_DIR = Path.of("/var/lib/sail/run");

  private final ShellExec shell = new ShellExecutor(false);

  @Test
  void mountIsReachableInsideAndSurvivesRestart() throws Exception {
    ensureIncusOrSkip();

    var hostDir = Files.createTempDirectory("sail-it-event-socket");
    Files.writeString(hostDir.resolve("marker"), "ok");
    Files.setPosixFilePermissions(hostDir, PosixFilePermissions.fromString("rwxr-xr-x"));
    deleteContainerQuietly();
    try {
      var launched = shell.exec(List.of("incus", "launch", IMAGE, CONTAINER));
      assumeTrue(launched.ok(), "could not launch test container: " + launched.stderr());

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
      deleteContainerQuietly();
      deleteRecursively(hostDir);
    }
  }

  /**
   * Skips when no incus daemon is reachable — except in the dedicated CI lane, which sets {@code
   * -Dsail.it.requireIncus=true}: there an unreachable daemon is a misconfiguration that must fail
   * loudly, never a silent skip that lets the lane pass having validated nothing.
   */
  private void ensureIncusOrSkip() {
    if (incusAvailable()) {
      return;
    }
    if (Boolean.getBoolean("sail.it.requireIncus")) {
      throw new AssertionError(
          "incus is required in this lane (-Dsail.it.requireIncus=true) but is not reachable from"
              + " the test process — the integration test cannot validate anything");
    }
    assumeTrue(false, "incus daemon not available; integration test skipped");
  }

  private boolean incusAvailable() {
    try {
      return shell.exec(List.of("incus", "version")).ok();
    } catch (Exception e) {
      return false;
    }
  }

  private boolean markerReachable() throws Exception {
    return shell
        .exec(
            List.of(
                "incus",
                "exec",
                CONTAINER,
                "--",
                "test",
                "-f",
                CONTAINER_DIR.resolve("marker").toString()))
        .ok();
  }

  private void deleteContainerQuietly() {
    try {
      shell.exec(List.of("incus", "delete", "--force", CONTAINER));
    } catch (Exception ignored) {
    }
  }

  private static void deleteRecursively(Path root) {
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(EventSocketMountIT::deleteQuietly);
    } catch (IOException ignored) {
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }
}
