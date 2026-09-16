/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ProjectFiles;
import ai.singlr.sail.store.FileStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

public record SharedProjectFiles(FileStore files, Path projectsDir, String project)
    implements ProjectFiles {
  public SharedProjectFiles {
    NameValidator.requireValidProjectName(project);
  }

  @Override
  public List<FileStore.FileRow> list() {
    return files.list(project);
  }

  @Override
  public Optional<byte[]> get(String path) {
    return files.find(project, path).map(row -> Base64.getDecoder().decode(row.content()));
  }

  @Override
  public String put(String path, byte[] bytes) {
    if (!FilePicker.isShareablePath(path)) {
      throw new IllegalArgumentException("Unsafe share path: '" + path + "'.");
    }
    if (bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("File exceeds the " + MAX_BYTES + "-byte limit.");
    }
    files.put(project, path, Base64.getEncoder().encodeToString(bytes));
    return path;
  }

  @Override
  public boolean remove(String path) throws IOException {
    if (!files.delete(project, path)) {
      return false;
    }
    materialize();
    return true;
  }

  @Override
  public FileMaterializer.Report materialize() throws IOException {
    return new FileMaterializer(files, projectsDir).materialize(project);
  }
}
