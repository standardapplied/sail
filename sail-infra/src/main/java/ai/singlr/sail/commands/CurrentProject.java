/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The current project, resolved the same way by every command: an explicit flag or positional
 * always wins, then the {@code sail.yaml} found upward from the working directory, then the project
 * {@code sail project switch} last selected (a single line under {@code ~/.sail}; the project
 * catalog itself lives in the database, this is only the pointer). Commands that need a project
 * fail with guidance naming both options when none of the three yields one.
 */
final class CurrentProject {

  private CurrentProject() {}

  static Path file() {
    return SailPaths.sailDir().resolve("current-project");
  }

  /** The current project, or empty if none is set. */
  static Optional<String> get() {
    return get(file());
  }

  static Optional<String> get(Path stateFile) {
    try {
      if (!Files.exists(stateFile)) {
        return Optional.empty();
      }
      var name = Files.readString(stateFile).strip();
      return Strings.isBlank(name) ? Optional.empty() : Optional.of(name);
    } catch (IOException unreadable) {
      return Optional.empty();
    }
  }

  /** Records {@code name} as the current project. */
  static void set(String name) {
    set(file(), name);
  }

  static void set(Path stateFile, String name) {
    try {
      Files.createDirectories(stateFile.getParent());
      Files.writeString(stateFile, name + "\n");
    } catch (IOException e) {
      throw new UncheckedIOException("Could not record the current project", e);
    }
  }

  /** The project named by the {@code sail.yaml} found upward from the working directory. */
  static Optional<String> fromCwd() {
    var yaml = SailPaths.findSailYamlUpward(Path.of(".")).orElse(null);
    if (yaml == null) {
      return Optional.empty();
    }
    try {
      var name = (String) YamlUtil.parseFile(yaml).get("name");
      return Strings.isBlank(name) ? Optional.<String>empty() : Optional.of(name);
    } catch (Exception unreadable) {
      return Optional.empty();
    }
  }

  static Optional<String> infer(String cwdProject, Path stateFile) {
    return Optional.ofNullable(cwdProject).or(() -> get(stateFile));
  }

  /**
   * Resolves the project a command should act on: the explicit name when given, otherwise the cwd's
   * {@code sail.yaml}, otherwise the current project. Throws with actionable guidance when none is
   * available.
   */
  static String require(String explicit) {
    return require(explicit, fromCwd().orElse(null), file());
  }

  static String require(String explicit, String cwdProject, Path stateFile) {
    if (Strings.isNotBlank(explicit)) {
      return explicit;
    }
    return infer(cwdProject, stateFile)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "No project given and none could be inferred (no sail.yaml in the current"
                        + " directory tree, no current project). Name the project on the command"
                        + " line (-p/--project or the <project> argument), or run 'sail project"
                        + " switch <project>' first."));
  }

  /**
   * Resolves the project filter for listing commands: empty means every project (requested with
   * {@code --all-projects} or {@code --project '*'}), otherwise {@link #require} semantics apply.
   */
  static Optional<String> scope(String explicit, boolean allProjects) {
    return scope(explicit, allProjects, fromCwd().orElse(null), file());
  }

  static Optional<String> scope(
      String explicit, boolean allProjects, String cwdProject, Path stateFile) {
    if (allProjects && Strings.isNotBlank(explicit) && !"*".equals(explicit)) {
      throw new IllegalArgumentException("Pass either --project or --all-projects, not both.");
    }
    if (allProjects || "*".equals(explicit)) {
      return Optional.empty();
    }
    if (Strings.isNotBlank(explicit)) {
      return Optional.of(explicit);
    }
    var inferred =
        infer(cwdProject, stateFile)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No project given and none could be inferred (no sail.yaml in the current"
                            + " directory tree, no current project). Pass -p/--project <project>"
                            + " (or --all-projects for every project), or run 'sail project switch"
                            + " <project>' first."));
    return Optional.of(inferred);
  }
}
