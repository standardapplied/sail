/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** A {@link LocalReplica} view over a fixed id set; see {@link LocalReplica#scopedTo}. */
record ScopedLocalReplica(LocalReplica inner, Set<String> ids) implements LocalReplica {

  ScopedLocalReplica {
    ids = Set.copyOf(ids);
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
      Map<String, Object> remote,
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
