/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.ShellExec;
import java.util.Objects;

/**
 * Loads a project's definition and live container state for the operations layer. One loader for
 * every lane — {@link SailOperations} routes and {@link DispatchOperations} both resolve projects
 * through it, so "project not found / stopped / errored" is decided (and worded) exactly once.
 */
final class ProjectLoader {

  record LoadedProject(SailYaml config, ContainerState state) {}

  private final ShellExec shell;
  private final ProjectReader definitions;

  ProjectLoader(ShellExec shell, ProjectReader definitions) {
    this.shell = shell;
    this.definitions = Objects.requireNonNull(definitions, "definitions");
  }

  LoadedProject load(String project) {
    var config = definition(project);
    try {
      var state = new ContainerManager(shell).queryState(project);
      return new LoadedProject(config, state);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.PROJECT_LOAD_FAILED, "Failed to load project.", e);
    }
  }

  private SailYaml definition(String project) {
    try {
      return definitions.require(project);
    } catch (ProjectReader.Unreadable e) {
      throw new ApiException(ErrorCode.PROJECT_LOAD_FAILED, e.getMessage(), e);
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
