/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A project's template: the instance {@code <project>--tmpl}, launched with {@value
 * BoxInstances#TEMPLATE_OF_KEY} on the box's ZFS pool and built by the project's own provisioner,
 * then stopped and snapshotted once per version, {@code v<n>}, which its workspaces clone. A
 * version is current only once the build, the stop and the snapshot are done and it is recorded
 * (W7); a stale template is converged on its disk, as {@code project apply} converges a container,
 * not built again from nothing.
 */
public final class WorkspaceTemplate {

  static final String CURRENT_KEY = "user.sail.template.current";
  static final String INPUTS_KEY = "user.sail.template.inputs";
  static final String LAST_KEY = "user.sail.template.last";

  private static final int MAX_INSTANCE_NAME = 63;
  private static final Pattern VERSION = Pattern.compile("v([1-9][0-9]{0,8})");

  /**
   * Provisions the project's template into the instance it is handed, {@code fresh} when that
   * instance does not exist yet, so no progress recorded for an earlier one is resumed.
   */
  @FunctionalInterface
  public interface Build {
    void into(ProjectProvisioner.Instance template, boolean fresh) throws Exception;
  }

  /**
   * What a project's template is built from on this box: the base profile's pool ({@code host.yaml}
   * names it), the digest of the definition and key a build provisions from ({@link
   * ProjectProvisioner#inputs}), whether the catalog holds a project by a name, and the build.
   */
  public record Source(String basePool, String inputs, Predicate<String> held, Build build) {
    public Source {
      Objects.requireNonNull(basePool, "basePool");
      Objects.requireNonNull(inputs, "inputs");
      Objects.requireNonNull(held, "held");
      Objects.requireNonNull(build, "build");
    }
  }

  /**
   * A project's template as it stands: its instance, the version workspaces clone, the inputs it
   * was built from, the pool it is on, and whether this call built it.
   */
  public record Template(
      String project, String instance, String version, String inputs, String pool, boolean built) {}

  private final ShellExec observer;
  private final ShellExec shell;
  private final Path locks;

  /**
   * @param observer reads Incus, always for real
   * @param shell makes every change, narrating it instead in a dry run
   * @param locks the box's lock directory, where a build holds its template's lock
   */
  public WorkspaceTemplate(ShellExec observer, ShellExec shell, Path locks) {
    this.observer = Objects.requireNonNull(observer, "observer");
    this.shell = Objects.requireNonNull(shell, "shell");
    this.locks = Objects.requireNonNull(locks, "locks");
  }

  /** The name of project {@code project}'s template instance. */
  public static String nameOf(String project) {
    return project + BoxInstances.TEMPLATE_SUFFIX;
  }

  /**
   * Builds {@code config}'s template unless its current version was built from {@code source}'s
   * inputs, which leaves it as it is.
   */
  public Template ensure(ProjectDefinition config, Source source) throws Exception {
    NameValidator.requireValidProjectName(config.name());
    var template = requireName(config.name(), source);
    var zfs = requireZfs(observer);
    return ensure(config, source, template, zfs, new BoxInstances(observer).all());
  }

  /**
   * As {@link #ensure(ProjectDefinition, Source)}, from {@code instances}, what the box holds, and
   * {@code zfs}, its ZFS pools: an existing template stays on its pool, a new one goes on the base
   * profile's pool when it is ZFS, else on the one ZFS pool beside it.
   */
  Template ensure(
      ProjectDefinition config,
      Source source,
      String template,
      List<String> zfs,
      List<BoxInstances.Instance> instances)
      throws Exception {
    var found = instances.stream().filter(i -> i.name().equals(template)).findFirst();
    if (found.isPresent()
        && !(found.get().kind() instanceof BoxInstances.Template(var of)
            && of.equals(config.name()))) {
      throw new IllegalStateException(
          "Instance '"
              + template
              + "' carries no "
              + BoxInstances.TEMPLATE_OF_KEY
              + " of project "
              + config.name()
              + ", so it is not "
              + config.name()
              + "'s template and nothing is built into it.");
    }
    var pool = found.isPresent() ? found.get().pool() : choose(zfs, source.basePool());
    if (!zfs.contains(pool)) {
      throw Remedied.state(
          template
              + " is on pool '"
              + pool
              + "', which is not ZFS, so a copy of it would be a full copy, not a clone.",
          Remedy.of(
              "Delete it so the next create builds it on ZFS: ",
              Remedy.program("incus", "delete", "--force", template)));
    }
    if (shell.isDryRun()) {
      return buildIfStale(config, source, template, pool, found);
    }
    try (var held = ContainerConfig.hold(locks, template)) {
      return buildIfStale(config, source, template, pool, found);
    }
  }

  private Template buildIfStale(
      ProjectDefinition config,
      Source source,
      String template,
      String pool,
      Optional<BoxInstances.Instance> found)
      throws Exception {
    var project = config.name();
    var versions =
        found.isPresent()
            ? new SnapshotManager(observer)
                .list(template).stream()
                    .map(SnapshotManager.SnapshotInfo::name)
                    .map(VERSION::matcher)
                    .filter(Matcher::matches)
                    .map(matcher -> Integer.parseInt(matcher.group(1)))
                    .toList()
            : List.<Integer>of();
    var current = found.flatMap(i -> i.key(CURRENT_KEY));
    if (current.isPresent()
        && found.get().key(INPUTS_KEY).equals(Optional.of(source.inputs()))
        && versions.stream().anyMatch(n -> current.get().equals("v" + n))) {
      return new Template(project, template, current.get(), source.inputs(), pool, false);
    }
    var given = found.flatMap(i -> i.key(LAST_KEY)).map(VERSION::matcher).filter(Matcher::matches);
    var version =
        "v"
            + (Stream.concat(
                        versions.stream(),
                        given.map(matcher -> Integer.parseInt(matcher.group(1))).stream())
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(0)
                + 1);
    if (found.isPresent() && !(found.get().state() instanceof ContainerState.Running)) {
      new ContainerManager(shell).start(template);
    }
    source
        .build()
        .into(
            new ProjectProvisioner.Instance(
                template, Optional.of(pool), Map.of(BoxInstances.TEMPLATE_OF_KEY, project)),
            found.isEmpty());
    new ContainerManager(shell).stop(template);
    var snapshots = new SnapshotManager(shell);
    snapshots.create(template, version);
    var recorded =
        new ContainerConfig(shell, locks)
            .set(
                template,
                List.of(
                    CURRENT_KEY + "=" + version,
                    INPUTS_KEY + "=" + source.inputs(),
                    LAST_KEY + "=" + version));
    if (!recorded.ok()) {
      var failure =
          new IOException(
              "Failed to record "
                  + version
                  + " as "
                  + project
                  + "'s current template, so it was removed and the version before stays current: "
                  + recorded.reason());
      try {
        snapshots.delete(template, version);
      } catch (IOException | TimeoutException removal) {
        failure.addSuppressed(removal);
      }
      throw failure;
    }
    return new Template(project, template, version, source.inputs(), pool, true);
  }

  /**
   * The ZFS pools Incus has; refused before anything changes on a box with none (W8), naming the
   * command that adds one.
   */
  static List<String> requireZfs(ShellExec observer)
      throws IOException, InterruptedException, TimeoutException {
    var zfs =
        new IncusStorage(observer)
            .pools().stream()
                .filter(pool -> HostYaml.BACKEND_ZFS.equals(pool.driver()))
                .map(IncusStorage.Pool::name)
                .toList();
    if (zfs.isEmpty()) {
      throw Remedied.state(
          "This box has no ZFS pool, and a workspace is a ZFS clone of its project's template.",
          Remedy.of(
              "Add one beside the box's pool with: ",
              Remedy.sail("host init", "--storage", "zfs", "--pool", "tank", "--disk", "<device>"),
              ", or with --pool-file-size <size> in place of --disk to try ZFS in a file."));
    }
    return zfs;
  }

  /** {@code project}'s template instance name, when it is one Incus can have and no project's. */
  static String requireName(String project, Source source) {
    var template = nameOf(project);
    requireLength(template, project + "'s template");
    if (source.held().test(template)) {
      throw new IllegalArgumentException(
          project
              + "'s template would be the instance '"
              + template
              + "', which is the name of project '"
              + template
              + "' on this box, so "
              + project
              + " has no workspaces here.");
    }
    return template;
  }

  /** Refuses {@code instance}, {@code what}'s name, when Incus cannot name an instance so. */
  static void requireLength(String instance, String what) {
    if (instance.length() > MAX_INSTANCE_NAME) {
      throw new IllegalArgumentException(
          what
              + " would be the instance '"
              + instance
              + "', "
              + instance.length()
              + " characters, and Incus names an instance in at most "
              + MAX_INSTANCE_NAME
              + ". Use a shorter name.");
    }
  }

  private static String choose(List<String> zfs, String basePool) {
    if (zfs.contains(basePool)) {
      return basePool;
    }
    if (zfs.size() == 1) {
      return zfs.getFirst();
    }
    throw Remedied.state(
        "Incus has the ZFS pools "
            + String.join(", ", zfs)
            + " and the base profile's pool '"
            + basePool
            + "' is none of them, so Sail cannot tell which one workspaces go on.",
        Remedy.of(
            "Keep one ZFS pool beside the base profile's, or put the base profile on the one to"
                + " use."));
  }
}
