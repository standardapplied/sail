/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.FieldsRefused;
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
    this(new Result.Failure<>(errorCode, message, remedy, null, cause));
  }

  private ApiException(Result.Failure<?> failure) {
    super(failure.errorMessage(), failure.cause());
    this.failure = failure;
  }

  /**
   * The answer for {@code failure}, a refusal from below the door: its reason and remedy as they
   * are, so neither is flattened to a sentence on the way out, and each field it names with the
   * sentence said under it.
   */
  public static ApiException of(ErrorCode errorCode, Exception failure) {
    return switch (failure) {
      case Remedied remedied ->
          new ApiException(errorCode, remedied.reason(), remedied.remedy(), failure);
      case FieldsRefused refused ->
          new ApiException(
              new Result.Failure<>(
                  errorCode,
                  refused.getMessage(),
                  Remedy.NONE,
                  refused.fields().entrySet().stream()
                      .map(field -> FieldError.of(field.getKey(), field.getValue()))
                      .toList(),
                  failure));
      default -> new ApiException(errorCode, failure.getMessage(), Remedy.NONE, failure);
    };
  }

  /**
   * The refusal {@code failure} answers with: its code, its reason, its remedy and the fields it
   * names, as they are.
   */
  public static ApiException of(Result.Failure<?> failure) {
    return new ApiException(failure);
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

  @Override
  public String code() {
    return failure.errorCode().code();
  }
}
