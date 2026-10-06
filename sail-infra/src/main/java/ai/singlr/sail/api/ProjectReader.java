/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ProjectStore;
import java.util.Optional;

/**
 * Where everything that runs a project reads its definition: the catalog row, parsed as stored,
 * personal-field placeholders included. The on-disk {@code sail.yaml} is a copy nothing running
 * reads, so a revision that reaches the catalog, by sync or by a command, is what the next read
 * sees.
 */
public interface ProjectReader {

  /** The project's definition, or empty when this box holds no definition of it. */
  Optional<SailYaml> read(String project);

  /** The project's definition, or a not-found error saying how to get one. */
  default SailYaml require(String project) {
    return read(project)
        .orElseThrow(
            () ->
                new ApiException(
                    ErrorCode.PROJECT_DESCRIPTOR_NOT_FOUND,
                    "Project '" + project + "' is not in the catalog.",
                    "If its descriptor is on this box, import it with 'sudo sail migrate'. To"
                        + " create the project, run 'sail project apply -f <file>'."));
  }

  /**
   * The reader over the catalog: a row is parsed as stored, one that does not parse is {@link
   * Unreadable}, and a failure of the store itself passes through unwrapped. A null store holds no
   * project.
   */
  static ProjectReader ofCatalog(ProjectStore store) {
    return project ->
        store == null
            ? Optional.empty()
            : store.findByName(project).map(row -> parse(project, row.definition()));
  }

  private static SailYaml parse(String project, String definition) {
    try {
      return SailYaml.fromMap(YamlUtil.parseMap(definition));
    } catch (RuntimeException e) {
      throw new Unreadable(project, e);
    }
  }

  /** A catalog row that holds a definition which cannot be read as one. */
  final class Unreadable extends RuntimeException {
    public Unreadable(String project, Throwable cause) {
      super(
          "The definition of project '"
              + project
              + "' in the catalog could not be read: "
              + cause.getMessage(),
          cause);
    }
  }
}
