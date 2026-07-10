/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The diagnosability contract: an unexpected exception reaching the API's catch-alls leaves its
 * full stack trace on stderr before the wire response is reduced to a generic internal error.
 */
class ApiLogTest {

  @Test
  void writesContextAndFullStackTraceToStderr() {
    var original = System.err;
    var captured = new ByteArrayOutputStream();
    System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
    try {
      ApiLog.unexpected("GET /v1/boom", new IllegalStateException("kaput"));
    } finally {
      System.setErr(original);
    }

    var log = captured.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("Unexpected error handling GET /v1/boom"));
    assertTrue(log.contains("java.lang.IllegalStateException: kaput"));
    assertTrue(log.contains("at ai.singlr.sail.api.ApiLogTest"));
  }
}
