/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import java.nio.file.Files;

/**
 * Loads a project's descriptor and live container state for the operations layer. One loader for
 * every lane — {@link SailOperations} routes and {@link DispatchOperations} both resolve projects
 * through it, so "project not found / stopped / errored" is decided (and worded) exactly once.
 */
final class ProjectLoader {

  record LoadedProject(SailYaml config, ContainerState state) {}

  private final ShellExec shell;
  private final String file;

  ProjectLoader(ShellExec shell, String file) {
    this.shell = shell;
    this.file = file;
  }

  LoadedProject load(String project) {
    var sailYamlPath = SailPaths.resolveSailYaml(project, file);
    if (!Files.exists(sailYamlPath)) {
      throw new ApiException(
          ErrorCode.PROJECT_DESCRIPTOR_NOT_FOUND,
          "Project descriptor was not found: " + sailYamlPath.toAbsolutePath());
    }
    try {
      var config = SailYaml.fromMap(YamlUtil.parseFile(sailYamlPath));
      var state = new ContainerManager(shell).queryState(project);
      return new LoadedProject(config, state);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.PROJECT_LOAD_FAILED, "Failed to load project.", e);
    }
  }

  LoadedProject loadRunning(String project) {
    var loaded = load(project);
    switch (loaded.state()) {
      case ContainerState.Running ignored -> {
        return loaded;
      }
      case ContainerState.Stopped ignored ->
          throw new ApiException(
              ErrorCode.PROJECT_STOPPED,
              "Project '" + project + "' is stopped.",
              "Start it with sail project start " + project + ".");
      case ContainerState.NotCreated ignored ->
          throw new ApiException(
              ErrorCode.PROJECT_NOT_CREATED, "Project '" + project + "' does not exist.");
      case ContainerState.Error error ->
          throw new ApiException(ErrorCode.CONTAINER_ERROR, error.message());
    }
  }

  void requireExists(String project) {
    loadCreated(project);
  }

  LoadedProject loadCreated(String project) {
    var loaded = load(project);
    if (loaded.state() instanceof ContainerState.NotCreated) {
      throw new ApiException(
          ErrorCode.PROJECT_NOT_CREATED, "Project '" + project + "' does not exist.");
    }
    if (loaded.state() instanceof ContainerState.Error error) {
      throw new ApiException(ErrorCode.CONTAINER_ERROR, error.message());
    }
    return loaded;
  }
}
