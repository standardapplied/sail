/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

/**
 * The API layer's one logging seam for unexpected failures. Every catch-all that converts an
 * unanticipated exception into a generic {@code internal} refusal must pass through here first, so
 * the full stack trace lands on stderr (the server's journal) before the wire response is reduced
 * to "sail API operation failed." — the client-facing envelope stays generic, but the operator can
 * actually diagnose the 500. Expected refusals ({@link ApiException}) carry their own structured
 * story and never travel this path.
 */
final class ApiLog {

  private ApiLog() {}

  static void unexpected(String context, Exception e) {
    System.err.println("  [api] Unexpected error handling " + context + ":");
    e.printStackTrace();
  }
}
