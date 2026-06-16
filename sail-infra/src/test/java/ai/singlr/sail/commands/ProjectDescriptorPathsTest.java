/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.SailPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

class ProjectDescriptorPathsTest {

  @TempDir Path tempDir;

  @Test
  void initDefaultOutputPathUsesCanonicalProjectDir() {
    assertEquals(
        SailPaths.projectDir("acme-health").resolve("sail.yaml"),
        ProjectInitCommand.defaultOutputPath("acme-health"));
  }

  @Test
  void createDefaultDescriptorPathUsesCanonicalProjectDir() {
    assertEquals(
        SailPaths.projectDir("acme-health").resolve("sail.yaml"),
        ProjectCreateCommand.defaultDescriptorPath("acme-health"));
  }

  @Test
  void initNextCreateCommandUsesProjectNameForCanonicalOutput() {
    var outputPath = ProjectInitCommand.defaultOutputPath("acme-health");

    assertEquals(
        "sail project create acme-health",
        ProjectInitCommand.nextCreateCommand("acme-health", outputPath));
  }

  @Test
  void initNextCreateCommandUsesFileFlagForCustomOutput() {
    var outputPath = tempDir.resolve("custom").resolve("sail.yaml");

    assertEquals(
        "sail project create acme-health -f " + outputPath,
        ProjectInitCommand.nextCreateCommand("acme-health", outputPath));
  }

  @Test
  void initOptionDescriptionMentionsCanonicalDefaultPath() throws Exception {
    var description =
        ProjectInitCommand.class
            .getDeclaredField("output")
            .getAnnotation(Option.class)
            .description()[0];

    assertTrue(description.contains(".sail/projects"));
    assertTrue(description.contains("sail.yaml"));
  }

  @Test
  void createParameterDescriptionMentionsCanonicalDefaultPath() throws Exception {
    var description =
        ProjectCreateCommand.class
            .getDeclaredField("name")
            .getAnnotation(Parameters.class)
            .description()[0];

    assertTrue(description.contains(".sail/projects"));
    assertTrue(description.contains("sail.yaml"));
  }

  @Test
  void createResolveSailYamlPathPrefersCanonicalDescriptor() throws Exception {
    var name = "project-path-test-" + System.nanoTime();
    var canonicalPath = ProjectCreateCommand.defaultDescriptorPath(name);
    Files.createDirectories(canonicalPath.getParent());
    Files.writeString(canonicalPath, "name: " + name + "\n");
    try {
      assertEquals(canonicalPath, ProjectCreateCommand.resolveSailYamlPath(name, null));
    } finally {
      Files.deleteIfExists(canonicalPath);
      Files.deleteIfExists(canonicalPath.getParent());
    }
  }

  @Test
  void createResolveSailYamlPathFallsBackToLegacyCanonicalDescriptor() throws Exception {
    var name = "project-legacy-path-test-" + System.nanoTime();
    var projectDir = SailPaths.projectDir(name);
    var legacyPath = projectDir.resolve("sail.yaml");
    Files.createDirectories(projectDir);
    Files.writeString(legacyPath, "name: " + name + "\n");
    try {
      assertEquals(legacyPath, ProjectCreateCommand.resolveSailYamlPath(name, null));
    } finally {
      Files.deleteIfExists(legacyPath);
      Files.deleteIfExists(projectDir);
    }
  }

  @Test
  void createResolveSailYamlPathReturnsCanonicalPathForMissingProject() {
    var name = "missing-project-" + System.nanoTime();

    assertEquals(
        ProjectCreateCommand.defaultDescriptorPath(name),
        ProjectCreateCommand.resolveSailYamlPath(name, null));
  }

  @Test
  void syncProjectBundleCopiesDescriptorAndFilesToCanonicalLocation() throws Exception {
    var sourceDir = tempDir.resolve("source");
    var canonicalDir = tempDir.resolve("canonical");
    var sourceYaml = sourceDir.resolve("sail.yaml");
    var sourceFilesDir = sourceDir.resolve("files");
    Files.createDirectories(sourceFilesDir.resolve("app"));
    Files.createDirectories(sourceFilesDir.resolve("scripts"));
    Files.writeString(sourceYaml, "name: acme-health\n");
    Files.writeString(sourceFilesDir.resolve("app/.env"), "FOO=bar\n");
    Files.writeString(sourceFilesDir.resolve("scripts/start.sh"), "#!/bin/bash\necho ok\n");

    var canonicalYaml = canonicalDir.resolve("sail.yaml");
    ProjectCreateCommand.syncProjectBundle(sourceYaml, canonicalYaml);

    assertEquals("name: acme-health\n", Files.readString(canonicalYaml));
    assertEquals("FOO=bar\n", Files.readString(canonicalDir.resolve("files/app/.env")));
    assertEquals(
        "#!/bin/bash\necho ok\n", Files.readString(canonicalDir.resolve("files/scripts/start.sh")));
  }

  @Test
  void syncProjectBundleRemovesStaleCanonicalFilesWhenSourceHasNone() throws Exception {
    var sourceDir = tempDir.resolve("source");
    var canonicalDir = tempDir.resolve("canonical");
    var sourceYaml = sourceDir.resolve("sail.yaml");
    Files.createDirectories(sourceDir);
    Files.createDirectories(canonicalDir.resolve("files"));
    Files.writeString(sourceYaml, "name: acme-health\n");
    Files.writeString(canonicalDir.resolve("files/old.env"), "STALE=true\n");

    ProjectCreateCommand.syncProjectBundle(sourceYaml, canonicalDir.resolve("sail.yaml"));

    assertFalse(Files.exists(canonicalDir.resolve("files")));
  }

  @Test
  void syncProjectBundleReplacesCanonicalFilesWithSourceBundle() throws Exception {
    var sourceDir = tempDir.resolve("source");
    var canonicalDir = tempDir.resolve("canonical");
    var sourceFilesDir = sourceDir.resolve("files");
    Files.createDirectories(sourceFilesDir);
    Files.createDirectories(canonicalDir.resolve("files"));
    Files.writeString(sourceDir.resolve("sail.yaml"), "name: acme-health\n");
    Files.writeString(sourceFilesDir.resolve("new.env"), "NEW=true\n");
    Files.writeString(canonicalDir.resolve("files/old.env"), "OLD=true\n");

    ProjectCreateCommand.syncProjectBundle(
        sourceDir.resolve("sail.yaml"), canonicalDir.resolve("sail.yaml"));

    assertFalse(Files.exists(canonicalDir.resolve("files/old.env")));
    assertEquals("NEW=true\n", Files.readString(canonicalDir.resolve("files/new.env")));
  }

  @Test
  void createResolveSailYamlPathUsesExplicitFileWhenProvided() throws Exception {
    var explicitFile = tempDir.resolve("custom.yaml");
    Files.writeString(explicitFile, "name: explicit\n");

    assertEquals(
        explicitFile,
        ProjectCreateCommand.resolveSailYamlPath("acme-health", explicitFile.toString()));
  }
}
