/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.store.ConflictDetector;
import ai.singlr.sail.store.MainVersion;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A {@link LocalReplica} view over a fixed id set; see {@link LocalReplica#scopedTo}. The ids keep
 * the order they arrived in: a page lists a reply after its parent, and the engine must visit them
 * that way or the reply is refused for a parent it has not adopted yet.
 */
record ScopedLocalReplica(LocalReplica inner, Set<String> ids) implements LocalReplica {

  ScopedLocalReplica {
    ids = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
  }

  @Override
  public Set<String> entityIds() {
    return ids;
  }

  @Override
  public Set<String> dirtyIds() {
    return inner.dirtyIds();
  }

  @Override
  public boolean mayPush(String id) {
    return inner.mayPush(id);
  }

  @Override
  public boolean live(String id) {
    return inner.live(id);
  }

  @Override
  public String lastHeardRev(String id) {
    return inner.lastHeardRev(id);
  }

  @Override
  public String author(String id) {
    return inner.author(id);
  }

  @Override
  public void offering(String id, Map<String, Object> offered, Map<String, Object> from) {
    inner.offering(id, offered, from);
  }

  @Override
  public void settled(String id) {
    inner.settled(id);
  }

  @Override
  public boolean acknowledge(String id, Map<String, Object> accepted, String rev) {
    return inner.acknowledge(id, accepted, rev);
  }

  @Override
  public Set<String> latestWinsFields() {
    return inner.latestWinsFields();
  }

  @Override
  public ConflictDetector.FieldMerger fieldMerger() {
    return inner.fieldMerger();
  }

  @Override
  public <T> T atomically(Supplier<T> work) {
    return inner.atomically(work);
  }

  @Override
  public Map<String, Object> current(String id) {
    return inner.current(id);
  }

  @Override
  public Map<String, Object> base(String id) {
    return inner.base(id);
  }

  @Override
  public String currentRev(String id) {
    return inner.currentRev(id);
  }

  @Override
  public void adopt(String id, Map<String, Object> snapshot, String rev) {
    inner.adopt(id, snapshot, rev);
  }

  @Override
  public void recordConflict(
      String id,
      Map<String, Object> base,
      Map<String, Object> local,
      MainVersion remote,
      List<String> fields) {
    inner.recordConflict(id, base, local, remote, fields);
  }

  @Override
  public long checkpoint(String peerId) {
    return inner.checkpoint(peerId);
  }

  @Override
  public void advanceCheckpoint(String peerId, long seq) {
    inner.advanceCheckpoint(peerId, seq);
  }
}
