/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import java.util.LinkedHashMap;
import java.util.List;

public final class DispatchRepos {

  private DispatchRepos() {}

  public static List<SailYaml.Repo> resolve(SailYaml config, Spec spec, List<String> overrides) {
    var configured = configuredRepos(config);
    var requested = requestedRepos(spec, overrides);
    if (requested.isEmpty()) {
      return configured.size() == 1 ? List.of(configured.values().iterator().next()) : List.of();
    }
    return requested.stream().map(path -> requireConfigured(configured, path)).toList();
  }

  private static LinkedHashMap<String, SailYaml.Repo> configuredRepos(SailYaml config) {
    var repos = new LinkedHashMap<String, SailYaml.Repo>();
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
  public static Spec withTargetRepos(Spec spec, List<SailYaml.Repo> targetRepos) {
    return new Spec(
        spec.id(),
        spec.project(),
        spec.title(),
        spec.status(),
        spec.assignee(),
        spec.dependsOn(),
        targetRepos.stream().map(SailYaml.Repo::path).toList(),
        spec.agent(),
        spec.model(),
        spec.reasoningEffort(),
        spec.branch());
  }

  private static String validatedPath(String path) {
    NameValidator.requireSafePath(path, "spec.repo");
    return path;
  }

  private static SailYaml.Repo requireConfigured(
      LinkedHashMap<String, SailYaml.Repo> configured, String path) {
    var repo = configured.get(path);
    if (repo == null) {
      throw new IllegalArgumentException(
          "Spec targets repo '" + path + "', but that repo is not configured in sail.yaml.");
    }
    return repo;
  }
}
