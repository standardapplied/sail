/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import ai.singlr.sail.config.ProjectDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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

  /**
   * Skips the test where no incus daemon answers, and otherwise makes this machine a box whose
   * sail-api has run: its socket directory exists, which every container's sail machinery mounts.
   * Each test gets it here, so none depends on another having created it first.
   */
  protected void ensureIncusOrSkip() {
    var unreachable = incusUnreachableReason();
    if (unreachable == null) {
      try {
        Files.createDirectories(SailPaths.apiSocketHostDir());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
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

  /** Launches {@code container} from the base image, with {@code options} to incus launch. */
  protected void launch(String container, String... options) throws Exception {
    deleteContainerQuietly(container);
    var command = new ArrayList<>(List.of("incus", "launch", IMAGE, container));
    command.addAll(List.of(options));
    var launched = shell.exec(command);
    assertTrue(
        launched.ok(), "could not launch test container " + IMAGE + ": " + launched.stderr());
  }

  private static final String PREPARED_ALIAS = "sail-it-prepared-v3";
  private static final String BUILDER = "sail-it-image-builder";
  private static final List<String> PREPARED_PACKAGES =
      Stream.concat(
              ProjectProvisioner.BASELINE_PACKAGES.stream(),
              Stream.of("python3", "podman", "uidmap", "sqlite3"))
          .distinct()
          .toList();
  private static final Duration INSTALL_BUDGET = Duration.ofMinutes(10);
  private static final Object PREPARE_LOCK = new Object();
  private static boolean preparedImageReady;
  private static Throwable bakeFailure;

  /**
   * Launches {@code container} from the locally published prepared image — the base image plus
   * every package the incus suite needs, including sail's full provisioning baseline, with {@code
   * options} to incus launch. The alias is versioned: bump it whenever the package set changes so
   * hosts holding an older bake rebuild instead of serving a stale image. The image is baked at
   * most once per host: baking is the only step that touches the network (the public image server,
   * container DNS, the apt archive), each stage is retried and fails naming itself with
   * diagnostics, and every test launch afterwards is a local copy. Tests must never reach the
   * internet from inside their own bodies — a bootstrap that depends on external infrastructure at
   * test time is a defect of the test. A bake that fails fails every later test of the run at once,
   * naming the stage, instead of being retried by each of them until the job's own limit cancels
   * the run.
   */
  protected void launchPrepared(String container, String... options) throws Exception {
    synchronized (PREPARE_LOCK) {
      if (bakeFailure != null) {
        throw new AssertionError("prepared image unavailable: " + bakeFailure.getMessage());
      }
      if (!preparedImageReady) {
        if (!shell.exec(List.of("incus", "image", "show", PREPARED_ALIAS)).ok()) {
          try {
            bakePreparedImage();
          } catch (Exception | AssertionError e) {
            bakeFailure = e;
            throw e;
          }
        }
        preparedImageReady = true;
      }
    }
    deleteContainerQuietly(container);
    var launch = new ArrayList<>(List.of("incus", "launch", PREPARED_ALIAS, container));
    launch.addAll(List.of(options));
    var launched = shell.exec(launch);
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
                    String.join(" ", install)),
                INSTALL_BUDGET));
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

  /** The ZFS pool a spec's workspaces are cloned on in this lane: a file, beside the dir pool. */
  protected static final String SPEC_POOL = "sail-it-specs";

  /**
   * What makes a fresh template a project's in this lane, run as root with each of the project's
   * repos after it: the dev user, lingering so its user manager runs, git's identity, and each repo
   * cloned at {@code /home/dev/workspace/<path>} from a bare origin of its own in {@code /srv},
   * holding one commit on {@code main}, so a workspace's prepare fetches with no network.
   */
  private static final String SEAL_SPEC_TEMPLATE =
      """
      set -eu
      userdel -r ubuntu 2>/dev/null || true
      id -u dev >/dev/null 2>&1 || useradd -m -u 1000 -s /bin/bash dev
      for _ in $(seq 1 30); do loginctl enable-linger dev 2>/dev/null && break; sleep 1; done
      loginctl enable-linger dev
      as_dev() { runuser -u dev -- env HOME=/home/dev "$@"; }
      as_dev git config --global user.email it@sail.invalid
      as_dev git config --global user.name it
      as_dev mkdir -p /home/dev/.sail /home/dev/workspace
      for repo in "$@"; do
        origin="/srv/$(printf '%s' "$repo" | tr / -).git"
        seed="/tmp/seed-$(printf '%s' "$repo" | tr / -)"
        git init --quiet --bare -b main "$origin"
        chown -R dev:dev "$origin"
        as_dev git init --quiet -b main "$seed"
        echo seed | as_dev tee "$seed/README" >/dev/null
        as_dev git -C "$seed" add README
        as_dev git -C "$seed" commit --quiet -m seed
        as_dev git -C "$seed" push --quiet "$origin" main
        as_dev mkdir -p "$(dirname "/home/dev/workspace/$repo")"
        as_dev git clone --quiet "file://$origin" "/home/dev/workspace/$repo"
      done
      """;

  /** What a test adds to a project's template while it runs, before it is sealed. */
  @FunctionalInterface
  protected interface TemplateSetup {
    void into(String template) throws Exception;
  }

  /**
   * Makes this box one a spec of {@code definition}'s project is dispatched on: a file-backed ZFS
   * pool, {@link #SPEC_POOL}, beside the box's dir pool, and the project's template, launched on it
   * from the prepared image, made the project's ({@link #SEAL_SPEC_TEMPLATE}), given what {@code
   * setup} adds, its sail machinery installed, stopped, recorded current at {@code v1} from the
   * definition as a dispatch reads it, and snapshotted: what every run of a spec is cloned from.
   */
  protected void specBox(ProjectDefinition definition, TemplateSetup setup) throws Exception {
    removeSpecBox(definition.name());
    var made = new IncusStorage(shell).create(SPEC_POOL, new PoolSource.File(10240));
    assertTrue(made.ok(), "Incus could not make a file-backed zfs pool: " + made.reason());
    var template = WorkspaceTemplate.nameOf(definition.name());
    launchPrepared(template, "--storage", SPEC_POOL);
    var seal = new ArrayList<>(List.of("bash", "-c", SEAL_SPEC_TEMPLATE, "seal"));
    for (var repo :
        Objects.requireNonNullElse(definition.repos(), List.<ProjectDefinition.Repo>of())) {
      seal.add(repo.path());
    }
    var sealed = exec(template, seal);
    assertTrue(sealed.ok(), "could not seal the template: " + sealed.stderr() + sealed.stdout());
    setup.into(template);
    ContainerSailSetup.ensureInstalled(shell, template);
    new ContainerManager(shell).stop(template);
    var recorded =
        shell.exec(
            List.of(
                "incus",
                "config",
                "set",
                template,
                BoxInstances.TEMPLATE_OF_KEY + "=" + definition.name(),
                WorkspaceTemplate.CURRENT_KEY + "=v1",
                WorkspaceTemplate.INPUTS_KEY
                    + "="
                    + ProjectProvisioner.inputs(definition, WorkstationIdentity.registered())));
    assertTrue(recorded.ok(), recorded.reason());
    new SnapshotManager(shell).create(template, "v1");
  }

  /** Removes every workspace and the template of {@code project}, then {@link #SPEC_POOL}. */
  protected void removeSpecBox(String project) {
    try {
      for (var instance : new BoxInstances(shell).all()) {
        if (instance.kind() instanceof BoxInstances.Workspace workspace
            && workspace.project().equals(project)) {
          deleteContainerQuietly(instance.name());
        }
      }
    } catch (Exception unlisted) {
      System.err.println("  [it] could not list the box's instances: " + unlisted.getMessage());
    }
    deleteContainerQuietly(WorkspaceTemplate.nameOf(project));
    try {
      shell.exec(List.of("incus", "storage", "delete", SPEC_POOL));
    } catch (Exception ignored) {
    }
  }

  /** The instance spec {@code specId}'s runs of {@code project} live in. */
  protected static String workspaceOf(String project, String specId) {
    return RunPlace.of(project, specId).instance();
  }

  protected ShellExec.Result exec(String container, List<String> argv) throws Exception {
    return shell.exec(execCommand(container, argv));
  }

  /** The bake's package install runs over a public mirror, so it gets its own time budget. */
  private ShellExec.Result exec(String container, List<String> argv, Duration budget)
      throws Exception {
    return shell.exec(execCommand(container, argv), null, budget);
  }

  private static List<String> execCommand(String container, List<String> argv) {
    var command = new ArrayList<>(List.of("incus", "exec", container, "--"));
    command.addAll(argv);
    return command;
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
