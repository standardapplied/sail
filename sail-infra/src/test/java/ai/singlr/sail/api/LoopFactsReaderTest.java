/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.LoopRows.AN_AGENT_THEN_A_PERSON;
import static ai.singlr.sail.api.LoopRows.SPEC;
import static ai.singlr.sail.api.ReviewLoop.PROJECT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.api.LoopFacts.Pipeline;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.identity.Acting;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a decision of the review loop is handed is what the stores hold, and no more than it reads.
 */
class LoopFactsReaderTest {

  private static final Pipeline STAGED = LoopRows.staged(AN_AGENT_THEN_A_PERSON);

  @TempDir Path tempDir;
  private ReviewLoop loop;
  private LoopFactsReader reader;

  @BeforeEach
  void setUp() {
    loop = ReviewLoop.of(tempDir, AN_AGENT_THEN_A_PERSON);
    loop.spec(SPEC, "api");
    reader = new LoopFactsReader(loop.specs, loop.reviews, loop.runs, () -> ReviewLoop.HANDLE);
  }

  @AfterEach
  void tearDown() {
    loop.close();
  }

  private LoopFacts read() {
    return reader.read(PROJECT, SPEC, STAGED);
  }

  /**
   * Iteration {@code iteration} of the spec's review, its agent stage holding an unreadable row.
   */
  private String reviewWithAFindingNothingCanRead(int iteration) {
    var review = Acting.system(() -> loop.reviews.createReview(SPEC, iteration));
    var stage = Acting.system(() -> loop.reviews.createStage(review, "codex", "agent"));
    loop.db.execute(
        """
        INSERT INTO review_findings (id, stage_id, severity, category, title, description)
        VALUES (?, ?, 'NO_SEVERITY', 'LOGIC', 'Bad', 'row')""",
        "unreadable-" + iteration,
        stage);
    loop.db.execute("UPDATE review_stages SET status = 'failed' WHERE id = ?", stage);
    return review;
  }

  @Test
  void findingsAreReadOnlyForAReviewAFixIsDueFor() {
    var review = reviewWithAFindingNothingCanRead(1);

    assertEquals(List.of(), read().openFindings(), "no decision on a running review weighs one");

    loop.db.execute("UPDATE reviews SET status = 'failed' WHERE id = ?", review);
    var fix = loop.run(SPEC, "fix");
    loop.db.execute("UPDATE runs SET review_id = ? WHERE id = ?", review, fix);

    assertEquals(List.of(), read().openFindings(), "nor one on a review its fix agent serves");

    Acting.system(() -> loop.runs.complete(fix, "stopped", 1));

    assertEquals(List.of(), read().openFindings(), "nor one on how that fix agent ended");

    loop.db.execute("DELETE FROM runs WHERE id = ?", fix);
    var holder = loop.run("billing", "build");
    loop.db.execute("UPDATE reviews SET waiting_on = ? WHERE id = ?", holder, review);

    assertEquals(List.of(), read().openFindings(), "nor one on a fix that waits for a live run");

    Acting.system(() -> loop.runs.complete(holder, "stopped", 0));

    assertThrows(
        IllegalArgumentException.class, this::read, "a fix is due: its findings are weighed");
  }

  @Test
  void erroredAttemptsAreThoseOfTheReviewsIterationInItsDispatchAttempt() {
    var earlierAttempt = Acting.system(() -> loop.reviews.createReview(SPEC, 1));
    Acting.system(() -> loop.reviews.failReviewWithError(earlierAttempt, "reviewer died"));
    Acting.system(() -> loop.reviews.supersedeForSpec(SPEC));
    var anotherIteration = Acting.system(() -> loop.reviews.createReview(SPEC, 2));
    Acting.system(() -> loop.reviews.failReviewWithError(anotherIteration, "reviewer died"));
    var first = Acting.system(() -> loop.reviews.createReview(SPEC, 1));
    Acting.system(() -> loop.reviews.failReviewWithError(first, "reviewer died"));
    var second = Acting.system(() -> loop.reviews.createReview(SPEC, 1));
    Acting.system(() -> loop.reviews.failReviewWithError(second, "reviewer died"));

    assertEquals(second, read().rows().review().orElseThrow().id());
    assertEquals(
        2,
        read().erroredAttempts(),
        "an attempt a re-dispatch superseded, and one of another iteration, spend none of it");
  }

  @Test
  void aProjectsPipelineIsNoneStagedOrUnreadable() {
    var named = AN_AGENT_THEN_A_PERSON;
    var unnamed = ReviewPipelineConfig.mandatoryDefault();
    var asked = new AtomicInteger();

    assertEquals(
        new Pipeline.None(), LoopFactsReader.pipelines(p -> null, p -> "codex").apply(PROJECT));
    assertEquals(
        new Pipeline.None(),
        LoopFactsReader.pipelines(p -> new ReviewPipelineConfig(3, 2, List.of()), p -> "codex")
            .apply(PROJECT));
    assertEquals(
        new Pipeline.Staged(named, null),
        LoopFactsReader.pipelines(
                p -> named,
                p -> {
                  throw new IllegalStateException("never asked");
                })
            .apply(PROJECT),
        "a pipeline that names every reviewer asks the roster for none");
    assertEquals(
        new Pipeline.Staged(unnamed, "codex"),
        LoopFactsReader.pipelines(
                p -> unnamed,
                p -> {
                  asked.incrementAndGet();
                  return "codex";
                })
            .apply(PROJECT));
    assertEquals(1, asked.get());
    assertEquals(
        new Pipeline.Unreadable("sail.yaml of project 'acme' could not be read"),
        LoopFactsReader.pipelines(
                p -> {
                  throw new IllegalStateException("sail.yaml of project 'acme' could not be read");
                },
                p -> "codex")
            .apply(PROJECT),
        "an unreadable descriptor is never taken for a project with no pipeline");
  }

  @Test
  void oneEventReadsAProjectsPipelineOnceAndEveryReadOfItIsUnderThatReading() {
    var resolved = new AtomicInteger();
    var read =
        reader.forOneEvent(
            project -> {
              resolved.incrementAndGet();
              return STAGED;
            });

    assertInstanceOf(Pipeline.Staged.class, read.apply(PROJECT, SPEC).pipeline());
    assertEquals(STAGED, read.apply(PROJECT, "another-spec").pipeline());
    assertEquals(1, resolved.get());
  }
}
