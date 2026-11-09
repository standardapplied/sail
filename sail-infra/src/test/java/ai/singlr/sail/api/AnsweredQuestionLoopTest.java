/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ReviewScripts.CRITICAL_FINDING;
import static ai.singlr.sail.api.ReviewScripts.fixAllCarried;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.ActingAs;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A build or fix agent that ends on its own question waits for the answer: nothing is reviewed
 * until someone who steers it replies, and then it goes on, with the question and the answer in its
 * prompt and no restart recorded.
 */
@ActingAs
class AnsweredQuestionLoopTest {

  private static final String SPEC = "auth";
  private static final String QUESTION = "Which JDK should the build use?";

  @TempDir Path tempDir;

  private final List<String> builds = new CopyOnWriteArrayList<>();
  private final List<String> fixes = new CopyOnWriteArrayList<>();
  private ReviewLoop loop;

  private void start(List<String> reviews) {
    var reviewed = new AtomicInteger();
    loop =
        ReviewLoop.staged(tempDir, "codex")
            .scripted(
                new ScriptedAgent() {
                  @Override
                  public String run(
                      String project, String agent, String prompt, String reviewId, String cred) {
                    if (prompt.contains("Review the changes on branch")) {
                      var turn = reviewed.getAndIncrement();
                      return turn < reviews.size() ? reviews.get(turn) : fixAllCarried(prompt);
                    }
                    builds.add(prompt);
                    return "built";
                  }

                  @Override
                  public String runFix(
                      String project,
                      String agent,
                      String prompt,
                      String reviewId,
                      String cred,
                      String branch,
                      List<String> repos,
                      String model,
                      String effort) {
                    fixes.add(prompt);
                    return "fixed";
                  }
                });
    var fdes = new FdeStore(loop.db);
    fdes.add(ReviewLoop.HANDLE, "Node A", null, "admin");
    fdes.add("mo", "Mo", null);
    loop.spec(SPEC, "api");
    loop.assign(SPEC, ReviewLoop.HANDLE);
  }

  private void asks(String runId, String question) {
    var principal = loop.runs.findById(runId).orElseThrow().principal();
    loop.messages.append(SPEC, principal, question, null, MessageStore.Mark.QUESTION);
  }

  private void says(String author, String body) {
    var posted = loop.messages.append(SPEC, author, body, null);
    loop.onEvent(
        Event.of(
            ReviewLoop.PROJECT,
            SPEC,
            Event.WellKnownTypes.SPEC_MESSAGE_POSTED,
            author,
            "host",
            Map.of(Event.WellKnownData.MESSAGE_ID, posted.id())));
  }

  @Test
  void aBuildThatEndsOnItsQuestionWaitsForTheAnswerAndGoesOnWithIt() {
    start(List.of());
    var build = loop.run(SPEC, "build");
    asks(build, QUESTION);

    loop.onEvent(ReviewLoop.buildStop(SPEC, build));

    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus(SPEC));
    assertTrue(loop.reviews.latestReviewForSpec(SPEC).isEmpty(), "nothing is reviewed");
    assertEquals(
        List.of(ReviewNarration.waitsOnAnswer(Lane.BUILD, QUESTION)),
        loop.roomLines(SPEC, "The build ended"));

    says("mo", "Try 25?");
    assertEquals(List.of(), builds, "a reply that never reaches the agent answers nothing");
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus(SPEC));

    says(ReviewLoop.HANDLE, "Use 21.");

    assertEquals(1, builds.size());
    assertTrue(builds.getFirst().contains(QUESTION) && builds.getFirst().contains("Use 21."));
    assertEquals(
        List.of(ReviewNarration.resumed(Lane.BUILD)), loop.roomLines(SPEC, "The build goes on"));
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus(SPEC));
    assertEquals(List.of(), loop.details(Event.WellKnownTypes.SPEC_RESTARTED));
  }

  @Test
  void aQuestionAnsweredWhileTheBuildRanHoldsNothing() {
    start(List.of());
    var build = loop.run(SPEC, "build");
    asks(build, QUESTION);
    loop.messages.append(SPEC, ReviewLoop.HANDLE, "Use 21.", null);

    loop.onEvent(ReviewLoop.buildStop(SPEC, build));

    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus(SPEC));
    assertEquals(List.of(), builds, "no build ran again");
    assertEquals(List.of(), loop.roomLines(SPEC, "The build ended"));
  }

  @Test
  void aFixAgentThatEndsOnItsQuestionWaitsForTheAnswerAndTheFixGoesOn() {
    start(List.of(CRITICAL_FINDING));
    fixes.clear();
    var asking = new AtomicInteger();
    loop.onLaunch(
        runId -> {
          var run = loop.runs.findById(runId).orElseThrow();
          if ("fix".equals(run.role()) && asking.getAndIncrement() == 0) {
            loop.messages.append(
                SPEC, run.principal(), "Keep the old API?", null, MessageStore.Mark.QUESTION);
          }
        });

    loop.onEvent(ReviewLoop.buildStop(SPEC, loop.run(SPEC, "build")));

    assertEquals(1, fixes.size());
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus(SPEC));
    assertEquals("failed", loop.statusOf(loop.reviewOf(SPEC)));
    assertEquals(
        List.of(ReviewNarration.waitsOnAnswer(Lane.FIX, "Keep the old API?")),
        loop.roomLines(SPEC, "The fix agent ended"));

    says(ReviewLoop.HANDLE, "Drop it.");

    assertEquals(2, fixes.size());
    assertTrue(
        fixes.getLast().contains("Keep the old API?") && fixes.getLast().contains("Drop it."));
    assertEquals(
        List.of(ReviewNarration.resumed(Lane.FIX)), loop.roomLines(SPEC, "The fix agent goes on"));
    assertEquals(List.of(), builds, "the fix goes on, not a new build");
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus(SPEC));
  }

  @Test
  void anAnswerAnotherRunsClaimHoldsUpResumesTheBuildOnceThatRunEnds() {
    start(List.of());
    loop.spec("other", "web");
    var build = loop.run(SPEC, "build");
    asks(build, QUESTION);
    loop.onEvent(ReviewLoop.buildStop(SPEC, build));
    var holder = loop.run(null, "adhoc");

    says(ReviewLoop.HANDLE, "Use 21.");

    assertEquals(List.of(), builds, "the build waits for the run that holds its claim");
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus(SPEC));

    loop.onEvent(
        RunStops.of(
            Event.WellKnownData.SOURCE_WATCHER,
            ReviewLoop.PROJECT,
            null,
            "claude-code",
            holder,
            "adhoc",
            0,
            null));

    assertEquals(1, builds.size());
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus(SPEC));
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus("other"), "another room's spec");
  }

  @Test
  void anAnswerTheLoopCannotActOnLeavesTheBuildWaiting() {
    start(List.of());
    var build = loop.run(SPEC, "build");
    asks(build, QUESTION);
    loop.onEvent(ReviewLoop.buildStop(SPEC, build));
    loop.corruptDefinition();

    says(ReviewLoop.HANDLE, "Use 21.");

    assertEquals(List.of(), builds);
    assertEquals(SpecStatus.IN_PROGRESS, loop.specStatus(SPEC));
  }

  @Test
  void aPostThatNamesNoConversationAnswersNothing() {
    start(List.of());
    var build = loop.run(SPEC, "build");
    asks(build, QUESTION);
    loop.onEvent(ReviewLoop.buildStop(SPEC, build));
    loop.messages.append(SPEC, ReviewLoop.HANDLE, "Use 21.", null);

    loop.onEvent(
        Event.of(
            ReviewLoop.PROJECT,
            null,
            Event.WellKnownTypes.SPEC_MESSAGE_POSTED,
            ReviewLoop.HANDLE,
            "host",
            Map.of()));

    assertEquals(List.of(), builds);
  }
}
