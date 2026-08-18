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
import java.util.stream.Stream;

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

  private static final String PREPARED_ALIAS = "sail-it-prepared-v2";
  private static final String BUILDER = "sail-it-image-builder";
  private static final List<String> PREPARED_PACKAGES =
      Stream.concat(
              ProjectProvisioner.BASELINE_PACKAGES.stream(),
              Stream.of("python3", "podman", "uidmap"))
          .distinct()
          .toList();
  private static final Object PREPARE_LOCK = new Object();
  private static boolean preparedImageReady;

  /**
   * Launches {@code container} from the locally published prepared image — the base image plus
   * every package the incus suite needs, including sail's full provisioning baseline. The alias is
   * versioned: bump it whenever the package set changes so hosts holding an older bake rebuild
   * instead of serving a stale image. The image is baked at most once per host: baking is the only
   * step that touches the network (the public image server, container DNS, the apt archive), each
   * stage is retried and fails naming itself with diagnostics, and every test launch afterwards is
   * a local copy. Tests must never reach the internet from inside their own bodies — a bootstrap
   * that depends on external infrastructure at test time is a defect of the test.
   */
  protected void launchPrepared(String container) throws Exception {
    synchronized (PREPARE_LOCK) {
      if (!preparedImageReady) {
        if (!shell.exec(List.of("incus", "image", "show", PREPARED_ALIAS)).ok()) {
          bakePreparedImage();
        }
        preparedImageReady = true;
      }
    }
    deleteContainerQuietly(container);
    var launched = shell.exec(List.of("incus", "launch", PREPARED_ALIAS, container));
    assertTrue(
        launched.ok(),
        "could not launch test container from local image "
            + PREPARED_ALIAS
            + ": "
            + launched.stderr());
  }

  private void bakePreparedImage() throws Exception {
    shell.exec(List.of("incus", "delete", "--force", BUILDER));
    retryStage(
        "launch builder container from " + IMAGE,
        () -> shell.exec(List.of("incus", "launch", IMAGE, BUILDER)));
    retryStage(
        "container outbound DNS",
        () ->
            exec(
                BUILDER,
                List.of(
                    "bash",
                    "-c",
                    "for i in $(seq 1 45); do"
                        + " getent hosts archive.ubuntu.com >/dev/null 2>&1 && exit 0; sleep 2;"
                        + " done; echo '--- resolv.conf ---'; cat /etc/resolv.conf;"
                        + " echo '--- addresses ---'; ip -brief addr; exit 1")));
    retryStage("apt-get update", () -> exec(BUILDER, List.of("apt-get", "update", "-qq")));
    var install = new ArrayList<>(List.of("apt-get", "install", "-y", "-qq"));
    install.addAll(PREPARED_PACKAGES);
    retryStage(
        "apt-get install " + String.join(" ", PREPARED_PACKAGES),
        () ->
            exec(
                BUILDER,
                List.of(
                    "env",
                    "DEBIAN_FRONTEND=noninteractive",
                    "bash",
                    "-c",
                    String.join(" ", install))));
    retryStage("stop builder", () -> shell.exec(List.of("incus", "stop", BUILDER)));
    retryStage(
        "publish prepared image",
        () -> shell.exec(List.of("incus", "publish", BUILDER, "--alias", PREPARED_ALIAS)));
    deleteContainerQuietly(BUILDER);
  }

  private void retryStage(String stage, StageCommand command) throws Exception {
    ShellExec.Result last = null;
    for (var attempt = 1; attempt <= 3; attempt++) {
      last = command.run();
      if (last.ok()) {
        return;
      }
    }
    deleteContainerQuietly(BUILDER);
    throw new AssertionError(
        "prepared-image bake failed at stage '"
            + stage
            + "' after 3 attempts: "
            + last.stderr()
            + (last.stdout().isBlank() ? "" : "\nstdout: " + last.stdout()));
  }

  @FunctionalInterface
  protected interface StageCommand {
    ShellExec.Result run() throws Exception;
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
