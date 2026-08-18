/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validates the baseline tool guarantee against a real Ubuntu 24.04 container: a freshly prepared
 * container exposes {@code gh} and {@code rg} to the dev user (the bake fails loudly if either apt
 * name stops resolving on the image), and {@link ProjectApplier#applyPackages} restores them
 * additively on a container that predates the baseline — the converge path an upgraded box rides —
 * while a current container converges as a pure skip without touching apt.
 */
class BaselinePackagesIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-baseline";

  @Test
  void baselineToolsPresentFreshAndRestoredByConverge() throws Exception {
    ensureIncusOrSkip();
    try {
      launchPrepared(CONTAINER);
      var dev =
          exec(
              CONTAINER,
              List.of(
                  "bash",
                  "-c",
                  "userdel -r ubuntu 2>/dev/null || true;"
                      + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev"));
      assertTrue(dev.ok(), "the dev user must exist: " + dev.stderr());
      assertToolRuns("gh");
      assertToolRuns("rg");

      var applier = new ProjectApplier(shell, new PrintStream(OutputStream.nullOutputStream()));
      var noop = applier.applyPackages(CONTAINER, null);
      assertEquals(0, noop.added(), "a current container must not reinstall anything");
      assertEquals(
          ProjectProvisioner.BASELINE_PACKAGES.size(),
          noop.skipped(),
          "every baseline package must be detected as present");

      var removed = exec(CONTAINER, List.of("apt-get", "remove", "-y", "-qq", "gh", "ripgrep"));
      assertTrue(removed.ok(), "could not stage the pre-baseline container: " + removed.stderr());

      var converge = applier.applyPackages(CONTAINER, null);

      assertEquals(2, converge.added(), "converge must install exactly the missing baseline tools");
      assertToolRuns("gh");
      assertToolRuns("rg");
    } finally {
      deleteContainerQuietly(CONTAINER);
    }
  }

  private void assertToolRuns(String binary) throws Exception {
    var result = shell.exec(ContainerExec.asDevUser(CONTAINER, List.of(binary, "--version")));
    assertTrue(result.ok(), binary + " --version must succeed as the dev user: " + result.stderr());
  }
}
