/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.identity.Acting;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the loop says is best-effort wherever it tells of work that must not fail for it. */
class LoopNarratorTest {

  @TempDir Path tempDir;
  private ReviewLoop loop;
  private LoopNarrator narrator;

  @BeforeEach
  void setUp() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("auth", "api");
    narrator = new LoopNarrator(loop.specs, loop.bus, loop.syncs::incrementAndGet);
    narrator.useMessages(loop.messages);
  }

  @AfterEach
  void tearDown() {
    loop.close();
  }

  @Test
  void aPipelineErrorThatCannotBePublishedNeverMasksTheFailureItTellsOf() {
    assertDoesNotThrow(
        () -> narrator.publishPipelineError(" ", "auth", new IllegalStateException("boom")),
        "an event with no project cannot be built, and that is not the failure to report");

    loop.settle();
    assertEquals(List.of(), loop.events("review_pipeline_error"));
  }

  @Test
  void aPipelineErrorSaysWhatFailedAndNamesTheFailureWhenItSaysNothing() {
    narrator.publishPipelineError(ReviewLoop.PROJECT, "auth", new IllegalStateException());

    loop.settle();
    assertEquals(List.of("IllegalStateException"), loop.details("review_pipeline_error"));
  }

  @Test
  void aRoomLineThatCannotBePostedNeverFailsTheWorkItTellsOf() {
    loop.db.execute(
        """
        CREATE TRIGGER room_down BEFORE INSERT ON room_messages
        BEGIN SELECT RAISE(ABORT, 'room is down'); END""");

    assertDoesNotThrow(() -> Acting.system(() -> narrator.postRoom("auth", "Review failed.")));

    assertEquals(0, loop.syncs.get(), "nothing was written, so nothing is synced");
  }

  @Test
  void aRoomThatCannotBeReadDegradesAPromptToNoMessages() {
    Acting.system(() -> narrator.postRoom("auth", "Review failed."));
    assertEquals(1, narrator.roomMessages("auth").size());
    loop.db.close();

    assertEquals(List.of(), narrator.roomMessages("auth"));
  }
}
