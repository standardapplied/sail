/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ApiJson {

  private ApiJson() {}

  static Map<String, Object> withSchema(Object value) {
    var map = new LinkedHashMap<String, Object>();
    map.put("schema_version", 1);
    var encoded = encode(value);
    if (encoded instanceof Map<?, ?> encodedMap) {
      for (var entry : encodedMap.entrySet()) {
        map.put(entry.getKey().toString(), entry.getValue());
      }
      return map;
    }
    map.put("data", encoded);
    return map;
  }

  @SuppressWarnings("unchecked")
  static Object encode(Object value) {
    return switch (value) {
      case null -> null;
      case String ignored -> value;
      case Number ignored -> value;
      case Boolean ignored -> value;
      case Enum<?> enumValue -> enumValue.name().toLowerCase();
      case List<?> list -> list.stream().map(ApiJson::encode).toList();
      case Map<?, ?> map -> encodeMap((Map<Object, Object>) map);
      case Mappable mappable -> encodeMap((Map<Object, Object>) (Map<?, ?>) mappable.toMap());
      default -> {
        if (value.getClass().isRecord()) {
          throw new IllegalArgumentException(
              "API record "
                  + value.getClass().getName()
                  + " must implement Mappable. Add 'implements Mappable' and a toMap() returning"
                  + " a LinkedHashMap<String, Object> of its fields keyed by their snake_case"
                  + " wire names.");
        }
        yield value.toString();
      }
    };
  }

  private static Map<String, Object> encodeMap(Map<Object, Object> source) {
    var map = new LinkedHashMap<String, Object>();
    for (var entry : source.entrySet()) {
      var value = encode(entry.getValue());
      if (value != null) {
        map.put(entry.getKey().toString(), value);
      }
    }
    return map;
  }
}
