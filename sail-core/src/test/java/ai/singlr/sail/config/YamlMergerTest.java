/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YamlMergerTest {

  @Test
  void scalarOverrideWins() {
    var base = Map.<String, Object>of("name", "base-name", "cpu", 2);
    var override = Map.<String, Object>of("name", "override-name");

    var result = YamlMerger.deepMerge(base, override);

    assertEquals("override-name", result.get("name"));
    assertEquals(2, result.get("cpu"));
  }

  @Test
  void deepMergeNestedMaps() {
    var base = Map.<String, Object>of("resources", Map.of("cpu", 2, "memory", "8GB"));
    var override = Map.<String, Object>of("resources", Map.of("cpu", 4));

    var result = YamlMerger.deepMerge(base, override);

    @SuppressWarnings("unchecked")
    var resources = (Map<String, Object>) result.get("resources");
    assertEquals(4, resources.get("cpu"));
    assertEquals("8GB", resources.get("memory"));
  }

  @Test
  void listUnionDeduplicates() {
    var base = Map.<String, Object>of("packages", List.of("curl", "jq", "git"));
    var override = Map.<String, Object>of("packages", List.of("jq", "postgresql-client-16"));

    var result = YamlMerger.deepMerge(base, override);

    @SuppressWarnings("unchecked")
    var packages = (List<String>) result.get("packages");
    assertEquals(List.of("curl", "jq", "git", "postgresql-client-16"), packages);
  }

  @Test
  void overrideAddsNewKeys() {
    var base = Map.<String, Object>of("name", "proj");
    var override = Map.<String, Object>of("description", "A new project");

    var result = YamlMerger.deepMerge(base, override);

    assertEquals("proj", result.get("name"));
    assertEquals("A new project", result.get("description"));
  }

  @Test
  void nullOverridePreservesBase() {
    var base = new LinkedHashMap<String, Object>();
    base.put("name", "proj");
    base.put("description", "keep me");
    var override = new LinkedHashMap<String, Object>();
    override.put("description", null);

    var result = YamlMerger.deepMerge(base, override);

    assertEquals("keep me", result.get("description"));
  }

  @Test
  void emptyMapsHandled() {
    var result1 = YamlMerger.deepMerge(Map.of(), Map.of("name", "proj"));
    assertEquals("proj", result1.get("name"));

    var result2 = YamlMerger.deepMerge(Map.of("name", "proj"), Map.of());
    assertEquals("proj", result2.get("name"));

    var result3 = YamlMerger.deepMerge(Map.of(), Map.of());
    assertTrue(result3.isEmpty());
  }

  @Test
  void deeplyNestedMerge() {
    var base =
        Map.<String, Object>of(
            "services", Map.of("postgres", Map.of("image", "postgres:16", "ports", List.of(5432))));
    var override =
        Map.<String, Object>of(
            "services",
            Map.of(
                "postgres", Map.of("image", "postgres:17"), "redis", Map.of("image", "redis:7")));

    var result = YamlMerger.deepMerge(base, override);

    @SuppressWarnings("unchecked")
    var services = (Map<String, Object>) result.get("services");
    @SuppressWarnings("unchecked")
    var postgres = (Map<String, Object>) services.get("postgres");
    assertEquals("postgres:17", postgres.get("image"));
    assertEquals(List.of(5432), postgres.get("ports"));
    assertNotNull(services.get("redis"));
  }

  @Test
  void doesNotMutateInputs() {
    var base = new LinkedHashMap<String, Object>();
    base.put("name", "base");
    var override = new LinkedHashMap<String, Object>();
    override.put("name", "override");

    YamlMerger.deepMerge(base, override);

    assertEquals("base", base.get("name"));
    assertEquals("override", override.get("name"));
  }

  @Test
  void overrideScalarReplacesBaseList() {
    var base = Map.<String, Object>of("field", List.of("a", "b"));
    var override = Map.<String, Object>of("field", "scalar-value");

    var result = YamlMerger.deepMerge(base, override);

    assertEquals("scalar-value", result.get("field"));
  }

  @Test
  void overrideMapReplacesBaseScalar() {
    var base = Map.<String, Object>of("field", "scalar");
    var override = Map.<String, Object>of("field", Map.of("nested", "value"));

    var result = YamlMerger.deepMerge(base, override);

    @SuppressWarnings("unchecked")
    var field = (Map<String, Object>) result.get("field");
    assertEquals("value", field.get("nested"));
  }

  @Test
  void listUnionPreservesOrder() {
    var base = Map.<String, Object>of("items", new ArrayList<>(List.of("c", "a")));
    var override = Map.<String, Object>of("items", List.of("b", "a", "d"));

    var result = YamlMerger.deepMerge(base, override);

    @SuppressWarnings("unchecked")
    var items = (List<String>) result.get("items");
    assertEquals(List.of("c", "a", "b", "d"), items);
  }
}
