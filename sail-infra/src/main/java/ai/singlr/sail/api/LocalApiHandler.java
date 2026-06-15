/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

/**
 * Handles a request that arrived over the local Unix-domain socket and returns the {@link
 * ApiResponse} to serialize back. Implemented by {@link LocalApiRouter}; kept as a seam so {@link
 * LocalApiSocket} (the transport) carries no routing logic and the routing carries no I/O.
 */
interface LocalApiHandler {
  ApiResponse handle(LocalApiRequest request);
}
