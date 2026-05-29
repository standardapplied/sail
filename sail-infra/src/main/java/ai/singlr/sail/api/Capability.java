/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

/**
 * A coarse permission required by an API route. Roles ({@link Role}) map to sets of capabilities,
 * and {@link Authorizer} checks the authenticated principal's role against the capability a route
 * requires. Kept deliberately small; finer-grained, resource-scoped permissions can be layered on
 * later without changing this contract.
 */
public enum Capability {

  /** Read state — list/get specs, events, reviews, agent status. */
  READ,

  /** Mutate state — create/edit specs, dispatch, review actions, publish events. */
  WRITE,

  /** Administer the control plane — manage FDEs, tokens, nodes, and server config. */
  ADMIN
}
