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
import ai.singlr.sail.store.SyncedStore;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** The dependency-ordered registry of every replicated entity and its store-specific policies. */
public final class SyncedEntities {
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
      TransitionDetector transitions) {
    public SyncedStore store(Sqlite db) {
      return factory.apply(db);
    }

    public ConflictResolver resolver(Sqlite db) {
      return (ConflictResolver) store(db);
    }
  }

  private static final PushPolicy ALL = (store, handle) -> id -> true;
  private static final TransitionDetector NONE = (id, before, after) -> List.of();
  private static final List<Entity> ENTITIES = List.of(
      new Entity("spec", SpecStore::new, ALL,
          (id, before, after) -> SyncTransitions.statusChange("spec", id, before, after)),
      new Entity("room", RoomStore::new, ALL, NONE),
      new Entity("file", FileStore::new, ALL, NONE),
      new Entity("project", ProjectStore::new, ALL, NONE),
      new Entity("run", RunStore::new,
          (store, handle) -> id -> ((RunStore) store).pushableFrom(id, handle),
          (id, before, after) -> SyncTransitions.statusChange("run", id, before, after)),
      new Entity("review", ReviewStore::new, ALL, SyncTransitions::reviewChanges),
      new Entity("message", MessageStore::new, ALL,
          (id, before, after) -> before == null
              ? List.of(new SyncTransition("message", id, null, "posted", after)) : List.of()));

  private SyncedEntities() {}

  public static List<Entity> all() {
    return ENTITIES;
  }

  public static Entity require(String type) {
    return ENTITIES.stream().filter(entity -> entity.type().equals(type)).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown conflict entity type: " + type));
  }

  public static Map<String, StoreReplica> replicas(Sqlite db, String host, String handle) {
    var changes = new ChangeLog(db);
    var conflicts = new SyncConflicts(db);
    var state = new SyncState(db);
    var replicas = new LinkedHashMap<String, StoreReplica>();
    for (var entity : ENTITIES) {
      var store = entity.store(db);
      replicas.put(entity.type(), new StoreReplica(host, store, changes, conflicts, state,
          entity.pushPolicy().forStore(store, handle)));
    }
    return Collections.unmodifiableMap(replicas);
  }
}
