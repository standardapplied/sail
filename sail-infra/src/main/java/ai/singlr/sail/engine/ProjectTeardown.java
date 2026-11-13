/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Takes a project off this box: its instances (its container, its workspaces and its template, as
 * {@link BoxInstances} tells them) and its {@code ~/.sail/projects/<name>} directory, never a row.
 * What {@code sail project destroy} without {@code --purge} does, and what a scoped node does to a
 * project main no longer grants it (W11).
 */
public final class ProjectTeardown {

  private final ShellExec shell;
  private final Path projectsDir;

  public ProjectTeardown(ShellExec shell, Path projectsDir) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.projectsDir = Objects.requireNonNull(projectsDir, "projectsDir");
  }

  /**
   * Force-deletes each of project {@code name}'s {@code instances}, in order, then its directory
   * when there is one. A dry run prints both instead.
   */
  public void tearDown(String name, List<String> instances)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(name);
    var containers = new ContainerManager(shell);
    for (var instance : instances) {
      containers.forceDelete(instance);
    }
    var dir = projectsDir.resolve(name);
    if (!Files.exists(dir)) {
      return;
    }
    if (shell.isDryRun()) {
      System.out.println("[dry-run] rm -rf " + dir);
      return;
    }
    deleteRecursively(dir);
  }

  private static void deleteRecursively(Path dir) throws IOException {
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.delete(path);
                } catch (IOException e) {
                  throw new UncheckedIOException(e);
                }
              });
    }
  }
}
