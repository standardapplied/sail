/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.util.Map;

/**
 * Rebases an entity onto main's conflicting state and writes the chosen resolution, returning the
 * rev the row now carries. Implemented by every store whose rows sync ({@link SpecStore}, {@link
 * FileStore}) so conflict resolution can dispatch on entity type without knowing the concrete
 * store.
 */
public interface ConflictResolver {

  String resolveConflict(String id, Map<String, Object> chosen, Map<String, Object> remote);
}
