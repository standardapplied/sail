/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

public final class ApiException extends RuntimeException {

  private final Result.Failure<?> failure;

  public ApiException(ErrorCode errorCode, String message) {
    this(errorCode, message, null, null);
  }

  public ApiException(ErrorCode errorCode, String message, String action) {
    this(errorCode, message, action, null);
  }

  public ApiException(ErrorCode errorCode, String message, Throwable cause) {
    this(errorCode, message, null, cause);
  }

  public ApiException(ErrorCode errorCode, String message, String action, Throwable cause) {
    super(message, cause);
    failure = new Result.Failure<>(errorCode, message, action, null, cause);
  }

  public int status() {
    return failure.errorCode().httpCode();
  }

  public ApiError error() {
    return ApiError.from(failure);
  }

  public Result.Failure<?> failure() {
    return failure;
  }
}
