/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Notifications;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SailPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

/**
 * Default {@link ProjectNotificationsResolver}: reads {@code
 * ~/.sail/projects/&lt;name&gt;/sail.yaml} for each lookup. No caching — config changes are picked
 * up on the next event delivery, at the cost of one YAML parse per event. Acceptable at the event
 * rates we expect (orders of magnitude below sail.yaml-parse rate).
 *
 * <p>The path lookup is parameterized so tests can point at a temp directory without touching the
 * real user-home location.
 */
public final class SailYamlNotificationsResolver implements ProjectNotificationsResolver {

  private final Function<String, Path> pathLookup;

  public SailYamlNotificationsResolver() {
    this(project -> SailPaths.resolveSailYaml(project, SailPaths.PROJECT_DESCRIPTOR));
  }

  /** Test seam: replace the project-to-path lookup. */
  public SailYamlNotificationsResolver(Function<String, Path> pathLookup) {
    this.pathLookup = Objects.requireNonNull(pathLookup, "pathLookup");
  }

  @Override
  public Notifications resolve(String project) {
    var path = pathLookup.apply(project);
    if (path == null || !Files.exists(path)) {
      return null;
    }
    try {
      var config = SailYaml.fromMap(YamlUtil.parseFile(path));
      if (config.agent() == null) {
        return null;
      }
      return config.agent().notifications();
    } catch (Exception e) {
      System.err.println(
          "  [webhook] Warning: could not load notifications for '"
              + project
              + "' from "
              + path
              + " — "
              + e.getMessage());
      return null;
    }
  }
}
