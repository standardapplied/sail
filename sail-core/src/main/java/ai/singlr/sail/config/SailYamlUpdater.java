/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure transforms over a {@link SailYaml} definition: add a service or repo, remove a service, or
 * update resources, each returning a new definition. Persistence is deliberately not this class's
 * concern — the database catalog is the source of truth, so callers route the result through {@code
 * ProjectDefinitions.persist} rather than writing a file here. Keeping these transforms pure lets a
 * mutation read the current definition catalog-first, transform it, and record the result, so an
 * edit can never diverge from the catalog or be lost on the next sync.
 */
public final class SailYamlUpdater {

  private SailYamlUpdater() {}

  /** Returns a copy of {@code config} with the service added. Throws if the name already exists. */
  public static SailYaml addService(SailYaml config, String serviceName, SailYaml.Service service) {
    var services =
        config.services() != null
            ? new LinkedHashMap<>(config.services())
            : new LinkedHashMap<String, SailYaml.Service>();
    if (services.containsKey(serviceName)) {
      throw new IllegalStateException("Service '" + serviceName + "' already exists.");
    }
    services.put(serviceName, service);
    return withServices(config, Map.copyOf(services));
  }

  /** Returns a copy of {@code config} with the service removed. Throws if it does not exist. */
  public static SailYaml removeService(SailYaml config, String serviceName) {
    var services =
        config.services() != null
            ? new LinkedHashMap<>(config.services())
            : new LinkedHashMap<String, SailYaml.Service>();
    if (!services.containsKey(serviceName)) {
      throw new IllegalStateException("Service '" + serviceName + "' not found.");
    }
    services.remove(serviceName);
    return withServices(config, services.isEmpty() ? null : Map.copyOf(services));
  }

  /** Returns a copy of {@code config} with the repo added. Throws if its path already exists. */
  public static SailYaml addRepo(SailYaml config, SailYaml.Repo repo) {
    var repos =
        config.repos() != null ? new ArrayList<>(config.repos()) : new ArrayList<SailYaml.Repo>();
    for (var existing : repos) {
      if (existing.path().equals(repo.path())) {
        throw new IllegalStateException("Repo with path '" + repo.path() + "' already exists.");
      }
    }
    repos.add(repo);
    return withRepos(config, List.copyOf(repos));
  }

  /** Returns a copy of {@code config} with resources merged; any null argument is preserved. */
  public static SailYaml updateResources(SailYaml config, Integer cpu, String memory, String disk) {
    return withResources(config, mergeResources(config.resources(), cpu, memory, disk));
  }

  /**
   * Returns the merged resources block for a partial resource update. Any null argument preserves
   * the existing value.
   */
  public static SailYaml.Resources mergeResources(
      SailYaml.Resources current, Integer cpu, String memory, String disk) {
    if (current == null) {
      throw new IllegalStateException("sail.yaml must have a resources section");
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
    return SailYaml.Resources.fromMap(merged);
  }

  private static SailYaml withServices(SailYaml c, Map<String, SailYaml.Service> services) {
    return new SailYaml(
        c.name(),
        c.description(),
        c.resources(),
        c.image(),
        c.packages(),
        c.runtimes(),
        c.git(),
        c.repos(),
        services,
        c.processes(),
        c.agent(),
        c.agentContext(),
        c.ssh());
  }

  private static SailYaml withRepos(SailYaml c, List<SailYaml.Repo> repos) {
    return new SailYaml(
        c.name(),
        c.description(),
        c.resources(),
        c.image(),
        c.packages(),
        c.runtimes(),
        c.git(),
        repos,
        c.services(),
        c.processes(),
        c.agent(),
        c.agentContext(),
        c.ssh());
  }

  private static SailYaml withResources(SailYaml c, SailYaml.Resources resources) {
    return new SailYaml(
        c.name(),
        c.description(),
        resources,
        c.image(),
        c.packages(),
        c.runtimes(),
        c.git(),
        c.repos(),
        c.services(),
        c.processes(),
        c.agent(),
        c.agentContext(),
        c.ssh());
  }
}
