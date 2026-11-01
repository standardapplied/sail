/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reads and records a project's definition in the control-plane catalog (the {@code projects}
 * table), the shared, replicated source of truth that everything running a project reads, and the
 * only place a definition lives. Only a node that cannot name its operator refuses, and its
 * commands resolve the operator before they change anything.
 */
public final class ProjectCatalog {

  private ProjectCatalog() {}

  /**
   * Refuses a project name that was pruned, before anything is provisioned under it: a pruned name
   * is spent for good. Reads the catalog without creating or migrating it, so a dry run changes
   * nothing; a catalog that is missing or cannot be read refuses nothing here, and the write that
   * follows fails on its own.
   */
  public static void requireUnpruned(String name) {
    requireUnpruned(SailPaths.controlPlaneDb(), name);
  }

  static void requireUnpruned(Path catalog, String name) {
    if (!Files.isRegularFile(catalog)) {
      return;
    }
    boolean pruned;
    try (var db = Sqlite.open(catalog)) {
      pruned = new ChangeLog(db).isErased(Erasure.PROJECT, name);
    } catch (Exception e) {
      return;
    }
    if (pruned) {
      throw new IllegalStateException(
          "Project '"
              + name
              + "' was pruned, and a pruned name is never used again. Give the project a new"
              + " name.");
    }
  }

  /**
   * Refuses, before a command changes anything, a definition of {@code name} the journal will not
   * take from {@code operator}: the project rule's own decision, asked of the catalog as it stands.
   * A box with no catalog yet refuses nothing here; one whose catalog cannot be read fails here, as
   * the write that follows would.
   */
  public static void requireRecordable(String name, Actor operator) {
    requireRecordable(SailPaths.controlPlaneDb(), name, operator);
  }

  static void requireRecordable(Path catalog, String name, Actor operator) {
    if (!Files.isRegularFile(catalog)) {
      return;
    }
    try (var db = Sqlite.open(catalog)) {
      new SchemaManager(db).migrate();
      requireInScope(db, operator::actingFde, name);
      Actor.call(operator, () -> new ProjectStore(db).decide(name, Map.of()))
          .ifPresent(
              refused -> {
                throw new WriteRefused(refused);
              });
    }
  }

  /**
   * Refuses, before a command writes anything, a project outside the scope main last named for this
   * node ({@link SyncScope}): main would deny its every row, so it could only ever exist here. A
   * box no welcome has scoped, and one with no catalog yet, refuses nothing.
   */
  public static void requireInScope(String name) {
    requireInScope(SailPaths.controlPlaneDb(), () -> BoxIdentity.config().handle(), name);
  }

  /** As {@link #requireInScope(String)}, naming in its refusal the box's FDE {@code handle}. */
  static void requireInScope(Path catalog, Supplier<String> handle, String name) {
    if (!Files.isRegularFile(catalog)) {
      return;
    }
    try (var db = Sqlite.open(catalog)) {
      new SchemaManager(db).migrate();
      requireInScope(db, handle, name);
    }
  }

  private static void requireInScope(Sqlite db, Supplier<String> handle, String name) {
    if (!new SyncScope(db).held().admits(name)) {
      var fde = handle.get();
      throw Remedied.state(
          "Project '"
              + name
              + "' is outside the projects main grants this box, so main would take nothing of it.",
          ProjectStore.ungranted(Strings.isBlank(fde) ? "<handle>" : fde, name));
    }
  }

  /** The definition project {@code name} holds in this box's catalog, or empty when none. */
  public static Optional<ProjectDefinition> read(String name) {
    return read(SailPaths.controlPlaneDb(), name);
  }

  static Optional<ProjectDefinition> read(Path catalog, String name) {
    if (!Files.isRegularFile(catalog)) {
      return Optional.empty();
    }
    try (var db = Sqlite.open(catalog)) {
      new SchemaManager(db).migrate();
      return new ProjectStore(db).findByName(name).map(ProjectStore.ProjectRow::definition);
    }
  }

  /**
   * Records {@code definition}, created or replaced, as {@code operator}, this box's operator
   * ({@link CliOperator}), which the caller resolves before it writes anything, so a node that
   * cannot name it refuses the edit rather than losing it. Throws when the definition was not
   * recorded, naming the cause; a write the project rule refuses is thrown as the refusal it is,
   * with its fix.
   */
  public static void record(ProjectDefinition definition, Actor operator) {
    write(
        SailPaths.controlPlaneDb(), definition.name(), operator, store -> store.upsert(definition));
  }

  /** Creates the project {@code definition} names as {@code operator}; refused when it exists. */
  public static void create(ProjectDefinition definition, Actor operator) {
    write(
        SailPaths.controlPlaneDb(), definition.name(), operator, store -> store.create(definition));
  }

  /**
   * Writes {@code section} of project {@code name} to what {@code edited} holds there, as {@code
   * operator}, only while the catalog holds the section as {@code read} did: an edit made from a
   * definition read before another write of that section is refused, never laid over it.
   */
  public static void writeSection(
      String name,
      Section section,
      ProjectDefinition read,
      ProjectDefinition edited,
      Actor operator) {
    write(
        SailPaths.controlPlaneDb(),
        name,
        operator,
        store ->
            store.writeSection(
                name,
                section,
                ProjectDefinitionJson.section(edited, section),
                ProjectStore.tagOf(ProjectDefinitionJson.section(read, section))));
  }

  /**
   * Writes the sections {@code edited} changes from {@code read}, as {@code operator}, in one
   * revision, only while the catalog holds each of them as {@code read} did; returns the sections
   * it wrote, none when the edit changed nothing.
   */
  public static List<Section> writeChanged(
      ProjectDefinition read, ProjectDefinition edited, Actor operator) {
    var before = ProjectDefinitionJson.sections(read);
    var after = ProjectDefinitionJson.sections(edited);
    var values = new LinkedHashMap<Section, String>();
    var tags = new LinkedHashMap<Section, String>();
    for (var section : Section.values()) {
      if (!Objects.equals(before.get(section.key()), after.get(section.key()))) {
        values.put(section, after.get(section.key()));
        tags.put(section, ProjectStore.tagOf(before.get(section.key())));
      }
    }
    if (!values.isEmpty()) {
      write(
          SailPaths.controlPlaneDb(),
          read.name(),
          operator,
          store -> store.writeSections(read.name(), values, tags));
    }
    return List.copyOf(values.keySet());
  }

  static void write(Path catalog, String name, Actor operator, Consumer<ProjectStore> change) {
    try (var db = Sqlite.open(catalog)) {
      new SchemaManager(db).migrate();
      Actor.run(operator, () -> change.accept(new ProjectStore(db)));
    } catch (WriteRefused refused) {
      throw refused;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Project '" + name + "' was not recorded in the catalog: " + e.getMessage(), e);
    }
  }
}
