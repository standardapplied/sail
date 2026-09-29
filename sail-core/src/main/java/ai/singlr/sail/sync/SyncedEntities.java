/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.authority.MessageAuthority;
import ai.singlr.sail.authority.ReviewAuthority;
import ai.singlr.sail.authority.RoomAuthority;
import ai.singlr.sail.authority.RunAuthority;
import ai.singlr.sail.authority.SpecAuthority;
import ai.singlr.sail.authority.WriteAuthority;
import ai.singlr.sail.authority.WriterAuthority;
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

  /**
   * One replicated type: its store, how it resolves a parked conflict, which of its changes a node
   * pushes ({@code pushPolicy}), who may write it ({@code authorityFactory}, the rule main's commit
   * asks), and which of its revisions are transitions worth narrating.
   */
  public record Entity(
      String type,
      Function<Sqlite, SyncedStore> factory,
      Function<Sqlite, ConflictResolver> resolverFactory,
      PushPolicy pushPolicy,
      Function<Sqlite, WriteAuthority> authorityFactory,
      TransitionDetector transitions,
      Map<String, TransitionKind> transitionKinds) {
    public SyncedStore store(Sqlite db) {
      return factory.apply(db);
    }

    /** Who may write this type on {@code db}'s box. */
    public WriteAuthority authority(Sqlite db) {
      return authorityFactory.apply(db);
    }

    /** Every replicated entity resolves its parked conflicts; the type system says so. */
    public ConflictResolver resolver(Sqlite db) {
      return resolverFactory.apply(db);
    }
  }

  private static final PushPolicy ALL = (store, handle) -> id -> true;
  private static final TransitionDetector NONE = (id, before, after) -> List.of();
  private static final List<Entity> ENTITIES =
      List.of(
          new Entity(
              "spec",
              SpecStore::new,
              SpecStore::new,
              ALL,
              SpecAuthority::new,
              (id, before, after) -> SyncTransitions.statusChange("spec", id, before, after),
              Map.of("spec", TransitionKind.SPEC_STATUS)),
          new Entity(
              "room", RoomStore::new, RoomStore::new, ALL, RoomAuthority::new, NONE, Map.of()),
          new Entity(
              "file",
              FileStore::new,
              FileStore::new,
              ALL,
              db -> new WriterAuthority(db, "files"),
              NONE,
              Map.of()),
          new Entity(
              "project",
              ProjectStore::new,
              ProjectStore::new,
              ALL,
              db -> new WriterAuthority(db, "projects"),
              NONE,
              Map.of()),
          new Entity(
              "run",
              RunStore::new,
              RunStore::new,
              (store, handle) -> id -> ((RunStore) store).pushableFrom(id, handle),
              RunAuthority::new,
              (id, before, after) -> SyncTransitions.statusChange("run", id, before, after),
              Map.of("run", TransitionKind.RUN_STATUS)),
          new Entity(
              "review",
              ReviewStore::new,
              ReviewStore::new,
              ALL,
              ReviewAuthority::new,
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
              ALL,
              MessageAuthority::new,
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

  /** One replica per registered type over {@code db}, identified as {@code boxId}. */
  public static Map<String, StoreReplica> replicas(Sqlite db, String boxId, String handle) {
    var changes = new ChangeLog(db);
    var conflicts = new SyncConflicts(db);
    var state = new SyncState(db);
    var replicas = new LinkedHashMap<String, StoreReplica>();
    for (var entity : ENTITIES) {
      var store = entity.store(db);
      replicas.put(
          entity.type(),
          new StoreReplica(
              boxId,
              store,
              changes,
              conflicts,
              state,
              entity.pushPolicy().forStore(store, handle),
              entity.authority(db)));
    }
    return Collections.unmodifiableMap(replicas);
  }
}
