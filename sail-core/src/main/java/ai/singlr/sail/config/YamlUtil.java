/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

/**
 * Thin facade over SnakeYAML Engine for parsing and emitting YAML. Also handles JSON since YAML 1.2
 * is a strict superset of JSON.
 */
public final class YamlUtil {

  private YamlUtil() {}

  /** Parse a YAML or JSON string into a Map. Returns empty map for null/blank input. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseMap(String yamlOrJson) {
    if (Strings.isBlank(yamlOrJson)) {
      return Map.of();
    }
    var load = new Load(LoadSettings.builder().build());
    var result = load.loadFromString(yamlOrJson);
    return result instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  /**
   * Parses a JSON/YAML object, rejecting duplicate keys. Used for security-critical inputs (e.g.
   * {@code clientDataJSON}) where a document carrying two values for the same field could let a
   * verifier read one while a signature covers the other — a parser-divergence attack the default
   * last-key-wins parse would silently allow.
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseMapStrict(String yamlOrJson) {
    if (Strings.isBlank(yamlOrJson)) {
      return Map.of();
    }
    var load = new Load(LoadSettings.builder().setAllowDuplicateKeys(false).build());
    Object result;
    try {
      result = load.loadFromString(yamlOrJson);
    } catch (org.snakeyaml.engine.v2.exceptions.YamlEngineException e) {
      throw new IllegalArgumentException("Malformed document: " + e.getMessage(), e);
    }
    return result instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  /** Parse a YAML file into a Map. */
  public static Map<String, Object> parseFile(Path path) throws IOException {
    try (var in = Files.newInputStream(path)) {
      var load = new Load(LoadSettings.builder().build());
      var result = load.loadFromInputStream(in);
      @SuppressWarnings("unchecked")
      var map = result instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.<String, Object>of();
      return map;
    }
  }

  /** Parse a JSON array string into a list of Maps. Returns empty list for null/blank input. */
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> parseList(String json) {
    if (Strings.isBlank(json)) {
      return List.of();
    }
    var load = new Load(LoadSettings.builder().build());
    var result = load.loadFromString(json);
    return result instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
  }

  /**
   * Dumps in block style: these files ({@code host.yaml}, {@code config.yaml}, {@code sail.yaml})
   * are declarative configuration the operator edits by hand, and the default flow style emits a
   * single-line {@code {...}} map that is hostile to both editing and diffing.
   */
  public static String dumpToString(Map<String, Object> map) {
    var dump = new Dump(DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).build());
    return dump.dumpToString(map);
  }

  /** Write a Map as YAML to a file. */
  public static void dumpToFile(Map<String, Object> map, Path path) throws IOException {
    Files.writeString(path, dumpToString(map));
  }

  /** Dump a list to a valid JSON string. */
  public static String dumpJson(List<?> list) {
    var sb = new StringBuilder();
    appendJson(sb, list);
    return sb.toString();
  }

  /** Dump a Map to a valid JSON string. */
  public static String dumpJson(Map<String, Object> map) {
    var sb = new StringBuilder();
    appendJson(sb, map);
    return sb.toString();
  }

  @SuppressWarnings("unchecked")
  private static void appendJson(StringBuilder sb, Object value) {
    switch (value) {
      case null -> sb.append("null");
      case String s -> sb.append('"').append(escapeJson(s)).append('"');
      case Number n -> sb.append(n);
      case Boolean b -> sb.append(b);
      case Map<?, ?> m -> {
        sb.append('{');
        var first = true;
        for (var entry : ((Map<String, Object>) m).entrySet()) {
          if (!first) sb.append(", ");
          sb.append('"').append(escapeJson(entry.getKey())).append("\": ");
          appendJson(sb, entry.getValue());
          first = false;
        }
        sb.append('}');
      }
      case List<?> l -> {
        sb.append('[');
        for (var i = 0; i < l.size(); i++) {
          if (i > 0) sb.append(", ");
          appendJson(sb, l.get(i));
        }
        sb.append(']');
      }
      default -> sb.append('"').append(escapeJson(value.toString())).append('"');
    }
  }

  private static String escapeJson(String s) {
    var sb = new StringBuilder(s.length());
    for (var i = 0; i < s.length(); i++) {
      var ch = s.charAt(i);
      switch (ch) {
        case '\\' -> sb.append("\\\\");
        case '"' -> sb.append("\\\"");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (ch < 0x20) {
            sb.append(String.format("\\u%04x", (int) ch));
          } else {
            sb.append(ch);
          }
        }
      }
    }
    return sb.toString();
  }
}
