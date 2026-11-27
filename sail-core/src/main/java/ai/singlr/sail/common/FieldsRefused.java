/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A value refused for the fields it names, each with the sentence said under it, so a door that
 * draws a form says each refusal under its field (the API's {@code field_errors}) and a door that
 * prints says its message.
 */
public final class FieldsRefused extends IllegalArgumentException {

  private final transient Map<String, String> fields;

  /** Refused with {@code message}, saying each of {@code fields}' sentences under its field. */
  public FieldsRefused(String message, Map<String, String> fields) {
    super(message);
    this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
  }

  /** What {@code read} reads, its refusal said under {@code field}. */
  public static <T> T naming(String field, Supplier<T> read) {
    try {
      return read.get();
    } catch (IllegalArgumentException refused) {
      var sentence = Objects.requireNonNullElse(refused.getMessage(), field + " is refused.");
      throw new FieldsRefused(sentence, Map.of(field, sentence));
    }
  }

  /** The sentence said under each field refused, by field, in the order they were refused. */
  public Map<String, String> fields() {
    return fields;
  }
}
