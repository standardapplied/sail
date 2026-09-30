/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.ConflictResolver;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import ai.singlr.sail.store.SyncedStore;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The dependency-ordered registry of every replicated entity and its store-specific policies. */
public final class SyncedEntities {
  public enum TransitionKind {
    SPEC_STATUS,
    RUN_STATUS,
    REVIEW_STATUS,
    REVIEW_STAGE_STATUS,
    MESSAGE_POSTED,
    NONE
  }

  @FunctionalInterface
  public interface TransitionDetector {
    List<SyncTransition> detect(String id, Map<String, Object> before, Map<String, Object> after);
  }

  /**
   * One replicated type: its store, which declares who may write it ({@link SyncedStore#authority})
   * and which of its changes a node pushes ({@link SyncedStore#mayPush}), how it resolves a parked
   * conflict, and which of its revisions are transitions worth narrating.
   */
  public record Entity(
      String type,
      Function<Sqlite, SyncedStore> factory,
      Function<Sqlite, ConflictResolver> resolverFactory,
      TransitionDetector transitions,
      Map<String, TransitionKind> transitionKinds) {
    public SyncedStore store(Sqlite db) {
      return factory.apply(db);
    }

    /** Every replicated entity resolves its parked conflicts; the type system says so. */
    public ConflictResolver resolver(Sqlite db) {
      return resolverFactory.apply(db);
    }
  }

  private static final TransitionDetector NONE = (id, before, after) -> List.of();
  private static final List<Entity> ENTITIES =
      List.of(
          new Entity(
              "spec",
              SpecStore::new,
              SpecStore::new,
              (id, before, after) -> SyncTransitions.statusChange("spec", id, before, after),
              Map.of("spec", TransitionKind.SPEC_STATUS)),
          new Entity("room", RoomStore::new, RoomStore::new, NONE, Map.of()),
          new Entity("file", FileStore::new, FileStore::new, NONE, Map.of()),
          new Entity("project", ProjectStore::new, ProjectStore::new, NONE, Map.of()),
          new Entity(
              "run",
              RunStore::new,
              RunStore::new,
              (id, before, after) -> SyncTransitions.statusChange("run", id, before, after),
              Map.of("run", TransitionKind.RUN_STATUS)),
          new Entity(
              "review",
              ReviewStore::new,
              ReviewStore::new,
              SyncTransitions::reviewChanges,
              Map.of(
                  "review",
                  TransitionKind.REVIEW_STATUS,
                  "review_stage",
                  TransitionKind.REVIEW_STAGE_STATUS)),
          new Entity(
              "message",
              MessageStore::new,
              MessageStore::new,
              (id, before, after) ->
                  before == null
                      ? List.of(new SyncTransition("message", id, null, "posted", after))
                      : List.of(),
              Map.of("message", TransitionKind.MESSAGE_POSTED)));

  private SyncedEntities() {}

  public static List<Entity> all() {
    return ENTITIES;
  }

  public static TransitionKind transitionKind(String type) {
    return ENTITIES.stream()
        .flatMap(entity -> entity.transitionKinds().entrySet().stream())
        .filter(entry -> entry.getKey().equals(type))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(TransitionKind.NONE);
  }

  public static Entity require(String type) {
    return ENTITIES.stream()
        .filter(entity -> entity.type().equals(type))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Unknown conflict entity type: " + type));
  }

  /**
   * One replica per registered type over {@code db}, identified as {@code boxId}, for the box whose
   * FDE handle is {@code handle}.
   */
  public static Map<String, StoreReplica> replicas(Sqlite db, String boxId, String handle) {
    var changes = new ChangeLog(db);
    var conflicts = new SyncConflicts(db);
    var state = new SyncState(db);
    var replicas = new LinkedHashMap<String, StoreReplica>();
    for (var entity : ENTITIES) {
      var store = entity.store(db);
      replicas.put(
          entity.type(), new StoreReplica(boxId, store, changes, conflicts, state, handle));
    }
    return Collections.unmodifiableMap(replicas);
  }
}
