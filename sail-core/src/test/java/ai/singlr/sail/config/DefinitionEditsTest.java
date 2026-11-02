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

class DefinitionEditsTest {

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

  private static ProjectDefinition parse(String yaml) {
    return ProjectDefinition.fromMap(YamlUtil.parseMap(yaml));
  }

  @Test
  void addServiceToExistingServicesSection() {
    var redis = new ProjectDefinition.Service("redis:7", List.of(6379), null, null, null);
    var updated = DefinitionEdits.addService(parse(BASE_YAML), "redis", redis);

    assertEquals(List.of("postgres", "redis"), List.copyOf(updated.services().keySet()));
    assertEquals("redis:7", updated.services().get("redis").image());
  }

  @Test
  void servicesKeepTheirOrderAcrossAddsAndRemovesSoTheirJsonAndTagNeverShuffle() {
    var config = parse(BASE_YAML);
    for (var name : List.of("redis", "kafka", "minio", "mailpit", "zookeeper", "elastic")) {
      config =
          DefinitionEdits.addService(
              config, name, new ProjectDefinition.Service(name + ":1", null, null, null, null));
    }
    config = DefinitionEdits.removeService(config, "minio");

    assertEquals(
        List.of("postgres", "redis", "kafka", "mailpit", "zookeeper", "elastic"),
        List.copyOf(config.services().keySet()));
    assertEquals(
        ProjectDefinitionJson.section(config, ProjectDefinition.Section.SERVICES),
        ProjectDefinitionJson.section(
            ProjectDefinitionJson.validated(config), ProjectDefinition.Section.SERVICES));
  }

  @Test
  void addServiceCreatesServicesSectionWhenMissing() {
    var pg = new ProjectDefinition.Service("postgres:16", List.of(5432), null, null, null);
    var updated = DefinitionEdits.addService(parse(MINIMAL_YAML), "postgres", pg);

    assertEquals(1, updated.services().size());
    assertEquals("postgres:16", updated.services().get("postgres").image());
  }

  @Test
  void addServiceThrowsOnDuplicate() {
    var pg = new ProjectDefinition.Service("postgres:17", List.of(5432), null, null, null);
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> DefinitionEdits.addService(parse(BASE_YAML), "postgres", pg));
    assertTrue(ex.getMessage().contains("already exists"));
  }

  @Test
  void addServiceDoesNotMutateTheInput() {
    var input = parse(BASE_YAML);
    DefinitionEdits.addService(
        input, "redis", new ProjectDefinition.Service("redis:7", null, null, null, null));
    assertEquals(1, input.services().size(), "the original definition is untouched");
  }

  @Test
  void addServicePreservesOtherSections() {
    var redis = new ProjectDefinition.Service("redis:7", List.of(6379), null, null, null);
    var updated = DefinitionEdits.addService(parse(BASE_YAML), "redis", redis);

    assertEquals("my-project", updated.name());
    assertEquals(2, updated.resources().cpu());
    assertEquals(1, updated.repos().size());
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
    var updated = DefinitionEdits.removeService(parse(yaml), "redis");

    assertEquals(1, updated.services().size());
    assertNotNull(updated.services().get("postgres"));
    assertNull(updated.services().get("redis"));
  }

  @Test
  void removeServiceNullsOutSectionWhenLastRemoved() {
    var updated = DefinitionEdits.removeService(parse(BASE_YAML), "postgres");
    assertNull(updated.services());
  }

  @Test
  void removeServiceThrowsWhenNotFound() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> DefinitionEdits.removeService(parse(BASE_YAML), "nonexistent"));
    assertTrue(ex.getMessage().contains("not found"));
  }

  @Test
  void removeServiceThrowsWhenNoServicesSection() {
    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> DefinitionEdits.removeService(parse(MINIMAL_YAML), "postgres"));
    assertTrue(ex.getMessage().contains("not found"));
  }

  @Test
  void addRepoToExistingReposSection() {
    var repo =
        new ProjectDefinition.Repo("https://github.com/org/frontend.git", "frontend", "main");
    var updated = DefinitionEdits.addRepo(parse(BASE_YAML), repo);

    assertEquals(2, updated.repos().size());
    assertEquals("backend", updated.repos().get(0).path());
    assertEquals("frontend", updated.repos().get(1).path());
    assertEquals("main", updated.repos().get(1).branch());
  }

  @Test
  void addRepoCreatesReposSectionWhenMissing() {
    var repo = new ProjectDefinition.Repo("https://github.com/org/app.git", "app", null);
    var updated = DefinitionEdits.addRepo(parse(MINIMAL_YAML), repo);

    assertEquals(1, updated.repos().size());
    assertEquals("app", updated.repos().getFirst().path());
  }

  @Test
  void addRepoThrowsOnDuplicatePath() {
    var repo = new ProjectDefinition.Repo("https://github.com/other/backend.git", "backend", null);
    var ex =
        assertThrows(
            IllegalStateException.class, () -> DefinitionEdits.addRepo(parse(BASE_YAML), repo));
    assertTrue(ex.getMessage().contains("already exists"));
  }

  @Test
  void addRepoPreservesOtherSections() {
    var repo = new ProjectDefinition.Repo("https://github.com/org/frontend.git", "frontend", null);
    var updated = DefinitionEdits.addRepo(parse(BASE_YAML), repo);

    assertEquals("my-project", updated.name());
    assertEquals(1, updated.services().size());
  }

  @Test
  void updateResourcesChangesRequestedFieldsAndPreservesOtherSections() {
    var updated = DefinitionEdits.updateResources(parse(BASE_YAML), 4, "16GB", null);

    assertEquals(4, updated.resources().cpu());
    assertEquals("16GB", updated.resources().memory());
    assertEquals("50GB", updated.resources().disk());
    assertEquals("my-project", updated.name());
    assertEquals(1, updated.services().size());
  }

  @Test
  void updateResourcesNormalizesBareSizes() {
    var updated = DefinitionEdits.updateResources(parse(BASE_YAML), null, "32", "120");

    assertEquals("32GB", updated.resources().memory());
    assertEquals("120GB", updated.resources().disk());
  }

  @Test
  void mutateThenSerializePreservesEverySectionIncludingProcesses() {
    var full =
        """
        name: my-project
        resources:
          cpu: 2
          memory: 8GB
          disk: 50GB
        image: ubuntu/24.04
        processes:
          web:
            command: "npm run dev"
            workdir: webapp
        agent:
          type: claude-code
          config:
            model: opus
        """;

    var mutated =
        DefinitionEdits.addRepo(
            parse(full), new ProjectDefinition.Repo("https://x/y.git", "y", null));
    var reparsed =
        ProjectDefinition.fromMap(YamlUtil.parseMap(YamlUtil.dumpToString(mutated.toMap())));

    assertNotNull(reparsed.processes(), "processes survives the mutate-and-serialize round trip");
    assertEquals("npm run dev", reparsed.processes().get("web").command());
    assertEquals("opus", reparsed.agent().config().get("model"), "agent.config survives too");
    assertEquals("y", reparsed.repos().getFirst().path(), "and the mutation applied");
  }

  @Test
  void mergeResourcesRejectsInvalidValues() {
    var current = new ProjectDefinition.Resources(2, "8GB", "50GB");

    var cpuEx =
        assertThrows(
            IllegalArgumentException.class,
            () -> DefinitionEdits.mergeResources(current, 0, null, null));
    assertTrue(cpuEx.getMessage().contains("cpu"));

    var memoryEx =
        assertThrows(
            IllegalArgumentException.class,
            () -> DefinitionEdits.mergeResources(current, null, "   ", null));
    assertTrue(memoryEx.getMessage().contains("memory"));

    var diskEx =
        assertThrows(
            IllegalArgumentException.class,
            () -> DefinitionEdits.mergeResources(current, null, null, " "));
    assertTrue(diskEx.getMessage().contains("disk"));
  }
}
