/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ShellExec;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * What a running workspace holds, read under its lock before a delete without force: work its repos
 * keep, with the idle sweep's own reads ({@link WorkspaceGit}), and anything Incus runs in it (a
 * terminal, an attach, an exec). Either refuses the delete, naming it.
 */
final class WorkspaceHoldings {

  private final WorkspaceGit git;
  private final BoxInstances box;

  WorkspaceHoldings(ShellExec shell) {
    this.git = new WorkspaceGit(shell);
    this.box = new BoxInstances(shell);
  }

  /**
   * Returns when {@code running}, {@code whose} ("workspace try of acme"), holds no work and has
   * nothing running in it; refuses with {@link ErrorCode#WORKSPACE_HOLDS_WORK} otherwise, or when
   * that cannot be read, with {@code forced} as the way to delete it anyway.
   */
  void requireNothingIn(
      ProjectDefinition config, BoxInstances.Instance running, String whose, Remedy forced)
      throws InterruptedException {
    String said;
    String first;
    try {
      var held = git.left(running.name(), repos(config), "");
      if (held.isPresent()) {
        said = capital(whose) + " holds work: " + held.get() + ".";
        first = "Take what it holds first";
      } else if (box.inUse().contains(running.name())) {
        said =
            capital(whose)
                + " is in use: Incus runs a command in it (a terminal, an attach or an exec).";
        first = "End what runs in it first";
      } else {
        return;
      }
    } catch (IOException | TimeoutException unread) {
      said = "Whether " + whose + " holds work cannot be told: " + unread.getMessage() + ".";
      first = "Take what it holds first";
    }
    throw new ApiException(
        ErrorCode.WORKSPACE_HOLDS_WORK, said, Remedy.of(first + ", or delete it anyway: ", forced));
  }

  private static String capital(String text) {
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
  }

  private static List<String> repos(ProjectDefinition config) {
    return Objects.requireNonNullElse(config.repos(), List.<ProjectDefinition.Repo>of()).stream()
        .map(ProjectDefinition.Repo::path)
        .toList();
  }
}
