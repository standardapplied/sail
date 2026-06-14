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
import java.nio.file.attribute.PosixFilePermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceFilesTest {

  @TempDir Path tempDir;

  @Test
  void resolveFilesDirFindsAdjacentDirectory() throws IOException {
    var filesDir = tempDir.resolve("files");
    Files.createDirectories(filesDir);
    var singYaml = tempDir.resolve("sail.yaml");
    Files.writeString(singYaml, "name: test");

    var result = WorkspaceFiles.resolveFilesDir(singYaml);

    assertNotNull(result);
    assertEquals(filesDir.toAbsolutePath(), result.toAbsolutePath());
  }

  @Test
  void resolveFilesDirReturnsNullWhenNoFilesDir() throws IOException {
    var singYaml = tempDir.resolve("sail.yaml");
    Files.writeString(singYaml, "name: test");

    var result = WorkspaceFiles.resolveFilesDir(singYaml);

    assertNull(result);
  }

  @Test
  void resolveFilesDirReturnsNullWhenFilesIsFile() throws IOException {
    var filesFile = tempDir.resolve("files");
    Files.writeString(filesFile, "not a directory");
    var singYaml = tempDir.resolve("sail.yaml");
    Files.writeString(singYaml, "name: test");

    var result = WorkspaceFiles.resolveFilesDir(singYaml);

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
    var singYaml = tempDir.resolve("sail.yaml").toAbsolutePath();
    Files.writeString(singYaml, "name: test");

    var result = WorkspaceFiles.resolveFilesDir(singYaml);

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
    var singYaml = projectDir.resolve("sail.yaml");
    Files.writeString(singYaml, "name: test");

    var result = WorkspaceFiles.resolveFilesDir(singYaml);

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
  void isExecutableReturnsTrueForShExtension() {
    assertTrue(WorkspaceFiles.isExecutable("setup.sh"));
    assertTrue(WorkspaceFiles.isExecutable("path/to/install.sh"));
    assertTrue(WorkspaceFiles.isExecutable("DEPLOY.SH"));
  }

  @Test
  void isExecutableReturnsFalseForNonExecutableFiles() {
    assertFalse(WorkspaceFiles.isExecutable(".env"));
    assertFalse(WorkspaceFiles.isExecutable("config.yaml"));
    assertFalse(WorkspaceFiles.isExecutable("readme.txt"));
    assertFalse(WorkspaceFiles.isExecutable("app.sh.bak"));
  }

  @Test
  void isExecutableReturnsFalseForNull() {
    assertFalse(WorkspaceFiles.isExecutable(null));
  }

  @Test
  void setExecutableIfNeededSetsPermissionsOnShFile() throws IOException {
    var script = tempDir.resolve("setup.sh");
    Files.writeString(script, "#!/bin/bash");

    WorkspaceFiles.setExecutableIfNeeded(script);

    var perms = Files.getPosixFilePermissions(script);
    assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE));
    assertTrue(perms.contains(PosixFilePermission.GROUP_EXECUTE));
    assertTrue(perms.contains(PosixFilePermission.OTHERS_EXECUTE));
  }

  @Test
  void setExecutableIfNeededSkipsNonShFile() throws IOException {
    var envFile = tempDir.resolve(".env");
    Files.writeString(envFile, "KEY=VALUE");

    WorkspaceFiles.setExecutableIfNeeded(envFile);

    var perms = Files.getPosixFilePermissions(envFile);
    assertFalse(perms.contains(PosixFilePermission.OWNER_EXECUTE));
  }

  @Test
  void setExecutableIfNeededPreservesExistingPermissions() throws IOException {
    var script = tempDir.resolve("run.sh");
    Files.writeString(script, "#!/bin/bash");

    WorkspaceFiles.setExecutableIfNeeded(script);

    var perms = Files.getPosixFilePermissions(script);
    assertTrue(perms.contains(PosixFilePermission.OWNER_READ));
    assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE));
    assertTrue(perms.contains(PosixFilePermission.OWNER_EXECUTE));
  }
}
