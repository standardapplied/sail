/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.config.YamlUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Project readers for tests that describe a project in a file rather than a catalog row. */
public final class TestProjects {

  private TestProjects() {}

  /**
   * A reader answering the project the definition in {@code file} names with that definition, and
   * every other name with none, as a catalog holding that one project would: empty while the file
   * is absent, and {@link ProjectReader.Unreadable} for one that does not parse.
   */
  public static ProjectReader reading(Path file) {
    return project -> {
      if (!Files.exists(file)) {
        return Optional.empty();
      }
      ProjectDefinition definition;
      try {
        definition = TestDefinitions.map(YamlUtil.parseFile(file));
      } catch (Exception e) {
        throw new ProjectReader.Unreadable(project, e);
      }
      return Optional.of(definition).filter(held -> held.name().equals(project));
    };
  }
}
