/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.store.FileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

/**
 * Imports each project's on-disk workspace files ({@code ~/.sail/projects/<name>/files/**}) into
 * the synced {@link FileStore}, so the file tree an FDE already has becomes the shared, replicated
 * copy the moment they upgrade — the disk-to-DB counterpart of {@link FileMaterializer}.
 * Idempotent: a file already stored with the same content is skipped, so re-running on every
 * upgrade neither churns revisions nor re-imports. The read-only counterpart to the git-based
 * {@code project pull} files directory it replaces.
 */
public final class FileImporter {

  private final Path projectsDir;
  private final FileStore files;

  public FileImporter(Path projectsDir, FileStore files) {
    this.projectsDir = projectsDir;
    this.files = files;
  }

  public record Report(int imported, List<String> notes) {}

  public Report importAll() {
    if (!Files.isDirectory(projectsDir)) {
      return new Report(0, List.of());
    }
    var imported = 0;
    var notes = new ArrayList<String>();
    try (Stream<Path> projects = Files.list(projectsDir)) {
      for (var projectDir : projects.filter(Files::isDirectory).toList()) {
        imported += importProject(projectDir.getFileName().toString(), projectDir.resolve("files"));
      }
    } catch (IOException e) {
      notes.add("Could not scan project files: " + e.getMessage());
    }
    return new Report(imported, List.copyOf(notes));
  }

  private int importProject(String project, Path filesDir) throws IOException {
    if (!Files.isDirectory(filesDir)) {
      return 0;
    }
    var imported = 0;
    try (Stream<Path> tree = Files.walk(filesDir)) {
      for (var file : tree.filter(Files::isRegularFile).toList()) {
        var path = filesDir.relativize(file).toString();
        var content = Base64.getEncoder().encodeToString(Files.readAllBytes(file));
        if (files.find(project, path).map(row -> !row.content().equals(content)).orElse(true)) {
          files.put(project, path, content);
          imported++;
        }
      }
    }
    return imported;
  }
}
