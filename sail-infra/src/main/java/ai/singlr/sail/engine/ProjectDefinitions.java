/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.HostCatalog;
import ai.singlr.sail.config.PlaceholderResolver;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

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
  public static SailYaml load(String name, Path explicitFile, OperatorCommand operator) {
    var text =
        definition(name, explicitFile)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No descriptor found for project '"
                            + name
                            + "'. Author one with '"
                            + operator.sail("project init")
                            + "', or sync it from main with '"
                            + operator.sail("sync")
                            + "'."));
    return SailYaml.fromMap(YamlUtil.parseMap(text));
  }

  /**
   * Parses a definition for provisioning, resolving the personal-field placeholders ({@code
   * ${GIT_NAME}}, {@code ${GIT_EMAIL}}, {@code ${SSH_PUBLIC_KEY}}) from this box's own identity. A
   * definition with no placeholders — one authored locally with concrete values, not yet synced —
   * is parsed unchanged and never touches the local identity. This is the seam where the fleet's
   * identity-free definition becomes a container that commits as the engineer and trusts their key.
   */
  public static SailYaml resolveForProvisioning(String definitionText, OperatorCommand operator) {
    return resolveForProvisioning(definitionText, LocalIdentity.detect(operator), operator);
  }

  static SailYaml resolveForProvisioning(
      String definitionText, LocalIdentity identity, OperatorCommand operator) {
    return resolveForProvisioning(definitionText, identity::valueFor, operator);
  }

  /**
   * Resolves placeholders with a caller-supplied value source — e.g. an interactive prompt on the
   * {@code project apply} path — instead of this box's silent identity. Keeps resolution honest:
   * the source decides where each value comes from, the definition stays identity-free until here.
   */
  public static SailYaml resolveForProvisioning(
      String definitionText, UnaryOperator<String> values, OperatorCommand operator) {
    var config = SailYaml.fromMap(PlaceholderResolver.resolve(definitionText, values));
    requireDevUser(config, operator);
    requireKeyLines(config);
    return config;
  }

  /**
   * Refuses a descriptor whose container could not work: sail runs every agent, shell and hook as
   * {@code dev} (uid 1000, {@code /home/dev}). Refused here, where only provisioning reads a
   * descriptor, and not when one is parsed, so sync, connect and listing still read such a row.
   */
  private static void requireDevUser(SailYaml config, OperatorCommand operator) {
    var user = config.sshUser();
    if (!"dev".equals(user)) {
      var name = config.name();
      throw new IllegalArgumentException(
          "ssh.user is '"
              + user
              + "', but sail runs every agent, shell and hook as 'dev' (uid 1000, /home/dev)."
              + " Remove 'ssh.user' from "
              + name
              + "'s descriptor (or set it to dev) with '"
              + operator.sail("project edit " + name)
              + "', then apply again. A container already created as '"
              + user
              + "' must be recreated: '"
              + operator.sail("project destroy " + name)
              + "', then '"
              + operator.sail("project apply " + name)
              + "'.");
    }
  }

  /**
   * Refuses a descriptor whose {@code ssh.authorized_keys} holds what step 6 cannot write as one
   * key line: nothing, text that is not one line, or something that is not text. Only a descriptor
   * read from a file can hold one (a catalog row keeps {@code ${SSH_PUBLIC_KEY}} alone), so the fix
   * is in that file.
   */
  private static void requireKeyLines(SailYaml config) {
    if (config.ssh() == null || config.ssh().authorizedKeys() == null) {
      return;
    }
    for (Object entry : config.ssh().authorizedKeys()) {
      if (entry == null || entry instanceof String blank && blank.isBlank()) {
        throw notAKeyLine("holds an empty entry");
      }
      if (!(entry instanceof String line)) {
        throw notAKeyLine("holds an entry that is not text: " + entry);
      }
      if (line.strip().lines().count() > 1) {
        throw notAKeyLine("holds a key of more than one line");
      }
    }
  }

  private static IllegalArgumentException notAKeyLine(String fault) {
    return new IllegalArgumentException(
        "ssh.authorized_keys "
            + fault
            + ". Write each key on a line of its own in the descriptor you applied, then apply"
            + " again.");
  }

  /**
   * The project's definition for a run on this box, which names the git identity the agent commits
   * as: the catalog row, with {@code ${GIT_NAME}} and {@code ${GIT_EMAIL}} replaced by this box's
   * git identity where the box has one. Any other placeholder, and either of these on a box with no
   * identity set, is left as the row holds it. A project not in the catalog, or one whose row
   * cannot be read, fails before anything is replaced.
   */
  public static SailYaml definitionWithBoxIdentity(
      HostCatalog catalog, String name, LocalIdentity identity, OperatorCommand operator) {
    catalog.definitions().require(name);
    var text = catalog.project(name).orElseThrow().definition();
    var values = new LinkedHashMap<String, String>();
    for (var field : List.of(PlaceholderResolver.GIT_NAME, PlaceholderResolver.GIT_EMAIL)) {
      if (text.contains(PlaceholderResolver.token(field))) {
        identity.gitValue(field).ifPresent(value -> values.put(field, value));
      }
    }
    return SailYaml.fromMap(PlaceholderResolver.substitute(YamlUtil.parseMap(text), values));
  }

  /** Writes a definition to the canonical descriptor (the materialized view of the catalog). */
  public static Path materialize(String name, String definition) throws IOException {
    var path = canonicalPath(name);
    write(path, definition);
    return path;
  }

  /**
   * Writes a project's descriptor at {@code path} the way the one the server reads is always
   * written — a file an engineer names with {@code -f} is theirs, and {@link #persist} writes that
   * in place, also when it names this same file: replaced in a single move, so whoever reads it
   * while it is rewritten — the review loop resolves a project's pipeline at every step — reads the
   * definition before or the one after, never a file that is half of either. The new file keeps the
   * mode of the one it replaces, or is readable by all like any descriptor written for the first
   * time; a descriptor that is a link is written where it points.
   */
  public static void write(Path path, String definition) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    var target = Files.exists(path) ? path.toRealPath() : path.toAbsolutePath();
    var written = Files.createTempFile(target.getParent(), ".sail-", ".tmp");
    try {
      Files.writeString(written, definition);
      Files.setPosixFilePermissions(
          written,
          Files.exists(target)
              ? Files.getPosixFilePermissions(target)
              : PosixFilePermissions.fromString("rw-r--r--"));
      Files.move(
          written, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(written);
    }
  }

  /**
   * Persists an edited definition so it cannot diverge from the catalog. When an explicit {@code
   * -f} file was given, the engineer is operating on that file directly, so the edit is written
   * there and the catalog is left untouched. Otherwise the catalog (the replicated source of truth)
   * is recorded first and the canonical descriptor re-materialized from it — exactly how {@code
   * project edit} saves — so the change survives the next sync and re-materialize instead of being
   * silently overwritten. The catalog write is {@code operator}'s, which the command resolved
   * before it changed anything.
   */
  public static void persist(String name, Path explicitFile, String definition, Actor operator)
      throws IOException {
    if (explicitFile != null) {
      Files.writeString(explicitFile, definition);
      return;
    }
    persist(SailPaths.controlPlaneDb(), name, canonicalPath(name), definition, operator);
  }

  /**
   * Records the definition in {@code catalog} and then writes it to {@code canonical}, in that
   * order: a definition the catalog did not take is written nowhere.
   */
  static void persist(Path catalog, String name, Path canonical, String definition, Actor operator)
      throws IOException {
    ProjectCatalog.record(
        catalog,
        name,
        definition,
        Objects.requireNonNull(
            operator, "A catalog write names the operator the command resolved."));
    write(canonical, definition);
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
