/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.ProjectStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Backfills the control-plane catalog from on-disk project descriptors ({@code
 * ~/.sail/projects/<name>/sail.yaml}). Its only job is to catch a project that exists on disk but
 * not yet in the database, so it imports a name <em>only when it is absent from the catalog</em> —
 * an already-catalogued project is left untouched, never re-read from disk (which would overwrite a
 * definition another box may have changed and synced). The database is the source of truth; this is
 * the read-from-disk fallback for projects created before the catalog existed.
 */
public final class ProjectImporter {

  private final Path projectsDir;
  private final ProjectStore store;

  public ProjectImporter(Path projectsDir, ProjectStore store) {
    this.projectsDir = projectsDir;
    this.store = store;
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
    try (Stream<Path> entries = Files.list(projectsDir)) {
      for (var dir : entries.filter(Files::isDirectory).sorted().toList()) {
        var descriptor = dir.resolve(SailPaths.PROJECT_DESCRIPTOR);
        if (!Files.isRegularFile(descriptor)) {
          continue;
        }
        var name = dir.getFileName().toString();
        if (store.findByName(name).isPresent()) {
          skipped++;
          continue;
        }
        try {
          store.upsert(name, Files.readString(descriptor), null);
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
