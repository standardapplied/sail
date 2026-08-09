/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Validates the content-addressed converge against a real container: a {@code spec} script pointing
 * at a stale socket path is fully rewritten and stamped on the next {@link
 * ContainerSailSetup#ensureInstalled} — even when the machinery stamp still matches, because the
 * probe verifies observed file contents, never the stamp alone — and a stamped, current container
 * converges as a no-op without an installer running.
 */
class StaleScriptRewriteIT extends AbstractIncusIT {

  private static final String CONTAINER = "sail-it-stale-script";

  @Test
  void anOldShapeContainerConvergesAndACurrentOneIsANoOp() throws Exception {
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

      var first = ContainerSailSetup.ensureInstalled(shell, CONTAINER);
      assertEquals(ContainerSailSetup.Result.UPDATED, first, "first touch installs and stamps");
      assertTrue(
          specScript().contains(currentPath), "a fresh install writes the current socket path");

      var second = ContainerSailSetup.ensureInstalled(shell, CONTAINER);
      assertEquals(
          ContainerSailSetup.Result.ALREADY_PRESENT,
          second,
          "a stamped, current container converges as a no-op");

      var corrupt =
          exec(
              CONTAINER,
              List.of(
                  "bash",
                  "-c",
                  "sed -i 's|"
                      + currentPath
                      + "|/run/sail/api.sock|' "
                      + SpecCliHelper.SCRIPT_PATH));
      assertTrue(corrupt.ok(), "could not stage the tampered container: " + corrupt.stderr());
      assertTrue(specScript().contains("/run/sail/api.sock"), "the script is now stale (old path)");
      var stamp = exec(CONTAINER, List.of("cat", ContainerSailSetup.STAMP_PATH));
      assertEquals(
          ContainerSailSetup.fingerprint(),
          stamp.stdout().strip(),
          "the stamp still matches — only the script content betrays the tampering");

      var heal = ContainerSailSetup.ensureInstalled(shell, CONTAINER);

      assertEquals(
          ContainerSailSetup.Result.UPDATED,
          heal,
          "a tampered payload reinstalls everything even though the stamp matches");
      var rewritten = specScript();
      assertTrue(
          rewritten.contains(currentPath),
          "apply must rewrite a script still on the old path to the current one");
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
