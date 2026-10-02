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
import java.util.ArrayList;
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
 * <p>The review is a <em>synced aggregate</em>: its snapshot carries the review row, its stages,
 * and its findings as content — every stage's findings, canonically serialized, hashed into the
 * snapshot as {@code findings_hash} with the bytes in the {@link BlobStore}, exactly as a spec's
 * body rides — so every box, main and Mast hold the same findings. {@code review_findings} is a
 * projection of that content: it is written only by applying a review revision, from the content,
 * on every box, and every finding query reads it. A finding names the follow-up spec drafted from
 * it, and one whose follow-up is done reads as {@code FIXED} wherever it is read, so shipping a
 * follow-up never writes the source review. Every mutation to the review or its children journals a
 * new revision of the whole aggregate within the same transaction, exactly as {@link RunStore} does
 * for a flat run row. Single-writer, so reconciliation is conflict-free in practice.
 */
public final class ReviewStore implements ConflictResolver, SyncedStore {

  private static final String ENTITY = "review";
  private static final String FINDINGS_HASH = ReviewFindingsContent.HASH_FIELD;
  private static final Set<String> SURROGATE_FIELDS = Set.of("id");

  private final Sqlite db;
  private final ChangeLog changeLog;
  private final RevisionJournal revisions;
  private final ReviewSchema schema;
  private final ReviewFindingsContent content;

  public ReviewStore(Sqlite db) {
    this.db = db;
    this.changeLog = new ChangeLog(db);
    this.schema = new ReviewSchema();
    this.revisions = new RevisionJournal(db, changeLog, schema);
    this.content = new ReviewFindingsContent(db, new BlobStore(db));
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
   * while the run of the same id is live on the box whose FDE handle is {@code handle}, or — only
   * before that run is recorded — when its first revision began here. A review whose run has
   * finished is not live, even while it waits for a person's approval, so main's approval reaches
   * it. Another box's review is never live here. Once it finishes, main's version settles it like
   * any other.
   */
  @Override
  public boolean live(String id, String handle) {
    if (findReview(id).filter(review -> "running".equals(review.status())).isEmpty()) {
      return false;
    }
    return new RunStore(db)
        .findById(id)
        .map(run -> run.liveOn(handle))
        .orElseGet(() -> changeLog.begunHere(ENTITY, id));
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
          journal(reviewOf(stageId));
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
          journal(reviewOf(stageId));
        });
  }

  /** Adds a finding to a stage: one revision of its review, carrying the finding in its content. */
  public void addFinding(String stageId, Finding finding) {
    db.transaction(
        () -> append(reviewOf(stageId), List.of(ReviewFindingsContent.of(stageId, finding))));
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
    var carried = carriedCopy(predecessor, evidence);
    addFinding(stageId, carried);
    return carried;
  }

  private static Finding carriedCopy(Finding predecessor, String evidence) {
    return predecessor.carriedCopy(
        Strings.isNotBlank(evidence) ? evidence : predecessor.carryEvidence());
  }

  /** One effective ruling on a carried finding: {@code OPEN} carries it forward, else resolves. */
  public record StageRuling(Finding finding, Finding.Resolution resolution, String evidence) {}

  /**
   * Applies a reviewer's complete stage result as one atomic write: every ruling on the carried
   * findings (resolving {@code FIXED}/{@code DISPUTED} predecessors, re-attaching {@code OPEN}
   * ones) and every newly discovered finding — one revision of this review, and one of each earlier
   * review whose findings were resolved. A ruling rests on the finding as the reviewer saw it when
   * the stage began: a predecessor someone resolved since — a human dismissing it on main while the
   * stage ran — keeps that resolution and is neither carried nor resolved again. Any failure — a
   * duplicate finding id, a constraint violation, a journaling error — rolls the whole result back,
   * so a partially committed verdict can never retire a carried finding on behalf of a stage that
   * subsequently errors: the retry still sees it {@code OPEN} and carries it. Returns the ids of
   * the findings whose rulings were left aside, already resolved.
   */
  public List<String> applyStageResult(
      String stageId, List<StageRuling> rulings, List<Finding> findings) {
    return db.transaction(
        () -> {
          var reviewId = reviewOf(stageId);
          var resolved = new LinkedHashMap<String, Map<String, Object>>();
          var added = new ArrayList<Map<String, Object>>();
          var alreadyResolved = new ArrayList<String>();
          for (var ruling : rulings) {
            if (!stillOpen(ruling.finding().id())) {
              alreadyResolved.add(ruling.finding().id());
              continue;
            }
            if (ruling.resolution() == Finding.Resolution.OPEN) {
              added.add(
                  ReviewFindingsContent.of(
                      stageId, carriedCopy(ruling.finding(), ruling.evidence())));
            } else {
              resolved.put(
                  ruling.finding().id(), resolutionOf(ruling.resolution(), ruling.evidence()));
            }
          }
          findings.forEach(finding -> added.add(ReviewFindingsContent.of(stageId, finding)));
          amend(resolved);
          append(reviewId, added);
          return List.copyOf(alreadyResolved);
        });
  }

  private boolean stillOpen(String findingId) {
    return db.queryOne(
            "SELECT resolution FROM review_findings WHERE id = ?", row -> row.text(0), findingId)
        .map(Finding.Resolution.OPEN.name()::equals)
        .orElse(false);
  }

  private static final String FINDING_COLUMNS =
      "f.id, f.severity, f.category, f.file, f.line_start, f.line_end,"
          + " f.title, f.description, f.evidence, f.suggestion_before,"
          + " f.suggestion_after, f.suggestion_rationale, f.confidence, f.resolution,"
          + " f.resolution_evidence, f.carried_from, f.carry_evidence";

  private static final String OPEN = "f.resolution = 'OPEN'";

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
        WHERE s.review_id = ? AND %s
        ORDER BY %s"""
            .formatted(FINDING_COLUMNS, OPEN, SEVERITY_ORDER),
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
        AND %s
        AND f.id NOT IN (
            SELECT f2.carried_from FROM review_findings f2
            JOIN review_stages s2 ON s2.id = f2.stage_id
            WHERE s2.review_id = ? AND s2.name = ? AND f2.carried_from IS NOT NULL)
        ORDER BY %s"""
            .formatted(FINDING_COLUMNS, OPEN, SEVERITY_ORDER),
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
    var chain = new ArrayList<Finding>();
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
   * the fix, or the ruled argument that retired a disputed finding: one revision of its review. A
   * finding this box does not hold, or one already so resolved, changes nothing.
   */
  public void resolveFinding(String findingId, Finding.Resolution resolution, String evidence) {
    db.transaction(() -> amend(Map.of(findingId, resolutionOf(resolution, evidence))));
  }

  private static Map<String, Object> resolutionOf(Finding.Resolution resolution, String evidence) {
    var changes = new LinkedHashMap<String, Object>();
    changes.put("resolution", resolution.name());
    changes.put("resolution_evidence", evidence);
    return changes;
  }

  /**
   * Names the follow-up spec drafted from each finding, in its review's content, so completing the
   * spec resolves exactly those findings ({@link #resolveFindingsOfShippedFollowUps}) on every box.
   * A finding names one follow-up, the latest drafted from it. Idempotent: a finding already naming
   * the spec changes nothing, and a review none of whose findings change gets no revision.
   */
  public void linkSourceFindings(String specId, List<String> findingIds) {
    var links = new LinkedHashMap<String, Map<String, Object>>();
    for (var findingId : findingIds) {
      links.put(findingId, Map.of("followup", specId));
    }
    db.transaction(() -> amend(links));
  }

  /** The finding ids a follow-up spec was drafted from, or empty for a regular spec. */
  public List<String> sourceFindingIds(String specId) {
    return db.query(
        "SELECT id FROM review_findings WHERE followup = ? ORDER BY rowid ASC",
        row -> row.text(0),
        specId);
  }

  /**
   * Marks every still-open finding whose follow-up spec is {@code done} {@code FIXED}, in its
   * review's content — one revision per source review — returning how many changed. Run when a
   * follow-up reaches {@code done}, and again on any later occasion, since it leaves resolved what
   * it already resolved: a run that was lost is made good by the next. The resolution outlives the
   * follow-up's archive or erasure; findings dismissed or fixed by other means are left untouched.
   */
  public int resolveFindingsOfShippedFollowUps() {
    if (db.read(this::openFindingsOfShippedFollowUps).isEmpty()) {
      return 0;
    }
    return db.transaction(
        () -> {
          var fixed = new LinkedHashMap<String, Map<String, Object>>();
          openFindingsOfShippedFollowUps()
              .forEach(
                  (findingId, followUp) ->
                      fixed.put(
                          findingId,
                          resolutionOf(
                              Finding.Resolution.FIXED, "fixed by follow-up " + followUp)));
          amend(fixed);
          return fixed.size();
        });
  }

  /** Open finding ids to the done follow-up each was drafted from; read before any write lock. */
  private Map<String, String> openFindingsOfShippedFollowUps() {
    var shipped = new LinkedHashMap<String, String>();
    db.query(
            "SELECT f.id, f.followup FROM review_findings f"
                + " JOIN specs sp ON sp.id = f.followup WHERE sp.status = 'done' AND "
                + OPEN,
            row -> Map.entry(row.text(0), row.text(1)))
        .forEach(entry -> shipped.put(entry.getKey(), entry.getValue()));
    return shipped;
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
    return db.transaction(
        () -> {
          var shipped = new LinkedHashMap<String, Map<String, Object>>();
          for (var finding : openFindingsAfterPass(specId)) {
            shipped.put(
                finding.id(),
                resolutionOf(Finding.Resolution.SHIPPED, "shipped below the review gate"));
          }
          amend(shipped);
          return shipped.size();
        });
  }

  /**
   * A stage's finding counts by severity for narration, counted from its findings. A review whose
   * findings were never synced — run on a box retired before they became content — still carries
   * the counts its snapshot replicated, in the legacy {@code finding_counts} column, which nothing
   * writes for a review with content. Empty when the stage has no findings.
   */
  public Map<String, Integer> findingCountsForStage(String stageId) {
    var counts = new LinkedHashMap<String, Integer>();
    for (var row :
        db.query(
            """
            SELECT severity, COUNT(*) FROM review_findings WHERE stage_id = ?
            GROUP BY severity ORDER BY MIN(rowid)""",
            row -> Map.entry(row.text(0), (int) row.integer(1)),
            stageId)) {
      counts.put(row.getKey(), row.getValue());
    }
    if (!counts.isEmpty()) {
      return counts;
    }
    var legacy =
        db.queryOne(
                "SELECT COALESCE(finding_counts, '') FROM review_stages WHERE id = ?",
                row -> row.text(0),
                stageId)
            .orElse("");
    if (!legacy.isBlank()) {
      YamlUtil.parseMap(legacy).forEach((k, v) -> counts.put(k, ((Number) v).intValue()));
    }
    return counts;
  }

  /** Appends {@code findings} to the review's content: one revision, none for nothing. */
  private void append(String reviewId, List<Map<String, Object>> findings) {
    if (findings.isEmpty()) {
      return;
    }
    var all = new ArrayList<>(content.read(reviewId));
    all.addAll(findings);
    writeContent(reviewId, all);
  }

  /**
   * Applies {@code changes}, fields to set per finding id, to the content of every review holding
   * one of the findings: one revision per review whose content changes, none for a review whose
   * findings already read so. A finding this box does not hold changes nothing.
   */
  private void amend(Map<String, Map<String, Object>> changes) {
    var reviews = new LinkedHashSet<String>();
    for (var findingId : changes.keySet()) {
      reviewOfFinding(findingId).ifPresent(reviews::add);
    }
    for (var reviewId : reviews) {
      var amended = new ArrayList<Map<String, Object>>();
      var changed = false;
      for (var finding : content.read(reviewId)) {
        var next = new LinkedHashMap<>(finding);
        next.putAll(changes.getOrDefault(Snapshots.text(finding, "id"), Map.of()));
        changed |= !next.equals(finding);
        amended.add(next);
      }
      if (changed) {
        writeContent(reviewId, amended);
      }
    }
  }

  /** Stores {@code findings} as the review's content and journals the revision. */
  private void writeContent(String reviewId, List<Map<String, Object>> findings) {
    content.store(reviewId, findings);
    journal(reviewId);
  }

  private String reviewOf(String stageId) {
    return findStage(stageId)
        .map(StageRow::reviewId)
        .orElseThrow(() -> new IllegalArgumentException("Review stage " + stageId + " not found"));
  }

  private Optional<String> reviewOfFinding(String findingId) {
    return db.queryOne(
        """
        SELECT s.review_id FROM review_stages s
        JOIN review_findings f ON f.stage_id = s.id WHERE f.id = ?""",
        row -> row.text(0),
        findingId);
  }

  /**
   * Folds the finding rows a box holds for review {@code id} from before findings were content into
   * one revision carrying them, for {@link ReviewFindingsMigration}; false when the review is
   * absent or already has content.
   */
  boolean foldLegacyFindings(String id) {
    if (findReview(id).isEmpty() || findingsHashOf(id) != null) {
      return false;
    }
    content.store(id, content.read(id));
    recordRevision(id, "migration");
    return true;
  }

  /**
   * Journals a fresh revision of the whole aggregate for a local mutation, within its transaction.
   */
  private void journal(String reviewId) {
    revisions.recordRevision(reviewId, ChangeLog.Entry.LOCAL, false);
  }

  /** Backfills a revision for a one-time data migration; delegates to the journal. */
  String recordRevision(String id, String origin) {
    return revisions.recordRevision(id, origin, false);
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

  @Override
  public Set<String> contentFields() {
    return Set.of(FINDINGS_HASH);
  }

  @Override
  public ConflictDetector.FieldMerger fieldMerger() {
    return schema.fieldMerger();
  }

  @Override
  public Set<String> liveContentHashes() {
    return new LinkedHashSet<>(
        db.query(
            "SELECT findings_hash FROM reviews WHERE findings_hash IS NOT NULL",
            row -> row.text(0)));
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
   * Adopts main's authoritative aggregate at its exact rev as the new synced ancestor: its review
   * row, its stages and its findings, projected from the content the snapshot names. When the
   * adopted content already matches the local aggregate — the normal case on the executing node
   * after its own successful push — only the revision is linked.
   */
  public void applyRevision(String id, Map<String, Object> snapshot, String rev) {
    revisions.applyRevision(id, snapshot, rev);
  }

  @Override
  public boolean acknowledge(String id, Map<String, Object> accepted, String rev) {
    return revisions.acknowledge(id, accepted, rev);
  }

  @Override
  public Set<String> latestWinsFields() {
    return revisions.latestWinsFields();
  }

  @Override
  public Map<String, Object> currentForSync(String id) {
    return revisions.currentForSync(id);
  }

  @Override
  public Optional<String> liveBase(String id) {
    return revisions.liveBase(id);
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
  public String resolveConflict(String id, Map<String, Object> chosen, MainVersion theirs) {
    return revisions.resolveConflict(id, chosen, theirs);
  }

  /**
   * The full aggregate snapshot: the review row, its stages in order with each stage's finding
   * counts by severity, and the hash of its findings. Null when the review is absent.
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
    var stages = new ArrayList<Map<String, Object>>();
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
    map.put(FINDINGS_HASH, findingsHashOf(id));
    return map;
  }

  private String findingsHashOf(String id) {
    return db.queryOne(
            "SELECT COALESCE(findings_hash, '') FROM reviews WHERE id = ?", row -> row.text(0), id)
        .filter(hash -> !hash.isBlank())
        .orElse(null);
  }

  /**
   * Writes an aggregate snapshot: the review row, its stages, and the projection of the findings
   * its content names. A stage the snapshot drops goes, with its rows. The legacy {@code
   * finding_counts} column is written only for a review that has no content yet.
   */
  @SuppressWarnings("unchecked")
  private void writeAggregate(String id, Map<String, Object> snapshot) {
    var hash = Snapshots.text(snapshot, FINDINGS_HASH);
    var findings = content.findingsOf(hash);
    db.execute(
        """
        INSERT INTO reviews (id, spec_id, iteration, status, created_at, completed_at,
            decided_by, superseded_at, error, findings_hash)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET spec_id = excluded.spec_id,
            iteration = excluded.iteration, status = excluded.status,
            created_at = excluded.created_at, completed_at = excluded.completed_at,
            decided_by = excluded.decided_by, superseded_at = excluded.superseded_at,
            error = excluded.error, findings_hash = excluded.findings_hash""",
        id,
        Snapshots.text(snapshot, "spec_id"),
        Snapshots.integer(snapshot, "iteration"),
        Snapshots.text(snapshot, "status"),
        Snapshots.text(snapshot, "created_at"),
        Snapshots.text(snapshot, "completed_at"),
        Snapshots.text(snapshot, "decided_by"),
        Snapshots.text(snapshot, "superseded_at"),
        Snapshots.text(snapshot, "error"),
        hash);
    var stages =
        Objects.requireNonNullElse(
            (List<Map<String, Object>>) snapshot.get("stages"), List.<Map<String, Object>>of());
    for (var stage : stages) {
      var counts = hash == null ? stage.get("finding_counts") : null;
      requireStageUnclaimed(id, Snapshots.text(stage, "id"));
      db.execute(
          """
          INSERT INTO review_stages (id, review_id, name, stage_type, status, reviewer,
              started_at, completed_at, error, finding_counts)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT(id) DO UPDATE SET name = excluded.name,
              stage_type = excluded.stage_type, status = excluded.status,
              reviewer = excluded.reviewer, started_at = excluded.started_at,
              completed_at = excluded.completed_at, error = excluded.error,
              finding_counts = excluded.finding_counts""",
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
        AND id NOT IN (SELECT value FROM json_each(?))""",
        id,
        YamlUtil.dumpJson(stages.stream().map(stage -> Snapshots.text(stage, "id")).toList()));
    content.project(id, findings);
  }

  /** A snapshot may only name stages of its own review: another review's stage is never moved. */
  private void requireStageUnclaimed(String reviewId, String stageId) {
    var owner = findStage(stageId).map(StageRow::reviewId).orElse(reviewId);
    if (!reviewId.equals(owner)) {
      throw new IllegalArgumentException(
          "Review stage " + stageId + " belongs to review " + owner + ", not " + reviewId);
    }
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

  /** The review's store-specific half of the shared {@link RevisionJournal} sync protocol. */
  private final class ReviewSchema implements EntitySchema {

    @Override
    public String entityType() {
      return ENTITY;
    }

    /**
     * Findings both sides changed merge finding by finding ({@link ReviewFindingsContent#merge}):
     * main resolving one as a follow-up ships while this box's review run rules on another is no
     * conflict.
     */
    @Override
    public ConflictDetector.FieldMerger fieldMerger() {
      return (field, base, local, remote) ->
          FINDINGS_HASH.equals(field)
              ? content.merge((String) base, (String) local, (String) remote).map(hash -> hash)
              : Optional.empty();
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
