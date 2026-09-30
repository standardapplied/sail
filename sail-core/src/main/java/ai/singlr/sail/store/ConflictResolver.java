/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.Map;

/**
 * Adopts main's side of a parked conflict at main's rev and author and writes the chosen resolution
 * over it, returning the rev the row now carries. Implemented by every synced store so conflict
 * resolution can dispatch on entity type without knowing the concrete store; every store that rides
 * the {@link RevisionJournal} delegates to it.
 */
public interface ConflictResolver {

  /**
   * Resolves the conflict on {@code id} to {@code chosen}, adopting {@code theirs}, main's side as
   * the conflict recorded it, first.
   */
  String resolveConflict(String id, Map<String, Object> chosen, MainVersion theirs);
}
