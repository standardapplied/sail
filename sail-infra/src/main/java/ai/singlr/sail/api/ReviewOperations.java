/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SpecStore;

/**
 * Review-workflow operations against the {@link ReviewStore}. Split out of {@code
 * SailApiOperations} so the review domain is one focused, fully-testable class. Methods return
 * their response value and throw {@link ApiException} on failure; the caller wraps them in a {@code
 * Result}. {@code specStore} is needed only to flip a spec to {@code done} when its human review is
 * approved.
 */
final class ReviewOperations {

  private final ReviewStore reviewStore;
  private final SpecStore specStore;

  ReviewOperations(ReviewStore reviewStore, SpecStore specStore) {
    this.reviewStore = reviewStore;
    this.specStore = specStore;
  }

  ReviewListResponse listForSpec(String specId) {
    requireStore();
    var views =
        reviewStore.reviewsForSpec(specId).stream()
            .map(
                r -> {
                  var stageViews =
                      reviewStore.stagesForReview(r.id()).stream()
                          .map(s -> StageView.from(s, reviewStore.findingsForStage(s.id()).size()))
                          .toList();
                  return ReviewView.from(r, stageViews);
                })
            .toList();
    return new ReviewListResponse(specId, views);
  }

  ReviewDetailResponse detail(String reviewId) {
    requireStore();
    var review = findReviewOrThrow(reviewId);
    var stageViews =
        reviewStore.stagesForReview(reviewId).stream()
            .map(s -> StageView.from(s, reviewStore.findingsForStage(s.id()).size()))
            .toList();
    var findings = reviewStore.findingsForReview(reviewId).stream().map(Finding::toMap).toList();
    return new ReviewDetailResponse(ReviewView.from(review, stageViews), findings);
  }

  ReviewApproveResponse approve(String reviewId, String actor) {
    requireStore();
    var review = findReviewOrThrow(reviewId);
    var humanStage =
        reviewStore.stagesForReview(reviewId).stream()
            .filter(s -> "human".equals(s.stageType()) && "running".equals(s.status()))
            .findFirst()
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.INVALID_REQUEST, "No human review stage awaiting approval."));
    reviewStore.completeStage(humanStage.id(), "passed");
    reviewStore.approve(reviewId, actor);
    specStore.updateStatus(review.specId(), SpecStatus.DONE);
    return new ReviewApproveResponse(reviewId, true);
  }

  FindingDismissResponse dismissFinding(String reviewId, String findingId) {
    requireStore();
    findReviewOrThrow(reviewId);
    reviewStore.resolveFinding(findingId, Finding.Resolution.DISMISSED);
    return new FindingDismissResponse(findingId, true);
  }

  private ReviewStore.ReviewRow findReviewOrThrow(String reviewId) {
    return reviewStore
        .findReview(reviewId)
        .orElseThrow(
            () -> new ApiException(ErrorCode.NOT_FOUND, "Review '" + reviewId + "' not found."));
  }

  private void requireStore() {
    if (reviewStore == null || specStore == null) {
      throw new ApiException(
          ErrorCode.INTERNAL,
          "Review store not available. Start the server with 'sail server start'.");
    }
  }
}
