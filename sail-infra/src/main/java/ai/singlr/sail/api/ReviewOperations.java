/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;

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
    reviewStore.resolveSourceFindings(review.specId());
    return new ReviewApproveResponse(reviewId, true);
  }

  /**
   * Drafts a follow-up spec from the open findings of {@code sourceSpecId}'s latest non-superseded
   * review. The draft copies the source spec's project and repos, derives its priority from the
   * highest severity present, and starts in {@code draft} so a human reviews and edits it before
   * promotion — generated work is never auto-dispatched. The findings it was drafted from are
   * linked, so they are marked resolved when the follow-up reaches {@code done}.
   */
  FollowupSpecResponse createFollowup(String sourceSpecId, FollowupCreateRequest request) {
    requireStore();
    var source =
        specStore
            .findById(sourceSpecId)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.SPEC_NOT_FOUND, "Spec '" + sourceSpecId + "' was not found."));
    var review =
        reviewStore.latestReviewForSpec(sourceSpecId).orElseThrow(() -> noReview(sourceSpecId));
    var findings = reviewStore.openFindingsForReview(review.id());
    if (findings.isEmpty()) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST,
          "The latest review of spec '" + sourceSpecId + "' has no open findings.",
          "Every finding is already fixed or dismissed — there is nothing to follow up on.");
    }
    var followupId = followupId(sourceSpecId, request.id());
    if (specStore.findById(followupId).isPresent()) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Spec '" + followupId + "' already exists.",
          "Pass --id <id> to choose a different id for the follow-up spec.");
    }
    specStore.create(
        new SpecStore.SpecRow(
            followupId,
            source.project(),
            FollowupDraft.title(source.title()),
            SpecStatus.DRAFT,
            null,
            null,
            null,
            null,
            null,
            FollowupDraft.priority(findings),
            request.createdBy(),
            "",
            "",
            request.createdBy(),
            List.of(),
            source.repos()));
    specStore.setContent(followupId, FollowupDraft.body(sourceSpecId, review, findings), "");
    reviewStore.linkSourceFindings(followupId, findings.stream().map(Finding::id).toList());
    var created = specStore.findById(followupId).orElseThrow();
    return new FollowupSpecResponse(
        GlobalSpecView.from(created), sourceSpecId, review.id(), findings.size());
  }

  private ApiException noReview(String sourceSpecId) {
    if (reviewStore.reviewsForSpec(sourceSpecId).isEmpty()) {
      return new ApiException(
          ErrorCode.NOT_FOUND,
          "Spec '" + sourceSpecId + "' has no reviews.",
          "Dispatch the spec with a review pipeline configured, let the review finish,"
              + " then re-run this command.");
    }
    return new ApiException(
        ErrorCode.CONFLICT,
        "Every review of spec '"
            + sourceSpecId
            + "' was superseded by a re-dispatch — there is no current review to draw findings"
            + " from.",
        "Let the current dispatch attempt finish its review, then re-run this command.");
  }

  private static String followupId(String sourceSpecId, String requestedId) {
    var followupId = requestedId != null ? requestedId : sourceSpecId + "-followup";
    try {
      NameValidator.requireValidSpecId(followupId);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, e.getMessage());
    }
    return followupId;
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
