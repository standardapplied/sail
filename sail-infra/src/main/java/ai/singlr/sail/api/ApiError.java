/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The error envelope a client reads. {@code action} is what unblocks the request as a reader who
 * types nothing reads it, each command its verb and arguments; when it names a command, {@code
 * remedy} carries it as data, words and commands, which a client renders for its own reader.
 */
public record ApiError(String code, String message, Remedy remedy, List<FieldError> fieldErrors)
    implements Mappable {
  public ApiError(String code, String message, String action) {
    this(code, message, Result.words(action), List.of());
  }

  public ApiError {
    remedy = remedy == null ? Remedy.NONE : remedy;
    fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
  }

  static ApiError from(Result.Failure<?> failure) {
    return new ApiError(
        failure.errorCode().code(),
        failure.errorMessage(),
        failure.remedy(),
        failure.fieldErrors());
  }

  /** The remedy as a reader who types nothing reads it; null when there is none. */
  public String action() {
    return remedy.isEmpty() ? null : remedy.described();
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("code", code);
    m.put("message", message);
    m.put("action", action());
    if (!remedy.commands().isEmpty()) {
      m.put("remedy", remedy.toMaps());
    }
    m.put("field_errors", fieldErrors);
    return m;
  }
}
