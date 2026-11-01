/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.identity.ProjectScope;

/**
 * Prepares this box's database when it is opened: on every {@code sail server start} and by {@code
 * sail migrate}. It creates the schema on a fresh database or refuses one of another release
 * ({@link SchemaManager}), and then a box that is not a node forgets the scope main last named it
 * ({@link SyncScope}): a box whose role change died between writing {@code host.yaml} and clearing
 * it would otherwise keep refusing its own writes and its nodes' pushes into every project outside
 * that scope. Only a whole {@code host.yaml} decides it ({@link NodeIdentity#declaresNotNode}): one
 * absent, empty or unreadable at that moment keeps the scope for the next start to decide, so no
 * node loses its grant to a file being moved or rewritten. It takes no lock: a whole file is the
 * box's identity when read, and a box just joined as a node is without a scope until its first
 * round records one.
 */
public final class BoxDatabase {

  private BoxDatabase() {}

  /** Creates or checks {@code db}'s schema, then settles the node scope the box's role names. */
  public static void prepare(Sqlite db) {
    new SchemaManager(db).migrate();
    forgetNodeScope(db);
  }

  private static void forgetNodeScope(Sqlite db) {
    if (NodeIdentity.declaresNotNode()) {
      new SyncScope(db).record(ProjectScope.all());
      new DatabaseCatalog(db).forgetMain();
    }
  }
}
