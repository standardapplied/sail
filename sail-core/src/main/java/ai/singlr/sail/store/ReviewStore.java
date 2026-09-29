/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.authority.ReviewAuthority;
import ai.singlr.sail.authority.WriteAuthority;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Review, stage, and finding CRUD on SQLite. Each review belongs to a spec and tracks one pass
 * through the review pipeline. Stages run sequentially; findings belong to stages.
 *
 * <p>The review is a <em>synced aggregate</em>: its snapshot carries the review row plus its stages
 * and each stage's finding counts by severity, so main can narrate the review loop (started, stage
 * passed/failed with counts, iteration, escalation) from replicated state — full findings stay on
 * the executing node, which is the only writer. Every mutation to the review or its children
 * journals a new revision of the whole aggregate within the same transaction, exactly as {@link
 * RunStore} does for a flat run row. Single-writer, so reconciliation is conflict-free in practice.
 */
public final class ReviewStore implements ConflictResolver, SyncedStore {

  private static final String ENTITY = "review";
  private static final Set<String> SURROGATE_FIELDS = Set.of("id");

  private final Sqlite db;
  private final ChangeLog changeLog;
  private final RevisionJournal revisions;

  public ReviewStore(Sqlite db) {
    this.db = db;
    this.changeLog = new ChangeLog(db);
    this.revisions = new RevisionJournal(db, changeLog, new ReviewSchema());
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
    db.transaction(
        () -> {
          db.execute(
              "INSERT INTO reviews (id, spec_id, iteration, status, created_at) VALUES (?, ?, ?, 'pending', ?)",
              id,
              specId,
              iteration,
              DateTimeUtils.now().toString());
          journal(id);
        });
    return id;
  }

  /**
   * A running review this box executes is live here: the pipeline writes its stages and findings
   * through its row, so main's version never rewrites or removes it mid-run. This box executes it
   * while the run of the same id is live on the box whose FDE handle is {@code handle}, or — before
   * that run is recorded — when its first revision began here. Another box's review is never live
   * here. Once it finishes, main's version settles it like any other.
   */
  @Override
  public boolean live(String id, String handle) {
    return findReview(id).filter(review -> "running".equals(review.status())).isPresent()
        && (new RunStore(db).live(id, handle) || begunHere(id));
  }

  private boolean begunHere(String id) {
    return db.queryOne(
            """
            SELECT peer IS NULL FROM change_log WHERE entity_type = 'review' AND entity_id = ?
            ORDER BY seq LIMIT 1""",
            row -> row.integer(0) == 1,
            id)
        .orElse(false);
  }

  /** Who may write a review on this box: the rule every door and main's commit decide by. */
  @Override
  public ReviewAuthority authority() {
    return new ReviewAuthority(db);
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
    db.transaction(
        () -> {
          db.execute(
              "UPDATE reviews SET status = 'passed', completed_at = ?, decided_by = ? WHERE id = ?",
              DateTimeUtils.now().toString(),
              decidedBy,
              reviewId);
          journal(reviewId);
        });
  }

  /**
   * Closes every prior-attempt review for a spec by marking it {@code superseded}, returning how
   * many changed. Called at dispatch time, so review iterations count per dispatch attempt: the
   * pipeline starts a superseded spec back at iteration 1 instead of inheriting (and eventually
   * exhausting) the lifetime count, which would otherwise silently escalate every re-dispatch once
   * {@code max_iterations} had ever been reached.
   */
  public int supersedeForSpec(String specId) {
    return db.transaction(
        () -> {
          var affected =
              db.query(
                  "SELECT id FROM reviews WHERE spec_id = ? AND superseded_at IS NULL",
                  row -> row.text(0),
                  specId);
          db.execute(
              "UPDATE reviews SET superseded_at = ? WHERE spec_id = ? AND superseded_at IS NULL",
              DateTimeUtils.now().toString(),
              specId);
          affected.forEach(this::journal);
          return affected.size();
        });
  }

  /**
   * Marks every {@code running} review this box executed {@code failed}, returning how many were
   * swept. A review's execution lives only in the controller's memory, so after a server restart a
   * {@code running} row this box ran is an orphan of an interrupted run; left in place it silently
   * blocks every future review for its spec (the pipeline skips a spec whose latest review is
   * running). A review another box runs is that box's to finish, so it is left alone: this box ran
   * a review whose run was stamped with its handle, {@code localHandle}, or — before the review's
   * run was recorded — one its own first revision began here. Called once at server start, before
   * missed stops are replayed.
   */
  public int failOrphanedRunning(String localHandle) {
    return db.transaction(
        () -> {
          var affected =
              db.query(
                  """
                  SELECT r.id FROM reviews r WHERE r.status = 'running'
                  AND CASE WHEN EXISTS (SELECT 1 FROM runs WHERE id = r.id)
                      THEN EXISTS (SELECT 1 FROM runs WHERE id = r.id AND IFNULL(node, '') = ?)
                      ELSE (SELECT peer IS NULL FROM change_log
                          WHERE entity_type = 'review' AND entity_id = r.id
                          ORDER BY seq LIMIT 1) END""",
                  row -> row.text(0),
                  Objects.toString(localHandle, ""));
          affected.forEach(
              id -> {
                db.execute(
                    "UPDATE reviews SET status = 'failed', completed_at = ? WHERE id = ?",
                    DateTimeUtils.now().toString(),
                    id);
                journal(id);
              });
          return affected.size();
        });
  }

  /**
   * Marks the review failed by infrastructure error, not verdict; retried without burning an
   * iteration.
   */
  public void failReviewWithError(String reviewId, String error) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE reviews SET status = 'failed', error = ?, completed_at = ? WHERE id = ?",
              error,
              DateTimeUtils.now().toString(),
              reviewId);
          journal(reviewId);
        });
  }

  public void updateReviewStatus(String reviewId, String status) {
    var completedAt =
        "passed".equals(status) || "failed".equals(status) || "escalated".equals(status)
            ? DateTimeUtils.now().toString()
            : null;
    db.transaction(
        () -> {
          db.execute(
              "UPDATE reviews SET status = ?, completed_at = COALESCE(?, completed_at) WHERE id = ?",
              status,
              completedAt,
              reviewId);
          journal(reviewId);
        });
  }

  public String createStage(String reviewId, String name, String stageType) {
    var id = DateTimeUtils.newId().toString();
    db.transaction(
        () -> {
          db.execute(
              "INSERT INTO review_stages (id, review_id, name, stage_type, status) VALUES (?, ?, ?, ?, 'pending')",
              id,
              reviewId,
              name,
              stageType);
          journal(reviewId);
        });
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
    db.transaction(
        () -> {
          db.execute(
              "UPDATE review_stages SET status = 'running', reviewer = ?, started_at = ? WHERE id = ?",
              reviewer,
              DateTimeUtils.now().toString(),
              stageId);
          journalForStage(stageId);
        });
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
    db.transaction(
        () -> {
          db.execute(
              "UPDATE review_stages SET status = ?, completed_at = ?, error = ? WHERE id = ?",
              status,
              DateTimeUtils.now().toString(),
              error,
              stageId);
          journalForStage(stageId);
        });
  }

  public void addFinding(String stageId, Finding finding) {
    db.transaction(
        () -> {
          db.execute(
              """
              INSERT INTO review_findings (id, stage_id, severity, category, file,
                  line_start, line_end, title, description, evidence,
                  suggestion_before, suggestion_after, suggestion_rationale,
                  confidence, resolution, resolution_evidence, carried_from, carry_evidence)
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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
              finding.resolution().name(),
              finding.resolutionEvidence(),
              finding.carriedFrom(),
              finding.carryEvidence());
          journalForStage(stageId);
        });
  }

  /**
   * Re-attaches a still-open finding from the previous review to the given stage as a fresh row
   * whose {@code carried_from} points at its predecessor, storing the carrying ruling's evidence
   * with it, and returns the new row. The predecessor stays {@code OPEN} where it is — history is
   * never rewritten; the chain is the identity. Each re-carry stores the latest ruling's evidence —
   * the newest explanation is the actionable one; the chain walk preserves the older ones. A blank
   * ruling preserves the predecessor's evidence instead: fail-closed reconciliation synthesizes
   * blank-evidence {@code still_open} rulings for omitted or unsupported verdicts, and defaulting
   * must never erase the last actionable reproduction target.
   */
  public Finding carryForward(String stageId, Finding predecessor, String evidence) {
    var carried =
        predecessor.carriedCopy(
            Strings.isNotBlank(evidence) ? evidence : predecessor.carryEvidence());
    addFinding(stageId, carried);
    return carried;
  }

  /** One effective ruling on a carried finding: {@code OPEN} carries it forward, else resolves. */
  public record StageRuling(Finding finding, Finding.Resolution resolution, String evidence) {}

  /**
   * Applies a reviewer's complete stage result as one atomic write: every ruling on the carried
   * findings (resolving {@code FIXED}/{@code DISPUTED} predecessors, re-attaching {@code OPEN}
   * ones) and every newly discovered finding. Any failure — a duplicate finding id, a constraint
   * violation, a journaling error — rolls the whole result back, so a partially committed verdict
   * can never retire a carried finding on behalf of a stage that subsequently errors: the retry
   * still sees it {@code OPEN} and carries it.
   */
  public void applyStageResult(String stageId, List<StageRuling> rulings, List<Finding> findings) {
    db.transaction(
        () -> {
          for (var ruling : rulings) {
            if (ruling.resolution() == Finding.Resolution.OPEN) {
              carryForward(stageId, ruling.finding(), ruling.evidence());
            } else {
              resolveFinding(ruling.finding().id(), ruling.resolution(), ruling.evidence());
            }
          }
          findings.forEach(finding -> addFinding(stageId, finding));
        });
  }

  private static final String FINDING_COLUMNS =
      "f.id, f.severity, f.category, f.file, f.line_start, f.line_end,"
          + " f.title, f.description, f.evidence, f.suggestion_before,"
          + " f.suggestion_after, f.suggestion_rationale, f.confidence, f.resolution,"
          + " f.resolution_evidence, f.carried_from, f.carry_evidence";

  private static final String SEVERITY_ORDER =
      "CASE f.severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1"
          + " WHEN 'MEDIUM' THEN 2 ELSE 3 END";

  public List<Finding> findingsForStage(String stageId) {
    return db.query(
        """
        SELECT %s FROM review_findings f
        WHERE f.stage_id = ?
        ORDER BY %s"""
            .formatted(FINDING_COLUMNS, SEVERITY_ORDER),
        this::mapFinding,
        stageId);
  }

  public List<Finding> findingsForReview(String reviewId) {
    return db.query(
        """
        SELECT %s FROM review_findings f
        JOIN review_stages s ON s.id = f.stage_id
        WHERE s.review_id = ?
        ORDER BY %s"""
            .formatted(FINDING_COLUMNS, SEVERITY_ORDER),
        this::mapFinding,
        reviewId);
  }

  public List<Finding> openFindingsForReview(String reviewId) {
    return db.query(
        """
        SELECT %s FROM review_findings f
        JOIN review_stages s ON s.id = f.stage_id
        WHERE s.review_id = ? AND f.resolution = 'OPEN'
        ORDER BY %s"""
            .formatted(FINDING_COLUMNS, SEVERITY_ORDER),
        this::mapFinding,
        reviewId);
  }

  /**
   * The findings the same-named stage of the next review must rule on: the open findings that
   * {@code stageName} emitted in the current dispatch attempt's latest failed review <em>in which
   * that stage actually executed</em>, excluding any already re-attached to that stage of {@code
   * currentReviewId}. Validity is per stage, not per review: a stage that errored — or never ran
   * because an earlier stage failed first — holds no verdict and is skipped, but a stage that
   * completed cleanly before a <em>later</em> stage errored the review still carries its findings;
   * skipping the whole review would silently drop them from the eventual passed review. Scoping by
   * stage keeps every finding facing the categories and gate of the stage that raised it — a HIGH
   * from a strict later stage can never be laundered through an earlier stage's looser gate.
   */
  public List<Finding> carryForwardFindings(
      String specId, String currentReviewId, String stageName) {
    return db.query(
        """
        SELECT %s FROM review_findings f
        JOIN review_stages s ON s.id = f.stage_id
        WHERE s.review_id = (
            SELECT r.id FROM reviews r
            JOIN review_stages prior ON prior.review_id = r.id
            WHERE r.spec_id = ? AND r.superseded_at IS NULL AND r.status = 'failed'
                AND r.id != ?
                AND prior.name = ? AND prior.status IN ('passed', 'failed')
                AND prior.error IS NULL
            ORDER BY r.created_at DESC, r.rowid DESC LIMIT 1)
        AND s.name = ?
        AND f.resolution = 'OPEN'
        AND f.id NOT IN (
            SELECT f2.carried_from FROM review_findings f2
            JOIN review_stages s2 ON s2.id = f2.stage_id
            WHERE s2.review_id = ? AND s2.name = ? AND f2.carried_from IS NOT NULL)
        ORDER BY %s"""
            .formatted(FINDING_COLUMNS, SEVERITY_ORDER),
        this::mapFinding,
        specId,
        currentReviewId,
        stageName,
        stageName,
        currentReviewId,
        stageName);
  }

  /**
   * Every finding of the current dispatch attempt a reviewer ruled {@code DISPUTED}, newest ruling
   * first. Disputed findings are excluded from the gate, so the room verdict lists them for the
   * human — an argument retires a finding only in the open.
   */
  public List<Finding> disputedFindings(String specId) {
    return db.query(
        """
        SELECT %s FROM review_findings f
        JOIN review_stages s ON s.id = f.stage_id
        JOIN reviews r ON r.id = s.review_id
        WHERE r.spec_id = ? AND r.superseded_at IS NULL AND f.resolution = 'DISPUTED'
        ORDER BY r.created_at DESC, %s"""
            .formatted(FINDING_COLUMNS, SEVERITY_ORDER),
        this::mapFinding,
        specId);
  }

  /**
   * How many fix iterations the finding has survived: the number of {@code carried_from} hops back
   * to its first sighting. A cycle (impossible by construction, since every carried row is fresh)
   * terminates the walk instead of hanging it.
   */
  public int findingAge(String findingId) {
    var visited = new LinkedHashSet<String>();
    var current = findingId;
    while (visited.add(current)) {
      var parent =
          db.queryOne(
                  "SELECT COALESCE(carried_from, '') FROM review_findings WHERE id = ?",
                  row -> row.text(0),
                  current)
              .orElse("");
      if (parent.isBlank()) {
        return visited.size() - 1;
      }
      current = parent;
    }
    return visited.size() - 1;
  }

  /** The finding's full lineage, newest first — the chain a persistent finding ages along. */
  public List<Finding> findingChain(String findingId) {
    var chain = new java.util.ArrayList<Finding>();
    var visited = new LinkedHashSet<String>();
    var current = findingId;
    while (current != null && visited.add(current)) {
      var finding = findFinding(current).orElse(null);
      if (finding == null) {
        break;
      }
      chain.add(finding);
      current = finding.carriedFrom();
    }
    return List.copyOf(chain);
  }

  public Optional<Finding> findFinding(String findingId) {
    return db.queryOne(
        "SELECT " + FINDING_COLUMNS + " FROM review_findings f WHERE f.id = ?",
        this::mapFinding,
        findingId);
  }

  public void resolveFinding(String findingId, Finding.Resolution resolution) {
    resolveFinding(findingId, resolution, null);
  }

  /**
   * Resolves a finding and records the evidence the resolution rests on — the reviewer's proof of
   * the fix, or the ruled argument that retired a disputed finding.
   */
  public void resolveFinding(String findingId, Finding.Resolution resolution, String evidence) {
    db.transaction(
        () -> {
          db.execute(
              "UPDATE review_findings SET resolution = ?, resolution_evidence = ? WHERE id = ?",
              resolution.name(),
              evidence,
              findingId);
          var reviewId =
              db.queryOne(
                  """
                  SELECT s.review_id FROM review_stages s
                  JOIN review_findings f ON f.stage_id = s.id WHERE f.id = ?""",
                  row -> row.text(0),
                  findingId);
          reviewId.ifPresent(this::journal);
        });
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
    return db.transaction(
        () -> {
          var reviewIds =
              db.query(
                  """
                  SELECT DISTINCT s.review_id FROM review_findings f
                  JOIN review_stages s ON s.id = f.stage_id
                  WHERE f.resolution = 'OPEN'
                  AND f.id IN (SELECT finding_id FROM spec_source_findings WHERE spec_id = ?)""",
                  row -> row.text(0),
                  specId);
          db.execute(
              """
              UPDATE review_findings SET resolution = 'FIXED'
              WHERE resolution = 'OPEN'
              AND id IN (SELECT finding_id FROM spec_source_findings WHERE spec_id = ?)""",
              specId);
          var changed = db.changes();
          reviewIds.forEach(this::journal);
          return changed;
        });
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

  /**
   * Closes the residue a spec shipped with — the {@link #openFindingsAfterPass} set — as {@link
   * Finding.Resolution#SHIPPED}, returning how many changed. Called on the {@code → done}
   * transition so a completed spec leaves no finding OPEN. A spec whose latest review did not pass
   * has no residue and is left untouched (its findings are in-flight work, not shipped).
   */
  public int resolveShippedFindings(String specId) {
    var residue = openFindingsAfterPass(specId);
    for (var finding : residue) {
      resolveFinding(finding.id(), Finding.Resolution.SHIPPED, "shipped below the review gate");
    }
    return residue.size();
  }

  // ---- Sync: the review as a replicated aggregate (review + stages + finding counts) ----

  /**
   * Journals a fresh revision of the whole aggregate for a local mutation, within its transaction.
   */
  private void journal(String reviewId) {
    revisions.recordRevision(reviewId, "local", false);
  }

  /** Backfills a revision for the one-time legacy data migration; delegates to the journal. */
  String recordRevision(String id, String origin) {
    return revisions.recordRevision(id, origin, false);
  }

  /** Journals the aggregate a stage belongs to — a stage or finding change is a review revision. */
  private void journalForStage(String stageId) {
    db.queryOne("SELECT review_id FROM review_stages WHERE id = ?", row -> row.text(0), stageId)
        .ifPresent(this::journal);
  }

  public Set<String> syncEntityIds() {
    return revisions.entityIds();
  }

  public Set<String> dirtyIds() {
    return revisions.dirtyIds();
  }

  public String latestRev(String id) {
    return revisions.latestRev(id);
  }

  @Override
  public String entityType() {
    return ENTITY;
  }

  public Map<String, Object> comparableSnapshot(String id) {
    return revisions.comparableSnapshot(id);
  }

  public Map<String, Object> comparableAtRev(String id, String rev) {
    return revisions.comparableAtRev(id, rev);
  }

  public String baseRevOf(String id) {
    return revisions.baseRevOf(id);
  }

  /**
   * Adopts main's authoritative aggregate at its exact rev as the new synced ancestor. When the
   * adopted content already matches the local aggregate — the normal case on the executing node
   * after its own successful push — only the revision is linked. Adopting a version that differs,
   * after a denial or on any pull, never deletes the finding rows this box holds (which never
   * replicate), which carry-forward and dispute resolution read.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    revisions.applyRevision(id, snapshot, rev);
  }

  @Override
  public void eraseRow(String id) {
    revisions.eraseRow(id);
  }

  /** Compare-and-set commit as main: accepts only if {@code expectedRev} still matches. */
  public PushOutcome commitRevision(
      String id, Map<String, Object> snapshot, String expectedRev, WriteAuthority authority) {
    return revisions.commitRevision(id, snapshot, expectedRev, authority);
  }

  @Override
  public String resolveConflict(String id, Map<String, Object> chosen, Map<String, Object> remote) {
    return revisions.resolveConflict(id, chosen, remote);
  }

  /**
   * The full aggregate snapshot: the review row, its stages in order, and each stage's finding
   * counts by severity. Null when the review is absent. Finding counts (not the finding rows) are
   * what main narrates; the rows stay on the executing node.
   */
  private Map<String, Object> aggregateMap(String id) {
    var review = findReview(id).orElse(null);
    if (review == null) {
      return null;
    }
    var map = new LinkedHashMap<String, Object>();
    map.put("id", review.id());
    map.put("spec_id", review.specId());
    map.put("iteration", review.iteration());
    map.put("status", review.status());
    map.put("created_at", review.createdAt());
    map.put("completed_at", review.completedAt());
    map.put("decided_by", review.decidedBy());
    map.put("superseded_at", review.supersededAt());
    map.put("error", review.error());
    var stages = new java.util.ArrayList<Map<String, Object>>();
    for (var stage : stagesForReview(id)) {
      var s = new LinkedHashMap<String, Object>();
      s.put("id", stage.id());
      s.put("name", stage.name());
      s.put("stage_type", stage.stageType());
      s.put("status", stage.status());
      s.put("reviewer", stage.reviewer());
      s.put("started_at", stage.startedAt());
      s.put("completed_at", stage.completedAt());
      s.put("error", stage.error());
      s.put("finding_counts", new LinkedHashMap<String, Object>(findingCountsForStage(stage.id())));
      stages.add(s);
    }
    map.put("stages", stages);
    return map;
  }

  /**
   * A stage's finding counts by severity for narration: read from the synced {@code finding_counts}
   * column on a box that holds the review via sync (its finding rows never replicated), else
   * counted live from {@code review_findings} on the executing node. Empty when the stage has no
   * findings.
   */
  public Map<String, Integer> findingCountsForStage(String stageId) {
    var stored =
        db.queryOne(
                "SELECT COALESCE(finding_counts, '') FROM review_stages WHERE id = ?",
                row -> row.text(0),
                stageId)
            .orElse("");
    var counts = new LinkedHashMap<String, Integer>();
    if (!stored.isBlank()) {
      YamlUtil.parseMap(stored).forEach((k, v) -> counts.put(k, ((Number) v).intValue()));
      return counts;
    }
    findingCounts(stageId).forEach((k, v) -> counts.put(k, ((Number) v).intValue()));
    return counts;
  }

  /** Counts of a stage's findings by severity name, e.g. {@code {"HIGH": 2, "MEDIUM": 1}}. */
  private Map<String, Object> findingCounts(String stageId) {
    var counts = new LinkedHashMap<String, Object>();
    for (var row :
        db.query(
            "SELECT severity, COUNT(*) FROM review_findings WHERE stage_id = ? GROUP BY severity",
            row -> Map.entry(row.text(0), (int) row.integer(1)),
            stageId)) {
      counts.put(row.getKey(), row.getValue());
    }
    return counts;
  }

  /**
   * Writes an aggregate snapshot: the review row and its stages (with finding counts). Finding rows
   * never replicate, so they are never deleted here: a stage this box holds findings in is kept
   * even where the snapshot drops it, and its counts stay the ones its findings give.
   */
  @SuppressWarnings("unchecked")
  private void writeAggregate(String id, Map<String, Object> snapshot) {
    db.execute(
        """
        INSERT INTO reviews (id, spec_id, iteration, status, created_at, completed_at,
            decided_by, superseded_at, error)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET spec_id = excluded.spec_id,
            iteration = excluded.iteration, status = excluded.status,
            created_at = excluded.created_at, completed_at = excluded.completed_at,
            decided_by = excluded.decided_by, superseded_at = excluded.superseded_at,
            error = excluded.error""",
        id,
        Snapshots.text(snapshot, "spec_id"),
        Snapshots.integer(snapshot, "iteration"),
        Snapshots.text(snapshot, "status"),
        Snapshots.text(snapshot, "created_at"),
        Snapshots.text(snapshot, "completed_at"),
        Snapshots.text(snapshot, "decided_by"),
        Snapshots.text(snapshot, "superseded_at"),
        Snapshots.text(snapshot, "error"));
    var stages =
        Objects.requireNonNullElse(
            (List<Map<String, Object>>) snapshot.get("stages"), List.<Map<String, Object>>of());
    for (var stage : stages) {
      var counts = stage.get("finding_counts");
      db.execute(
          """
          INSERT INTO review_stages (id, review_id, name, stage_type, status, reviewer,
              started_at, completed_at, error, finding_counts)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(id) DO UPDATE SET review_id = excluded.review_id, name = excluded.name,
              stage_type = excluded.stage_type, status = excluded.status,
              reviewer = excluded.reviewer, started_at = excluded.started_at,
              completed_at = excluded.completed_at, error = excluded.error,
              finding_counts = CASE WHEN EXISTS (SELECT 1 FROM review_findings f
                  WHERE f.stage_id = review_stages.id) THEN NULL
                  ELSE excluded.finding_counts END""",
          Snapshots.text(stage, "id"),
          id,
          Snapshots.text(stage, "name"),
          Snapshots.text(stage, "stage_type"),
          Snapshots.text(stage, "status"),
          Snapshots.text(stage, "reviewer"),
          Snapshots.text(stage, "started_at"),
          Snapshots.text(stage, "completed_at"),
          Snapshots.text(stage, "error"),
          counts == null ? null : YamlUtil.dumpJson((Map<String, Object>) counts));
    }
    db.execute(
        """
        DELETE FROM review_stages WHERE review_id = ?
        AND id NOT IN (SELECT value FROM json_each(?))
        AND NOT EXISTS (SELECT 1 FROM review_findings f WHERE f.stage_id = review_stages.id)""",
        id,
        YamlUtil.dumpJson(stages.stream().map(stage -> Snapshots.text(stage, "id")).toList()));
  }

  private void deleteAggregate(String id) {
    db.execute(
        "DELETE FROM review_findings WHERE stage_id IN (SELECT id FROM review_stages WHERE review_id = ?)",
        id);
    db.execute("DELETE FROM review_stages WHERE review_id = ?", id);
    db.execute("DELETE FROM reviews WHERE id = ?", id);
  }

  private static Map<String, Object> comparable(Map<String, Object> full) {
    if (full == null) {
      return null;
    }
    var m = new LinkedHashMap<String, Object>();
    for (var field : full.keySet()) {
      if (!SURROGATE_FIELDS.contains(field) && !field.startsWith("_")) {
        m.put(field, full.get(field));
      }
    }
    return m;
  }

  private static boolean sameContent(Map<String, Object> a, Map<String, Object> b) {
    return Objects.equals(comparable(a), comparable(b));
  }

  private String specIdOf(String reviewId) {
    return findReview(reviewId).map(ReviewRow::specId).orElse(null);
  }

  /** The review's store-specific half of the shared {@link RevisionJournal} sync protocol. */
  private final class ReviewSchema implements EntitySchema {

    @Override
    public String entityType() {
      return ENTITY;
    }

    @Override
    public String table() {
      return "reviews";
    }

    @Override
    public boolean exists(String id) {
      return findReview(id).isPresent();
    }

    @Override
    public Map<String, Object> snapshotMap(String id) {
      return aggregateMap(id);
    }

    /** Adopts main's aggregate, but only when it actually differs ({@link #writeAggregate}). */
    @Override
    public void apply(String id, Map<String, Object> snapshot) {
      if (!sameContent(aggregateMap(id), snapshot)) {
        writeAggregate(id, snapshot);
      }
    }

    @Override
    public Map<String, Object> comparable(Map<String, Object> full) {
      return ReviewStore.comparable(full);
    }

    @Override
    public void deleteRow(String id) {
      deleteAggregate(id);
    }
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
        Finding.Resolution.valueOf(row.text(13)),
        row.text(14),
        row.text(15),
        row.text(16));
  }
}
