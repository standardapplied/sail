/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The current project, resolved the same way by every command: an explicit flag or positional
 * always wins, then the project {@code sail project switch} last selected (a single line under
 * {@code ~/.sail}; the project catalog itself lives in the database, this is only the pointer).
 * Commands that need a project fail with guidance naming both options when neither yields one.
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

  /**
   * Resolves the project a command should act on: the explicit name when given, otherwise the
   * current project. Throws with actionable guidance when neither is.
   */
  static String require(String explicit) {
    return require(explicit, file());
  }

  static String require(String explicit, Path stateFile) {
    if (Strings.isNotBlank(explicit)) {
      return explicit;
    }
    return get(stateFile)
        .orElseThrow(
            () ->
                Remedied.state(
                    "No project given, and no current project.",
                    Remedy.of(
                        "Name the project on the command line (-p/--project or the <project>"
                            + " argument), or run '",
                        Remedy.sail("project switch", "<project>"),
                        "' first.")));
  }

  /**
   * Resolves the project filter for listing commands: empty means every project (requested with
   * {@code --all-projects} or {@code --project '*'}), otherwise {@link #require} semantics apply.
   */
  static Optional<String> scope(String explicit, boolean allProjects) {
    return scope(explicit, allProjects, file());
  }

  static Optional<String> scope(String explicit, boolean allProjects, Path stateFile) {
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
        get(stateFile)
            .orElseThrow(
                () ->
                    Remedied.state(
                        "No project given, and no current project.",
                        Remedy.of(
                            "Pass -p/--project <project> (or --all-projects for every project), or"
                                + " run '",
                            Remedy.sail("project switch", "<project>"),
                            "' first.")));
    return Optional.of(inferred);
  }
}
