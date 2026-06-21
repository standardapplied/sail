/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.PlaceholderResolver;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves a project's definition with the control-plane database as the source of truth. The
 * on-disk {@code sail.yaml} is a materialized view, so once a project is in the catalog the catalog
 * wins — which is how a definition edited or synced on main reaches {@code create}/{@code
 * apply}/{@code config} without a stale local file shadowing it.
 *
 * <p>Resolution order: an explicit {@code -f} file (a deliberate override or a brand-new authoring)
 * wins; otherwise the catalog row; otherwise the canonical on-disk descriptor (a project authored
 * locally with {@code project init} that has not been catalogued yet). To change a catalogued
 * project, use {@code sail project edit}, which writes the catalog and re-materializes the file.
 */
public final class ProjectDefinitions {

  private ProjectDefinitions() {}

  /** The canonical descriptor path for a project: {@code ~/.sail/projects/<name>/sail.yaml}. */
  public static Path canonicalPath(String name) {
    return SailPaths.projectDir(name).resolve(SailPaths.PROJECT_DESCRIPTOR);
  }

  /**
   * Interprets a {@code -f} option as a deliberate override: {@code null} when it is absent or the
   * bare default {@code sail.yaml} (so resolution stays database-first), else the given path.
   */
  public static Path explicitFile(String fileOption) {
    return fileOption == null || fileOption.equals(SailPaths.PROJECT_DESCRIPTOR)
        ? null
        : Path.of(fileOption);
  }

  /** Resolves the definition text, database-first. Empty when no source has it. */
  public static Optional<String> definition(String name, Path explicitFile) {
    if (explicitFile != null) {
      return read(explicitFile);
    }
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      new SchemaManager(db).migrate();
      var resolved = resolve(new ProjectStore(db), name, null, canonicalPath(name));
      if (resolved.isPresent()) {
        return resolved;
      }
    } catch (Exception ignored) {
      // Fall through to the on-disk descriptor when the catalog is unreachable.
    }
    return read(canonicalPath(name));
  }

  /** Pure resolver, visible for tests: explicit file → catalog row → canonical file. */
  static Optional<String> resolve(
      ProjectStore store, String name, Path explicitFile, Path canonical) {
    if (explicitFile != null) {
      return read(explicitFile);
    }
    var row = store.findByName(name);
    if (row.isPresent()) {
      return row.map(ProjectStore.ProjectRow::definition);
    }
    return read(canonical);
  }

  /** Loads and parses the definition into a {@link SailYaml}, database-first. */
  public static SailYaml load(String name, Path explicitFile) {
    var text =
        definition(name, explicitFile)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No descriptor found for project '"
                            + name
                            + "'. Author one with 'sail project init', or sync it from main with"
                            + " 'sail sync'."));
    return SailYaml.fromMap(YamlUtil.parseMap(text));
  }

  /**
   * Parses a definition for provisioning, resolving the personal-field placeholders ({@code
   * ${GIT_NAME}}, {@code ${GIT_EMAIL}}, {@code ${SSH_PUBLIC_KEY}}) from this box's own identity. A
   * definition with no placeholders — one authored locally with concrete values, not yet synced —
   * is parsed unchanged and never touches the local identity. This is the seam where the fleet's
   * identity-free definition becomes a container that commits as the engineer and trusts their key.
   */
  public static SailYaml resolveForProvisioning(String definitionText) {
    return resolveForProvisioning(definitionText, LocalIdentity.detect());
  }

  static SailYaml resolveForProvisioning(String definitionText, LocalIdentity identity) {
    var resolved = PlaceholderResolver.resolve(definitionText, identity::valueFor);
    return SailYaml.fromMap(YamlUtil.parseMap(resolved));
  }

  /** Writes a definition to the canonical descriptor (the materialized view of the catalog). */
  public static Path materialize(String name, String definition) throws IOException {
    var path = canonicalPath(name);
    Files.createDirectories(path.getParent());
    Files.writeString(path, definition);
    return path;
  }

  /**
   * Persists an edited definition so it cannot diverge from the catalog. When an explicit {@code
   * -f} file was given, the engineer is operating on that file directly, so the edit is written
   * there and the catalog is left untouched. Otherwise the catalog (the replicated source of truth)
   * is recorded first and the canonical descriptor re-materialized from it — exactly how {@code
   * project edit} saves — so the change survives the next sync and re-materialize instead of being
   * silently overwritten.
   */
  public static void persist(String name, Path explicitFile, String definition, String actor)
      throws IOException {
    if (explicitFile != null) {
      Files.writeString(explicitFile, definition);
      return;
    }
    ProjectCatalog.record(name, definition, actor);
    materialize(name, definition);
  }

  private static Optional<String> read(Path path) {
    try {
      return path != null && Files.exists(path)
          ? Optional.of(Files.readString(path))
          : Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    }
  }
}
