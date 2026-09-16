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
import java.util.function.Predicate;

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
  public interface PushPolicy {
    Predicate<String> forStore(SyncedStore store, String handle);
  }

  @FunctionalInterface
  public interface TransitionDetector {
    List<SyncTransition> detect(String id, Map<String, Object> before, Map<String, Object> after);
  }

  public record Entity(
      String type,
      Function<Sqlite, SyncedStore> factory,
      PushPolicy pushPolicy,
      TransitionDetector transitions,
      Map<String, TransitionKind> transitionKinds) {
    public SyncedStore store(Sqlite db) {
      return factory.apply(db);
    }

    public ConflictResolver resolver(Sqlite db) {
      return (ConflictResolver) store(db);
    }
  }

  private static final PushPolicy ALL = (store, handle) -> id -> true;
  private static final TransitionDetector NONE = (id, before, after) -> List.of();
  private static final List<Entity> ENTITIES =
      List.of(
          new Entity(
              "spec",
              SpecStore::new,
              ALL,
              (id, before, after) -> SyncTransitions.statusChange("spec", id, before, after),
              Map.of("spec", TransitionKind.SPEC_STATUS)),
          new Entity("room", RoomStore::new, ALL, NONE, Map.of()),
          new Entity("file", FileStore::new, ALL, NONE, Map.of()),
          new Entity("project", ProjectStore::new, ALL, NONE, Map.of()),
          new Entity(
              "run",
              RunStore::new,
              (store, handle) -> id -> ((RunStore) store).pushableFrom(id, handle),
              (id, before, after) -> SyncTransitions.statusChange("run", id, before, after),
              Map.of("run", TransitionKind.RUN_STATUS)),
          new Entity(
              "review",
              ReviewStore::new,
              ALL,
              SyncTransitions::reviewChanges,
              Map.of(
                  "review",
                  TransitionKind.REVIEW_STATUS,
                  "review_stage",
                  TransitionKind.REVIEW_STAGE_STATUS)),
          new Entity(
              "message",
              MessageStore::new,
              ALL,
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

  public static Map<String, StoreReplica> replicas(Sqlite db, String host, String handle) {
    var changes = new ChangeLog(db);
    var conflicts = new SyncConflicts(db);
    var state = new SyncState(db);
    var replicas = new LinkedHashMap<String, StoreReplica>();
    for (var entity : ENTITIES) {
      var store = entity.store(db);
      replicas.put(
          entity.type(),
          new StoreReplica(
              host, store, changes, conflicts, state, entity.pushPolicy().forStore(store, handle)));
    }
    return Collections.unmodifiableMap(replicas);
  }
}
