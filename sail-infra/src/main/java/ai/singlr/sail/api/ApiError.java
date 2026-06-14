/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Strings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ApiError(String code, String message, String action, List<FieldError> fieldErrors)
    implements Mappable {
  public ApiError(String code, String message, String action) {
    this(code, message, action, List.of());
  }

  public ApiError {
    action = Strings.isBlank(action) ? null : action;
    fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
  }

  static ApiError from(Result.Failure<?> failure) {
    return new ApiError(
        failure.errorCode().code(),
        failure.errorMessage(),
        failure.action(),
        failure.fieldErrors());
  }

  @Override
  public Map<String, Object> toMap() {
    var m = new LinkedHashMap<String, Object>();
    m.put("code", code);
    m.put("message", message);
    m.put("action", action);
    m.put("field_errors", fieldErrors);
    return m;
  }
}
