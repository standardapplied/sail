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

import ai.singlr.sail.config.ReviewPipelineConfig;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.engine.JudgePrompt;
import ai.singlr.sail.engine.ProjectSkillInstaller;
import ai.singlr.sail.engine.StageSkill;
import ai.singlr.sail.engine.WorkPrompt;
import ai.singlr.sail.gen.SpecSkillGenerator;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.SpecStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two prompts and the skills a launch installs, driven through the production launch path over
 * the real stores and a fake container that keeps what is pushed into it: a build, a reviewer and a
 * fix agent of a project that configures nothing, a stage with a brief, and project skills.
 */
class ProjectSkillLaunchTest {

  private static final String CLAUDE_SKILLS = "/home/dev/.claude/skills/";
  private static final String CODEX_SKILLS = "/home/dev/.agents/skills/";
  private static final String HOW = "\n## How this run works\n";

  private static final String E2E =
      """
      ---
      name: e2e
      description: Runs the e2e suite.
      ---

      Run the suite.
      """;

  @TempDir Path tempDir;
  private ReviewLoop loop;

  @AfterEach
  void tearDown() {
    loop.close();
  }

  private static ReviewPipelineConfig pipeline(Map<String, Object>... stages) {
    return ReviewPipelineConfig.fromMap(Map.of("stages", List.of(stages)));
  }

  private static String stampOf(StageSkill skill) {
    return ProjectSkillInstaller.fingerprint(skill);
  }

  private String task(String runId) {
    return loop.runs.findById(runId).orElseThrow().task();
  }

  /**
   * How many times {@code skill} was built beside {@code skillsDir}'s harness home: its manifest is
   * pushed once per build.
   */
  private long pushesFor(String skillsDir, String skill) {
    var home = skillsDir.substring(0, skillsDir.indexOf("/skills/") + 1);
    return loop.container.commandsContaining("incus file push").stream()
        .filter(command -> command.contains(home + ".sail-stage-build-" + skill + "."))
        .filter(command -> command.endsWith("/SKILL.md"))
        .count();
  }

  private static void assertNamesNoSkill(String task) {
    assertFalse(task.contains("spec-board"), task);
    assertFalse(task.contains("## How to do this work"), task);
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

  private DispatchOperations.Dispatched dispatched(String specId) {
    return assertInstanceOf(
        DispatchOperations.Dispatched.class,
        Actor.call(
            Actor.cliOperator(HANDLE),
            () ->
                loop.operations.dispatch(
                    PROJECT,
                    new DispatchOperations.Request(specId, "background", false, null, false),
                    HANDLE)));
  }

  private Map<String, String> installedUnder(String folder) {
    var files = new TreeMap<String, String>();
    loop.container.filesUnder(folder).forEach(path -> files.put(path, loop.container.file(path)));
    return files;
  }

  @Test
  void aProjectThatConfiguresNothingRunsTheLoopUnderTheTwoPromptsAndSailsSkill() {
    loop = ReviewLoop.wired(tempDir, ReviewLoop.YAML);
    pendingSpec("auth", null);

    var build = dispatched("auth");

    var spec = loop.specs.findById("auth").orElseThrow().toSpec();
    assertEquals(
        WorkPrompt.build(spec, "Do auth.", List.of(), List.of()).prompt(),
        build.task(),
        "the build's prompt is the work prompt whole");
    assertEquals(build.task(), task(build.runId()), "the run is recorded with the prompt it got");
    var specBoard = SpecSkillGenerator.skill();
    assertEquals(
        Map.of(
            CLAUDE_SKILLS + "spec-board/SKILL.md",
            SpecSkillGenerator.skillMd(),
            CLAUDE_SKILLS + "spec-board/.sail-skill",
            stampOf(specBoard)),
        Map.of(
            CLAUDE_SKILLS + "spec-board/SKILL.md",
            loop.container.file(CLAUDE_SKILLS + "spec-board/SKILL.md"),
            CLAUDE_SKILLS + "spec-board/.sail-skill",
            loop.container.file(CLAUDE_SKILLS + "spec-board/.sail-skill")),
        "spec-board is installed for the build's harness");
    assertEquals("0644", loop.container.mode(CLAUDE_SKILLS + "spec-board/SKILL.md"));
    assertNull(loop.container.file("/home/dev/.claude/CLAUDE.md"), "no context file is written");
    assertTrue(
        loop.container.commandsContaining("incus file push").stream()
            .noneMatch(command -> command.contains("CLAUDE.md") || command.contains("AGENTS.md")),
        "nothing writes a context file");

    loop.finish(build.runId(), "built");
    var reviewer = loop.onlyLive();

    assertEquals("codex", reviewer.agent(), "the roster's other agent reviews");
    var judge =
        JudgePrompt.build(
                spec, "Do auth.", "review", JudgePrompt.DEFAULT_BRIEF, List.of(), List.of())
            .prompt();
    assertEquals(judge, reviewer.task(), "the judge's prompt is the judge prompt whole");
    assertEquals(
        SpecSkillGenerator.skillMd(), loop.container.file(CODEX_SKILLS + "spec-board/SKILL.md"));
    assertEquals(stampOf(specBoard), loop.container.file(CODEX_SKILLS + "spec-board/.sail-skill"));

    loop.finish(reviewer.id(), CRITICAL_FINDING);
    var fix = loop.onlyLive();

    assertEquals("fix", fix.role());
    assertEquals("claude-code", fix.agent());
    var findings = loop.reviews.openFindingsForReview(reviewer.reviewId());
    assertEquals(1, findings.size());
    assertEquals(
        WorkPrompt.build(spec, "Do auth.", loop.messages.list("auth", null, 20), findings).prompt(),
        fix.task(),
        "the fix's prompt is the work prompt with the findings section");
    assertTrue(fix.task().contains("## Review findings on your branch\n"), fix.task());
    assertEquals(
        build.task().substring(build.task().indexOf(HOW)),
        fix.task().substring(fix.task().indexOf(HOW)),
        "a fix run ends exactly as a build does");
    assertNamesNoSkill(build.task());
    assertNamesNoSkill(reviewer.task());
    assertNamesNoSkill(fix.task());
  }

  private ApiException refusedDispatch(String specId) {
    return assertThrows(
        ApiException.class,
        () ->
            Actor.call(
                Actor.cliOperator(HANDLE),
                () ->
                    loop.operations.dispatch(
                        PROJECT,
                        new DispatchOperations.Request(specId, "background", false, null, false),
                        HANDLE)));
  }

  @Test
  void aBuildForAHarnessSailDoesNotKnowIsABadRequestBeforeTheClaim() {
    loop = ReviewLoop.wired(tempDir, ReviewLoop.YAML);
    pendingSpec("auth", "gemini");

    var refused = refusedDispatch("auth");

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
      var refused = refusedDispatch(spec);

      assertEquals(ErrorCode.BAD_REQUEST, refused.failure().errorCode());
      assertEquals(
          "Project 'test-project' names no agent; set agent.type or the spec's agent.",
          refused.getMessage());
      assertEquals(SpecStatus.PENDING, loop.specStatus(spec));
    }
  }

  @Test
  void theSkillsAreInstalledAfterSailsOwnHelpersAndBeforeTheRunsFilesAreStaged() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.built("auth");
    var reviewer = loop.onlyLive();

    var commands = loop.container.commands().stream().map(c -> String.join(" ", c)).toList();
    var helpers = indexOf(commands, "incus config device add");
    var stamp = indexOf(commands, CODEX_SKILLS + "spec-board/.sail-skill");
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
  void aStageWithABriefJudgesUnderItWhereTheDefaultWentAndNothingElseDiffers() {
    loop = ReviewLoop.of(tempDir, pipeline(Map.of("name", "review", "agent", "codex")));
    loop.built("auth");
    var underDefault = loop.onlyLive().task();
    loop.close();

    var brief = "Run the project's e2e skill against this branch and report every failure.";
    loop =
        ReviewLoop.of(
            tempDir, pipeline(Map.of("name", "review", "agent", "codex", "brief", brief)));
    loop.built("auth");
    var underBrief = loop.onlyLive().task();

    assertEquals(underDefault.replace(JudgePrompt.DEFAULT_BRIEF, brief), underBrief);
  }

  @Test
  void aProjectSkillIsInstalledWholeForTheLaunchingHarnessAndNeverNamedInThePrompt() {
    loop = ReviewLoop.staged(tempDir, "claude-code");
    loop.skillFile("e2e", "SKILL.md", E2E, 0644);
    loop.skillFile("e2e", "scripts/run.sh", "#!/bin/sh\nnpm run e2e\n", 0755);
    loop.skillFile("e2e", "reference/cases.md", "Cases.\n", 0640);
    loop.built("auth");

    var reviewer = loop.onlyLive();

    assertFalse(reviewer.task().contains("e2e"), reviewer.task());
    assertNamesNoSkill(reviewer.task());
    assertEquals(
        List.of(
            CLAUDE_SKILLS + "e2e/.sail-skill",
            CLAUDE_SKILLS + "e2e/SKILL.md",
            CLAUDE_SKILLS + "e2e/reference/cases.md",
            CLAUDE_SKILLS + "e2e/scripts/run.sh",
            CLAUDE_SKILLS + "spec-board/.sail-skill",
            CLAUDE_SKILLS + "spec-board/SKILL.md",
            CLAUDE_SKILLS + "spec-board/spec-template.md"),
        loop.container.filesUnder("/home/dev/.claude/skills"));
    assertEquals(
        "#!/bin/sh\nnpm run e2e\n", loop.container.file(CLAUDE_SKILLS + "e2e/scripts/run.sh"));
    assertEquals("0755", loop.container.mode(CLAUDE_SKILLS + "e2e/scripts/run.sh"));
    assertEquals("0640", loop.container.mode(CLAUDE_SKILLS + "e2e/reference/cases.md"));
    assertTrue(loop.container.filesUnder(CODEX_SKILLS + "e2e").isEmpty(), "and no other harness");
  }

  @Test
  void aSkillLaunchedTwiceIsPushedOnceAChangedOneIsReplacedWholeAndARemovedOneIsRemoved() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.skillFile("e2e", "SKILL.md", E2E, 0644);
    loop.skillFile("e2e", "reference/old.md", "Old.\n", 0644);
    loop.built("auth");
    var first = loop.onlyLive();
    loop.finish(first.id(), CLEAN_REVIEW);
    loop.built("auth2");
    var second = loop.onlyLive();

    assertEquals("review", second.role());
    assertEquals(
        1,
        pushesFor(CODEX_SKILLS, "e2e"),
        "the second reviewer finds its stamp and pushes nothing");
    assertEquals(1, pushesFor(CODEX_SKILLS, "spec-board"));

    loop.finish(second.id(), CLEAN_REVIEW);
    loop.skillFile("e2e", "SKILL.md", E2E.replace("Run the suite.", "Run it twice."), 0644);
    Acting.system(() -> loop.files.delete(PROJECT, ".sail/skills/e2e/reference/old.md"));
    loop.built("auth3");
    var third = loop.onlyLive();

    assertEquals(2, pushesFor(CODEX_SKILLS, "e2e"), "a changed skill is built again");
    assertEquals(
        List.of(CODEX_SKILLS + "e2e/.sail-skill", CODEX_SKILLS + "e2e/SKILL.md"),
        loop.container.filesUnder(CODEX_SKILLS + "e2e"),
        "replaced whole: the removed file is gone with the old folder");
    assertTrue(loop.container.file(CODEX_SKILLS + "e2e/SKILL.md").contains("Run it twice."));

    loop.finish(third.id(), CLEAN_REVIEW);
    Acting.system(() -> loop.files.delete(PROJECT, ".sail/skills/e2e/SKILL.md"));
    loop.container.wroteFile(CODEX_SKILLS + "mine/SKILL.md", "an engineer's own skill");
    loop.built("auth4");

    assertTrue(loop.container.filesUnder(CODEX_SKILLS + "e2e").isEmpty(), "rm e2e removes it");
    assertEquals(
        "an engineer's own skill",
        loop.container.file(CODEX_SKILLS + "mine/SKILL.md"),
        "a folder without a stamp is not sail's and is left alone");
    assertEquals(
        SpecSkillGenerator.skillMd(), loop.container.file(CODEX_SKILLS + "spec-board/SKILL.md"));
  }

  @Test
  void aFolderThatIsNoSkillIsSkippedTheOthersInstallAndTheRoomIsToldOnce() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.skillFile("e2e", "SKILL.md", E2E, 0644);
    loop.skillFile("broken", "notes.md", "no manifest\n", 0644);
    loop.built("auth");
    var reviewer = loop.onlyLive();

    assertEquals("review", reviewer.role(), "the launch went ahead");
    assertEquals(E2E, loop.container.file(CODEX_SKILLS + "e2e/SKILL.md"));
    assertTrue(loop.container.filesUnder(CODEX_SKILLS + "broken").isEmpty());
    assertEquals(
        List.of(
            "Not installed for this run: skill folder 'broken' skipped:"
                + " .sail/skills/broken/SKILL.md is missing or is not a text file."),
        loop.roomLines("auth", "Not installed"));
  }

  @Test
  void aSkillFileWhoseContentIsNotOnThisBoxFailsTheLaunchNamingIt() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.skillFile("e2e", "SKILL.md", E2E, 0644);
    loop.skillFile("e2e", "scripts/run.sh", "#!/bin/sh\n", 0755);
    loop.db.execute(
        "DELETE FROM blobs WHERE hash = ?",
        loop.files.find(PROJECT, ".sail/skills/e2e/scripts/run.sh").orElseThrow().contentHash());
    loop.built("auth");

    assertTrue(loop.live().isEmpty(), "no reviewer started");
    var why = loop.details("review_errored").getFirst();
    assertTrue(
        why.contains(
            "Skill 'e2e' of project 'test-project' cannot be read: the content of"
                + " .sail/skills/e2e/scripts/run.sh is not on this box ("),
        why);
    assertTrue(loop.container.filesUnder(CODEX_SKILLS + "e2e").isEmpty(), "and nothing landed");
  }

  @Test
  void aVerdictUnderABriefThatAsksForProseIsStillReadByTheVerdictContract() {
    loop =
        ReviewLoop.of(
            tempDir,
            pipeline(
                Map.of(
                    "name",
                    "review",
                    "agent",
                    "codex",
                    "brief",
                    "Answer in prose, never in JSON.")));
    loop.built("auth");
    var reviewer = loop.onlyLive();

    assertTrue(
        reviewer.task().indexOf("Answer in prose, never in JSON.")
            < reviewer.task().indexOf("Respond with exactly one JSON object"),
        "the contract follows the brief, so the brief does not have the last word");
    assertTrue(reviewer.task().endsWith("Begin the JSON object with ```json and end with ```.\n"));

    loop.finish(reviewer.id(), "It all looks fine to me.");

    assertEquals(1, loop.details("review_errored").size(), "prose is no verdict, as ever");
    assertEquals("failed", loop.statusOf(loop.reviewOf("auth")));
    assertTrue(loop.reviews.reviewsForSpec("auth").getFirst().error() != null);
  }

  @Test
  void anAdhocRunAndARoomTurnInstallSailsSkillAndNoPromptNamesIt() {
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
    assertEquals(
        SpecSkillGenerator.skillMd(), loop.container.file(CLAUDE_SKILLS + "spec-board/SKILL.md"));
    loop.finish(adhoc.runId(), "tidied");

    var room =
        Actor.call(
            Actor.cliOperator(HANDLE), () -> loop.operations.startRoomRun(PROJECT, "auth", HANDLE));

    assertEquals("room", loop.runs.findById(room).orElseThrow().role());
    assertFalse(task(room).contains("spec-board"), task(room));
    assertEquals(
        1,
        pushesFor(CLAUDE_SKILLS, "spec-board"),
        "the room turn found the stamp and pushed nothing");
  }
}
