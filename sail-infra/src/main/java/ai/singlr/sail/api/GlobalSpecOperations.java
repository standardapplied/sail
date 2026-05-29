/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.store.SpecStore;
import java.util.Objects;

/**
 * Global (control-plane) spec CRUD against the {@link SpecStore}. Pure database operations, split
 * out of {@code SailApiOperations} so the spec-store domain lives in one focused, fully-testable
 * class. Methods return their response value and throw {@link ApiException} on failure; the caller
 * wraps them in a {@code Result}.
 */
final class GlobalSpecOperations {

  private final SpecStore specStore;

  GlobalSpecOperations(SpecStore specStore) {
    this.specStore = specStore;
  }

  GlobalSpecsListResponse list(SpecStore.SpecFilter filter) {
    requireStore();
    try {
      var specs = specStore.list(filter).stream().map(GlobalSpecView::from).toList();
      return new GlobalSpecsListResponse(specs, specs.size());
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, e.getMessage());
    }
  }

  GlobalSpecDetailResponse get(String specId) {
    requireStore();
    var row = findOrThrow(specId);
    var content = specStore.getContent(specId).orElse(null);
    return new GlobalSpecDetailResponse(
        GlobalSpecView.from(row),
        content != null ? content.body() : null,
        content != null ? content.plan() : null);
  }

  GlobalSpecCreatedResponse create(SpecCreateRequest request) {
    requireStore();
    if (request.id() == null || request.id().isBlank()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "spec id is required.");
    }
    if (request.title() == null || request.title().isBlank()) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, "spec title is required.");
    }
    NameValidator.requireValidSpecId(request.id());
    if (request.project() == null || request.project().isBlank()) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST,
          "spec project is required.",
          "Pass --project <name> or run from a directory containing sail.yaml.");
    }
    var row =
        new SpecStore.SpecRow(
            request.id(),
            request.project(),
            request.title(),
            parseStatus(request.status(), SpecStatus.PENDING),
            request.assignee(),
            request.agent(),
            validModel(request.model()),
            validReasoning(request.reasoningEffort()),
            request.branch(),
            request.priority(),
            request.createdBy(),
            "",
            "",
            request.dependsOn(),
            request.repos());
    specStore.create(row);
    if (request.body() != null || request.plan() != null) {
      specStore.setContent(
          request.id(),
          Objects.requireNonNullElse(request.body(), ""),
          Objects.requireNonNullElse(request.plan(), ""));
    }
    var created = specStore.findById(request.id()).orElseThrow();
    return new GlobalSpecCreatedResponse(GlobalSpecView.from(created));
  }

  GlobalSpecUpdatedResponse update(String specId, SpecUpdateRequest request) {
    requireStore();
    var existing = findOrThrow(specId);
    var updated =
        new SpecStore.SpecRow(
            specId,
            request.project() != null ? request.project() : existing.project(),
            request.title() != null ? request.title() : existing.title(),
            parseStatus(request.status(), existing.status()),
            request.assignee() != null ? request.assignee() : existing.assignee(),
            request.agent() != null ? request.agent() : existing.agent(),
            request.model() != null ? validModel(request.model()) : existing.model(),
            request.reasoningEffort() != null
                ? validReasoning(request.reasoningEffort())
                : existing.reasoningEffort(),
            request.branch() != null ? request.branch() : existing.branch(),
            request.priority() != null ? request.priority() : existing.priority(),
            existing.createdBy(),
            existing.createdAt(),
            existing.updatedAt(),
            request.dependsOn() != null ? request.dependsOn() : existing.dependsOn(),
            request.repos() != null ? request.repos() : existing.repos());
    specStore.update(updated);
    var result = specStore.findById(specId).orElseThrow();
    return new GlobalSpecUpdatedResponse(GlobalSpecView.from(result));
  }

  GlobalSpecDeletedResponse delete(String specId) {
    requireStore();
    findOrThrow(specId);
    specStore.delete(specId);
    return new GlobalSpecDeletedResponse(specId);
  }

  GlobalSpecContentResponse content(String specId) {
    requireStore();
    findOrThrow(specId);
    var content = specStore.getContent(specId).orElse(new SpecStore.SpecContent("", "", ""));
    return new GlobalSpecContentResponse(specId, content.body(), content.plan());
  }

  GlobalSpecContentResponse setContent(String specId, SpecContentRequest request) {
    requireStore();
    findOrThrow(specId);
    specStore.setContent(
        specId,
        Objects.requireNonNullElse(request.body(), ""),
        Objects.requireNonNullElse(request.plan(), ""));
    var content = specStore.getContent(specId).orElseThrow();
    return new GlobalSpecContentResponse(specId, content.body(), content.plan());
  }

  GlobalBoardResponse board(String project) {
    requireStore();
    return new GlobalBoardResponse(specStore.board(project));
  }

  private SpecStore.SpecRow findOrThrow(String specId) {
    return specStore
        .findById(specId)
        .orElseThrow(
            () ->
                new ApiException(ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
  }

  private void requireStore() {
    if (specStore == null) {
      throw new ApiException(
          ErrorCode.INTERNAL,
          "Spec store not available. Start the server with 'sail server start'.");
    }
  }

  private static String validModel(String model) {
    try {
      return Spec.validatedModel(model);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, e.getMessage());
    }
  }

  private static String validReasoning(String reasoningEffort) {
    try {
      return Spec.validatedReasoningEffort(reasoningEffort);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, e.getMessage());
    }
  }

  private static SpecStatus parseStatus(String value, SpecStatus fallback) {
    if (value == null) {
      return fallback;
    }
    try {
      return SpecStatus.fromWire(value);
    } catch (IllegalArgumentException e) {
      throw new ApiException(ErrorCode.INVALID_REQUEST, e.getMessage());
    }
  }
}
