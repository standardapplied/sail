/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Finding;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import java.util.List;
import java.util.Objects;

/** The rows the sync scenarios make, in project {@code acme}, as one FDE or another. */
final class SyncFixtures {

  private SyncFixtures() {}

  /** A pending spec {@code id} assigned to {@code assignee}, titled after its id. */
  static SpecStore.SpecRow spec(String id, String assignee) {
    return new SpecStore.SpecRow(
        id,
        "acme",
        "Spec " + id,
        SpecStatus.PENDING,
        assignee,
        null,
        null,
        null,
        null,
        0,
        null,
        "",
        "",
        null,
        List.of(),
        List.of());
  }

  /** A spec {@code as} creates on {@code box} in its own room, with that room. */
  static void ownSpec(SyncBox box, String as, String id, String assignee) {
    Acting.as(
        as,
        () -> {
          box.specs.create(spec(id, assignee));
          new RoomStore(box.db)
              .create(
                  new RoomStore.RoomRow(
                      id, "acme", "Spec " + id, assignee, null, null, null, null, null, null));
        });
  }

  /** Spec {@code id} on {@code box} reassigned to {@code assignee} by {@code as}. */
  static void assign(SyncBox box, String as, String id, String assignee) {
    var row = box.specs.findById(id).orElseThrow();
    Acting.as(
        as,
        () ->
            box.specs.update(
                new SpecStore.SpecRow(
                    row.id(),
                    row.project(),
                    row.title(),
                    row.status(),
                    assignee,
                    row.agent(),
                    row.model(),
                    row.reasoningEffort(),
                    row.branch(),
                    row.priority(),
                    row.createdBy(),
                    row.createdAt(),
                    row.updatedAt(),
                    row.updatedBy(),
                    row.dependsOn(),
                    row.repos(),
                    row.roomId())));
  }

  /** A room {@code id} that {@code as} creates on {@code box} and is assigned. */
  static void room(SyncBox box, String as, String id) {
    Acting.as(
        as,
        () ->
            new RoomStore(box.db)
                .create(
                    new RoomStore.RoomRow(id, "acme", id, as, null, null, null, null, null, null)));
  }

  /** A build run of {@code specId} that {@code fde} starts on {@code box}; its id. */
  static String run(SyncBox box, String fde, String specId) {
    var id = DateTimeUtils.newId().toString();
    Acting.as(
        fde,
        () ->
            new RunStore(box.db)
                .create(
                    id,
                    "acme",
                    specId,
                    fde,
                    "build",
                    "claude-code",
                    "b",
                    "t",
                    null,
                    null,
                    "/log",
                    "unit"));
    return id;
  }

  /** The principal minted for run {@code runId} on {@code box}. */
  static String principal(SyncBox box, String runId) {
    return new RunStore(box.db).findById(runId).orElseThrow().principal();
  }

  /** An open HIGH security finding titled {@code title}, with a fresh id. */
  static Finding finding(String title) {
    return Finding.create(
        Finding.Severity.HIGH,
        Finding.Category.SECURITY,
        "A.java",
        1,
        2,
        title,
        "desc",
        "evidence",
        new Finding.Suggestion("a", "b", "c"),
        0.9);
  }

  /** The findings {@code box} holds for review {@code reviewId}. */
  static List<Finding> findings(SyncBox box, String reviewId) {
    return new ReviewStore(box.db).findingsForReview(reviewId);
  }

  /**
   * Whether a revision of review {@code reviewId} in {@code box}'s change log carries the finding
   * {@code findingId} in the content its snapshot names, and the box still holds that content.
   */
  static boolean findingKeptInChangeLog(SyncBox box, String reviewId, String findingId) {
    var blobs = new BlobStore(box.db);
    return new ChangeLog(box.db)
        .history("review", reviewId).stream()
            .map(entry -> Snapshots.text(YamlUtil.parseMap(entry.snapshot()), "findings_hash"))
            .filter(Objects::nonNull)
            .filter(blobs::has)
            .anyMatch(hash -> blobs.text(hash).contains(findingId));
  }
}
