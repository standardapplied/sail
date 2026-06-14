/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SailYamlUpdaterTest {

  @TempDir java.nio.file.Path tempDir;

  private static final String BASE_YAML =
      """
      name: my-project
      resources:
        cpu: 2
        memory: 8GB
        disk: 50GB
      image: ubuntu/24.04
      services:
        postgres:
          image: postgres:16
          ports: [5432]
      repos:
        - url: "https://github.com/org/backend.git"
          path: backend
      ssh:
        user: dev
      """;

  private static final String MINIMAL_YAML =
      """
      name: bare-project
      resources:
        cpu: 2
        memory: 8GB
        disk: 50GB
      image: ubuntu/24.04
      """;

  @Test
  void addServiceToExistingServicesSection() throws Exception {
    var path = writeTempYaml(BASE_YAML);

    var redis = new SailYaml.Service("redis:7", List.of(6379), null, null, null);
    var updated = SailYamlUpdater.addService(path, "redis", redis);

    assertEquals(2, updated.services().size());
    assertNotNull(updated.services().get("postgres"));
    assertNotNull(updated.services().get("redis"));
    assertEquals("redis:7", updated.services().get("redis").image());

    var reloaded = SailYaml.fromMap(YamlUtil.parseFile(path));
    assertEquals(2, reloaded.services().size());
  }

  @Test
  void addServiceCreatesServicesSectionWhenMissing() throws Exception {
    var path = writeTempYaml(MINIMAL_YAML);

    var pg = new SailYaml.Service("postgres:16", List.of(5432), null, null, null);
    var updated = SailYamlUpdater.addService(path, "postgres", pg);

    assertEquals(1, updated.services().size());
    assertEquals("postgres:16", updated.services().get("postgres").image());
  }

  @Test
  void addServiceThrowsOnDuplicate() throws Exception {
    var path = writeTempYaml(BASE_YAML);

    var pg = new SailYaml.Service("postgres:17", List.of(5432), null, null, null);
    var ex =
        assertThrows(
            IllegalStateException.class, () -> SailYamlUpdater.addService(path, "postgres", pg));

    assertTrue(ex.getMessage().contains("already exists"));
  }

  @Test
  void addServicePreservesOtherSections() throws Exception {
    var path = writeTempYaml(BASE_YAML);

    var redis = new SailYaml.Service("redis:7", List.of(6379), null, null, null);
    var updated = SailYamlUpdater.addService(path, "redis", redis);

    assertEquals("my-project", updated.name());
    assertEquals(2, updated.resources().cpu());
    assertEquals(1, updated.repos().size());
    assertEquals("dev", updated.ssh().user());
  }

  @Test
  void removeServiceFromExistingSection() throws Exception {
    var yaml =
        """
        name: my-project
        resources:
          cpu: 2
          memory: 8GB
          disk: 50GB
        image: ubuntu/24.04
        services:
          postgres:
            image: postgres:16
            ports: [5432]
          redis:
            image: redis:7
            ports: [6379]
        ssh:
          user: dev
        """;
    var path = writeTempYaml(yaml);

    var updated = SailYamlUpdater.removeService(path, "redis");

    assertEquals(1, updated.services().size());
    assertNotNull(updated.services().get("postgres"));
    assertNull(updated.services().get("redis"));

    var reloaded = SailYaml.fromMap(YamlUtil.parseFile(path));
    assertEquals(1, reloaded.services().size());
  }

  @Test
  void removeServiceNullsOutSectionWhenLastRemoved() throws Exception {
    var path = writeTempYaml(BASE_YAML);

    var updated = SailYamlUpdater.removeService(path, "postgres");

    assertNull(updated.services());
  }

  @Test
  void removeServiceThrowsWhenNotFound() throws Exception {
    var path = writeTempYaml(BASE_YAML);

    var ex =
        assertThrows(
            IllegalStateException.class, () -> SailYamlUpdater.removeService(path, "nonexistent"));
    assertTrue(ex.getMessage().contains("not found"));
  }

  @Test
  void removeServiceThrowsWhenNoServicesSection() throws Exception {
    var path = writeTempYaml(MINIMAL_YAML);

    var ex =
        assertThrows(
            IllegalStateException.class, () -> SailYamlUpdater.removeService(path, "postgres"));
    assertTrue(ex.getMessage().contains("not found"));
  }

  @Test
  void removeServicePreservesOtherSections() throws Exception {
    var yaml =
        """
        name: my-project
        resources:
          cpu: 2
          memory: 8GB
          disk: 50GB
        image: ubuntu/24.04
        services:
          postgres:
            image: postgres:16
            ports: [5432]
          redis:
            image: redis:7
            ports: [6379]
        repos:
          - url: "https://github.com/org/backend.git"
            path: backend
        ssh:
          user: dev
        """;
    var path = writeTempYaml(yaml);

    var updated = SailYamlUpdater.removeService(path, "redis");

    assertEquals("my-project", updated.name());
    assertEquals(2, updated.resources().cpu());
    assertEquals(1, updated.repos().size());
    assertEquals("dev", updated.ssh().user());
  }

  @Test
  void addRepoToExistingReposSection() throws Exception {
    var path = writeTempYaml(BASE_YAML);

    var repo = new SailYaml.Repo("https://github.com/org/frontend.git", "frontend", "main");
    var updated = SailYamlUpdater.addRepo(path, repo);

    assertEquals(2, updated.repos().size());
    assertEquals("backend", updated.repos().get(0).path());
    assertEquals("frontend", updated.repos().get(1).path());
    assertEquals("main", updated.repos().get(1).branch());

    var reloaded = SailYaml.fromMap(YamlUtil.parseFile(path));
    assertEquals(2, reloaded.repos().size());
  }

  @Test
  void addRepoCreatesReposSectionWhenMissing() throws Exception {
    var path = writeTempYaml(MINIMAL_YAML);

    var repo = new SailYaml.Repo("https://github.com/org/app.git", "app", null);
    var updated = SailYamlUpdater.addRepo(path, repo);

    assertEquals(1, updated.repos().size());
    assertEquals("app", updated.repos().getFirst().path());
  }

  @Test
  void addRepoThrowsOnDuplicatePath() throws Exception {
    var path = writeTempYaml(BASE_YAML);

    var repo = new SailYaml.Repo("https://github.com/other/backend.git", "backend", null);
    var ex = assertThrows(IllegalStateException.class, () -> SailYamlUpdater.addRepo(path, repo));

    assertTrue(ex.getMessage().contains("already exists"));
  }

  @Test
  void addRepoPreservesOtherSections() throws Exception {
    var path = writeTempYaml(BASE_YAML);

    var repo = new SailYaml.Repo("https://github.com/org/frontend.git", "frontend", null);
    var updated = SailYamlUpdater.addRepo(path, repo);

    assertEquals("my-project", updated.name());
    assertEquals(1, updated.services().size());
    assertEquals("dev", updated.ssh().user());
  }

  @Test
  void addServiceThrowsWhenFileNotFound() {
    var path = tempDir.resolve("nonexistent.yaml");
    var svc = new SailYaml.Service("redis:7", List.of(6379), null, null, null);

    var ex =
        assertThrows(
            IllegalStateException.class, () -> SailYamlUpdater.addService(path, "redis", svc));
    assertTrue(ex.getMessage().contains("not found"));
  }

  @Test
  void addRepoThrowsWhenFileNotFound() {
    var path = tempDir.resolve("nonexistent.yaml");
    var repo = new SailYaml.Repo("https://github.com/org/app.git", "app", null);

    var ex = assertThrows(IllegalStateException.class, () -> SailYamlUpdater.addRepo(path, repo));
    assertTrue(ex.getMessage().contains("not found"));
  }

  @Test
  void updateResourcesChangesRequestedFieldsAndPreservesOtherSections() throws Exception {
    var path = writeTempYaml(BASE_YAML);

    var updated = SailYamlUpdater.updateResources(path, 4, "16GB", null);

    assertEquals(4, updated.resources().cpu());
    assertEquals("16GB", updated.resources().memory());
    assertEquals("50GB", updated.resources().disk());
    assertEquals("my-project", updated.name());
    assertEquals("dev", updated.ssh().user());
    assertEquals(1, updated.services().size());

    var reloaded = SailYaml.fromMap(YamlUtil.parseFile(path));
    assertEquals(4, reloaded.resources().cpu());
    assertEquals("16GB", reloaded.resources().memory());
    assertEquals("50GB", reloaded.resources().disk());
  }

  @Test
  void updateResourcesNormalizesBareSizes() throws Exception {
    var path = writeTempYaml(BASE_YAML);

    var updated = SailYamlUpdater.updateResources(path, null, "32", "120");

    assertEquals("32GB", updated.resources().memory());
    assertEquals("120GB", updated.resources().disk());
  }

  @Test
  void mergeResourcesRejectsInvalidValues() {
    var current = new SailYaml.Resources(2, "8GB", "50GB");

    var cpuEx =
        assertThrows(
            IllegalArgumentException.class,
            () -> SailYamlUpdater.mergeResources(current, 0, null, null));
    assertTrue(cpuEx.getMessage().contains("cpu"));

    var memoryEx =
        assertThrows(
            IllegalArgumentException.class,
            () -> SailYamlUpdater.mergeResources(current, null, "   ", null));
    assertTrue(memoryEx.getMessage().contains("memory"));

    var diskEx =
        assertThrows(
            IllegalArgumentException.class,
            () -> SailYamlUpdater.mergeResources(current, null, null, " "));
    assertTrue(diskEx.getMessage().contains("disk"));
  }

  private java.nio.file.Path writeTempYaml(String content) throws Exception {
    var path = tempDir.resolve("sail.yaml");
    Files.writeString(path, content);
    return path;
  }
}
