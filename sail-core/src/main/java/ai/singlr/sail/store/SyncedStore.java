/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.Map;
import java.util.Set;

/**
 * The store surface a sync replica drives — everything the {@code StoreReplica} needs to act as
 * both the node ({@code LocalReplica}) and the authority ({@code MainReplica}) for one entity type,
 * without knowing the concrete store. Implemented by every synced store (specs, runs, reviews,
 * projects, files, messages), which collapses the six near-identical hand-written replicas into one
 * generic adapter.
 *
 * <p>The three {@code default} hooks cover the two stores that diverge: {@link #currentForSync} and
 * {@link #adoptForSync} let a store weave a resurrection-blocking tombstone into the sync view
 * (only {@code ProjectStore} does), and {@code mayPush} — a per-node runtime decision — is supplied
 * to the replica as a policy rather than living here.
 */
public interface SyncedStore {

  /** The {@code change_log.entity_type} discriminator, e.g. {@code "spec"}. */
  String entityType();

  /** Every entity id this replica knows of, including tombstoned ones. */
  Set<String> syncEntityIds();

  /** Current comparable state; {@code null} if deleted or absent. */
  Map<String, Object> comparableSnapshot(String id);

  /** Comparable state recorded at a revision (the merge base); {@code null} if not recorded. */
  Map<String, Object> comparableAtRev(String id, String rev);

  /** The revision this row last synced from main; {@code null} if never. */
  String baseRevOf(String id);

  /** Latest revision, including a tombstone; {@code null} if unknown. */
  String latestRev(String id);

  /** Adopts an authoritative state at main's exact rev ({@code null} snapshot = delete). */
  void applyRevision(String id, Map<String, Object> snapshot, String rev);

  /** Compare-and-set commit of an authoritative state ({@code null} = delete). */
  PushOutcome commitRevision(String id, Map<String, Object> snapshot, String expectedRev);

  /**
   * The state the replica reports as "current" to the sync engine — {@link #comparableSnapshot} for
   * every store except one that surfaces a blocking tombstone marker for a deleted-but-blocked id.
   */
  default Map<String, Object> currentForSync(String id) {
    return comparableSnapshot(id);
  }

  /**
   * Adopts an authoritative state, unwrapping any sync-only marker the store's {@link
   * #currentForSync} produced — {@link #applyRevision} for every store except the one that reads a
   * blocking-tombstone marker back as a deletion.
   */
  default void adoptForSync(String id, Map<String, Object> snapshot, String rev) {
    applyRevision(id, snapshot, rev);
  }
}
