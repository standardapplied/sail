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
import java.util.Optional;

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

  /** The bound of a setup step that launches a container. */
  protected static final Duration LAUNCH_BOUND = Duration.ofSeconds(120);

  /** The bound of a setup step that makes a storage pool. */
  protected static final Duration POOL_BOUND = Duration.ofSeconds(180);

  /** The bound of any other setup step: a seal, an install, a stop, a snapshot, a cleanup. */
  protected static final Duration STEP_BOUND = Duration.ofSeconds(120);

  private final SetupSteps steps =
      new SetupSteps(new NoAddressEvidence(new ShellExecutor(false)), System.out, System::nanoTime);

  /**
   * This host's Incus, keeping evidence of a make that gets no address ({@link NoAddressEvidence}),
   * each command in a setup step held to that step's bound ({@link #step}).
   */
  protected final ShellExec shell = steps;

  /** One step of a test's setup. */
  @FunctionalInterface
  protected interface Action {
    void run() throws Exception;
  }

  /**
   * Runs {@code action} as the setup step {@code name}: said as it starts and ends, with the time,
   * and failed naming the command it waits for once it has run {@code bound} ({@link SetupSteps}).
   */
  protected void step(String name, Duration bound, Action action) throws Exception {
    steps.step(
        name,
        bound,
        () -> {
          action.run();
          return null;
        });
  }

  /**
   * Skips the test where no incus daemon answers, and otherwise makes this machine a box whose
   * sail-api has run: its socket directory exists, which every container's sail machinery mounts.
   * Each test gets it here, so none depends on another having created it first.
   */
  protected void ensureIncusOrSkip() {
    String[] reason = {null};
    try {
      step("ask Incus whether it answers", STEP_BOUND, () -> reason[0] = incusUnreachableReason());
    } catch (Exception e) {
      reason[0] = e.getClass().getSimpleName() + ": " + e.getMessage();
    }
    var unreachable = reason[0];
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
    step(
        "launch " + container + " from " + IMAGE,
        LAUNCH_BOUND,
        () -> {
          deleteContainerQuietly(container);
          var command = new ArrayList<>(List.of("incus", "launch", IMAGE, container));
          command.addAll(List.of(options));
          var launched = shell.exec(command);
          assertTrue(
              launched.ok(), "could not launch test container " + IMAGE + ": " + launched.stderr());
        });
  }

  private static final String PREPARED_ALIAS = "sail-it-prepared-v3";
  private static final Object PREPARE_LOCK = new Object();
  private static boolean preparedImageReady;

  /**
   * Launches {@code container} from the locally published prepared image — the base image plus
   * every package the incus suite needs, including sail's full provisioning baseline, with {@code
   * options} to incus launch. The alias is versioned: bump it whenever the package set changes so
   * hosts holding an older bake rebuild instead of serving a stale image. The image is baked before
   * any test, by {@code ci/images prepared-image bake}, which the incus action runs on a cache
   * miss: the bake is the only step of the lane that reaches an external host for packages, and a
   * bake that fails stops the job there, once. A host without the image fails every test at once,
   * naming the bake, instead of reaching the internet from a test's body.
   */
  protected void launchPrepared(String container, String... options) throws Exception {
    step(
        "launch " + container + " from " + PREPARED_ALIAS,
        LAUNCH_BOUND,
        () -> {
          synchronized (PREPARE_LOCK) {
            if (!preparedImageReady) {
              assertTrue(
                  shell.exec(List.of("incus", "image", "show", PREPARED_ALIAS)).ok(),
                  "the prepared image "
                      + PREPARED_ALIAS
                      + " is not on this host: bake it with ci/images prepared-image bake");
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
        });
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
    step(
        "remove " + definition.name() + "'s spec box leftovers",
        STEP_BOUND,
        () -> removeSpecBox(definition.name()));
    step(
        "make the file-backed zfs pool " + SPEC_POOL,
        POOL_BOUND,
        () -> {
          var made = new IncusStorage(shell).create(SPEC_POOL, new PoolSource.File(10240));
          assertTrue(made.ok(), "Incus could not make a file-backed zfs pool: " + made.reason());
        });
    var template = WorkspaceTemplate.nameOf(definition.name());
    launchPrepared(template, "--storage", SPEC_POOL);
    step(
        "seal " + template,
        STEP_BOUND,
        () -> {
          var seal = new ArrayList<>(List.of("bash", "-c", SEAL_SPEC_TEMPLATE, "seal"));
          for (var repo :
              Objects.requireNonNullElse(definition.repos(), List.<ProjectDefinition.Repo>of())) {
            seal.add(repo.path());
          }
          var sealed = exec(template, seal);
          assertTrue(
              sealed.ok(), "could not seal the template: " + sealed.stderr() + sealed.stdout());
        });
    step("the test's own setup of " + template, STEP_BOUND, () -> setup.into(template));
    step(
        "install sail's machinery in " + template,
        STEP_BOUND,
        () -> ContainerSailSetup.ensureInstalled(shell, template));
    step("stop " + template, STEP_BOUND, () -> new ContainerManager(shell).stop(template));
    step(
        "record " + template + " current at v1",
        STEP_BOUND,
        () -> {
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
                          + ProjectProvisioner.inputs(definition, Optional.empty())));
          assertTrue(recorded.ok(), recorded.reason());
        });
    step(
        "snapshot " + template + " v1",
        STEP_BOUND,
        () -> new SnapshotManager(shell).create(template, "v1"));
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

  private static List<String> execCommand(String container, List<String> argv) {
    var command = new ArrayList<>(List.of("incus", "exec", container, "--"));
    command.addAll(argv);
    return command;
  }

  /**
   * Deletes {@code container}, saying nothing when there is none; a delete that runs out of time
   * fails, naming it, as a hung Incus is no leftover to step over.
   */
  protected void deleteContainerQuietly(String container) {
    try {
      shell.exec(List.of("incus", "delete", "--force", container));
    } catch (CommandTimedOut timedOut) {
      throw new AssertionError("could not delete " + container + ": " + timedOut.getMessage());
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
