/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.LinkedHashMap;
import java.util.Map;

public record FieldError(String field, String message) implements Mappable {
  public static FieldError of(String field, String message) {
    return new FieldError(field, message);
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("field", field);
    m.put("message", message);
    return m;
  }
}
