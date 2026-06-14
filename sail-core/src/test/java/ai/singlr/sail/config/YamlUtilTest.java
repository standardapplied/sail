/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YamlUtilTest {

  @Test
  void parseMapReturnsEmptyForNull() {
    assertEquals(Map.of(), YamlUtil.parseMap(null));
  }

  @Test
  void parseMapReturnsEmptyForBlank() {
    assertEquals(Map.of(), YamlUtil.parseMap("  "));
  }

  @Test
  void parseMapParsesYaml() {
    var result = YamlUtil.parseMap("name: acme\nversion: 1");
    assertEquals("acme", result.get("name"));
    assertEquals(1, result.get("version"));
  }

  @Test
  void parseMapParsesJson() {
    var result = YamlUtil.parseMap("{\"name\": \"acme\", \"count\": 42}");
    assertEquals("acme", result.get("name"));
    assertEquals(42, result.get("count"));
  }

  @Test
  void parseListReturnsEmptyForNull() {
    assertEquals(List.of(), YamlUtil.parseList(null));
  }

  @Test
  void parseListParsesJsonArray() {
    var result = YamlUtil.parseList("[{\"name\": \"a\"}, {\"name\": \"b\"}]");
    assertEquals(2, result.size());
    assertEquals("a", result.get(0).get("name"));
    assertEquals("b", result.get(1).get("name"));
  }

  @Test
  void dumpToStringEmitsBlockStyleForHandEditing() {
    var map = new LinkedHashMap<String, Object>();
    map.put("host", "devbox");
    map.put("webauthn", Map.of("origins", List.of("http://localhost:7070")));

    var yaml = YamlUtil.dumpToString(map);

    assertFalse(yaml.contains("{"), yaml);
    assertTrue(yaml.contains("host: devbox\n"), yaml);
    assertTrue(yaml.contains("origins:\n"), yaml);
    assertEquals(map, YamlUtil.parseMap(yaml));
  }

  @Test
  void dumpJsonProducesFlowStyleOutput() {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", "snap-1");
    map.put("created_at", "2026-02-19T10:00:00Z");
    var result = YamlUtil.dumpJson(List.of(map));

    assertTrue(result.startsWith("["));
    assertTrue(result.endsWith("]"));
    assertTrue(result.contains("snap-1"));
    assertTrue(result.contains("2026-02-19T10:00:00Z"));
  }

  @Test
  void dumpJsonEmptyListProducesEmptyArray() {
    var result = YamlUtil.dumpJson(List.of());
    assertEquals("[]", result);
  }

  @Test
  void dumpJsonHandlesSpecialCharacters() {
    var map = new LinkedHashMap<String, Object>();
    map.put("msg", "hello \"world\"");
    var result = YamlUtil.dumpJson(List.of(map));

    assertTrue(result.contains("hello"));
  }

  @Test
  void dumpJsonMapProducesFlowStyleObject() {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", "acme");
    map.put("status", "running");
    var result = YamlUtil.dumpJson(map);

    assertTrue(result.startsWith("{"));
    assertTrue(result.endsWith("}"));
    assertTrue(result.contains("name"));
    assertTrue(result.contains("acme"));
    assertTrue(result.contains("status"));
    assertTrue(result.contains("running"));
  }

  @Test
  void dumpJsonMapWithNumbers() {
    var map = new LinkedHashMap<String, Object>();
    map.put("count", 42);
    map.put("active", true);
    var result = YamlUtil.dumpJson(map);

    assertTrue(result.contains("42"));
    assertTrue(result.contains("true"));
  }

  @Test
  void dumpJsonMapWithNull() {
    var map = new LinkedHashMap<String, Object>();
    map.put("ip", null);
    var result = YamlUtil.dumpJson(map);

    assertTrue(result.contains("null"));
  }

  @Test
  void dumpJsonEscapesControlCharacters() {
    var map = new LinkedHashMap<String, Object>();
    map.put("tab", "a\tb");
    map.put("backspace", "a\bb");
    map.put("formfeed", "a\fb");
    map.put("control", "a\u0001b");
    var result = YamlUtil.dumpJson(map);

    assertTrue(result.contains("a\\tb"), "tab should be escaped");
    assertTrue(result.contains("a\\bb"), "backspace should be escaped");
    assertTrue(result.contains("a\\fb"), "formfeed should be escaped");
    assertTrue(result.contains("a\\u0001b"), "control char should be \\u escaped");
  }

  @Test
  void dumpJsonEscapesNewlinesAndQuotes() {
    var map = new LinkedHashMap<String, Object>();
    map.put("msg", "line1\nline2\r\"quoted\"\\backslash");
    var result = YamlUtil.dumpJson(map);

    assertTrue(result.contains("\\n"), "newline should be escaped");
    assertTrue(result.contains("\\r"), "carriage return should be escaped");
    assertTrue(result.contains("\\\"quoted\\\""), "quotes should be escaped");
    assertTrue(result.contains("\\\\backslash"), "backslash should be escaped");
  }

  @Test
  void dumpToStringProducesYaml() {
    var result = YamlUtil.dumpToString(Map.of("name", "acme"));
    assertTrue(result.contains("name: acme"));
  }
}
