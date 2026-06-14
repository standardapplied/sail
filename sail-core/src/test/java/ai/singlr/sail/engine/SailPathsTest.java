/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SailPathsTest {

  @TempDir Path tempDir;

  private static final Path HOME = Path.of(System.getProperty("user.home"));

  @Test
  void sailDirIsUnderHome() {
    assertEquals(HOME.resolve(".sail"), SailPaths.sailDir());
  }

  @Test
  void projectDirIsUnderSingDir() {
    assertEquals(HOME.resolve(".sail/projects/acme-health"), SailPaths.projectDir("acme-health"));
  }

  @Test
  void provisionStateIsInsideProjectDir() {
    var stateFile = SailPaths.provisionState("test");

    assertTrue(stateFile.startsWith(SailPaths.projectDir("test")));
    assertTrue(stateFile.toString().endsWith("provision-state.yaml"));
  }

  @Test
  void projectDirVariesByName() {
    assertNotEquals(SailPaths.projectDir("alpha"), SailPaths.projectDir("beta"));
  }

  @Test
  void hostConfigPathFallsBackToHomeWhenNoSharedCopy() {
    assertEquals(HOME.resolve(".sail/host.yaml"), SailPaths.hostConfigPath(false));
  }

  @Test
  void hostConfigPathPrefersTheSharedSystemCopy() {
    assertEquals(Path.of("/var/lib/sail/host.yaml"), SailPaths.hostConfigPath(true));
  }

  @Test
  void clientConfigPathIsUnderSingDir() {
    assertEquals(HOME.resolve(".sail/config.yaml"), SailPaths.clientConfigPath());
  }

  @Test
  void updateCheckFileIsUnderSingDir() {
    assertEquals(HOME.resolve(".sail/update-check.yaml"), SailPaths.updateCheckFile());
  }

  @Test
  void resolveSailYamlFindsCanonicalFirst() throws Exception {
    var projectDir = HOME.resolve(".sail/projects/test-canonical");
    Files.createDirectories(projectDir);
    var canonical = projectDir.resolve("sail.yaml");
    Files.writeString(canonical, "name: test");
    try {
      var result = SailPaths.resolveSailYaml("test-canonical", "nonexistent.yaml");

      assertEquals(canonical, result);
    } finally {
      Files.deleteIfExists(canonical);
      Files.deleteIfExists(projectDir);
    }
  }

  @Test
  void resolveSailYamlFallsBackToLegacyCanonical() throws Exception {
    var projectDir = HOME.resolve(".sail/projects/test-legacy-canonical");
    Files.createDirectories(projectDir);
    var legacy = projectDir.resolve("sail.yaml");
    Files.writeString(legacy, "name: test");
    try {
      var result = SailPaths.resolveSailYaml("test-legacy-canonical", "nonexistent.yaml");

      assertEquals(legacy, result);
    } finally {
      Files.deleteIfExists(legacy);
      Files.deleteIfExists(projectDir);
    }
  }

  @Test
  void resolveSailYamlFallsBackToExplicitFile() throws Exception {
    var yamlFile = tempDir.resolve("sail.yaml");
    Files.writeString(yamlFile, "name: test");

    var result = SailPaths.resolveSailYaml("nonexistent-project", yamlFile.toString());

    assertEquals(yamlFile, result);
  }

  @Test
  void resolveSailYamlFallsBackToNamedDir() throws Exception {
    var projectDir = tempDir.resolve("my-project");
    Files.createDirectories(projectDir);
    var namedYaml = projectDir.resolve("sail.yaml");
    Files.writeString(namedYaml, "name: my-project");

    var result =
        SailPaths.resolveSailYaml(
            projectDir.toString(), tempDir.resolve("nonexistent.yaml").toString());

    assertEquals(namedYaml, result);
  }

  @Test
  void resolveSailYamlFallsBackToLegacyNamedDir() throws Exception {
    var projectDir = tempDir.resolve("my-legacy-project");
    Files.createDirectories(projectDir);
    var namedYaml = projectDir.resolve("sail.yaml");
    Files.writeString(namedYaml, "name: my-legacy-project");

    var result =
        SailPaths.resolveSailYaml(
            projectDir.toString(), tempDir.resolve("nonexistent.yaml").toString());

    assertEquals(namedYaml, result);
  }

  @Test
  void resolveSailYamlReturnsCanonicalWhenNothingExists() {
    var result = SailPaths.resolveSailYaml("whatever", "/does/not/exist.yaml");

    assertEquals(SailPaths.projectDir("whatever").resolve("sail.yaml"), result);
  }

  @Test
  void resolveSailYamlNullNameReturnsFilePath() {
    var result = SailPaths.resolveSailYaml(null, "sail.yaml");

    assertEquals(Path.of("sail.yaml"), result);
  }

  @Test
  void expandHomeExpandsTilde() {
    var result = SailPaths.expandHome("~/.ssh/id_ed25519");

    assertTrue(result.startsWith("/"));
    assertTrue(result.endsWith("/.ssh/id_ed25519"));
    assertFalse(result.startsWith("~"));
  }

  @Test
  void expandHomeReturnsAbsolutePathUnchanged() {
    assertEquals("/home/user/.ssh/id_ed25519", SailPaths.expandHome("/home/user/.ssh/id_ed25519"));
  }

  @Test
  void expandHomeReturnsNullForNull() {
    assertNull(SailPaths.expandHome(null));
  }

  @Test
  void expandHomeDoesNotExpandMidPath() {
    assertEquals("/some/~/path", SailPaths.expandHome("/some/~/path"));
  }

  @Test
  void dataDirEnvOverrideWinsEvenOverProvisionedSystemDir() {
    assertEquals(Path.of("/custom/dir"), SailPaths.dataDir("/custom/dir", false));
    assertEquals(Path.of("/custom/dir"), SailPaths.dataDir("/custom/dir", true));
  }

  @Test
  void dataDirAutoDetectsProvisionedSystemDir() {
    assertEquals(Path.of("/var/lib/sail"), SailPaths.dataDir(null, true));
  }

  @Test
  void dataDirFallsBackToSailDirWhenUnsetAndNoSystemDb() {
    assertEquals(SailPaths.sailDir(), SailPaths.dataDir(null, false));
    assertEquals(SailPaths.sailDir(), SailPaths.dataDir("   ", false));
  }

  @Test
  void controlPlaneDbIsSailDbUnderDataDir() {
    assertEquals("sail.db", SailPaths.controlPlaneDb().getFileName().toString());
  }
}
