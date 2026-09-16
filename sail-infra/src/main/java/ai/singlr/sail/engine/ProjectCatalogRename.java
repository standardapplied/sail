/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.HostCatalog;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;

public final class ProjectCatalogRename {
  private ProjectCatalogRename() {}

  public static HostCatalog.Renamed rename(Sqlite db, String from, String to) {
    NameValidator.requireValidProjectName(from);
    NameValidator.requireValidProjectName(to);
    if (from.equals(to)) {
      throw new IllegalArgumentException("'" + to + "' is already the project's name.");
    }
    return db.transaction(
        () -> {
          var projects = new ProjectStore(db);
          var existing =
              projects
                  .findByName(from)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "No project '" + from + "' in the catalog to rename."));
          if (projects.findByName(to).isPresent()) {
            throw new IllegalStateException("A project named '" + to + "' already exists.");
          }
          projects.rename(from, to, ProjectRenamer.withName(existing.definition(), to));
          new SpecStore(db).reproject(from, to);
          new FileStore(db).reproject(from, to);
          return new HostCatalog.Renamed(from, to, existing.definition());
        });
  }

  public static void restore(Sqlite db, HostCatalog.Renamed renamed) {
    db.transaction(
        () -> {
          new ProjectStore(db).rename(renamed.to(), renamed.from(), renamed.previousDefinition());
          new SpecStore(db).reproject(renamed.to(), renamed.from());
          new FileStore(db).reproject(renamed.to(), renamed.from());
        });
  }
}
