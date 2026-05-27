/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports file-based specs (specs/&lt;id&gt;/spec.yaml + spec.md) into the SQLite database.
 * Idempotent: skips specs whose ID already exists in the database.
 */
public final class SpecMigrator {

  private final SpecStore store;

  public SpecMigrator(SpecStore store) {
    this.store = store;
  }

  public record MigrationResult(int imported, int skipped, List<String> errors) {}

  public MigrationResult importFromDirectory(Path specsDir) {
    if (!Files.isDirectory(specsDir)) {
      return new MigrationResult(0, 0, List.of());
    }

    var imported = 0;
    var skipped = 0;
    var errors = new ArrayList<String>();

    try (var dirs = Files.list(specsDir)) {
      var specDirs = dirs.filter(Files::isDirectory).sorted().toList();
      for (var specDir : specDirs) {
        var specYaml = specDir.resolve("spec.yaml");
        if (!Files.exists(specYaml)) continue;

        var specId = specDir.getFileName().toString();
        if (store.findById(specId).isPresent()) {
          skipped++;
          continue;
        }

        try {
          var result = importSpec(specDir, specId);
          if (result) {
            imported++;
          } else {
            skipped++;
          }
        } catch (Exception e) {
          errors.add(specId + ": " + e.getMessage());
        }
      }
    } catch (IOException e) {
      errors.add("Failed to list specs directory: " + e.getMessage());
    }

    return new MigrationResult(imported, skipped, List.copyOf(errors));
  }

  @SuppressWarnings("unchecked")
  private boolean importSpec(Path specDir, String specId) throws IOException {
    var specYaml = specDir.resolve("spec.yaml");
    var map = YamlUtil.parseFile(specYaml);
    if (!map.containsKey("id")) {
      map.put("id", specId);
    }

    var spec = Spec.fromMap(map);
    var status = mapLegacyStatus(spec.status());
    var dependsOn = spec.dependsOn() != null ? spec.dependsOn() : List.<String>of();
    var repos = spec.repos() != null ? spec.repos() : List.<String>of();

    var row =
        new SpecStore.SpecRow(
            spec.id(),
            spec.title(),
            status,
            spec.assignee(),
            spec.agent(),
            spec.model(),
            spec.reasoningEffort(),
            spec.branch(),
            0,
            spec.assignee(),
            "",
            "",
            dependsOn,
            repos);
    store.create(row);

    var specMd = specDir.resolve("spec.md");
    var planMd = specDir.resolve("plan.md");
    var body = Files.exists(specMd) ? Files.readString(specMd) : "";
    var plan = Files.exists(planMd) ? Files.readString(planMd) : "";
    if (!body.isEmpty() || !plan.isEmpty()) {
      store.setContent(spec.id(), body, plan);
    }

    return true;
  }

  private static String mapLegacyStatus(String status) {
    return switch (status) {
      case "pending", "in_progress", "review", "done" -> status;
      case "archive", "archived" -> "archived";
      default -> "draft";
    };
  }
}
