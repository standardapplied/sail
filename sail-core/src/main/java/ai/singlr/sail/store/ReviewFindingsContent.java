/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.config.YamlUtil;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A review's findings as its synced content: one document per review, every finding of every stage
 * with each of its columns verbatim, in canonical order (stage order, then finding id), serialized
 * as JSON and stored once in the {@link BlobStore} under the hash the review's snapshot carries as
 * {@code findings_hash}. {@code review_findings} is the projection of that document: {@link
 * #project} is the only writer of the table, on every box, so the rows a box reads are exactly the
 * content its review names. Content may only ever write its own review's rows, whoever offered it.
 */
final class ReviewFindingsContent {

  static final String HASH_FIELD = "findings_hash";

  static final List<String> FIELDS =
      List.of(
          "stage_id",
          "id",
          "severity",
          "category",
          "file",
          "line_start",
          "line_end",
          "title",
          "description",
          "evidence",
          "suggestion_before",
          "suggestion_after",
          "suggestion_rationale",
          "confidence",
          "resolution",
          "resolution_evidence",
          "carried_from",
          "carry_evidence",
          "followup");

  private static final String COLUMNS = String.join(", ", FIELDS);
  private static final String PREFIXED_COLUMNS =
      FIELDS.stream().map("f."::concat).collect(Collectors.joining(", "));
  private static final String PLACEHOLDERS =
      FIELDS.stream().map(field -> "?").collect(Collectors.joining(", "));

  private final Sqlite db;
  private final BlobStore blobs;

  ReviewFindingsContent(Sqlite db, BlobStore blobs) {
    this.db = Objects.requireNonNull(db, "db");
    this.blobs = Objects.requireNonNull(blobs, "blobs");
  }

  /** One finding of {@code stageId} as the content holds it. */
  static Map<String, Object> of(String stageId, Finding finding) {
    var suggestion =
        Objects.requireNonNullElse(finding.suggestion(), new Finding.Suggestion(null, null, null));
    var content = new LinkedHashMap<String, Object>();
    content.put("stage_id", stageId);
    content.put("id", finding.id());
    content.put("severity", finding.severity().name());
    content.put("category", finding.category().name());
    content.put("file", finding.file());
    content.put("line_start", finding.lineStart());
    content.put("line_end", finding.lineEnd());
    content.put("title", finding.title());
    content.put("description", finding.description());
    content.put("evidence", finding.evidence());
    content.put("suggestion_before", suggestion.before());
    content.put("suggestion_after", suggestion.after());
    content.put("suggestion_rationale", suggestion.rationale());
    content.put("confidence", finding.confidence());
    content.put("resolution", finding.resolution().name());
    content.put("resolution_evidence", finding.resolutionEvidence());
    content.put("carried_from", finding.carriedFrom());
    content.put("carry_evidence", finding.carryEvidence());
    content.put("followup", null);
    return content;
  }

  /** The review's findings as its content holds them, read back from the projection. */
  List<Map<String, Object>> read(String reviewId) {
    return db.query(
        "SELECT "
            + PREFIXED_COLUMNS
            + " FROM review_findings f JOIN review_stages s ON s.id = f.stage_id"
            + " WHERE s.review_id = ? ORDER BY s.rowid, f.id",
        row -> {
          var content = new LinkedHashMap<String, Object>();
          for (var i = 0; i < FIELDS.size(); i++) {
            content.put(FIELDS.get(i), value(FIELDS.get(i), row, i));
          }
          return content;
        },
        reviewId);
  }

  private static Object value(String field, Sqlite.Row row, int column) {
    if (row.isNull(column)) {
      return null;
    }
    return switch (field) {
      case "line_start", "line_end" -> (int) row.integer(column);
      case "confidence" -> Double.parseDouble(row.text(column));
      default -> row.text(column);
    };
  }

  /**
   * Stores {@code findings} as the review's content — canonically ordered, serialized, hashed into
   * the review's row with the bytes in the blob store — and projects it. Returns the hash.
   */
  String store(String reviewId, List<Map<String, Object>> findings) {
    var content = canonical(reviewId, findings);
    var hash = blobs.putText(serialize(content));
    db.execute("UPDATE reviews SET " + HASH_FIELD + " = ? WHERE id = ?", hash, reviewId);
    project(reviewId, content);
    return hash;
  }

  private List<Map<String, Object>> canonical(String reviewId, List<Map<String, Object>> findings) {
    var stages =
        db.query(
            "SELECT id FROM review_stages WHERE review_id = ? ORDER BY rowid",
            row -> row.text(0),
            reviewId);
    return findings.stream()
        .sorted(
            Comparator.comparingInt(
                    (Map<String, Object> f) -> stages.indexOf(Snapshots.text(f, "stage_id")))
                .thenComparing(f -> Snapshots.text(f, "id")))
        .toList();
  }

  private static String serialize(List<Map<String, Object>> findings) {
    var document = new LinkedHashMap<String, Object>();
    document.put("findings", findings);
    return YamlUtil.dumpJson(document);
  }

  /** The findings a content hash names; none for a review that has no content. */
  @SuppressWarnings("unchecked")
  List<Map<String, Object>> findingsOf(String hash) {
    if (hash == null) {
      return List.of();
    }
    blobs.requireHeld(hash);
    var document = YamlUtil.parseMap(blobs.text(hash));
    if (!(document.get("findings") instanceof List<?> findings)) {
      throw new IllegalStateException(
          "Review findings content " + hash + " holds no findings list");
    }
    return new ArrayList<>((List<Map<String, Object>>) findings);
  }

  /**
   * Three-way merge of a review's content both sides changed: finding by finding, a finding one
   * side changed, added or kept takes that side's version; one both sides changed differently
   * cannot be reconciled, and the merge is empty. The merged document is stored, its hash returned,
   * in the local order with the findings only main holds after.
   */
  Optional<String> merge(String baseHash, String localHash, String remoteHash) {
    var base = byId(findingsOf(baseHash));
    var local = byId(findingsOf(localHash));
    var remote = byId(findingsOf(remoteHash));
    var merged = new LinkedHashMap<String, Map<String, Object>>();
    var ids = new LinkedHashSet<>(local.keySet());
    ids.addAll(remote.keySet());
    for (var id : ids) {
      var ours = local.get(id);
      var theirs = remote.get(id);
      var was = base.get(id);
      if (Objects.equals(ours, theirs) || Objects.equals(theirs, was)) {
        merged.put(id, ours);
      } else if (Objects.equals(ours, was)) {
        merged.put(id, theirs);
      } else {
        return Optional.empty();
      }
    }
    var findings = merged.values().stream().filter(Objects::nonNull).toList();
    return Optional.of(blobs.putText(serialize(findings)));
  }

  private static Map<String, Map<String, Object>> byId(List<Map<String, Object>> findings) {
    var byId = new LinkedHashMap<String, Map<String, Object>>();
    for (var finding : findings) {
      byId.put(Snapshots.text(finding, "id"), finding);
    }
    return byId;
  }

  /**
   * Writes the projection of the review's content: its finding rows are exactly the content's, in
   * its order. Content naming a stage of another review, or none this box holds, or a finding id
   * another review already holds, aborts the enclosing transaction with the reason.
   */
  void project(String reviewId, List<Map<String, Object>> findings) {
    for (var finding : findings) {
      requireOwn(reviewId, finding);
    }
    db.execute(
        "DELETE FROM review_findings WHERE stage_id IN"
            + " (SELECT id FROM review_stages WHERE review_id = ?)",
        reviewId);
    for (var finding : findings) {
      db.execute(
          "INSERT INTO review_findings (" + COLUMNS + ") VALUES (" + PLACEHOLDERS + ")",
          FIELDS.stream().map(finding::get).toArray());
    }
  }

  private void requireOwn(String reviewId, Map<String, Object> finding) {
    var stageId = Snapshots.text(finding, "stage_id");
    var stageOwner =
        db.queryOne("SELECT review_id FROM review_stages WHERE id = ?", row -> row.text(0), stageId)
            .orElse(null);
    if (!reviewId.equals(stageOwner)) {
      throw new IllegalArgumentException(
          "Finding stage " + stageId + " does not belong to review " + reviewId);
    }
    var findingId = Snapshots.text(finding, "id");
    var findingOwner =
        db.queryOne(
                """
                SELECT s.review_id FROM review_findings f
                JOIN review_stages s ON s.id = f.stage_id WHERE f.id = ?""",
                row -> row.text(0),
                findingId)
            .orElse(reviewId);
    if (!reviewId.equals(findingOwner)) {
      throw new IllegalArgumentException(
          "Finding " + findingId + " already belongs to review " + findingOwner);
    }
  }
}
