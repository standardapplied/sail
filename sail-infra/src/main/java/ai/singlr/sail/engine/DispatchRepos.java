/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.Spec;
import java.util.LinkedHashMap;
import java.util.List;

public final class DispatchRepos {

  private DispatchRepos() {}

  public static List<ProjectDefinition.Repo> resolve(
      ProjectDefinition config, Spec spec, List<String> overrides) {
    var configured = configuredRepos(config);
    var requested = requestedRepos(spec, overrides);
    if (requested.isEmpty()) {
      return configured.size() == 1 ? List.of(configured.values().iterator().next()) : List.of();
    }
    return requested.stream().map(path -> requireConfigured(configured, path)).toList();
  }

  private static LinkedHashMap<String, ProjectDefinition.Repo> configuredRepos(
      ProjectDefinition config) {
    var repos = new LinkedHashMap<String, ProjectDefinition.Repo>();
    if (config.repos() == null) {
      return repos;
    }
    for (var repo : config.repos()) {
      repos.put(repo.path(), repo);
    }
    return repos;
  }

  private static List<String> requestedRepos(Spec spec, List<String> overrides) {
    var requested = overrides != null && !overrides.isEmpty() ? overrides : spec.repos();
    return requested.stream().map(DispatchRepos::validatedPath).distinct().toList();
  }

  /** Returns a copy of {@code spec} whose repos are the resolved target repo paths. */
  public static Spec withTargetRepos(Spec spec, List<ProjectDefinition.Repo> targetRepos) {
    return spec.withRepos(targetRepos.stream().map(ProjectDefinition.Repo::path).toList());
  }

  private static String validatedPath(String path) {
    NameValidator.requireSafePath(path, "spec.repo");
    return path;
  }

  private static ProjectDefinition.Repo requireConfigured(
      LinkedHashMap<String, ProjectDefinition.Repo> configured, String path) {
    var repo = configured.get(path);
    if (repo == null) {
      throw new IllegalArgumentException(
          "Spec targets repo '" + path + "', but the project's definition has no such repo.");
    }
    return repo;
  }
}
