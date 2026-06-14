/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiResultIntegrationTest {

  @Test
  void exceptionExposesFailureStatusAndError() {
    var exception =
        new ApiException(ErrorCode.PROJECT_STOPPED, "Stopped.", "Run sail project start.");

    assertEquals(409, exception.status());
    assertEquals(ErrorCode.PROJECT_STOPPED, exception.failure().errorCode());
    assertEquals("project_stopped", exception.error().code());
    assertEquals("Run sail project start.", exception.error().action());
  }

  @Test
  void exceptionSupportsCauses() {
    var cause = new IllegalStateException("root");
    var exception = new ApiException(ErrorCode.COMMAND_FAILED, "Failed.", cause);

    assertEquals(cause, exception.failure().cause());
  }

  @Test
  void responseHelpersSerializeSuccessAndFailureResults() {
    var success = ApiResponse.ok(Map.of("ok", true));
    var failure = ApiResponse.from(Result.failure(ErrorCode.NOT_FOUND, "Missing."));

    assertEquals(200, success.status());
    assertEquals(true, success.body().get("ok"));
    assertEquals(404, failure.status());
    assertEquals("not_found", failure.body().toString().contains("not_found") ? "not_found" : null);
  }

  @Test
  void fromCreatedReturns201ForSuccess() {
    var result = ApiResponse.fromCreated(Result.success(Map.of("id", "test")));
    assertEquals(201, result.status());
  }

  @Test
  void fromCreatedReturnsErrorForFailure() {
    var result = ApiResponse.fromCreated(Result.failure(ErrorCode.INVALID_REQUEST, "Bad input."));
    assertEquals(422, result.status());
  }
}
