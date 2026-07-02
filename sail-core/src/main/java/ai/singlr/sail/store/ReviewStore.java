/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.DateTimeUtils;
import java.util.List;
import java.util.Optional;

/**
 * Review, stage, and finding CRUD on SQLite. Each review belongs to a spec and tracks one pass
 * through the review pipeline. Stages run sequentially; findings belong to stages.
 */
public final class ReviewStore {

  private final Sqlite db;

  public ReviewStore(Sqlite db) {
    this.db = db;
  }

  /**
   * @param supersededAt when a later dispatch attempt closed this review, or {@code null} while it
   *     belongs to the current attempt. The pipeline ignores superseded rows, so iterations count
   *     per attempt rather than per spec lifetime.
   */
  public record ReviewRow(
      String id,
      String specId,
      int iteration,
      String status,
      String createdAt,
      String completedAt,
      String decidedBy,
      String supersededAt,
      String error) {

    public boolean superseded() {
      return supersededAt != null;
    }

    public boolean errored() {
      return error != null;
    }
  }

  public record StageRow(
      String id,
      String reviewId,
      String name,
      String stageType,
      String status,
      String reviewer,
      String startedAt,
      String completedAt,
      String error) {}

  public String createReview(String specId, int iteration) {
    var id = DateTimeUtils.newId().toString();
    db.execute(
        "INSERT INTO reviews (id, spec_id, iteration, status, created_at) VALUES (?, ?, ?, 'pending', ?)",
        id,
        specId,
        iteration,
        DateTimeUtils.now().toString());
    return id;
  }

  public Optional<ReviewRow> findReview(String reviewId) {
    return db.queryOne(
        "SELECT id, spec_id, iteration, status, created_at, completed_at, decided_by,"
            + " superseded_at, error FROM reviews WHERE id = ?",
        this::mapReview,
        reviewId);
  }

  /**
   * The latest review of the spec's <em>current</em> dispatch attempt, or empty when none exists
   * yet or a re-dispatch superseded them all. Superseded rows are history, not pipeline state: the
   * controller keys its already-running guard and its iteration count off this, so excluding them
   * here is what makes each dispatch attempt start fresh at iteration 1.
   */
  public Optional<ReviewRow> latestReviewForSpec(String specId) {
    return db.queryOne(
        """
        SELECT id, spec_id, iteration, status, created_at, completed_at, decided_by,
          superseded_at, error
        FROM reviews WHERE spec_id = ? AND superseded_at IS NULL
        ORDER BY created_at DESC, rowid DESC LIMIT 1""",
        this::mapReview,
        specId);
  }

  /** Every review ever run for the spec, across all dispatch attempts, oldest first. */
  public List<ReviewRow> reviewsForSpec(String specId) {
    return db.query(
        """
        SELECT id, spec_id, iteration, status, created_at, completed_at, decided_by,
          superseded_at, error
        FROM reviews WHERE spec_id = ? ORDER BY created_at ASC, rowid ASC""",
        this::mapReview,
        specId);
  }

  /** Marks a review passed and records the deciding principal (the human who approved it). */
  public void approve(String reviewId, String decidedBy) {
    db.execute(
        "UPDATE reviews SET status = 'passed', completed_at = ?, decided_by = ? WHERE id = ?",
        DateTimeUtils.now().toString(),
        decidedBy,
        reviewId);
  }

  /**
   * Closes every prior-attempt review for a spec by marking it {@code superseded}, returning how
   * many changed. Called at dispatch time, so review iterations count per dispatch attempt: the
   * pipeline starts a superseded spec back at iteration 1 instead of inheriting (and eventually
   * exhausting) the lifetime count, which would otherwise silently escalate every re-dispatch once
   * {@code max_iterations} had ever been reached.
   */
  public int supersedeForSpec(String specId) {
    db.execute(
        "UPDATE reviews SET superseded_at = ? WHERE spec_id = ? AND superseded_at IS NULL",
        DateTimeUtils.now().toString(),
        specId);
    return db.changes();
  }

  /**
   * Marks every {@code running} review {@code failed}, returning how many were swept. A review's
   * execution lives only in the controller's memory, so after a server restart a {@code running}
   * row is an orphan of an interrupted run; left in place it silently blocks every future review
   * for its spec (the pipeline skips a spec whose latest review is running). Called once at server
   * start, before missed stops are replayed.
   */
  public int failOrphanedRunning() {
    db.execute(
        "UPDATE reviews SET status = 'failed', completed_at = ? WHERE status = 'running'",
        DateTimeUtils.now().toString());
    return db.changes();
  }

  /**
   * Marks the review failed by infrastructure error, not verdict; retried without burning an
   * iteration.
   */
  public void failReviewWithError(String reviewId, String error) {
    db.execute(
        "UPDATE reviews SET status = 'failed', error = ?, completed_at = ? WHERE id = ?",
        error,
        DateTimeUtils.now().toString(),
        reviewId);
  }

  public void updateReviewStatus(String reviewId, String status) {
    var completedAt =
        "passed".equals(status) || "failed".equals(status) || "escalated".equals(status)
            ? DateTimeUtils.now().toString()
            : null;
    db.execute(
        "UPDATE reviews SET status = ?, completed_at = COALESCE(?, completed_at) WHERE id = ?",
        status,
        completedAt,
        reviewId);
  }

  public String createStage(String reviewId, String name, String stageType) {
    var id = DateTimeUtils.newId().toString();
    db.execute(
        "INSERT INTO review_stages (id, review_id, name, stage_type, status) VALUES (?, ?, ?, ?, 'pending')",
        id,
        reviewId,
        name,
        stageType);
    return id;
  }

  public Optional<StageRow> findStage(String stageId) {
    return db.queryOne(
        """
        SELECT id, review_id, name, stage_type, status, reviewer, started_at, completed_at, error
        FROM review_stages WHERE id = ?""",
        this::mapStage,
        stageId);
  }

  public List<StageRow> stagesForReview(String reviewId) {
    return db.query(
        """
        SELECT id, review_id, name, stage_type, status, reviewer, started_at, completed_at, error
        FROM review_stages WHERE review_id = ? ORDER BY rowid ASC""",
        this::mapStage,
        reviewId);
  }

  public void startStage(String stageId, String reviewer) {
    db.execute(
        "UPDATE review_stages SET status = 'running', reviewer = ?, started_at = ? WHERE id = ?",
        reviewer,
        DateTimeUtils.now().toString(),
        stageId);
  }

  public void completeStage(String stageId, String status) {
    completeStage(stageId, status, null);
  }

  /**
   * Completes a stage, optionally recording why it failed for reasons other than its gate — the
   * reviewer process erroring (quota, exec failure) rather than findings tripping the gate. The
   * error is what {@code sail agent review} shows, so an infrastructure failure is never mistaken
   * for a genuine review verdict.
   */
  public void completeStage(String stageId, String status, String error) {
    db.execute(
        "UPDATE review_stages SET status = ?, completed_at = ?, error = ? WHERE id = ?",
        status,
        DateTimeUtils.now().toString(),
        error,
        stageId);
  }

  public void addFinding(String stageId, Finding finding) {
    db.execute(
        """
        INSERT INTO review_findings (id, stage_id, severity, category, file,
            line_start, line_end, title, description, evidence,
            suggestion_before, suggestion_after, suggestion_rationale,
            confidence, resolution)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        finding.id(),
        stageId,
        finding.severity().name(),
        finding.category().name(),
        finding.file(),
        finding.lineStart(),
        finding.lineEnd(),
        finding.title(),
        finding.description(),
        finding.evidence(),
        finding.suggestion() != null ? finding.suggestion().before() : null,
        finding.suggestion() != null ? finding.suggestion().after() : null,
        finding.suggestion() != null ? finding.suggestion().rationale() : null,
        finding.confidence(),
        finding.resolution().name());
  }

  public List<Finding> findingsForStage(String stageId) {
    return db.query(
        """
        SELECT id, severity, category, file, line_start, line_end, title,
            description, evidence, suggestion_before, suggestion_after,
            suggestion_rationale, confidence, resolution
        FROM review_findings WHERE stage_id = ? ORDER BY
            CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
            WHEN 'MEDIUM' THEN 2 ELSE 3 END""",
        this::mapFinding,
        stageId);
  }

  public List<Finding> findingsForReview(String reviewId) {
    return db.query(
        """
        SELECT f.id, f.severity, f.category, f.file, f.line_start, f.line_end,
            f.title, f.description, f.evidence, f.suggestion_before,
            f.suggestion_after, f.suggestion_rationale, f.confidence, f.resolution
        FROM review_findings f
        JOIN review_stages s ON s.id = f.stage_id
        WHERE s.review_id = ?
        ORDER BY CASE f.severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
            WHEN 'MEDIUM' THEN 2 ELSE 3 END""",
        this::mapFinding,
        reviewId);
  }

  public List<Finding> openFindingsForReview(String reviewId) {
    return db.query(
        """
        SELECT f.id, f.severity, f.category, f.file, f.line_start, f.line_end,
            f.title, f.description, f.evidence, f.suggestion_before,
            f.suggestion_after, f.suggestion_rationale, f.confidence, f.resolution
        FROM review_findings f
        JOIN review_stages s ON s.id = f.stage_id
        WHERE s.review_id = ? AND f.resolution = 'OPEN'
        ORDER BY CASE f.severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
            WHEN 'MEDIUM' THEN 2 ELSE 3 END""",
        this::mapFinding,
        reviewId);
  }

  public void resolveFinding(String findingId, Finding.Resolution resolution) {
    db.execute(
        "UPDATE review_findings SET resolution = ? WHERE id = ?", resolution.name(), findingId);
  }

  /**
   * Records which review findings a follow-up spec was drafted from, so completing the spec can
   * resolve exactly those findings. Idempotent: re-linking an already-linked finding is a no-op.
   */
  public void linkSourceFindings(String specId, List<String> findingIds) {
    db.transaction(
        () -> {
          for (var findingId : findingIds) {
            db.execute(
                "INSERT OR IGNORE INTO spec_source_findings (spec_id, finding_id) VALUES (?, ?)",
                specId,
                findingId);
          }
        });
  }

  /** The finding ids a follow-up spec was drafted from, or empty for a regular spec. */
  public List<String> sourceFindingIds(String specId) {
    return db.query(
        "SELECT finding_id FROM spec_source_findings WHERE spec_id = ? ORDER BY rowid ASC",
        row -> row.text(0),
        specId);
  }

  /**
   * Marks every still-open finding linked to the follow-up spec {@code FIXED}, returning how many
   * changed. Called when the follow-up spec reaches {@code done}; findings already dismissed or
   * fixed by other means are left untouched.
   */
  public int resolveSourceFindings(String specId) {
    db.execute(
        """
        UPDATE review_findings SET resolution = 'FIXED'
        WHERE resolution = 'OPEN'
        AND id IN (SELECT finding_id FROM spec_source_findings WHERE spec_id = ?)""",
        specId);
    return db.changes();
  }

  /**
   * Open findings the spec shipped with: the latest non-superseded review's open findings, but only
   * when that review passed. A failed or still-running review is in-flight work, not residue, so it
   * contributes nothing here.
   */
  public List<Finding> openFindingsAfterPass(String specId) {
    return latestReviewForSpec(specId)
        .filter(review -> "passed".equals(review.status()))
        .map(review -> openFindingsForReview(review.id()))
        .orElse(List.of());
  }

  private ReviewRow mapReview(Sqlite.Row row) {
    return new ReviewRow(
        row.text(0),
        row.text(1),
        (int) row.integer(2),
        row.text(3),
        row.text(4),
        row.text(5),
        row.text(6),
        row.text(7),
        row.text(8));
  }

  private StageRow mapStage(Sqlite.Row row) {
    return new StageRow(
        row.text(0),
        row.text(1),
        row.text(2),
        row.text(3),
        row.text(4),
        row.text(5),
        row.text(6),
        row.text(7),
        row.text(8));
  }

  private Finding mapFinding(Sqlite.Row row) {
    return new Finding(
        row.text(0),
        Finding.Severity.parse(row.text(1)),
        Finding.Category.parse(row.text(2)),
        row.text(3),
        (int) row.integer(4),
        (int) row.integer(5),
        row.text(6),
        row.text(7),
        row.text(8),
        new Finding.Suggestion(row.text(9), row.text(10), row.text(11)),
        row.isNull(12) ? 0.0 : Double.parseDouble(row.text(12)),
        Finding.Resolution.valueOf(row.text(13)));
  }
}
