/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure transforms over a {@link ProjectDefinition}: add a service or repo, remove a service, or
 * update resources, each returning a new definition. A caller writes the section it changed to the
 * catalog, which validates the definition whole before it holds it.
 */
public final class DefinitionEdits {

  private DefinitionEdits() {}

  /**
   * Returns a copy of {@code config} with the service added after those it holds, in their order.
   * Throws if the name already exists.
   */
  public static ProjectDefinition addService(
      ProjectDefinition config, String serviceName, ProjectDefinition.Service service) {
    var services =
        config.services() != null
            ? new LinkedHashMap<>(config.services())
            : new LinkedHashMap<String, ProjectDefinition.Service>();
    if (services.containsKey(serviceName)) {
      throw new IllegalStateException("Service '" + serviceName + "' already exists.");
    }
    services.put(serviceName, service);
    return config.withServices(Collections.unmodifiableMap(services));
  }

  /** Returns a copy of {@code config} with the service removed. Throws if it does not exist. */
  public static ProjectDefinition removeService(ProjectDefinition config, String serviceName) {
    var services =
        config.services() != null
            ? new LinkedHashMap<>(config.services())
            : new LinkedHashMap<String, ProjectDefinition.Service>();
    if (!services.containsKey(serviceName)) {
      throw new IllegalStateException("Service '" + serviceName + "' not found.");
    }
    services.remove(serviceName);
    return config.withServices(services.isEmpty() ? null : Collections.unmodifiableMap(services));
  }

  /** Returns a copy of {@code config} with the repo added. Throws if its path already exists. */
  public static ProjectDefinition addRepo(ProjectDefinition config, ProjectDefinition.Repo repo) {
    var repos =
        config.repos() != null
            ? new ArrayList<>(config.repos())
            : new ArrayList<ProjectDefinition.Repo>();
    for (var existing : repos) {
      if (existing.path().equals(repo.path())) {
        throw new IllegalStateException("Repo with path '" + repo.path() + "' already exists.");
      }
    }
    repos.add(repo);
    return config.withRepos(List.copyOf(repos));
  }

  /** Returns a copy of {@code config} with resources merged; any null argument is preserved. */
  public static ProjectDefinition updateResources(
      ProjectDefinition config, Integer cpu, String memory, String disk) {
    return config.withResources(mergeResources(config.resources(), cpu, memory, disk));
  }

  /**
   * Returns the merged resources block for a partial resource update. Any null argument preserves
   * the existing value.
   */
  public static ProjectDefinition.Resources mergeResources(
      ProjectDefinition.Resources current, Integer cpu, String memory, String disk) {
    if (current == null) {
      throw new IllegalStateException("The definition sets no resources to update.");
    }
    if (cpu != null && cpu < 1) {
      throw new IllegalArgumentException("resources.cpu must be >= 1");
    }
    if (memory != null && memory.isBlank()) {
      throw new IllegalArgumentException("resources.memory must not be blank");
    }
    if (disk != null && disk.isBlank()) {
      throw new IllegalArgumentException("resources.disk must not be blank");
    }
    var merged =
        Map.<String, Object>of(
            "cpu", cpu != null ? cpu : current.cpu(),
            "memory", memory != null ? memory : current.memory(),
            "disk", disk != null ? disk : current.disk());
    return ProjectDefinition.Resources.fromMap(new LinkedHashMap<>(merged));
  }
}
