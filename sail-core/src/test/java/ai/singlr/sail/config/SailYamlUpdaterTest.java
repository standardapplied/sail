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

import java.util.List;
import org.junit.jupiter.api.Test;

class SailYamlUpdaterTest {

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

  private static SailYaml parse(String yaml) {
    return SailYaml.fromMap(YamlUtil.parseMap(yaml));
  }

  @Test
  void addServiceToExistingServicesSection() {
    var redis = new SailYaml.Service("redis:7", List.of(6379), null, null, null);
    var updated = SailYamlUpdater.addService(parse(BASE_YAML), "redis", redis);

    assertEquals(2, updated.services().size());
    assertNotNull(updated.services().get("postgres"));
    assertEquals("redis:7", updated.services().get("redis").image());
  }

  @Test
  void addServiceCreatesServicesSectionWhenMissing() {
    var pg = new SailYaml.Service("postgres:16", List.of(5432), null, null, null);
    var updated = SailYamlUpdater.addService(parse(MINIMAL_YAML), "postgres", pg);

    assertEquals(1, updated.services().size());
    assertEquals("postgres:16", updated.services().get("postgres").image());
  }

  @Test
  void addServiceThrowsOnDuplicate() {
    var pg = new SailYaml.Service("postgres:17", List.of(5432), null, null, null);
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> SailYamlUpdater.addService(parse(BASE_YAML), "postgres", pg));
    assertTrue(ex.getMessage().contains("already exists"));
  }

  @Test
  void addServiceDoesNotMutateTheInput() {
    var input = parse(BASE_YAML);
    SailYamlUpdater.addService(
        input, "redis", new SailYaml.Service("redis:7", null, null, null, null));
    assertEquals(1, input.services().size(), "the original definition is untouched");
  }

  @Test
  void addServicePreservesOtherSections() {
    var redis = new SailYaml.Service("redis:7", List.of(6379), null, null, null);
    var updated = SailYamlUpdater.addService(parse(BASE_YAML), "redis", redis);

    assertEquals("my-project", updated.name());
    assertEquals(2, updated.resources().cpu());
    assertEquals(1, updated.repos().size());
    assertEquals("dev", updated.ssh().user());
  }

  @Test
  void removeServiceFromExistingSection() {
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
          redis:
            image: redis:7
        """;
    var updated = SailYamlUpdater.removeService(parse(yaml), "redis");

    assertEquals(1, updated.services().size());
    assertNotNull(updated.services().get("postgres"));
    assertNull(updated.services().get("redis"));
  }

  @Test
  void removeServiceNullsOutSectionWhenLastRemoved() {
    var updated = SailYamlUpdater.removeService(parse(BASE_YAML), "postgres");
    assertNull(updated.services());
  }

  @Test
  void removeServiceThrowsWhenNotFound() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> SailYamlUpdater.removeService(parse(BASE_YAML), "nonexistent"));
    assertTrue(ex.getMessage().contains("not found"));
  }

  @Test
  void removeServiceThrowsWhenNoServicesSection() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> SailYamlUpdater.removeService(parse(MINIMAL_YAML), "postgres"));
    assertTrue(ex.getMessage().contains("not found"));
  }

  @Test
  void addRepoToExistingReposSection() {
    var repo = new SailYaml.Repo("https://github.com/org/frontend.git", "frontend", "main");
    var updated = SailYamlUpdater.addRepo(parse(BASE_YAML), repo);

    assertEquals(2, updated.repos().size());
    assertEquals("backend", updated.repos().get(0).path());
    assertEquals("frontend", updated.repos().get(1).path());
    assertEquals("main", updated.repos().get(1).branch());
  }

  @Test
  void addRepoCreatesReposSectionWhenMissing() {
    var repo = new SailYaml.Repo("https://github.com/org/app.git", "app", null);
    var updated = SailYamlUpdater.addRepo(parse(MINIMAL_YAML), repo);

    assertEquals(1, updated.repos().size());
    assertEquals("app", updated.repos().getFirst().path());
  }

  @Test
  void addRepoThrowsOnDuplicatePath() {
    var repo = new SailYaml.Repo("https://github.com/other/backend.git", "backend", null);
    var ex =
        assertThrows(
            IllegalStateException.class, () -> SailYamlUpdater.addRepo(parse(BASE_YAML), repo));
    assertTrue(ex.getMessage().contains("already exists"));
  }

  @Test
  void addRepoPreservesOtherSections() {
    var repo = new SailYaml.Repo("https://github.com/org/frontend.git", "frontend", null);
    var updated = SailYamlUpdater.addRepo(parse(BASE_YAML), repo);

    assertEquals("my-project", updated.name());
    assertEquals(1, updated.services().size());
    assertEquals("dev", updated.ssh().user());
  }

  @Test
  void updateResourcesChangesRequestedFieldsAndPreservesOtherSections() {
    var updated = SailYamlUpdater.updateResources(parse(BASE_YAML), 4, "16GB", null);

    assertEquals(4, updated.resources().cpu());
    assertEquals("16GB", updated.resources().memory());
    assertEquals("50GB", updated.resources().disk());
    assertEquals("my-project", updated.name());
    assertEquals("dev", updated.ssh().user());
    assertEquals(1, updated.services().size());
  }

  @Test
  void updateResourcesNormalizesBareSizes() {
    var updated = SailYamlUpdater.updateResources(parse(BASE_YAML), null, "32", "120");

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
}
