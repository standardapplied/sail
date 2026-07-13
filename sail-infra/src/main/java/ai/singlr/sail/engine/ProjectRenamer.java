/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Renames a project on this box — re-keying every place its name is the identity: the catalog, its
 * specs and shared files, the on-disk state directory, the Incus container, and the guest hostname
 * the in-container {@code spec} CLI reads. A purely local operation: it emits no sync rename event,
 * so other boxes do not learn of it through {@code sail sync} (the caller surfaces that to the
 * engineer).
 *
 * <p>The mutating steps run in an order whose every prior step has a compensation, recorded on a
 * stack as it succeeds; any failure unwinds them in reverse so a half-done rename never corrupts
 * state. Once the rename has committed, the container is brought up and given time to become ready
 * (its dev-user session bus, which {@code incus start} does not wait for) before the finishing
 * touches (guest hostname, re-wiring the sail plumbing, regenerating agent context) run against the
 * running instance, which is then stopped again only if it started out stopped. These are
 * best-effort — a failure there is reported and recovered with {@code sail project reconfigure},
 * not rolled back.
 */
public final class ProjectRenamer {

  @FunctionalInterface
  private interface Compensation {
    void run() throws Exception;
  }

  /**
   * Outcome of a rename: the names, whether a container was renamed, and any best-effort warnings.
   */
  public record Result(String from, String to, boolean containerRenamed, List<String> warnings) {
    public Result {
      warnings = List.copyOf(warnings);
    }
  }

  private final Sqlite db;
  private final ShellExec shell;
  private final Path projectsDir;

  public ProjectRenamer(Sqlite db, ShellExec shell, Path projectsDir) {
    this.db = Objects.requireNonNull(db, "db");
    this.shell = Objects.requireNonNull(shell, "shell");
    this.projectsDir = Objects.requireNonNull(projectsDir, "projectsDir");
  }

  public Result rename(String old, String renamed) throws Exception {
    NameValidator.requireValidProjectName(old);
    NameValidator.requireValidProjectName(renamed);
    if (old.equals(renamed)) {
      throw new IllegalArgumentException("'" + renamed + "' is already the project's name.");
    }

    var projects = new ProjectStore(db);
    var specs = new SpecStore(db);
    var files = new FileStore(db);
    var containers = new ContainerManager(shell);

    var existing =
        projects
            .findByName(old)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No project '" + old + "' in the catalog to rename."));
    if (projects.findByName(renamed).isPresent()) {
      throw new IllegalStateException("A project named '" + renamed + "' already exists.");
    }
    if (!(containers.queryState(renamed) instanceof ContainerState.NotCreated)) {
      throw new IllegalStateException("A container named '" + renamed + "' already exists.");
    }

    var oldState = containers.queryState(old);
    if (oldState instanceof ContainerState.Error error) {
      throw new IllegalStateException("Cannot inspect container '" + old + "': " + error.message());
    }
    var hasContainer =
        oldState instanceof ContainerState.Running || oldState instanceof ContainerState.Stopped;
    var wasRunning = oldState instanceof ContainerState.Running;
    var newDefinition = withName(existing.definition(), renamed);

    var undo = new ArrayDeque<Compensation>();
    try {
      if (wasRunning) {
        containers.stop(old);
        undo.push(() -> containers.start(old));
      }
      if (hasContainer) {
        containers.rename(old, renamed);
        undo.push(() -> containers.rename(renamed, old));
      }
      projects.rename(old, renamed, newDefinition);
      undo.push(() -> projects.rename(renamed, old, existing.definition()));
      specs.reproject(old, renamed);
      undo.push(() -> specs.reproject(renamed, old));
      files.reproject(old, renamed);
      undo.push(() -> files.reproject(renamed, old));
      moveProjectDir(old, renamed);
      undo.push(() -> moveProjectDir(renamed, old));
      materialize(renamed, newDefinition);
    } catch (Exception e) {
      rollback(undo);
      throw e;
    }

    var warnings = new ArrayList<String>();
    if (hasContainer) {
      attempt(
          () -> {
            containers.start(renamed);
            containers.waitUntilReady(renamed);
          },
          warnings,
          "restart the container");
      finish(containers, renamed, newDefinition, warnings);
      if (!wasRunning) {
        attempt(() -> containers.stop(renamed), warnings, "stop the container");
      }
    }
    return new Result(old, renamed, hasContainer, warnings);
  }

  private void finish(
      ContainerManager containers, String container, String definition, List<String> warnings) {
    attempt(() -> containers.setHostname(container), warnings, "set the container hostname");
    attempt(() -> ContainerSailSetup.ensureInstalled(shell, container), warnings, "re-wire sail");
    attempt(
        () -> AgentContextInstaller.install(shell, container, parse(definition)),
        warnings,
        "regenerate agent context");
  }

  private void moveProjectDir(String from, String to) throws IOException {
    var src = projectsDir.resolve(from);
    if (!Files.exists(src)) {
      return;
    }
    var dst = projectsDir.resolve(to);
    Files.createDirectories(dst.getParent());
    Files.move(src, dst);
  }

  private void materialize(String name, String definition) throws IOException {
    var dir = projectsDir.resolve(name);
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(SailPaths.PROJECT_DESCRIPTOR), definition);
  }

  static String withName(String definition, String name) {
    var map = YamlUtil.parseMap(definition);
    map.put("name", name);
    return YamlUtil.dumpToString(map);
  }

  private static SailYaml parse(String definition) {
    return ProjectDefinitions.resolveForProvisioning(definition);
  }

  private static void attempt(Compensation step, List<String> warnings, String what) {
    try {
      step.run();
    } catch (Exception e) {
      warnings.add("could not " + what + ": " + e.getMessage());
    }
  }

  private static void rollback(Deque<Compensation> undo) {
    while (!undo.isEmpty()) {
      try {
        undo.pop().run();
      } catch (Exception ignored) {
        // Best-effort unwind; the original failure is what the caller sees.
      }
    }
  }
}
