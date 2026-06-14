/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Snapshot of every project descriptor under {@code ~/.sail/projects/}. Holds enough to back the
 * spec re-bucket heuristic (project name + repo list) and to validate that a {@code --project}
 * argument names a real project on disk. Loaded once, queried many times.
 */
public final class ProjectRegistry {

  private final Map<String, ProjectInfo> byName;

  private ProjectRegistry(Map<String, ProjectInfo> byName) {
    this.byName = Map.copyOf(byName);
  }

  /** Loads every {@code sail.yaml} under {@link SailPaths#projectsDir()}. Missing dir = empty. */
  public static ProjectRegistry loadFromDisk() {
    return loadFromDisk(SailPaths.projectsDir());
  }

  /** Test-visible variant that scans a caller-provided projects directory. */
  public static ProjectRegistry loadFromDisk(Path projectsDir) {
    var byName = new LinkedHashMap<String, ProjectInfo>();
    if (!Files.isDirectory(projectsDir)) {
      return new ProjectRegistry(byName);
    }
    try (var stream = Files.list(projectsDir)) {
      stream
          .filter(Files::isDirectory)
          .sorted()
          .forEach(
              dir -> {
                var yaml = dir.resolve(SailPaths.PROJECT_DESCRIPTOR);
                if (!Files.isRegularFile(yaml)) {
                  return;
                }
                try {
                  var sail = SailYaml.fromMap(YamlUtil.parseFile(yaml));
                  byName.put(sail.name(), ProjectInfo.from(sail));
                } catch (Exception ignored) {
                }
              });
    } catch (IOException ignored) {
    }
    return new ProjectRegistry(byName);
  }

  /** Returns every project name in lexicographic order. */
  public List<String> names() {
    return List.copyOf(byName.keySet());
  }

  /** Returns the project info for {@code name}, or empty if no descriptor matches. */
  public Optional<ProjectInfo> find(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  /**
   * Returns every project whose repo set contains {@code repo}. Used by the spec re-bucket
   * heuristic — a spec whose only repo is {@code chorus} probably belongs to whichever project
   * declares {@code chorus} (and only if exactly one such project exists).
   */
  public List<String> projectsContainingRepo(String repo) {
    var hits = new ArrayList<String>();
    for (var entry : byName.entrySet()) {
      if (entry.getValue().repos().contains(repo)) {
        hits.add(entry.getKey());
      }
    }
    return List.copyOf(hits);
  }

  /** Minimal project snapshot the re-bucket heuristic and {@code --project} validation need. */
  public record ProjectInfo(String name, List<String> repos) {
    public ProjectInfo {
      repos = List.copyOf(repos);
    }

    static ProjectInfo from(SailYaml sail) {
      var repoNames = new ArrayList<String>();
      if (sail.repos() != null) {
        for (var repo : sail.repos()) {
          repoNames.add(repo.path());
        }
      }
      return new ProjectInfo(sail.name(), repoNames);
    }
  }
}
