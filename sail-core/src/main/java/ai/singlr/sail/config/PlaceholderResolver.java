/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves {@code ${PLACEHOLDER}} tokens in YAML content by prompting the user interactively. Only
 * a known set of placeholders is supported — unknown placeholders cause an error (safety against
 * injection).
 */
public final class PlaceholderResolver {

  private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([A-Z_]+)\\}");

  private static final Map<String, String> KNOWN_PLACEHOLDERS =
      Map.of(
          "GIT_NAME", "Your name (for git commits)",
          "GIT_EMAIL", "Your email (for git commits)",
          "SSH_PUBLIC_KEY", "Your SSH public key (for Zed remote access)");

  private PlaceholderResolver() {}

  /**
   * Scans the YAML content for {@code ${...}} placeholders, prompts the user for each unique one,
   * and returns the content with all placeholders replaced.
   *
   * @param content the raw YAML string with placeholders
   * @return the resolved YAML string
   * @throws IllegalArgumentException if an unknown placeholder is found
   */
  public static String resolve(String content) {
    return resolve(content, null);
  }

  /**
   * Scans the YAML content for placeholders and resolves them. If a {@code reader} is provided, it
   * is used for input instead of stdin (for testing).
   */
  static String resolve(String content, BufferedReader reader) {
    var matcher = PLACEHOLDER_PATTERN.matcher(content);
    var found = new LinkedHashMap<String, String>();

    while (matcher.find()) {
      var name = matcher.group(1);
      if (!found.containsKey(name)) {
        if (!KNOWN_PLACEHOLDERS.containsKey(name)) {
          throw new IllegalArgumentException(
              "Unknown placeholder: ${"
                  + name
                  + "}. Known placeholders: "
                  + KNOWN_PLACEHOLDERS.keySet());
        }
        found.put(name, null);
      }
    }

    if (found.isEmpty()) {
      return content;
    }

    var resolved = new LinkedHashMap<String, String>();
    for (var name : found.keySet()) {
      var prompt = KNOWN_PLACEHOLDERS.get(name);
      System.out.print("  " + prompt + ": ");
      System.out.flush();
      var value = readLine(reader);
      if (Strings.isBlank(value)) {
        throw new IllegalArgumentException("Value required for ${" + name + "} (" + prompt + ")");
      }
      resolved.put(name, value.strip());
    }

    var result = content;
    for (var entry : resolved.entrySet()) {
      result = result.replace("${" + entry.getKey() + "}", entry.getValue());
    }
    return result;
  }

  private static String readLine(BufferedReader reader) {
    try {
      if (reader != null) {
        return reader.readLine();
      }
      var console = System.console();
      if (console != null) {
        return console.readLine();
      }
      return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
          .readLine();
    } catch (Exception e) {
      return null;
    }
  }
}
