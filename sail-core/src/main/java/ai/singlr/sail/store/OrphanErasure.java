/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.engine.NodeIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Erases, once, what an older release left behind with nothing to belong to: a run or a review
 * whose spec this box holds no row and no history of, and a message whose room is neither a room
 * nor a spec here, live or in history. Nothing can reach or restore those rows, and nothing else
 * would ever remove them.
 *
 * <p>Only an authoritative box — main, or a standalone box — erases, recording an erasure row per
 * entity that every node then adopts through its pages; a node's own view of "no parent" may only
 * mean a page it has not pulled yet, so on a node this migration erases nothing. One orphan per
 * transaction, with what belongs to it — a message goes with its replies, which live in the same
 * orphaned room — and re-checked under its lock, so the order the orphans are read in never
 * matters, an upgrade killed partway resumes where it stopped and running it twice erases nothing
 * twice. The count is printed.
 */
public final class OrphanErasure implements DataMigration {

  public static final String NAME = "orphans-erased-v1";

  private static final Map<String, String> ORPHANED =
      Map.of(
          Erasure.RUN,
          """
          SELECT id FROM runs r WHERE r.spec_id IS NOT NULL
          AND NOT EXISTS (SELECT 1 FROM specs s WHERE s.id = r.spec_id)
          AND NOT EXISTS (SELECT 1 FROM change_heads h
              WHERE h.entity_type = 'spec' AND h.entity_id = r.spec_id)""",
          Erasure.REVIEW,
          """
          SELECT id FROM reviews v
          WHERE NOT EXISTS (SELECT 1 FROM specs s WHERE s.id = v.spec_id)
          AND NOT EXISTS (SELECT 1 FROM change_heads h
              WHERE h.entity_type = 'spec' AND h.entity_id = v.spec_id)""",
          Erasure.MESSAGE,
          """
          SELECT id FROM room_messages m
          WHERE NOT EXISTS (SELECT 1 FROM rooms o WHERE o.id = m.room_id)
          AND NOT EXISTS (SELECT 1 FROM specs s WHERE s.id = m.room_id OR s.room_id = m.room_id)
          AND NOT EXISTS (SELECT 1 FROM change_heads h
              WHERE h.entity_type IN ('room', 'spec') AND h.entity_id = m.room_id)""");

  private static final List<String> TYPES = List.of(Erasure.RUN, Erasure.REVIEW, Erasure.MESSAGE);

  private final BooleanSupplier authoritative;

  public OrphanErasure() {
    this(NodeIdentity::authoritative);
  }

  OrphanErasure(BooleanSupplier authoritative) {
    this.authoritative = Objects.requireNonNull(authoritative, "authoritative");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public boolean resumable() {
    return true;
  }

  @Override
  public Report apply(Sqlite db, ProjectRegistry projects, Prompter prompter) {
    if (!authoritative.getAsBoolean()) {
      return new Report(
          0, 0, 0, List.of("Orphans are erased by main; this node adopts main's erasures."));
    }
    var erasure = new Erasure(db);
    var erased = new ArrayList<Erasure.Target>();
    for (var type : TYPES) {
      for (var id : db.query(ORPHANED.get(type), row -> row.text(0))) {
        var result =
            db.transaction(
                () ->
                    stillOrphaned(db, type, id)
                        ? erasure.erase(
                            erasure.closure(List.of(new Erasure.Target(type, id))),
                            "sail",
                            "migration")
                        : new Erasure.Result(List.of(), 0));
        erased.addAll(result.entities());
      }
    }
    var result = new Erasure.Result(erased, 0);
    return new Report(
        erased.size(),
        0,
        0,
        List.of(
            "Erased "
                + erased.size()
                + " orphaned rows: "
                + result.count(Erasure.RUN)
                + " runs, "
                + result.count(Erasure.REVIEW)
                + " reviews, "
                + result.count(Erasure.MESSAGE)
                + " messages"));
  }

  private static boolean stillOrphaned(Sqlite db, String type, String id) {
    return db.queryOne("SELECT 1 FROM (" + ORPHANED.get(type) + ") WHERE id = ?", row -> true, id)
        .orElse(false);
  }
}
