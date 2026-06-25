/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validates the self-heal that 0.13.101 missed: after the socket moved off {@code /run}, a
 * container whose in-container {@code spec} script still points at the old path must be rewritten
 * to the current path on the next {@link ContainerSailSetup#ensureInstalled} — the call {@code
 * migrate} and {@code reconfigure} both make. Exercises a real container so the content-aware probe
 * is proven against an actual stale script, not a mocked shell.
 */
class StaleScriptRewriteIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-stale-script";

  @Test
  void reconfigureRewritesASpecScriptLeftOnTheOldSocketPath() throws Exception {
    ensureIncusOrSkip();

    var currentPath = SailPaths.apiSocketContainerPath().toString();
    Files.createDirectories(SailPaths.apiSocketHostDir());
    try {
      launch(CONTAINER);
      var dev =
          exec(
              CONTAINER,
              List.of(
                  "bash",
                  "-c",
                  "userdel -r ubuntu 2>/dev/null || true;"
                      + " id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev"));
      assertTrue(dev.ok(), "the dev user must exist for the in-container helpers: " + dev.stderr());

      ContainerSailSetup.ensureInstalled(shell, CONTAINER);
      assertTrue(
          specScript().contains(currentPath), "a fresh install writes the current socket path");

      var corrupt =
          exec(
              CONTAINER,
              List.of(
                  "sed",
                  "-i",
                  "s|" + currentPath + "|/run/sail/api.sock|",
                  SpecCliHelper.SCRIPT_PATH));
      assertTrue(corrupt.ok(), "could not stage the stale script: " + corrupt.stderr());
      assertTrue(specScript().contains("/run/sail/api.sock"), "the script is now stale (old path)");

      ContainerSailSetup.ensureInstalled(shell, CONTAINER);

      var rewritten = specScript();
      assertTrue(
          rewritten.contains(currentPath),
          "reconfigure must rewrite a script still on the old path to the current one");
      assertFalse(
          rewritten.contains("/run/sail/api.sock"),
          "no stale /run path may remain after the rewrite");
    } finally {
      deleteContainerQuietly(CONTAINER);
    }
  }

  private String specScript() throws Exception {
    return exec(CONTAINER, List.of("cat", SpecCliHelper.SCRIPT_PATH)).stdout();
  }
}
