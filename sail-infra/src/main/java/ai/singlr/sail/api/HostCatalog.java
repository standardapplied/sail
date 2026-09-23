/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Optional;

/** The project catalog and the spec rows a host command reads or reshapes. */
public interface HostCatalog {
  Optional<ProjectStore.ProjectRow> project(String project);

  List<ProjectStore.ProjectRow> projects();

  List<Spec> projectSpecs(String project);

  Optional<SpecStore.SpecContent> specContent(String id);

  boolean roomKnown(String room);

  String demoDefinition();

  List<String> projectsWithFiles();

  Destroyed destroy(String name, boolean purge);

  /** What purging project {@code name} would erase, rehearsed and rolled back, as one line. */
  String purgeSummary(String name);

  Renamed rename(String from, String to);

  void undoRename(Renamed renamed);

  /**
   * What a destroy did to the catalog: whether the project was purged and, on a node, whether that
   * purge was asked of main rather than applied here yet.
   */
  record Destroyed(String name, boolean purged, boolean requested) {}

  record Renamed(String from, String to, String previousDefinition) {}
}
