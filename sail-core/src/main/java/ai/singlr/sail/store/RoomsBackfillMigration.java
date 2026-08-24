/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.Engagement;
import ai.singlr.sail.config.ProjectRegistry;
import ai.singlr.sail.config.Roster;
import java.util.Arrays;
import java.util.List;

/**
 * Mints one room per existing spec with the room id equal to the spec id, carrying the spec's
 * conversation-side state (wake, engagement-as-roster, assignee) onto the new rooms table. Runs
 * exactly once per database via the {@code data_migrations} marker.
 *
 * <p>Every field of the backfilled row — and therefore its content-hash revision — derives solely
 * from the spec row, so two boxes holding the same synced spec mint a byte-identical room at an
 * identical rev. The write is journaled as LOCAL with no synced ancestor: boxes that both
 * backfilled a room converge as a no-op (equal revs), and a room main never minted — a spec that
 * reached main only by sync — pushes up on the first round instead of reading back as a remote
 * deletion. Rooms diverge only where the underlying specs had genuinely diverged, which sync
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
    if (!hasLegacyColumns(db)) {
      return new Report(0, 0, 0, List.of());
    }
    var specs =
        db.query(
            """
            SELECT id, project, title, assignee, wake, engagement, created_by, created_at,
                updated_by, updated_at
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
                    text(row, 8),
                    text(row, 9)));
    var created = 0;
    for (var spec : specs) {
      var id = spec.get(0);
      if (rooms.findById(id).isPresent()) {
        continue;
      }
      rooms.createJournaled(roomRow(spec));
      created++;
    }
    return new Report(created, 0, 0, List.of());
  }

  /**
   * The room row a spec backfills to, every field taken from the spec row verbatim — timestamps
   * included — so each box mints a byte-identical local revision with no synced ancestor. The
   * engagement column rides through {@link Engagement#fromJson}'s corruption tolerance before
   * becoming the roster's genesis value: a corrupt or blank engagement backfills as no members,
   * exactly as it already read.
   */
  private static RoomStore.RoomRow roomRow(List<String> spec) {
    var member = Engagement.fromJson(spec.get(5));
    return new RoomStore.RoomRow(
        spec.get(0),
        spec.get(1),
        spec.get(2),
        spec.get(3),
        spec.get(4),
        member == null ? null : Roster.solo(member).toJson(),
        spec.get(6),
        spec.get(7),
        spec.get(9),
        spec.get(8));
  }

  /**
   * Whether the specs table still carries the pre-decouple {@code wake}/{@code engagement} columns
   * this backfill reads. A schema without them has nothing left to backfill — either the database
   * was born after the columns retired, or the backfill already ran before the retiring migration
   * dropped them — so the answer is a clean no-op, never a missing-column error.
   */
  private static boolean hasLegacyColumns(Sqlite db) {
    return !db.query(
            "SELECT name FROM pragma_table_info('specs') WHERE name = 'wake'", row -> row.text(0))
        .isEmpty();
  }

  private static String text(Sqlite.Row row, int index) {
    return row.text(index);
  }
}
