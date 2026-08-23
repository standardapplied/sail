/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.config.YamlUtil;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Mints one room per existing spec with the room id equal to the spec id, carrying the spec's
 * conversation-side state (wake, engagement-as-roster, assignee) onto the new rooms table. Runs
 * exactly once per database via the {@code data_migrations} marker.
 *
 * <p>Every field of the backfilled snapshot — including its revision — is derived solely from the
 * spec row, so two boxes that hold the same synced spec mint a byte-identical room at an identical
 * content-hash rev, recorded as its own synced ancestor ({@code base_rev = rev}). The first fleet
 * sync after an upgrade therefore converges every backfilled room with zero pushes, pulls, or
 * conflicts; rooms diverge only where the underlying specs had genuinely diverged, which sync then
 * surfaces through the ordinary conflict path.
 */
public final class RoomsBackfillMigration implements DataMigration {

  public static final String NAME = "rooms-from-specs-v1";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public Report apply(Sqlite db, ProjectRegistry projects, Prompter prompter) {
    var rooms = new RoomStore(db);
    var specs =
        db.query(
            """
            SELECT id, project, title, assignee, wake, engagement, created_by, created_at,
                updated_by
            FROM specs ORDER BY id""",
            row ->
                Arrays.asList(
                    text(row, 0),
                    text(row, 1),
                    text(row, 2),
                    text(row, 3),
                    text(row, 4),
                    text(row, 5),
                    text(row, 6),
                    text(row, 7),
                    text(row, 8)));
    var created = 0;
    for (var spec : specs) {
      var id = spec.get(0);
      if (rooms.findById(id).isPresent()) {
        continue;
      }
      var snapshot = roomSnapshot(spec);
      rooms.applyRevision(id, snapshot, Revisions.next(null, YamlUtil.dumpJson(snapshot)));
      created++;
    }
    return new Report(created, 0, 0, List.of());
  }

  /**
   * The comparable-shaped snapshot sync itself would deliver: the room's work-carrying fields plus
   * the {@code _actor} attribution resolved into {@code updated_by} on apply. Field values come
   * from the spec row verbatim; the engagement JSON object becomes the roster's one-element array.
   */
  private static LinkedHashMap<String, Object> roomSnapshot(List<String> spec) {
    var engagement = spec.get(5);
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("project", spec.get(1));
    snapshot.put("title", spec.get(2));
    snapshot.put("assignee", spec.get(3));
    snapshot.put("wake", spec.get(4));
    snapshot.put("roster", engagement == null ? null : "[" + engagement + "]");
    snapshot.put("created_by", spec.get(6));
    snapshot.put("created_at", spec.get(7));
    if (spec.get(8) != null) {
      snapshot.put(Snapshots.ACTOR, spec.get(8));
    }
    return snapshot;
  }

  private static String text(Sqlite.Row row, int index) {
    return row.text(index);
  }
}
