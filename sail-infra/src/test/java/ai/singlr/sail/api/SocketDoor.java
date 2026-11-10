/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The container socket's router as a test outside this package asks it: one request, one answer.
 */
public final class SocketDoor {

  private SocketDoor() {}

  /**
   * What the socket of the box whose FDE {@code boxFde} names answers {@code method} on {@code
   * path} with {@code form} as its body, asked with {@code bearer}.
   */
  public static ApiResponse ask(
      LocalLaneOperations operations,
      Supplier<String> boxFde,
      String method,
      String path,
      String bearer,
      String form) {
    return new LocalApiRouter(operations, boxFde)
        .handle(
            new LocalApiRequest(
                method,
                path,
                Map.of(),
                Map.of(
                    "authorization",
                    "Bearer " + bearer,
                    "content-type",
                    "application/x-www-form-urlencoded"),
                form.getBytes(StandardCharsets.UTF_8)));
  }
}
