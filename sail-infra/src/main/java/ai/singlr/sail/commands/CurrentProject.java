/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The current project — the one {@code sail switch} last selected — so project commands need
 * neither a name argument nor a {@code cd} into a descriptor directory. It is a single line under
 * {@code ~/.sail}; the project catalog itself lives in the database, this is only the pointer.
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
   * current project. Throws with actionable guidance when neither is available.
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
                new IllegalStateException(
                    "No current project. Run 'sail switch <project>' or pass a project name."));
  }
}
