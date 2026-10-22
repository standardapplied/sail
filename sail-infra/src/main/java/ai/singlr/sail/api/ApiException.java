/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;

public final class ApiException extends RuntimeException implements Remedied {

  private final Result.Failure<?> failure;

  public ApiException(ErrorCode errorCode, String message) {
    this(errorCode, message, Remedy.NONE, null);
  }

  public ApiException(ErrorCode errorCode, String message, String action) {
    this(errorCode, message, Result.words(action), null);
  }

  public ApiException(ErrorCode errorCode, String message, Throwable cause) {
    this(errorCode, message, Remedy.NONE, cause);
  }

  public ApiException(ErrorCode errorCode, String message, String action, Throwable cause) {
    this(errorCode, message, Result.words(action), cause);
  }

  public ApiException(ErrorCode errorCode, String message, Remedy remedy) {
    this(errorCode, message, remedy, null);
  }

  public ApiException(ErrorCode errorCode, String message, Remedy remedy, Throwable cause) {
    super(message, cause);
    failure = new Result.Failure<>(errorCode, message, remedy, null, cause);
  }

  /**
   * The answer for {@code failure}, a refusal from below the door: its reason and remedy as they
   * are, so neither is flattened to a sentence on the way out.
   */
  public static ApiException of(ErrorCode errorCode, Exception failure) {
    return failure instanceof Remedied remedied
        ? new ApiException(errorCode, remedied.reason(), remedied.remedy(), failure)
        : new ApiException(errorCode, failure.getMessage(), Remedy.NONE, failure);
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

  @Override
  public String reason() {
    return failure.errorMessage();
  }

  @Override
  public Remedy remedy() {
    return failure.remedy();
  }
}
