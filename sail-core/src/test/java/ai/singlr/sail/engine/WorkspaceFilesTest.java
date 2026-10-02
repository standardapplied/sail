/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFilesTest {

  @TempDir Path tempDir;

  @Test
  void resolveFilesDirFindsAdjacentDirectory() throws IOException {
    var filesDir = tempDir.resolve("files");
    Files.createDirectories(filesDir);
    var sailYaml = tempDir.resolve("sail.yaml");
    Files.writeString(sailYaml, "name: test");

    var result = WorkspaceFiles.resolveFilesDir(sailYaml);

    assertNotNull(result);
    assertEquals(filesDir.toAbsolutePath(), result.toAbsolutePath());
  }

  @Test
  void resolveFilesDirReturnsNullWhenNoFilesDir() throws IOException {
    var sailYaml = tempDir.resolve("sail.yaml");
    Files.writeString(sailYaml, "name: test");

    var result = WorkspaceFiles.resolveFilesDir(sailYaml);

    assertNull(result);
  }

  @Test
  void resolveFilesDirReturnsNullWhenFilesIsFile() throws IOException {
    var filesFile = tempDir.resolve("files");
    Files.writeString(filesFile, "not a directory");
    var sailYaml = tempDir.resolve("sail.yaml");
    Files.writeString(sailYaml, "name: test");

    var result = WorkspaceFiles.resolveFilesDir(sailYaml);

    assertNull(result);
  }

  @Test
  void resolveFilesDirReturnsNullWhenPathIsNull() {
    var result = WorkspaceFiles.resolveFilesDir(null);

    assertNull(result);
  }

  @Test
  void listFilesFindsSingleFile() throws IOException {
    var filesDir = tempDir.resolve("files");
    Files.createDirectories(filesDir);
    Files.writeString(filesDir.resolve("config.env"), "KEY=VALUE");

    var entries = WorkspaceFiles.listFiles(filesDir);

    assertEquals(1, entries.size());
    assertEquals("config.env", entries.getFirst().relativePath());
    assertEquals(filesDir.resolve("config.env"), entries.getFirst().hostPath());
  }

  @Test
  void listFilesPreservesNestedPaths() throws IOException {
    var filesDir = tempDir.resolve("files");
    var nested = filesDir.resolve("outline");
    Files.createDirectories(nested);
    Files.writeString(nested.resolve(".env"), "DB_URL=localhost");
    Files.writeString(nested.resolve("setup.sh"), "#!/bin/bash");

    var entries = WorkspaceFiles.listFiles(filesDir);

    assertEquals(2, entries.size());
    assertEquals("outline/.env", entries.get(0).relativePath());
    assertEquals("outline/setup.sh", entries.get(1).relativePath());
  }

  @Test
  void listFilesSortsByRelativePath() throws IOException {
    var filesDir = tempDir.resolve("files");
    Files.createDirectories(filesDir.resolve("z-project"));
    Files.createDirectories(filesDir.resolve("a-project"));
    Files.writeString(filesDir.resolve("z-project/config"), "z");
    Files.writeString(filesDir.resolve("a-project/config"), "a");
    Files.writeString(filesDir.resolve("root.txt"), "root");

    var entries = WorkspaceFiles.listFiles(filesDir);

    assertEquals(3, entries.size());
    assertEquals("a-project/config", entries.get(0).relativePath());
    assertEquals("root.txt", entries.get(1).relativePath());
    assertEquals("z-project/config", entries.get(2).relativePath());
  }

  @Test
  void listFilesHandsDeeplyNestedStructure() throws IOException {
    var filesDir = tempDir.resolve("files");
    var deep = filesDir.resolve("a/b/c/d");
    Files.createDirectories(deep);
    Files.writeString(deep.resolve("deep.txt"), "content");

    var entries = WorkspaceFiles.listFiles(filesDir);

    assertEquals(1, entries.size());
    assertEquals("a/b/c/d/deep.txt", entries.getFirst().relativePath());
  }

  @Test
  void listFilesReturnsEmptyForEmptyDirectory() throws IOException {
    var filesDir = tempDir.resolve("files");
    Files.createDirectories(filesDir);

    var entries = WorkspaceFiles.listFiles(filesDir);

    assertTrue(entries.isEmpty());
  }

  @Test
  void listFilesSkipsSubdirectoriesFromEntries() throws IOException {
    var filesDir = tempDir.resolve("files");
    Files.createDirectories(filesDir.resolve("subdir"));
    Files.writeString(filesDir.resolve("file.txt"), "content");

    var entries = WorkspaceFiles.listFiles(filesDir);

    assertEquals(1, entries.size());
    assertEquals("file.txt", entries.getFirst().relativePath());
  }

  @Test
  void listFilesHandlesMultipleFilesInSameDir() throws IOException {
    var filesDir = tempDir.resolve("files");
    var project = filesDir.resolve("myapp");
    Files.createDirectories(project);
    Files.writeString(project.resolve(".env"), "APP_KEY=secret");
    Files.writeString(project.resolve(".env.development"), "DEBUG=true");
    Files.writeString(project.resolve("docker-compose.override.yml"), "version: 3");

    var entries = WorkspaceFiles.listFiles(filesDir);

    assertEquals(3, entries.size());
    var paths = entries.stream().map(WorkspaceFiles.FileEntry::relativePath).toList();
    assertTrue(paths.contains("myapp/.env"));
    assertTrue(paths.contains("myapp/.env.development"));
    assertTrue(paths.contains("myapp/docker-compose.override.yml"));
  }

  @Test
  void listFilesUsesForwardSlashes() throws IOException {
    var filesDir = tempDir.resolve("files");
    var nested = filesDir.resolve("project").resolve("config");
    Files.createDirectories(nested);
    Files.writeString(nested.resolve("app.properties"), "key=value");

    var entries = WorkspaceFiles.listFiles(filesDir);

    assertEquals("project/config/app.properties", entries.getFirst().relativePath());
    assertFalse(entries.getFirst().relativePath().contains("\\"));
  }

  @Test
  void resolveFilesDirWorksWithAbsolutePath() throws IOException {
    var filesDir = tempDir.resolve("files");
    Files.createDirectories(filesDir);
    var sailYaml = tempDir.resolve("sail.yaml").toAbsolutePath();
    Files.writeString(sailYaml, "name: test");

    var result = WorkspaceFiles.resolveFilesDir(sailYaml);

    assertNotNull(result);
  }

  @Test
  void listFilesResultIsImmutable() throws IOException {
    var filesDir = tempDir.resolve("files");
    Files.createDirectories(filesDir);
    Files.writeString(filesDir.resolve("file.txt"), "content");

    var entries = WorkspaceFiles.listFiles(filesDir);

    assertThrows(UnsupportedOperationException.class, () -> entries.add(null));
  }

  @Test
  void resolveFilesDirFromSubdirectory() throws IOException {
    var projectDir = tempDir.resolve("myproject");
    Files.createDirectories(projectDir.resolve("files"));
    var sailYaml = projectDir.resolve("sail.yaml");
    Files.writeString(sailYaml, "name: test");

    var result = WorkspaceFiles.resolveFilesDir(sailYaml);

    assertNotNull(result);
    assertTrue(result.toString().endsWith("files"));
  }

  @Test
  void listFilesHandlesHiddenFiles() throws IOException {
    var filesDir = tempDir.resolve("files");
    Files.createDirectories(filesDir);
    Files.writeString(filesDir.resolve(".hidden"), "secret");
    Files.writeString(filesDir.resolve("visible.txt"), "public");

    var entries = WorkspaceFiles.listFiles(filesDir);

    assertEquals(2, entries.size());
    var paths = entries.stream().map(WorkspaceFiles.FileEntry::relativePath).toList();
    assertTrue(paths.contains(".hidden"));
    assertTrue(paths.contains("visible.txt"));
  }

  @Test
  void preservesActualModeRegardlessOfExtension() throws IOException {
    for (var name : java.util.List.of("run", "data.sh")) {
      var file = Files.writeString(tempDir.resolve(name), "content");
      for (var mode : java.util.List.of(0750, 0640, 0600)) {
        WorkspaceFiles.mode(file, mode);
        assertEquals(mode, WorkspaceFiles.mode(file));
      }
    }
  }
}
