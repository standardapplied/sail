/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Remedy;
import java.util.List;
import java.util.stream.Collectors;

public sealed interface Result<T> permits Result.Success, Result.Failure {

  record Success<T>(T value, int code) implements Result<T> {}

  /**
   * A refused request: what went wrong ({@code errorMessage}) and what unblocks it ({@code remedy},
   * {@link Remedy#NONE} when nothing does), its commands kept as data so the door that prints it
   * renders them for its reader.
   */
  record Failure<T>(
      ErrorCode errorCode,
      String errorMessage,
      Remedy remedy,
      List<FieldError> fieldErrors,
      Throwable cause)
      implements Result<T> {
    public Failure {
      remedy = remedy == null ? Remedy.NONE : remedy;
      fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public String fullError() {
      if (errorMessage == null) {
        return null;
      }
      if (fieldErrors.isEmpty()) {
        return errorMessage;
      }
      var details =
          fieldErrors.stream()
              .map(fieldError -> fieldError.field() + ": " + fieldError.message())
              .collect(Collectors.joining(", "));
      return errorMessage + " - Field errors: [" + details + "]";
    }
  }

  default boolean isSuccess() {
    return this instanceof Success<?>;
  }

  default boolean isFailure() {
    return this instanceof Failure<?>;
  }

  default T orThrow() {
    return switch (this) {
      case Success<T> s -> s.value();
      case Failure<T> f -> throw new IllegalStateException(f.fullError(), f.cause());
    };
  }

  default T value() {
    return null;
  }

  @SuppressWarnings("unchecked")
  default int code() {
    var failure = (Failure<T>) this;
    return failure.errorCode().httpCode();
  }

  default ErrorCode errorCode() {
    return null;
  }

  default String errorMessage() {
    return null;
  }

  default Remedy remedy() {
    return Remedy.NONE;
  }

  default List<FieldError> fieldErrors() {
    return List.of();
  }

  default Throwable cause() {
    return null;
  }

  default <U> Result<U> asFailure() {
    if (this instanceof Failure<?> failure) {
      return new Failure<>(
          failure.errorCode(),
          failure.errorMessage(),
          failure.remedy(),
          failure.fieldErrors(),
          failure.cause());
    }
    throw new IllegalStateException("Cannot convert a success result to a failure");
  }

  default String fullError() {
    return null;
  }

  static <T> Result<T> success(T value) {
    return new Success<>(value, 200);
  }

  static <T> Result<T> success(T value, int code) {
    return new Success<>(value, code);
  }

  static <T> Result<T> failure(ErrorCode errorCode, String errorMessage) {
    return new Failure<>(errorCode, errorMessage, null, List.of(), null);
  }

  static <T> Result<T> failure(ErrorCode errorCode, String errorMessage, String action) {
    return new Failure<>(errorCode, errorMessage, words(action), List.of(), null);
  }

  static <T> Result<T> failure(ErrorCode errorCode, String errorMessage, Remedy remedy) {
    return new Failure<>(errorCode, errorMessage, remedy, List.of(), null);
  }

  /** {@code action}, a remedy that names no command, as a remedy; none when it is blank. */
  static Remedy words(String action) {
    return action == null || action.isBlank() ? Remedy.NONE : Remedy.of(action);
  }

  static <T> Result<T> failure(ErrorCode errorCode, String errorMessage, Throwable cause) {
    return new Failure<>(errorCode, errorMessage, null, List.of(), cause);
  }

  static <T> Result<T> failure(
      ErrorCode errorCode, String errorMessage, List<FieldError> fieldErrors) {
    return new Failure<>(errorCode, errorMessage, null, fieldErrors, null);
  }
}
