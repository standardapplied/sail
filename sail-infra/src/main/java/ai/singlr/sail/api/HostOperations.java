/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.identity.Actor;
import java.util.function.Supplier;

/**
 * The host lane: what a command running on the control-plane box may do beyond the web API, grouped
 * by role so a caller depends on the facet it uses and nothing else. Only the in-process factory
 * hands one out; the HTTP and local-socket routers see {@link Operations} and {@link
 * LocalLaneOperations} respectively, so a host-privileged verb can never leak onto a door.
 */
public interface HostOperations extends Operations, AutoCloseable {
  HostDispatching dispatching();

  HostCatalog catalog();

  HostIdentity identity();

  HostPty pty();

  HostSchema schema();

  /** {@code work}, done as this box's operator: the one principal its host CLI acts as. */
  default <T> T asOperator(Supplier<T> work) {
    return Actor.call(identity().operator(), work::get);
  }

  @Override
  void close();
}
