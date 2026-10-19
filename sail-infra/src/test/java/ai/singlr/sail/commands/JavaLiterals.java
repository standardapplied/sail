/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * The string literals of a Java source, comments skipped, with literals joined by {@code +} read as
 * one: a sentence wrapped across lines by the formatter is scanned whole.
 */
final class JavaLiterals {

  private JavaLiterals() {}

  static List<String> of(String source) {
    var literals = new ArrayList<String>();
    var current = new StringBuilder();
    var joinable = false;
    var i = 0;
    while (i < source.length()) {
      var c = source.charAt(i);
      if (source.startsWith("//", i)) {
        i = endOfLine(source, i);
      } else if (source.startsWith("/*", i)) {
        i = source.indexOf("*/", i + 2) + 2;
      } else if (source.startsWith("\"\"\"", i)) {
        var end = source.indexOf("\"\"\"", i + 3);
        while (source.charAt(end - 1) == '\\') {
          end = source.indexOf("\"\"\"", end + 1);
        }
        current.append(source, i + 3, end);
        joinable = true;
        i = end + 3;
      } else if (c == '"') {
        var end = i + 1;
        while (source.charAt(end) != '"') {
          end += source.charAt(end) == '\\' ? 2 : 1;
        }
        current.append(source, i + 1, end);
        joinable = true;
        i = end + 1;
      } else if (c == '\'') {
        i = source.indexOf('\'', i + (source.charAt(i + 1) == '\\' ? 3 : 2)) + 1;
      } else {
        if (joinable && !Character.isWhitespace(c) && c != '+') {
          literals.add(current.toString());
          current.setLength(0);
          joinable = false;
        }
        i++;
      }
    }
    if (joinable) {
      literals.add(current.toString());
    }
    return literals;
  }

  private static int endOfLine(String source, int from) {
    var end = source.indexOf('\n', from);
    return end < 0 ? source.length() : end;
  }
}
