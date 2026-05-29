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
import java.util.Objects;

/**
 * Imports file-based specs (specs/&lt;id&gt;/spec.yaml + spec.md) into the SQLite database.
 * Idempotent: skips specs whose ID already exists in the database.
 *
 * <p>Import runs in two phases so a spec can depend on another spec in the same batch regardless of
 * iteration order: phase one inserts every spec row (with no dependencies), phase two wires the
 * {@code depends_on} edges once all rows exist. {@code spec_dependencies.depends_on} is a foreign
 * key to {@code specs(id)}, so wiring a dependency before its target row exists fails with a FK
 * violation — which is exactly the forward-reference case phase two removes. Dependencies whose
 * target is in neither the batch nor the database are skipped and reported, never fatal.
 */
public final class SpecMigrator {

  /** Bucket assigned to imported specs that don't declare a {@code project:} key. */
  public static final String UNASSIGNED_PROJECT = "unassigned";

  private final SpecStore store;

  public SpecMigrator(SpecStore store) {
    this.store = store;
  }

  public record MigrationResult(int imported, int skipped, List<String> errors) {}

  /** A spec to import, with its body, plan, and resolved project bucket. */
  public record SpecImport(Spec spec, String project, String body, String plan) {}

  public MigrationResult importFromDirectory(Path specsDir) {
    return importFromDirectory(specsDir, UNASSIGNED_PROJECT);
  }

  /**
   * Imports specs from {@code specsDir}. Each spec whose {@code spec.yaml} omits {@code project:}
   * is assigned to {@code defaultProject}, so the FDE can re-bucket later via {@code sail spec
   * edit}.
   */
  public MigrationResult importFromDirectory(Path specsDir, String defaultProject) {
    if (!Files.isDirectory(specsDir)) {
      return new MigrationResult(0, 0, List.of());
    }

    var imports = new ArrayList<SpecImport>();
    var errors = new ArrayList<String>();
    try (var dirs = Files.list(specsDir)) {
      for (var specDir : dirs.filter(Files::isDirectory).sorted().toList()) {
        if (!Files.exists(specDir.resolve("spec.yaml"))) {
          continue;
        }
        var specId = specDir.getFileName().toString();
        try {
          imports.add(readSpecImport(specDir, specId, defaultProject));
        } catch (Exception e) {
          errors.add(specId + ": " + e.getMessage());
        }
      }
    } catch (IOException e) {
      errors.add("Failed to list specs directory: " + e.getMessage());
    }

    var result = importSpecs(imports);
    errors.addAll(result.errors());
    return new MigrationResult(result.imported(), result.skipped(), List.copyOf(errors));
  }

  /**
   * Imports a batch of specs in two phases (rows, then dependencies). Specs already present in the
   * database are skipped by id. Returns the aggregate counts plus one error line per spec that
   * failed to insert or per dependency edge whose target could not be found.
   */
  public MigrationResult importSpecs(List<SpecImport> imports) {
    var imported = 0;
    var skipped = 0;
    var errors = new ArrayList<String>();
    var created = new ArrayList<SpecImport>();

    for (var imp : imports) {
      var spec = imp.spec();
      if (spec.id() == null || spec.id().isBlank()) {
        errors.add("(spec missing id)");
        continue;
      }
      if (store.findById(spec.id()).isPresent()) {
        skipped++;
        continue;
      }
      try {
        store.create(baseRow(spec, imp.project()));
        var body = Objects.requireNonNullElse(imp.body(), "");
        var plan = Objects.requireNonNullElse(imp.plan(), "");
        if (!body.isEmpty() || !plan.isEmpty()) {
          store.setContent(spec.id(), body, plan);
        }
        created.add(imp);
        imported++;
      } catch (Exception e) {
        errors.add(spec.id() + ": " + e.getMessage());
      }
    }

    for (var imp : created) {
      wireDependencies(imp.spec(), errors);
    }

    return new MigrationResult(imported, skipped, List.copyOf(errors));
  }

  private void wireDependencies(Spec spec, List<String> errors) {
    var deps = spec.dependsOn() != null ? spec.dependsOn() : List.<String>of();
    if (deps.isEmpty()) {
      return;
    }
    var present = new ArrayList<String>();
    for (var dep : deps) {
      if (store.findById(dep).isPresent()) {
        present.add(dep);
      } else {
        errors.add(spec.id() + ": skipped unknown dependency '" + dep + "'");
      }
    }
    if (!present.isEmpty()) {
      try {
        store.addDependencies(spec.id(), present);
      } catch (Exception e) {
        errors.add(spec.id() + ": " + e.getMessage());
      }
    }
  }

  private SpecImport readSpecImport(Path specDir, String specId, String defaultProject)
      throws IOException {
    var map = YamlUtil.parseFile(specDir.resolve("spec.yaml"));
    map.putIfAbsent("id", specId);
    var spec = Spec.fromMap(map);
    var project = spec.project() != null ? spec.project() : defaultProject;

    var specMd = specDir.resolve("spec.md");
    var planMd = specDir.resolve("plan.md");
    var body = Files.exists(specMd) ? Files.readString(specMd) : "";
    var plan = Files.exists(planMd) ? Files.readString(planMd) : "";
    return new SpecImport(spec, project, body, plan);
  }

  private static SpecStore.SpecRow baseRow(Spec spec, String project) {
    var repos = spec.repos() != null ? spec.repos() : List.<String>of();
    return new SpecStore.SpecRow(
        spec.id(),
        project,
        spec.title(),
        mapLegacyStatus(spec.status()),
        spec.assignee(),
        spec.agent(),
        spec.model(),
        spec.reasoningEffort(),
        spec.branch(),
        0,
        spec.assignee(),
        "",
        "",
        List.of(),
        repos);
  }

  private static String mapLegacyStatus(String status) {
    return switch (status) {
      case "pending", "in_progress", "review", "done" -> status;
      case "archive", "archived" -> "archived";
      default -> "draft";
    };
  }
}
