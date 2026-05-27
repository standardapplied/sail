/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.util.Map;

public record ApiResponse(int status, Map<String, Object> body) {

  public static ApiResponse from(Result<?> result) {
    return switch (result) {
      case Result.Success<?> success ->
          new ApiResponse(success.code(), ApiJson.withSchema(success.value()));
      case Result.Failure<?> failure -> error(failure);
    };
  }

  public static ApiResponse fromCreated(Result<?> result) {
    return switch (result) {
      case Result.Success<?> success -> new ApiResponse(201, ApiJson.withSchema(success.value()));
      case Result.Failure<?> failure -> error(failure);
    };
  }

  public static ApiResponse ok(Object body) {
    return new ApiResponse(200, ApiJson.withSchema(body));
  }

  public static ApiResponse created(Object body) {
    return new ApiResponse(201, ApiJson.withSchema(body));
  }

  public static ApiResponse error(Result.Failure<?> failure) {
    return new ApiResponse(
        failure.errorCode().httpCode(),
        ApiJson.withSchema(new ErrorResponse(ApiError.from(failure))));
  }
}
