/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a definition's values by type, refusing one of the wrong type or a key no block has with a
 * sentence that names the field, so a section written by any door is refused as precisely as one
 * typed by hand. Absent is null.
 */
final class Fields {

  private Fields() {}

  /** Refuses a key of {@code map}, the block at {@code at}, that is not one of {@code allowed}. */
  static void only(Map<String, Object> map, String at, Collection<String> allowed) {
    for (var key : map.keySet()) {
      if (!allowed.contains(key)) {
        throw new IllegalArgumentException(
            path(at, key)
                + " is not a key of "
                + (at.isEmpty() ? "a project definition" : at)
                + ". Its keys are: "
                + String.join(", ", allowed)
                + ".");
      }
    }
  }

  static String text(Map<String, Object> map, String key, String field) {
    return switch (map.get(key)) {
      case null -> null;
      case String text -> text;
      default -> throw wrong(field, "text");
    };
  }

  static Integer integer(Map<String, Object> map, String key, String field) {
    return switch (map.get(key)) {
      case null -> null;
      case Integer number -> number;
      case Long number when number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE ->
          number.intValue();
      default -> throw wrong(field, "a whole number");
    };
  }

  static boolean bool(Map<String, Object> map, String key, String field) {
    return switch (map.get(key)) {
      case null -> false;
      case Boolean flag -> flag;
      default -> throw wrong(field, "true or false");
    };
  }

  static Map<String, Object> map(Map<String, Object> map, String key, String field) {
    var value = map.get(key);
    return value == null ? null : asMap(value, field);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> asMap(Object value, String field) {
    if (value instanceof Map<?, ?> block) {
      for (var key : block.keySet()) {
        if (!(key instanceof String)) {
          throw new IllegalArgumentException(
              field + " has the key " + key + ", which is not text.");
        }
      }
      return (Map<String, Object>) block;
    }
    throw wrong(field, "a block of keys");
  }

  @SuppressWarnings("unchecked")
  static List<Object> list(Map<String, Object> map, String key, String field) {
    return switch (map.get(key)) {
      case null -> null;
      case List<?> list -> (List<Object>) list;
      default -> throw wrong(field, "a list");
    };
  }

  /** The block at index {@code index} of the list at {@code field}. */
  static Map<String, Object> element(List<Object> list, int index, String field) {
    return asMap(list.get(index), field + "[" + index + "]");
  }

  static List<String> textList(Map<String, Object> map, String key, String field) {
    var list = list(map, key, field);
    if (list == null) {
      return null;
    }
    var texts = new ArrayList<String>();
    for (var i = 0; i < list.size(); i++) {
      if (!(list.get(i) instanceof String text)) {
        throw wrong(field + "[" + i + "]", "text");
      }
      texts.add(text);
    }
    return List.copyOf(texts);
  }

  static List<Integer> integerList(Map<String, Object> map, String key, String field) {
    var list = list(map, key, field);
    if (list == null) {
      return null;
    }
    var numbers = new ArrayList<Integer>();
    for (var i = 0; i < list.size(); i++) {
      numbers.add(integer(Map.of("value", list.get(i)), "value", field + "[" + i + "]"));
    }
    return List.copyOf(numbers);
  }

  /** A block of names to single values, each read as its text. */
  static Map<String, String> textMap(Map<String, Object> map, String key, String field) {
    var block = map(map, key, field);
    if (block == null) {
      return null;
    }
    var texts = new LinkedHashMap<String, String>();
    block.forEach(
        (name, value) -> {
          if (value == null || value instanceof Map<?, ?> || value instanceof List<?>) {
            throw wrong(field + "." + name, "a single value");
          }
          texts.put(name, String.valueOf(value));
        });
    return texts;
  }

  private static String path(String at, String key) {
    return at.isEmpty() ? key : at + "." + key;
  }

  private static IllegalArgumentException wrong(String field, String expected) {
    return new IllegalArgumentException(field + " must be " + expected + ".");
  }
}
