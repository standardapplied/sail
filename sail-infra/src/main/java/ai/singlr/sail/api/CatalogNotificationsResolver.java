/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Notifications;
import ai.singlr.sail.config.SailYaml;
import java.util.Objects;

/**
 * The {@link ProjectNotificationsResolver} of the server: a project's {@code agent.notifications}
 * as its catalog row holds them at each lookup, so a revision that reaches the catalog is what the
 * next event is sent under. No row, no {@code agent} block or no {@code notifications} is null; a
 * definition that cannot be read warns and is null, so an event is dropped rather than the reactor.
 */
public final class CatalogNotificationsResolver implements ProjectNotificationsResolver {

  private final ProjectReader definitions;

  public CatalogNotificationsResolver(ProjectReader definitions) {
    this.definitions = Objects.requireNonNull(definitions, "definitions");
  }

  @Override
  public Notifications resolve(String project) {
    try {
      return definitions
          .read(project)
          .map(SailYaml::agent)
          .map(SailYaml.Agent::notifications)
          .orElse(null);
    } catch (Exception e) {
      System.err.println("  [webhook] Warning: could not load notifications: " + e.getMessage());
      return null;
    }
  }
}
