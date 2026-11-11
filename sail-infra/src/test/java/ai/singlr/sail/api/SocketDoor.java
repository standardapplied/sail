/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The container socket's router as a test asks it, without the socket's bytes: requests built
 * whole, or one form-encoded request asked with a bearer credential.
 */
public final class SocketDoor {

  private final LocalApiRouter router;

  /** The socket over {@code operations}, whose box gate decides on the box they stand for. */
  public SocketDoor(LocalLaneOperations operations) {
    this.router = new LocalApiRouter(operations);
  }

  /** What the socket answers {@code request}. */
  ApiResponse ask(LocalApiRequest request) {
    return router.handle(request);
  }

  /**
   * What the socket answers {@code method} on {@code path} with {@code body}, asked by {@code
   * bearer}, or by no credential when it is null.
   */
  public ApiResponse ask(String method, String path, String bearer, String body) {
    var headers =
        bearer == null ? Map.<String, String>of() : Map.of("authorization", "Bearer " + bearer);
    return ask(
        new LocalApiRequest(
            method, path, Map.of(), headers, body.getBytes(StandardCharsets.UTF_8)));
  }

  List<Route<?>> routes() {
    return router.routes();
  }
}
