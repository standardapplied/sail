/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition.Section;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectDefinitionJsonTest {

  private static final String DOCUMENT =
      """
      {
        "name": "acme",
        "description": "Storefront",
        "resources": {"cpu": 4, "memory": "8GB", "disk": "50GB"},
        "repos": [{"url": "https://github.com/acme/web.git", "path": "web"}],
        "services": {"postgres": {"image": "postgres:16", "ports": [5432]}},
        "processes": {"web": {"command": "npm run dev", "port": 3000}},
        "agent": {"type": "claude-code"},
        "pipeline": {"max_iterations": 5}
      }
      """;

  @Test
  void aDocumentIsReadWholeAndWrittenBackAsTheSameDefinition() {
    var definition = ProjectDefinitionJson.read(DOCUMENT);

    assertEquals("acme", definition.name());
    assertEquals(5, definition.pipeline().maxIterations());
    assertEquals(definition, ProjectDefinitionJson.read(ProjectDefinitionJson.write(definition)));
    assertEquals(definition, ProjectDefinitionJson.validated(definition));
  }

  @Test
  void eachSectionIsTheJsonValueItHoldsAndAnUnsetOneIsNull() {
    var definition = ProjectDefinitionJson.read(DOCUMENT);
    var sections = ProjectDefinitionJson.sections(definition);

    assertEquals(
        List.of(Section.values()).stream().map(Section::key).toList(),
        List.copyOf(sections.keySet()));
    assertEquals("\"Storefront\"", sections.get("description"));
    assertEquals(
        "{\"cpu\": 4, \"memory\": \"8GB\", \"disk\": \"50GB\"}", sections.get("resources"));
    assertNull(sections.get("image"));
    assertEquals(sections.get("repos"), ProjectDefinitionJson.section(definition, Section.REPOS));
    assertEquals(definition, ProjectDefinitionJson.fromSections("acme", sections));
  }

  @Test
  void aSectionWriteSetsOrUnsetsItAndChecksTheDefinitionWhole() {
    var definition = ProjectDefinitionJson.read(DOCUMENT);

    var image = ProjectDefinitionJson.withSection(definition, Section.IMAGE, "\"ubuntu/24.04\"");
    var unset = ProjectDefinitionJson.withSection(definition, Section.PIPELINE, null);

    assertEquals("ubuntu/24.04", image.image());
    assertNull(unset.pipeline());
    assertEquals(definition.services(), unset.services());
    assertEquals(
        "Port 5432 is claimed by both service 'postgres' and process 'web'. Give one of them"
            + " another port.",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    ProjectDefinitionJson.withSection(
                        definition,
                        Section.PROCESSES,
                        "{\"web\": {\"command\": \"x\", \"port\": 5432}}"))
            .getMessage());
    assertEquals(
        "resources.cpu must be a whole number.",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    ProjectDefinitionJson.withSection(
                        definition,
                        Section.RESOURCES,
                        "{\"cpu\": \"4\", \"memory\": \"8GB\", \"disk\": \"50GB\"}"))
            .getMessage());
  }

  @Test
  void aDocumentThatIsNoDefinitionIsRefusedSayingWhy() {
    assertEquals(
        "A project definition is empty.",
        assertThrows(IllegalArgumentException.class, () -> ProjectDefinitionJson.read("  "))
            .getMessage());
    var malformed =
        assertThrows(IllegalArgumentException.class, () -> ProjectDefinitionJson.read("{\"a\": "));
    assertTrue(
        malformed.getMessage().startsWith("A project definition is not JSON: "),
        malformed.getMessage());
    var duplicate =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectDefinitionJson.read("{\"name\": \"a\", \"name\": \"b\"}"));
    assertTrue(
        duplicate.getMessage().startsWith("A project definition is not JSON: "),
        duplicate.getMessage());
    var section =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectDefinitionJson.withSection(
                    ProjectDefinitionJson.read(DOCUMENT), Section.REPOS, "[{"));
    assertTrue(
        section.getMessage().startsWith("The repos section is not JSON: "), section.getMessage());
  }

  @Test
  void aSectionValueOfAnyShapeWritesAsJson() {
    var map = new LinkedHashMap<String, Object>();
    map.put("a", List.of(1, true, "x"));
    map.put("b", null);

    assertEquals("{\"a\": [1, true, \"x\"], \"b\": null}", YamlUtil.dumpJsonValue(map));
    assertEquals("\"t\"", YamlUtil.dumpJsonValue("t"));
    assertEquals("null", YamlUtil.dumpJsonValue(null));
    assertEquals(
        "{\n  \"a\": [\n    1,\n    {}\n  ],\n  \"b\": []\n}",
        YamlUtil.dumpJsonPretty(Map.of("a", List.of(1, Map.of()))).replace("}", "}").length() > 0
            ? YamlUtil.dumpJsonPretty(orderedPretty())
            : "");
  }

  private static Map<String, Object> orderedPretty() {
    var map = new LinkedHashMap<String, Object>();
    map.put("a", List.of(1, Map.of()));
    map.put("b", List.of());
    return map;
  }
}
