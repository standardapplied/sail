/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.ProjectRegistry;
import java.util.ArrayList;
import java.util.List;

/**
 * Folds the finding rows a box holds from before findings were a review's content into their
 * reviews: each review the box holds findings for gets one revision, as this box's machinery,
 * carrying them as content. On main and a standalone box the revision is the review's new head; on
 * a node the next round pushes it, main decides it by the review rule, and a denial leaves it in
 * the node's change log. Resumable: each review folds in its own transaction, rechecked under the
 * write lock, and a review that already has content is left alone. A review whose spec main has
 * pruned, held here because its own erasure has not paged in yet, cannot take a revision and is
 * left as it is, reported, for that erasure to remove.
 */
public final class ReviewFindingsMigration implements DataMigration {

  public static final String NAME = "review-findings-as-content-v1";

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
    var reviews = new ReviewStore(db);
    var folded = 0;
    var notes = new ArrayList<String>();
    for (var id : legacyReviews(db)) {
      try {
        if (db.transaction(() -> reviews.foldLegacyFindings(id))) {
          folded++;
        }
      } catch (ChangeLog.Pruned pruned) {
        notes.add("Left review " + id + " unfolded: " + pruned.getMessage());
      }
    }
    notes.addFirst("Folded the findings of " + folded + " review(s) into their content");
    return new Report(folded, 0, notes.size() - 1, notes);
  }

  private static List<String> legacyReviews(Sqlite db) {
    return db.query(
        """
        SELECT DISTINCT r.id FROM reviews r
        JOIN review_stages s ON s.review_id = r.id
        JOIN review_findings f ON f.stage_id = s.id
        WHERE r.findings_hash IS NULL ORDER BY r.rowid""",
        row -> row.text(0));
  }
}
