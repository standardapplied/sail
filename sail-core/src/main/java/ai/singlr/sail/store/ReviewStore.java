/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Review, stage, and finding CRUD on SQLite. Each review belongs to a spec and tracks one pass
 * through the review pipeline. Stages run sequentially; findings belong to stages.
 */
public final class ReviewStore {

  private final Sqlite db;

  public ReviewStore(Sqlite db) {
    this.db = db;
  }

  public record ReviewRow(
      String id,
      String specId,
      int iteration,
      String status,
      String createdAt,
      String completedAt) {}

  public record StageRow(
      String id,
      String reviewId,
      String name,
      String stageType,
      String status,
      String reviewer,
      String startedAt,
      String completedAt) {}

  public String createReview(String specId, int iteration) {
    var id = UUID.randomUUID().toString();
    db.execute(
        "INSERT INTO reviews (id, spec_id, iteration, status, created_at) VALUES (?, ?, ?, 'pending', ?)",
        id,
        specId,
        iteration,
        Instant.now().toString());
    return id;
  }

  public Optional<ReviewRow> findReview(String reviewId) {
    return db.queryOne(
        "SELECT id, spec_id, iteration, status, created_at, completed_at FROM reviews WHERE id = ?",
        this::mapReview,
        reviewId);
  }

  public Optional<ReviewRow> latestReviewForSpec(String specId) {
    return db.queryOne(
        """
        SELECT id, spec_id, iteration, status, created_at, completed_at
        FROM reviews WHERE spec_id = ? ORDER BY iteration DESC LIMIT 1""",
        this::mapReview,
        specId);
  }

  public List<ReviewRow> reviewsForSpec(String specId) {
    return db.query(
        """
        SELECT id, spec_id, iteration, status, created_at, completed_at
        FROM reviews WHERE spec_id = ? ORDER BY iteration ASC""",
        this::mapReview,
        specId);
  }

  public void updateReviewStatus(String reviewId, String status) {
    var completedAt =
        "passed".equals(status) || "failed".equals(status) || "escalated".equals(status)
            ? Instant.now().toString()
            : null;
    db.execute(
        "UPDATE reviews SET status = ?, completed_at = COALESCE(?, completed_at) WHERE id = ?",
        status,
        completedAt,
        reviewId);
  }

  public String createStage(String reviewId, String name, String stageType) {
    var id = UUID.randomUUID().toString();
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
        SELECT id, review_id, name, stage_type, status, reviewer, started_at, completed_at
        FROM review_stages WHERE id = ?""",
        this::mapStage,
        stageId);
  }

  public List<StageRow> stagesForReview(String reviewId) {
    return db.query(
        """
        SELECT id, review_id, name, stage_type, status, reviewer, started_at, completed_at
        FROM review_stages WHERE review_id = ? ORDER BY rowid ASC""",
        this::mapStage,
        reviewId);
  }

  public void startStage(String stageId, String reviewer) {
    db.execute(
        "UPDATE review_stages SET status = 'running', reviewer = ?, started_at = ? WHERE id = ?",
        reviewer,
        Instant.now().toString(),
        stageId);
  }

  public void completeStage(String stageId, String status) {
    db.execute(
        "UPDATE review_stages SET status = ?, completed_at = ? WHERE id = ?",
        status,
        Instant.now().toString(),
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

  private ReviewRow mapReview(Sqlite.Row row) {
    return new ReviewRow(
        row.text(0), row.text(1), (int) row.integer(2), row.text(3), row.text(4), row.text(5));
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
        row.text(7));
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
