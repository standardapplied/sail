/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.store.ProjectStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Imports the control-plane catalog from on-disk project descriptors ({@code
 * ~/.sail/projects/<name>/sail.yaml}). Retained in the explicit migrate lane to recover a current
 * project creation whose best-effort catalog write failed. It imports a name <em>only when it has
 * never been journaled</em> — an already-known project or tombstone is left untouched, never
 * re-read from disk (which would overwrite a synced definition or resurrect a deletion), and never
 * a name the box's scope does not admit: a scoped node's leftover descriptor of a project main no
 * longer grants it is not work to import, and its next round takes it off the box.
 */
public final class ProjectImporter {

  private final Path projectsDir;
  private final ProjectStore store;
  private final ProjectScope scope;

  public ProjectImporter(Path projectsDir, ProjectStore store, ProjectScope scope) {
    this.projectsDir = projectsDir;
    this.store = store;
    this.scope = scope;
  }

  public record Report(int imported, int skipped, List<String> notes) {
    public static Report empty() {
      return new Report(0, 0, List.of());
    }
  }

  public Report importAll() {
    if (!Files.isDirectory(projectsDir)) {
      return Report.empty();
    }
    var imported = 0;
    var skipped = 0;
    var notes = new ArrayList<String>();
    var knownProjectIds = store.syncEntityIds();
    try (Stream<Path> entries = Files.list(projectsDir)) {
      for (var dir : entries.filter(Files::isDirectory).sorted().toList()) {
        var descriptor = dir.resolve(SailPaths.PROJECT_DESCRIPTOR);
        if (!Files.isRegularFile(descriptor)) {
          continue;
        }
        var name = dir.getFileName().toString();
        if (knownProjectIds.contains(name) || !scope.admits(name)) {
          skipped++;
          continue;
        }
        try {
          store.upsert(name, Files.readString(descriptor));
          imported++;
        } catch (IOException e) {
          skipped++;
          notes.add("could not import " + name + ": " + e.getMessage());
        }
      }
    } catch (IOException e) {
      return new Report(
          imported, skipped, List.of("could not scan " + projectsDir + ": " + e.getMessage()));
    }
    return new Report(imported, skipped, List.copyOf(notes));
  }
}
