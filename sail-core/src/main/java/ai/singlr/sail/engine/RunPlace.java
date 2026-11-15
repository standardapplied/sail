/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.webauthn.Hashes;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Which container a run lives in, and the one answer to that question (R1): a run that names a spec
 * lives in that spec's own workspace, every run of the spec in the same one; a run that names none
 * (an ad hoc session, a spec-less room) lives in the project's own container. The workspace's name
 * is derived from the spec id one way and never parsed back: the instance also carries the id as
 * {@code user.sail.spec}, which is what tells it for the spec's.
 */
public sealed interface RunPlace {

  /** The project the run belongs to. */
  String project();

  /** The Incus instance the run's processes and files are in. */
  String instance();

  /** The project's own container, named after the project. */
  record ProjectContainer(String project) implements RunPlace {
    @Override
    public String instance() {
      return project;
    }
  }

  /**
   * Spec {@code specId}'s workspace {@code workspace} of {@code project}, the instance named so.
   */
  record SpecWorkspace(String project, String specId, String workspace, String instance)
      implements RunPlace {}

  /** Incus names an instance in at most this many characters. */
  int MAX_INSTANCE = 63;

  /** What a cut name ends with past its cut: {@code -} and this many hex characters of the hash. */
  int HASH_HEX = 6;

  /** The place of a run of {@code project} naming {@code specId}, none when it is blank. */
  static RunPlace of(String project, String specId) {
    if (Strings.isBlank(specId)) {
      return new ProjectContainer(project);
    }
    var workspace = workspaceName(project, specId);
    return new SpecWorkspace(project, specId, workspace, project + "-" + workspace);
  }

  /**
   * The name of spec {@code specId}'s workspace of {@code project} (D2): the id itself when {@code
   * <project>-<id>} is at most {@value #MAX_INSTANCE} characters, the id is not {@code main} (the
   * project's own container's name among its workspaces), does not end in {@code -} and does not
   * make a template's name; otherwise the id cut to fit, any trailing {@code -} removed, then
   * {@code -} and the first {@value #HASH_HEX} hex characters of its SHA-256.
   *
   * @throws IllegalArgumentException when the project's name leaves no character for a cut id
   */
  static String workspaceName(String project, String specId) {
    if (project.length() + 1 + specId.length() <= MAX_INSTANCE
        && !"main".equals(specId)
        && !specId.endsWith("-")
        && !(project + "-" + specId).endsWith("--tmpl")) {
      return specId;
    }
    var room = MAX_INSTANCE - project.length() - 2 - HASH_HEX;
    if (room < 1) {
      throw new IllegalArgumentException(
          "Project '"
              + project
              + "' is "
              + project.length()
              + " characters, so a spec's workspace of it cannot be named: Incus names an instance"
              + " in at most "
              + MAX_INSTANCE
              + ", and a workspace named for a long spec id needs the project's name, a '-', one"
              + " character of the id and '-' with "
              + HASH_HEX
              + " hex characters. Use a project name of at most "
              + (MAX_INSTANCE - 3 - HASH_HEX)
              + " characters.");
    }
    var cut = specId.substring(0, Math.min(room, specId.length())).replaceAll("-+$", "");
    return cut + "-" + hash(specId);
  }

  private static String hash(String specId) {
    return HexFormat.of()
        .formatHex(Hashes.sha256(specId.getBytes(StandardCharsets.UTF_8)))
        .substring(0, HASH_HEX);
  }
}
