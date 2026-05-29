/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.SpecMigrator;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Imports pre-control-plane file-based specs into the control-plane database. The control plane
 * runs on the host, but project specs live at {@code /home/<sshUser>/workspace/<specsDir>/} inside
 * each project's Incus container — a filesystem the host cannot read directly. So this reads them
 * the same way every other command does: through the container shell ({@link SpecWorkspace} over
 * {@code incus exec}), bucketing each spec under its project name.
 *
 * <p>Specs from every running container are collected first, then imported as a single batch so a
 * spec can depend on a spec in another project. {@link SpecMigrator} does the two-phase insert and
 * skips by id, so this is idempotent. Driven only by {@code sail migrate}; the daemon never walks
 * containers.
 */
public final class ContainerSpecImporter {

  /**
   * Per-run summary surfaced to {@code sail migrate}.
   *
   * @param imported newly-inserted specs across all containers
   * @param skipped specs already in the DB (skip-by-id)
   * @param notes one line per project the scan touched (or skipped), plus any import errors
   */
  public record Report(int imported, int skipped, List<String> notes) {
    public static Report empty() {
      return new Report(0, 0, List.of());
    }
  }

  private final ShellExec shell;
  private final ContainerManager containers;
  private final SpecMigrator migrator;
  private final Path projectsDir;

  public ContainerSpecImporter(
      ShellExec shell, ContainerManager containers, SpecStore store, Path projectsDir) {
    this.shell = Objects.requireNonNull(shell);
    this.containers = Objects.requireNonNull(containers);
    this.migrator = new SpecMigrator(store);
    this.projectsDir = Objects.requireNonNull(projectsDir);
  }

  public Report importAll() {
    if (!Files.isDirectory(projectsDir)) {
      return Report.empty();
    }
    List<Path> projectDirs;
    try (var stream = Files.list(projectsDir)) {
      projectDirs = stream.filter(Files::isDirectory).sorted().toList();
    } catch (IOException e) {
      return new Report(0, 0, List.of("  • Failed to list projects: " + e.getMessage()));
    }

    var imports = new ArrayList<SpecMigrator.SpecImport>();
    var notes = new ArrayList<String>();
    for (var projectDir : projectDirs) {
      var project = projectDir.getFileName().toString();
      var descriptor = projectDir.resolve(SailPaths.PROJECT_DESCRIPTOR);
      if (!Files.isRegularFile(descriptor)) {
        continue;
      }
      try {
        imports.addAll(collectProject(project, descriptor, notes));
      } catch (Exception e) {
        notes.add("  • " + project + " ERROR: " + e.getMessage());
      }
    }

    var result = migrator.importSpecs(imports);
    for (var error : result.errors()) {
      notes.add("  • " + error);
    }
    return new Report(result.imported(), result.skipped(), List.copyOf(notes));
  }

  private List<SpecMigrator.SpecImport> collectProject(
      String project, Path descriptor, List<String> notes) throws Exception {
    var config = SailYaml.fromMap(YamlUtil.parseFile(descriptor));
    if (config.agent() == null || config.agent().specsDir() == null) {
      return List.of();
    }
    var state = containers.queryState(project);
    if (!(state instanceof ContainerState.Running)) {
      notes.add("  • " + project + ": skipped (" + describe(state) + ")");
      return List.of();
    }

    var specsDir = "/home/" + config.sshUser() + "/workspace/" + config.agent().specsDir();
    var workspace = new SpecWorkspace(shell, project, specsDir);
    var specs = workspace.readSpecs();
    if (specs.isEmpty()) {
      return List.of();
    }

    var imports = new ArrayList<SpecMigrator.SpecImport>();
    for (var spec : specs) {
      var bucket = spec.project() != null ? spec.project() : project;
      var body = workspace.readSpecBody(spec.id());
      var plan = workspace.readPlanBody(spec.id());
      imports.add(new SpecMigrator.SpecImport(spec, bucket, body, plan));
    }
    notes.add("  • " + project + ": " + specs.size() + " spec(s) found in " + specsDir);
    return imports;
  }

  private static String describe(ContainerState state) {
    return switch (state) {
      case ContainerState.Running ignored -> "running";
      case ContainerState.Stopped ignored -> "container stopped";
      case ContainerState.NotCreated ignored -> "container not created";
      case ContainerState.Error error -> "container error: " + error.message();
    };
  }
}
