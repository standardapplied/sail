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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared plumbing for integration tests that drive a real incus daemon — the boundary unit tests
 * (mocked shell) cannot reach and that cannot run locally (dev is inside an unprivileged incus
 * container with no nested daemon). Subclasses are {@code *IT.java}, run by maven-failsafe only
 * under the {@code integration} profile, which sets {@code sail.it.requireIncus=true}.
 *
 * <p>{@link #ensureIncusOrSkip} skips where no daemon is reachable, except in that lane, where an
 * unreachable daemon fails loudly with the exact reason rather than silently passing. Every real
 * step is a hard assertion — an {@code assumeTrue} on a launch or exec would be a false-green trap.
 */
public abstract class AbstractIncusIT {

  protected static final String IMAGE = "images:ubuntu/24.04";

  protected final ShellExec shell = new ShellExecutor(false);

  protected void ensureIncusOrSkip() {
    var unreachable = incusUnreachableReason();
    if (unreachable == null) {
      return;
    }
    if (Boolean.getBoolean("sail.it.requireIncus")) {
      throw new AssertionError(
          "incus is required in this lane (-Dsail.it.requireIncus=true) but is not reachable from"
              + " the test process — the integration test cannot validate anything. Reason: "
              + unreachable);
    }
    assumeTrue(false, "incus daemon not available; integration test skipped (" + unreachable + ")");
  }

  /** {@code null} when {@code incus version} succeeds; otherwise why it did not. */
  private String incusUnreachableReason() {
    try {
      var result = shell.exec(List.of("incus", "version"));
      return result.ok() ? null : "`incus version` exit non-zero: " + result.stderr().strip();
    } catch (Exception e) {
      return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  protected void launch(String container) throws Exception {
    deleteContainerQuietly(container);
    var launched = shell.exec(List.of("incus", "launch", IMAGE, container));
    assertTrue(
        launched.ok(), "could not launch test container " + IMAGE + ": " + launched.stderr());
  }

  protected ShellExec.Result exec(String container, List<String> argv) throws Exception {
    var command = new ArrayList<>(List.of("incus", "exec", container, "--"));
    command.addAll(argv);
    return shell.exec(command);
  }

  protected void deleteContainerQuietly(String container) {
    try {
      shell.exec(List.of("incus", "delete", "--force", container));
    } catch (Exception ignored) {
    }
  }

  protected static void deleteRecursively(Path root) {
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(AbstractIncusIT::deleteQuietly);
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
