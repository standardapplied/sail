/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static ai.singlr.sail.api.ReviewLoop.HANDLE;
import static ai.singlr.sail.api.ReviewLoop.PROJECT;
import static ai.singlr.sail.api.ReviewScripts.CLEAN_REVIEW;
import static ai.singlr.sail.api.ReviewScripts.CRITICAL_FINDING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.Lane;
import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.engine.StageSkillInstaller;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.gen.BuiltInSkills;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The stage-skill contract's scenario matrix (ARCHITECTURE.md, "Stage skills"), through the launch
 * path the server runs: a build dispatched, a reviewer and a fix agent launched by the loop, over
 * the real stores and a fake container that keeps what is pushed into it.
 */
class StageSkillLaunchTest {

  private static final String CLAUDE_SKILLS = "/home/dev/.claude/skills/";
  private static final String CODEX_SKILLS = "/home/dev/.agents/skills/";
  private static final String HEADING = "## How to do this work";

  private static final String ACME_REVIEW =
      """
      ---
      name: acme-review
      description: How acme reviews.
      ---

      Judge it acme's way.
      """;

  @TempDir Path tempDir;
  private ReviewLoop loop;

  @AfterEach
  void tearDown() {
    loop.close();
  }

  private static ReviewPipelineConfig pipeline(String fixSkill, Map<String, Object>... stages) {
    return fixSkill == null
        ? ReviewPipelineConfig.fromMap(Map.of("stages", List.of(stages)))
        : ReviewPipelineConfig.fromMap(Map.of("fix_skill", fixSkill, "stages", List.of(stages)));
  }

  private static Map<String, Object> stage(String name, String agent, String skill) {
    return skill == null
        ? Map.of("name", name, "agent", agent)
        : Map.of("name", name, "agent", agent, "skill", skill);
  }

  private static String defaultBlock(String name, String agent) {
    return StageSkills.block(BuiltInSkills.of(name).orElseThrow(), Harnesses.of(agent));
  }

  private static String stampOf(StageSkill skill) {
    return StageSkillInstaller.fingerprint(skill);
  }

  private String task(String runId) {
    return loop.runs.findById(runId).orElseThrow().task();
  }

  private long pushesFor(String skill) {
    return loop.container.commandsContaining("incus file push").stream()
        .filter(command -> command.contains("/home/dev/.agents/.sail-stage-build-" + skill + "."))
        .count();
  }

  private void pendingSpec(String id, String agent) {
    Acting.system(
        () -> {
          loop.specs.create(
              new SpecStore.SpecRow(
                  id,
                  PROJECT,
                  "Add auth",
                  SpecStatus.PENDING,
                  HANDLE,
                  agent,
                  null,
                  null,
                  null,
                  0,
                  HANDLE,
                  null,
                  null,
                  HANDLE,
                  List.of(),
                  List.of("api")));
          loop.specs.setContent(id, "Do auth.", "");
          if (new FdeStore(loop.db).byHandle(HANDLE).isEmpty()) {
            new FdeStore(loop.db).add(HANDLE, null, null, "admin");
          }
        });
  }

  private static DispatchOperations.Outcome dispatch(
      DispatchOperations operations, String specId, boolean dryRun) {
    return Actor.call(
        Actor.cliOperator(HANDLE),
        () ->
            operations.dispatch(
                PROJECT,
                new DispatchOperations.Request(specId, "background", dryRun, null, false),
                HANDLE));
  }

  private DispatchOperations.Dispatched dispatched(String specId) {
    return assertInstanceOf(
        DispatchOperations.Dispatched.class, dispatch(loop.operations, specId, false));
  }

  private ApiException refusedDispatch(String specId, boolean dryRun) {
    return assertThrows(ApiException.class, () -> dispatch(loop.operations, specId, dryRun));
  }

  @Test
  void aProjectThatConfiguresNothingRunsEveryStageUnderSailsDefaultInstalledForItsHarness() {
    loop = ReviewLoop.wired(tempDir, ReviewLoop.YAML);
    pendingSpec("auth", null);

    var build = dispatched("auth");

    var buildSkill = BuiltInSkills.of(StageSkill.BUILD).orElseThrow();
    assertTrue(
        build
            .task()
            .contains(
                "Do auth.\n\n"
                    + defaultBlock(StageSkill.BUILD, "claude-code")
                    + "\n\n## Autonomous Operation\n"),
        build.task());
    assertEquals(build.task(), task(build.runId()), "the run is recorded with the prompt it got");
    assertEquals(
        Map.of(
            CLAUDE_SKILLS + "sail-build/SKILL.md",
            BuiltInSkills.text(StageSkill.BUILD).orElseThrow(),
            CLAUDE_SKILLS + "sail-build/.sail-skill",
            stampOf(buildSkill)),
        installedUnder(CLAUDE_SKILLS + "sail-build"));
    assertEquals("0644", loop.container.mode(CLAUDE_SKILLS + "sail-build/SKILL.md"));

    loop.finish(build.runId(), "built");
    var reviewer = loop.onlyLive();

    assertEquals("codex", reviewer.agent(), "the roster's other agent reviews");
    assertTrue(
        reviewer
            .task()
            .contains(
                "Focus on these categories: security, correctness\n\n"
                    + defaultBlock(StageSkill.REVIEW, "codex")
                    + "\n\nRespond with exactly one JSON object"),
        reviewer.task());
    assertEquals(
        BuiltInSkills.text(StageSkill.REVIEW).orElseThrow(),
        loop.container.file(CODEX_SKILLS + "sail-review/SKILL.md"));
    assertEquals(
        stampOf(BuiltInSkills.of(StageSkill.REVIEW).orElseThrow()),
        loop.container.file(CODEX_SKILLS + "sail-review/.sail-skill"));
    assertNull(
        loop.container.file(CLAUDE_SKILLS + "sail-review/SKILL.md"),
        "installed for the harness that ran it, and no other");

    loop.finish(reviewer.id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();

    assertEquals("fix", fix.role());
    assertEquals("claude-code", fix.agent());
    assertTrue(
        fix.task()
            .contains(
                "\n\n"
                    + defaultBlock(StageSkill.FIX, "claude-code")
                    + "\n\n--- Finding 1 [CRITICAL] SECURITY ---"),
        fix.task());
    assertEquals(
        BuiltInSkills.text(StageSkill.FIX).orElseThrow(),
        loop.container.file(CLAUDE_SKILLS + "sail-fix/SKILL.md"));
    assertEquals(
        stampOf(BuiltInSkills.of(StageSkill.FIX).orElseThrow()),
        loop.container.file(CLAUDE_SKILLS + "sail-fix/.sail-skill"));
  }

  private Map<String, String> installedUnder(String folder) {
    var files = new TreeMap<String, String>();
    loop.container.filesUnder(folder).forEach(path -> files.put(path, loop.container.file(path)));
    return files;
  }

  @Test
  void theSkillIsInstalledAfterSailsOwnHelpersAndBeforeTheRunsFilesAreStaged() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();

    var commands = loop.container.commands().stream().map(c -> String.join(" ", c)).toList();
    var helpers = indexOf(commands, "incus config device add");
    var stamp = indexOf(commands, CODEX_SKILLS + "sail-review/.sail-skill");
    var placed = indexOf(commands, "mv -T \"$2\" \"$1\"");
    var staged = indexOf(commands, "/home/dev/.sail/runs/" + reviewer.id());

    assertTrue(helpers < stamp, "ensureSailSetup runs first");
    assertTrue(stamp < placed && placed < staged, "and the run's directory is made after");
  }

  private static int indexOf(List<String> commands, String fragment) {
    for (var i = 0; i < commands.size(); i++) {
      if (commands.get(i).contains(fragment)) {
        return i;
      }
    }
    throw new AssertionError("never ran: " + fragment);
  }

  @Test
  void aStageThatNamesAProjectSkillCarriesItsBodyInPlaceOfTheDefaultsAndNothingElseDiffers() {
    loop = ReviewLoop.of(tempDir, pipeline(null, stage("review", "codex", null)));
    loop.built("auth");
    var underDefault = loop.onlyLive().task();
    loop.close();

    loop = ReviewLoop.of(tempDir, pipeline(null, stage("review", "codex", "acme-review")));
    loop.skillFile("acme-review", "SKILL.md", ACME_REVIEW, 0644);
    loop.built("auth");
    var underProject = loop.onlyLive().task();

    assertEquals(
        underDefault.replace(
            defaultBlock(StageSkill.REVIEW, "codex"),
            HEADING + " (skill: acme-review)\n\nJudge it acme's way."),
        underProject);
    assertFalse(underProject.contains("Only report genuine issues."), underProject);
    assertEquals(ACME_REVIEW, loop.container.file(CODEX_SKILLS + "acme-review/SKILL.md"));
  }

  @Test
  void aProjectSkillsOtherFilesAreInstalledWithTheirModesAndThePromptSaysWhere() {
    loop =
        ReviewLoop.of(
            tempDir,
            pipeline(
                "acme-review",
                stage("first", "claude-code", "acme-review"),
                stage("second", "codex", "acme-review")));
    loop.skillFile("acme-review", "SKILL.md", ACME_REVIEW, 0644);
    loop.skillFile("acme-review", "scripts/check.sh", "#!/bin/sh\nmvn -q verify\n", 0755);
    loop.skillFile("acme-review", "reference/rules.md", "Rules.\n", 0640);
    loop.built("auth");

    var first = loop.onlyLive();

    assertTrue(
        first
            .task()
            .contains(
                "Judge it acme's way.\n\nThe skill's other files are in"
                    + " ~/.claude/skills/acme-review/.\n\nRespond with exactly one JSON object"),
        first.task());
    assertEquals(
        List.of(
            CLAUDE_SKILLS + "acme-review/.sail-skill",
            CLAUDE_SKILLS + "acme-review/SKILL.md",
            CLAUDE_SKILLS + "acme-review/reference/rules.md",
            CLAUDE_SKILLS + "acme-review/scripts/check.sh"),
        loop.container.filesUnder(CLAUDE_SKILLS + "acme-review"));
    assertEquals(
        "#!/bin/sh\nmvn -q verify\n",
        loop.container.file(CLAUDE_SKILLS + "acme-review/scripts/check.sh"));
    assertEquals("0755", loop.container.mode(CLAUDE_SKILLS + "acme-review/scripts/check.sh"));
    assertEquals("0640", loop.container.mode(CLAUDE_SKILLS + "acme-review/reference/rules.md"));
    assertEquals("0644", loop.container.mode(CLAUDE_SKILLS + "acme-review/SKILL.md"));
    assertTrue(loop.container.filesUnder(CODEX_SKILLS + "acme-review").isEmpty());

    loop.finish(first.id(), CLEAN_REVIEW);
    var second = loop.onlyLive();

    assertEquals("codex", second.agent());
    assertTrue(
        second.task().contains("The skill's other files are in ~/.agents/skills/acme-review/."),
        "a stage Codex runs names Codex's folder: " + second.task());
    assertEquals(
        List.of(
            CODEX_SKILLS + "acme-review/.sail-skill",
            CODEX_SKILLS + "acme-review/SKILL.md",
            CODEX_SKILLS + "acme-review/reference/rules.md",
            CODEX_SKILLS + "acme-review/scripts/check.sh"),
        loop.container.filesUnder(CODEX_SKILLS + "acme-review"));
    assertEquals("0755", loop.container.mode(CODEX_SKILLS + "acme-review/scripts/check.sh"));
  }

  @Test
  void theSameSkillLaunchedTwiceIsPushedOnceAndAChangedOneIsReplacedWhole() {
    loop =
        ReviewLoop.of(
            tempDir,
            pipeline(
                null,
                stage("first", "codex", "acme-review"),
                stage("second", "codex", "acme-review"),
                stage("third", "codex", "acme-review")));
    loop.skillFile("acme-review", "SKILL.md", ACME_REVIEW, 0644);
    loop.skillFile("acme-review", "old.md", "Old.\n", 0644);
    var folder = CODEX_SKILLS + "acme-review";
    loop.built("auth");
    var first = loop.onlyLive();

    assertEquals(2, pushesFor("acme-review"));

    loop.finish(first.id(), CLEAN_REVIEW);
    var second = loop.onlyLive();

    assertEquals(2, pushesFor("acme-review"), "the second launch of the same skill writes no file");

    loop.skillFile("acme-review", "SKILL.md", ACME_REVIEW.replace("acme's way", "a new way"), 0644);
    loop.skillFile("acme-review", "new.md", "New.\n", 0644);
    Acting.system(() -> loop.files.delete(PROJECT, ".sail/skills/acme-review/old.md"));
    loop.finish(second.id(), CLEAN_REVIEW);
    var third = loop.onlyLive();

    assertTrue(third.task().contains("Judge it a new way."), third.task());
    assertEquals(
        List.of(folder + "/.sail-skill", folder + "/SKILL.md", folder + "/new.md"),
        loop.container.filesUnder(folder),
        "the folder holds exactly the new files");
    assertEquals(
        ACME_REVIEW.replace("acme's way", "a new way"), loop.container.file(folder + "/SKILL.md"));
    assertEquals(
        List.of(folder + "/.sail-skill", folder + "/SKILL.md", folder + "/new.md"),
        loop.container.filesUnder(CODEX_SKILLS + "acme-review").stream().sorted().toList());
    assertTrue(
        loop.container.filesUnder(CODEX_SKILLS).stream()
            .allMatch(path -> path.startsWith(folder + "/")),
        "and no build folder is left beside it: " + loop.container.filesUnder(CODEX_SKILLS));
  }

  @Test
  void aBuildWhoseSkillTheProjectDoesNotHoldIsRefusedBeforeAnythingIsReservedOrClaimed() {
    loop =
        ReviewLoop.wired(
            tempDir, ReviewLoop.YAML.replace("agent:\n", "agent:\n  build_skill: acme-build\n"));
    pendingSpec("auth", null);

    for (var dryRun : List.of(true, false)) {
      var refused = refusedDispatch("auth", dryRun);

      assertEquals(ErrorCode.BAD_REQUEST, refused.failure().errorCode());
      assertEquals(
          "Skill 'acme-build' of project 'test-project' cannot be read:"
              + " .sail/skills/acme-build/SKILL.md is missing or is not a text file. Add it with"
              + " 'sail project files add <file> --as .sail/skills/acme-build/SKILL.md'.",
          refused.getMessage());
      assertNull(refused.getCause());
    }
    assertEquals(SpecStatus.PENDING, loop.specStatus("auth"));
    assertTrue(loop.runs.listForProject(PROJECT).isEmpty());
    assertTrue(loop.container.launched().isEmpty());

    loop.skillFile("acme-build", "SKILL.md", "Build it acme's way.\n", 0644);
    var build = dispatched("auth");

    assertTrue(
        build.task().contains(HEADING + " (skill: acme-build)\n\nBuild it acme's way.\n\n## Auto"),
        build.task());
    assertFalse(build.task().contains("Execute without waiting for confirmation"), build.task());
    assertEquals(
        "Build it acme's way.\n", loop.container.file(CLAUDE_SKILLS + "acme-build/SKILL.md"));
  }

  @Test
  void aBuildWhoseSkillIsOverItsBoundsIsRefusedNamingTheLimit() {
    loop =
        ReviewLoop.wired(
            tempDir, ReviewLoop.YAML.replace("agent:\n", "agent:\n  build_skill: acme-build\n"));
    pendingSpec("auth", null);
    loop.skillFile("acme-build", "SKILL.md", "x".repeat(32_001), 0644);

    var refused = refusedDispatch("auth", false);

    assertEquals(ErrorCode.BAD_REQUEST, refused.failure().errorCode());
    assertEquals(
        "Skill 'acme-build' of project 'test-project' is refused: Skill 'acme-build' has a body of"
            + " 32001 code points; the limit is 32000.",
        refused.getMessage());
    assertEquals(SpecStatus.PENDING, loop.specStatus("auth"));
  }

  @Test
  void aBuildForAHarnessSailDoesNotKnowIsABadRequestBeforeTheClaim() {
    loop = ReviewLoop.wired(tempDir, ReviewLoop.YAML);
    pendingSpec("auth", "gemini");

    var refused = refusedDispatch("auth", false);

    assertEquals(ErrorCode.BAD_REQUEST, refused.failure().errorCode());
    assertEquals(
        "Unknown agent CLI: 'gemini'. Known agents: claude-code, codex.\n  Check the 'install'"
            + " list in your sail.yaml agent section.",
        refused.getMessage());
    assertEquals(SpecStatus.PENDING, loop.specStatus("auth"));
    assertTrue(loop.runs.listForProject(PROJECT).isEmpty());
  }

  @Test
  void aBuildWithNoAgentNamedAnywhereIsABadRequestSayingWhatToSet() {
    loop = ReviewLoop.wired(tempDir, ReviewLoop.YAML.replace("  type: claude-code\n", ""));
    pendingSpec("auth", null);
    pendingSpec("blank", " ");

    for (var spec : List.of("auth", "blank")) {
      var refused = refusedDispatch(spec, false);

      assertEquals(ErrorCode.BAD_REQUEST, refused.failure().errorCode());
      assertEquals(
          "Project 'test-project' names no agent; set agent.type or the spec's agent.",
          refused.getMessage());
      assertEquals(SpecStatus.PENDING, loop.specStatus(spec));
    }
  }

  @Test
  void aReviewerWhoseSkillIsMissingErrorsTheReviewThreeTimesAndThenEscalates() {
    loop = ReviewLoop.of(tempDir, pipeline(null, stage("review", "codex", "acme-review")));
    var missing =
        "reviewer could not start: Skill 'acme-review' of project 'test-project' cannot be read:"
            + " .sail/skills/acme-review/SKILL.md is missing or is not a text file. Add it with"
            + " 'sail project files add <file> --as .sail/skills/acme-review/SKILL.md'.";
    loop.built("auth");
    var reconciler = loop.reconciler(ReviewLoop.LATER);

    for (var attempt = 1; attempt <= LoopDecision.MAX_ERRORED_RETRIES; attempt++) {
      assertEquals(attempt, loop.details("review_errored").size());
      assertEquals(missing, loop.details("review_errored").getLast());
      assertEquals("failed", loop.statusOf(loop.reviewOf("auth")));
      assertTrue(loop.live().isEmpty(), "no reviewer started");

      assertEquals(1, reconciler.sweep());
      loop.settle();
    }

    assertEquals(LoopDecision.MAX_ERRORED_RETRIES, loop.details("review_errored").size());
    assertEquals("escalated", loop.statusOf(loop.reviewOf("auth")));
    assertEquals(1, loop.details("review_escalated").size());
    assertTrue(
        loop.details("review_escalated")
            .getFirst()
            .startsWith("3 review attempts errored in a row"),
        loop.details("review_escalated").getFirst());
    assertTrue(loop.runs.forReview(loop.reviewOf("auth")).isEmpty(), "and none was ever reserved");
    assertTrue(loop.container.filesUnder(CODEX_SKILLS).isEmpty());
  }

  @Test
  void aFixAgentWhoseSkillIsMissingEscalatesAtOnce() {
    loop = ReviewLoop.of(tempDir, pipeline("acme-fix", stage("review", "codex", null)));
    loop.built("auth");

    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);

    assertTrue(loop.live().isEmpty(), "no fix agent started");
    assertEquals("escalated", loop.statusOf(loop.reviewOf("auth")));
    assertEquals(1, loop.details("review_escalated").size());
    assertEquals(
        List.of(
            "fix iteration failed — fix agent could not start: Skill 'acme-fix' of project"
                + " 'test-project' cannot be read: .sail/skills/acme-fix/SKILL.md is missing or is"
                + " not a text file. Add it with 'sail project files add <file> --as"
                + " .sail/skills/acme-fix/SKILL.md'.; triage and re-dispatch"),
        loop.details("review_escalated"));
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"));
  }

  @Test
  void aPipelineBlockWithOnlyAFixSkillRunsTheDefaultStagesAndFixesUnderThatSkill() {
    loop =
        ReviewLoop.wired(
            tempDir, ReviewLoop.YAML + "  review_pipeline:\n    fix_skill: acme-fix\n");
    loop.skillFile("acme-fix", "SKILL.md", "Fix it acme's way.\n", 0644);
    loop.built("auth");
    var reviewer = loop.onlyLive();

    assertTrue(reviewer.task().contains(defaultBlock(StageSkill.REVIEW, "codex")));
    assertTrue(reviewer.task().contains("Focus on these categories: security, correctness"));

    loop.finish(reviewer.id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();

    assertEquals("fix", fix.role());
    assertTrue(
        fix.task().contains(HEADING + " (skill: acme-fix)\n\nFix it acme's way.\n\n--- Finding 1"),
        fix.task());
    assertFalse(fix.task().contains("Fix what is real."), fix.task());
    assertEquals("Fix it acme's way.\n", loop.container.file(CLAUDE_SKILLS + "acme-fix/SKILL.md"));
  }

  @Test
  void aVerdictUnderASkillThatAsksForProseIsStillReadByTheVerdictContract() {
    loop = ReviewLoop.of(tempDir, pipeline(null, stage("review", "codex", "prose")));
    loop.skillFile("prose", "SKILL.md", "Answer in prose, never in JSON.\n", 0644);
    loop.built("auth");
    var reviewer = loop.onlyLive();

    assertTrue(
        reviewer.task().indexOf("Answer in prose, never in JSON.")
            < reviewer.task().indexOf("Respond with exactly one JSON object"),
        "the contract follows the skill, so the skill does not have the last word");
    assertTrue(reviewer.task().endsWith("Begin the JSON object with ```json and end with ```.\n"));

    loop.finish(reviewer.id(), "It all looks fine to me.");

    assertEquals(1, loop.details("review_errored").size(), "prose is no verdict, as ever");
    assertEquals("failed", loop.statusOf(loop.reviewOf("auth")));
    assertTrue(loop.reviews.reviewsForSpec("auth").getFirst().error() != null);
  }

  @Test
  void aVerdictInTheEnvelopeUnderThatSkillPassesTheReview() {
    loop = ReviewLoop.of(tempDir, pipeline(null, stage("review", "codex", "prose")));
    loop.skillFile("prose", "SKILL.md", "Answer in prose, never in JSON.\n", 0644);
    loop.built("auth");

    loop.finish(loop.onlyLive().id(), CLEAN_REVIEW);

    assertEquals("passed", loop.statusOf(loop.reviewOf("auth")));
    assertEquals(SpecStatus.AWAITING_MERGE, loop.specStatus("auth"));
  }

  @Test
  void aSkillFileThatChangesBetweenItsResolveAndItsPushFailsTheLaunchAndStampsNothing() {
    loop = ReviewLoop.of(tempDir, pipeline(null, stage("review", "codex", "acme-review")));
    loop.skillFile("acme-review", "SKILL.md", ACME_REVIEW, 0644);
    loop.skillFile("acme-review", "reference/rules.md", "Rules.\n", 0644);
    var once = new AtomicBoolean(true);
    loop.container.beforeHost(
        "file push",
        () -> {
          if (once.compareAndSet(true, false)) {
            loop.skillFile("acme-review", "reference/rules.md", "Other rules.\n", 0644);
          }
        });

    loop.built("auth");

    assertTrue(loop.live().isEmpty(), "no reviewer started");
    assertEquals(
        List.of(
            "reviewer could not start: Skill 'acme-review' of project 'test-project' changed while"
                + " it was being installed: .sail/skills/acme-review/reference/rules.md is no longer"
                + " the file that was resolved. Launch again."),
        loop.details("review_errored"));
    assertTrue(
        loop.container.filesUnder(CODEX_SKILLS).isEmpty(),
        "nothing half-installed is left, stamped or not: "
            + loop.container.filesUnder(CODEX_SKILLS));
    assertTrue(
        loop.runs.forReview(loop.reviewOf("auth")).stream()
            .noneMatch(run -> "running".equals(run.status())),
        "and the run the launch reserved is released");

    assertEquals(1, loop.reconciler(ReviewLoop.LATER).sweep());
    loop.settle();

    var retried = loop.onlyLive();
    assertEquals(
        "Other rules.\n", loop.container.file(CODEX_SKILLS + "acme-review/reference/rules.md"));
    assertTrue(retried.task().contains("Judge it acme's way."));
  }

  @Test
  void anInstallTheContainerRefusesFailsTheLaunchSayingWhichSkillAndWhatToDo() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("auth", "api");
    loop.container.failing("mv -T \"$2\" \"$1\"", "No space left on device");
    var invocation =
        new ReviewLanes.Invocation(
            Lane.REVIEW,
            DateTimeUtils.newId().toString(),
            PROJECT,
            "auth",
            "codex",
            "review it",
            "feat/test",
            List.of("api"),
            null,
            null,
            List.of(),
            BuiltInSkills.of(StageSkill.REVIEW).orElseThrow());

    var failed =
        assertThrows(
            ApiException.class,
            () ->
                Acting.system(
                    () -> loop.operations.reviewLanes().launch(invocation, HANDLE, () -> {})));

    assertEquals(ErrorCode.AGENT_LAUNCH_FAILED, failed.failure().errorCode());
    assertEquals("Failed to install skill 'sail-review' in test-project.", failed.getMessage());
    assertEquals("Check the container is running and retry.", failed.failure().action());
    assertEquals(
        "Failed to put in place skill 'sail-review' in test-project: No space left on device",
        failed.getCause().getMessage());
    assertTrue(loop.container.launched().isEmpty(), "no agent was started");
  }

  @Test
  void anAdhocRunAndARoomTurnCarryNoSkillAndInstallNone() {
    loop = ReviewLoop.wired(tempDir, ReviewLoop.YAML);
    pendingSpec("auth", null);

    var adhoc =
        Actor.call(
            Actor.cliOperator(HANDLE),
            () ->
                loop.operations.startAdhoc(
                    PROJECT,
                    new DispatchOperations.AdhocRequest(
                        "tidy the imports", null, null, true, false),
                    HANDLE));

    assertEquals("tidy the imports", task(adhoc.runId()));
    loop.finish(adhoc.runId(), "tidied");

    var room =
        Actor.call(
            Actor.cliOperator(HANDLE), () -> loop.operations.startRoomRun(PROJECT, "auth", HANDLE));

    assertEquals("room", loop.runs.findById(room).orElseThrow().role());
    assertFalse(task(room).contains(HEADING), task(room));
    assertTrue(
        loop.container.commands().stream()
            .map(command -> String.join(" ", command))
            .noneMatch(command -> command.contains("/skills/")),
        "neither launch read, built or stamped a skill folder");
  }

  @Test
  void aBoxWithNoProjectFilesWiredLaunchesSailsOwnSkillAndRefusesAProjects() {
    loop = ReviewLoop.wired(tempDir, ReviewLoop.YAML);
    pendingSpec("auth", null);
    pendingSpec("billing", null);
    var unwired =
        new DispatchOperations(
                loop.container,
                ProjectReader.ofCatalog(new ProjectStore(loop.db)),
                loop.specs,
                loop.reviews,
                loop.runs,
                new FdeStore(loop.db),
                loop.bus::publish,
                new WatcherSpawner(loop.container, null),
                (project, descriptor) -> "",
                command -> {
                  loop.container.started(command.get(command.size() - 3));
                  return 0;
                },
                DispatchOperations.Listener.NONE,
                SessionYield.NONE)
            .useMessages(loop.messages)
            .useRooms(new RoomStore(loop.db));

    var build =
        assertInstanceOf(DispatchOperations.Dispatched.class, dispatch(unwired, "auth", false));

    assertTrue(build.task().contains(defaultBlock(StageSkill.BUILD, "claude-code")));
    assertEquals(
        BuiltInSkills.text(StageSkill.BUILD).orElseThrow(),
        loop.container.file(CLAUDE_SKILLS + "sail-build/SKILL.md"));
    loop.finish(build.runId(), "built");
    loop.skillFile("acme-build", "SKILL.md", "Build it acme's way.\n", 0644);
    loop.describe(ReviewLoop.YAML.replace("agent:\n", "agent:\n  build_skill: acme-build\n"));

    var refused = assertThrows(ApiException.class, () -> dispatch(unwired, "billing", false));

    assertEquals(ErrorCode.BAD_REQUEST, refused.failure().errorCode());
    assertEquals(
        "Skill 'acme-build' of project 'test-project' cannot be read: this box has no project"
            + " files wired.",
        refused.getMessage());
    assertEquals(SpecStatus.PENDING, loop.specStatus("billing"));
    assertThrows(NullPointerException.class, () -> unwired.useStageSkills(null));
  }

  @Test
  void aBuildsAndAFixAgentsSkillWithOtherFilesNameTheFolderOfTheHarnessThatRunsThem() {
    loop =
        ReviewLoop.wired(
            tempDir,
            ReviewLoop.YAML.replace("agent:\n", "agent:\n  build_skill: acme-build\n")
                + "  review_pipeline:\n    fix_skill: acme-fix\n");
    pendingSpec("auth", "codex");
    loop.skillFile("acme-build", "SKILL.md", "Build it acme's way.\n", 0644);
    loop.skillFile("acme-build", "notes.md", "Notes.\n", 0644);
    loop.skillFile("acme-fix", "SKILL.md", "Fix it acme's way.\n", 0644);
    loop.skillFile("acme-fix", "checklist.md", "Checklist.\n", 0644);

    var build = dispatched("auth");

    assertTrue(
        build
            .task()
            .contains(
                "Build it acme's way.\n\nThe skill's other files are in"
                    + " ~/.agents/skills/acme-build/.\n\n## Autonomous"),
        build.task());
    assertEquals("Notes.\n", loop.container.file(CODEX_SKILLS + "acme-build/notes.md"));
    assertTrue(loop.container.filesUnder(CLAUDE_SKILLS).isEmpty());

    loop.finish(build.runId(), "built");
    loop.finish(loop.onlyLive().id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();

    assertEquals("fix", fix.role());
    assertEquals("codex", fix.agent());
    assertTrue(
        fix.task()
            .contains(
                "Fix it acme's way.\n\nThe skill's other files are in ~/.agents/skills/acme-fix/.\n\n"
                    + "--- Finding 1"),
        fix.task());
    assertEquals("Checklist.\n", loop.container.file(CODEX_SKILLS + "acme-fix/checklist.md"));
  }

  @Test
  void theRunOfAStageIsNeverLeftRunningByASkillThatCouldNotBeRead() {
    loop = ReviewLoop.of(tempDir, pipeline(null, stage("review", "codex", "acme-review")));
    loop.built("auth");

    assertTrue(
        loop.runs.listForProject(PROJECT).stream()
            .map(RunStore.RunRow::role)
            .noneMatch("review"::equals),
        "the skill is read before the reviewer's run is reserved");
  }
}
