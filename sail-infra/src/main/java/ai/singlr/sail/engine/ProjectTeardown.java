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
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Takes a project off this box: its container and its {@code ~/.sail/projects/<name>} directory,
 * never a row. What {@code sail project destroy} without {@code --purge} does, and what a scoped
 * node does to a project main no longer grants it.
 */
public final class ProjectTeardown {

  private final ShellExec shell;
  private final Path projectsDir;

  public ProjectTeardown(ShellExec shell, Path projectsDir) {
    this.shell = Objects.requireNonNull(shell, "shell");
    this.projectsDir = Objects.requireNonNull(projectsDir, "projectsDir");
  }

  /**
   * Force-deletes the container {@code name} when {@code containerPresent}, then the project's
   * directory when there is one. A dry run prints both instead.
   */
  public void tearDown(String name, boolean containerPresent)
      throws IOException, InterruptedException, TimeoutException {
    NameValidator.requireValidProjectName(name);
    if (containerPresent) {
      new ContainerManager(shell).forceDelete(name);
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
