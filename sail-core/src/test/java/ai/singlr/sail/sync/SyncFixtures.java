/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.sync;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.util.List;

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
}
