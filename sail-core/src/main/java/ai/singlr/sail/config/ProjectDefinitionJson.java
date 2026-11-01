/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.config.ProjectDefinition.Section;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one codec of a {@link ProjectDefinition}: its JSON document, and each of its sections as the
 * JSON value it holds. Every definition this reads is checked whole, a section's own invariants and
 * those across sections (a port claimed once), so whatever a door writes is a definition the
 * catalog can hold.
 */
public final class ProjectDefinitionJson {

  private ProjectDefinitionJson() {}

  /** The definition {@code document} holds, checked whole. */
  public static ProjectDefinition read(String document) {
    return ProjectDefinition.fromMap(parse(document, "A project definition"));
  }

  /** {@code definition} as its JSON document. */
  public static String write(ProjectDefinition definition) {
    return YamlUtil.dumpJson(definition.toMap());
  }

  /** {@code definition} as it is once written and read back: checked whole, refused if not. */
  public static ProjectDefinition validated(ProjectDefinition definition) {
    return ProjectDefinition.fromMap(definition.toMap());
  }

  /** The JSON value {@code definition} holds in {@code section}, {@code null} when unset. */
  public static String section(ProjectDefinition definition, Section section) {
    return value(definition.toMap().get(section.key()));
  }

  /** Each section's JSON value in {@code definition}, by key; an unset section is {@code null}. */
  public static Map<String, String> sections(ProjectDefinition definition) {
    var map = definition.toMap();
    var sections = new LinkedHashMap<String, String>();
    for (var section : Section.values()) {
      sections.put(section.key(), value(map.get(section.key())));
    }
    return sections;
  }

  /**
   * The definition of {@code name} whose sections are {@code sections}, each a JSON value as {@link
   * #sections} writes it, checked whole.
   */
  public static ProjectDefinition fromSections(String name, Map<String, String> sections) {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", name);
    sections.forEach(
        (key, json) -> {
          var value = parseValue(json, key);
          if (value != null) {
            map.put(key, value);
          }
        });
    return ProjectDefinition.fromMap(map);
  }

  /**
   * {@code definition} with {@code section} set to the JSON value {@code json} ({@code null} unsets
   * it), checked whole: what one section's write leaves the definition as.
   */
  public static ProjectDefinition withSection(
      ProjectDefinition definition, Section section, String json) {
    var map = definition.toMap();
    var value = parseValue(json, section.key());
    if (value == null) {
      map.remove(section.key());
    } else {
      map.put(section.key(), value);
    }
    return ProjectDefinition.fromMap(map);
  }

  private static Object parseValue(String json, String key) {
    if (json == null) {
      return null;
    }
    var wrapped = parse("{\"value\": " + json + "}", "The " + key + " section");
    return wrapped.get("value");
  }

  private static Map<String, Object> parse(String json, String what) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException(what + " is empty.");
    }
    try {
      return YamlUtil.parseMapStrict(json);
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(what + " is not JSON: " + e.getMessage(), e);
    }
  }

  private static String value(Object value) {
    return value == null ? null : YamlUtil.dumpJsonValue(value);
  }
}
