/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
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
@FunctionalInterface
public interface ProjectReader {

  /** The project's definition, or empty when this box holds no definition of it. */
  Optional<SailYaml> read(String project);

  /** The project's definition, or a not-found error saying how to get one. */
  default SailYaml require(String project) {
    return read(project).orElseThrow(() -> absent(project));
  }

  /** The not-found error for a project this box holds no definition of. */
  static ApiException absent(String project) {
    return new ApiException(
        ErrorCode.PROJECT_DESCRIPTOR_NOT_FOUND,
        "Project '" + project + "' is not in the catalog.",
        Remedy.of(
            "If its descriptor is on this box, import it with '",
            Remedy.sail("migrate"),
            "'. To create the project, run '",
            Remedy.sail("project apply", "-f", "<file>"),
            "'."));
  }

  /**
   * The reader over the catalog: a row is parsed as stored, one that does not parse is {@link
   * Unreadable}, and a failure of the store itself passes through unwrapped. A null store holds no
   * project.
   */
  static ProjectReader ofCatalog(ProjectStore store) {
    if (store == null) {
      return project -> Optional.empty();
    }
    return project -> store.findByName(project).map(row -> parse(project, row.definition()));
  }

  private static SailYaml parse(String project, String definition) {
    try {
      return SailYaml.fromMap(YamlUtil.parseMap(definition));
    } catch (RuntimeException e) {
      throw new Unreadable(project, e);
    }
  }

  /** A catalog row that holds a definition which cannot be read as one, with its cause's remedy. */
  final class Unreadable extends RuntimeException implements Remedied {
    private final String reason;
    private final transient Remedy remedy;

    Unreadable(String project, Throwable cause) {
      this(
          Remedied.joined(
              "The definition of project '" + project + "' in the catalog could not be read:",
              Remedied.reasonOf(cause)),
          Remedied.remedyOf(cause),
          cause);
    }

    private Unreadable(String reason, Remedy remedy, Throwable cause) {
      super(Remedied.message(reason, remedy), cause);
      this.reason = reason;
      this.remedy = remedy;
    }

    @Override
    public String reason() {
      return reason;
    }

    @Override
    public Remedy remedy() {
      return remedy;
    }
  }
}
