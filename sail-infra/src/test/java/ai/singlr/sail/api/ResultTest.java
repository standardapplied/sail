/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResultTest {

  @Test
  void successExposesValueAndStatus() {
    var result = Result.success("ok", 201);

    assertTrue(result.isSuccess());
    assertFalse(result.isFailure());
    assertEquals("ok", result.orThrow());
    assertEquals("ok", result.value());
    assertEquals(201, result.code());
    assertNull(result.errorCode());
    assertNull(result.errorMessage());
    assertNull(result.action());
    assertEquals(List.of(), result.fieldErrors());
    assertNull(result.cause());
    assertNull(result.fullError());
  }

  @Test
  void defaultSuccessUsesOkStatus() {
    var result = Result.success("ok");

    assertEquals(200, result.code());
  }

  @Test
  void failureExposesStructuredError() {
    var fieldErrors =
        List.of(FieldError.of("spec_id", "is required"), FieldError.of("mode", "is invalid"));
    var result = Result.failure(ErrorCode.INVALID_REQUEST, "Invalid request.", fieldErrors);

    assertFalse(result.isSuccess());
    assertTrue(result.isFailure());
    assertNull(result.value());
    assertEquals(422, result.code());
    assertEquals(ErrorCode.INVALID_REQUEST, result.errorCode());
    assertEquals("Invalid request.", result.errorMessage());
    assertNull(result.action());
    assertEquals(fieldErrors, result.fieldErrors());
    assertNull(result.cause());
    assertEquals(
        "Invalid request. - Field errors: [spec_id: is required, mode: is invalid]",
        result.fullError());
  }

  @Test
  void failureSupportsActionAndCause() {
    var cause = new IllegalArgumentException("boom");
    var withAction = Result.failure(ErrorCode.CONFLICT, "Busy.", "Wait.");
    var withCause = Result.failure(ErrorCode.INTERNAL, "Failed.", cause);

    assertEquals("Wait.", withAction.action());
    assertEquals(cause, withCause.cause());
    assertEquals("Busy.", withAction.fullError());
  }

  @Test
  void failureCanBeReTyped() {
    Result<String> result = Result.failure(ErrorCode.NOT_FOUND, "Missing.");

    var failure = result.<Integer>asFailure();

    assertEquals(ErrorCode.NOT_FOUND, failure.errorCode());
    assertEquals("Missing.", failure.errorMessage());
  }

  @Test
  void failureWithNullMessageHasNoFullError() {
    var failure = new Result.Failure<String>(ErrorCode.INTERNAL, null, null, null, null);

    assertNull(failure.fullError());
    assertEquals(List.of(), failure.fieldErrors());
  }

  @Test
  void failuresThrowWithFullErrorAndCause() {
    var cause = new IllegalStateException("root");
    Result<String> result = Result.failure(ErrorCode.INTERNAL, "Failed.", cause);

    var thrown = assertThrows(IllegalStateException.class, result::orThrow);

    assertEquals("Failed.", thrown.getMessage());
    assertEquals(cause, thrown.getCause());
  }

  @Test
  void successCannotBeConvertedToFailure() {
    var result = Result.success("ok");

    assertThrows(IllegalStateException.class, result::asFailure);
  }
}
