/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${PLACEHOLDER}} tokens in a YAML definition from a known, fixed set — a
 * developer's git identity and SSH public key. Unknown placeholders are an error (safety against
 * injection). Values come from a supplied resolver that reads the local box's identity, so the
 * synced definition stays identity-free until it lands on a box. The placeholder names are public
 * so {@link PersonalFields} can mint them and a box-local identity provider can answer them.
 *
 * <p>Values are substituted into the parsed definition, never into its text: a redacted row holds
 * its placeholders quoted, and a value's own characters — the apostrophe in {@code O'Neil} — must
 * land in the value, not in the YAML around it.
 */
public final class PlaceholderResolver {

  public static final String GIT_NAME = "GIT_NAME";
  public static final String GIT_EMAIL = "GIT_EMAIL";
  public static final String SSH_PUBLIC_KEY = "SSH_PUBLIC_KEY";

  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Z_]+)\\}");

  private static final Map<String, String> KNOWN_PLACEHOLDERS =
      Map.of(
          GIT_NAME, "Your name (for git commits)",
          GIT_EMAIL, "Your email (for git commits)",
          SSH_PUBLIC_KEY, "Your SSH public key (for Zed remote access)");

  private PlaceholderResolver() {}

  /** Returns the {@code ${NAME}} token for a placeholder, e.g. {@code ${GIT_NAME}}. */
  public static String token(String name) {
    return "${" + name + "}";
  }

  /** The human prompt for a known placeholder, so callers needn't restate it. */
  public static String promptFor(String name) {
    var prompt = KNOWN_PLACEHOLDERS.get(name);
    if (prompt == null) {
      throw new IllegalArgumentException(
          "Unknown placeholder: ${"
              + name
              + "}. Known placeholders: "
              + KNOWN_PLACEHOLDERS.keySet());
    }
    return prompt;
  }

  /**
   * Parses the definition with each unique {@code ${NAME}} replaced by {@code values.apply(NAME)}.
   * The resolver is consulted only for placeholders actually present, so content with none needs no
   * identity at all. Unknown placeholders and blank values are errors.
   */
  public static Map<String, Object> resolve(String content, UnaryOperator<String> values) {
    var matcher = PLACEHOLDER_PATTERN.matcher(content);
    var resolved = new LinkedHashMap<String, String>();
    while (matcher.find()) {
      var name = matcher.group(1);
      if (resolved.containsKey(name)) {
        continue;
      }
      var prompt = promptFor(name);
      var value = values.apply(name);
      if (Strings.isBlank(value)) {
        throw new IllegalArgumentException("No value for " + token(name) + " (" + prompt + ")");
      }
      resolved.put(name, value.strip());
    }
    return substitute(YamlUtil.parseMap(content), resolved);
  }

  /**
   * Returns the parsed definition with each {@code ${NAME}} in {@code values} replaced inside the
   * string values that carry it, wherever they sit in the tree. Every other value, and every
   * placeholder not in {@code values}, is kept as it was.
   */
  public static Map<String, Object> substitute(
      Map<String, Object> root, Map<String, String> values) {
    return values.isEmpty() ? root : substituteMap(root, values);
  }

  private static <K> Map<K, Object> substituteMap(Map<K, ?> map, Map<String, String> values) {
    var resolved = new LinkedHashMap<K, Object>();
    map.forEach((key, child) -> resolved.put(key, substituteNode(child, values)));
    return resolved;
  }

  private static Object substituteNode(Object node, Map<String, String> values) {
    return switch (node) {
      case String text -> substituteText(text, values);
      case Map<?, ?> map -> substituteMap(map, values);
      case List<?> list -> list.stream().map(child -> substituteNode(child, values)).toList();
      case null, default -> node;
    };
  }

  private static String substituteText(String text, Map<String, String> values) {
    var result = text;
    for (var entry : values.entrySet()) {
      result = result.replace(token(entry.getKey()), entry.getValue());
    }
    return result;
  }
}
