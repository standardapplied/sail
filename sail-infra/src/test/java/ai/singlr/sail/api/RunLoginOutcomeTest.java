/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run whose harness refused, limited or passed over the login Sail gave it says so in its spec's
 * room and as an {@code agent_login_outcome} event, through the box's own finishers over a fake
 * container holding the harness's recorded lines: the watcher's stop the pipeline and tracker take,
 * and the reconciler's late read. Said once, however often its stop is heard (RL1-RL4, RL6, RL7).
 */
class RunLoginOutcomeTest {

  @TempDir Path tempDir;
  private ReviewLoop loop;

  @AfterEach
  void tearDown() {
    if (loop != null) {
      loop.close();
    }
  }

  static String fixture(String name) {
    try (var in =
        RunLoginOutcomeTest.class.getResourceAsStream("/ai/singlr/sail/harness/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A run of {@code agent} in {@code role} for spec auth, launched with its person's login. */
  private String launched(String role, String agent, String name, String kind) {
    var run = loop.runOf("auth", role, agent);
    Acting.system(
        () ->
            loop.runs.recordLaunch(
                run,
                new RunStore.Launched(
                    null, null, null, new RunStore.RunLogin(name, kind), ReviewLoop.HANDLE)));
    return run;
  }

  private static Event stop(String run, String agent, String role, int exitCode) {
    return RunStops.of(
        Event.WellKnownData.SOURCE_WATCHER,
        ReviewLoop.PROJECT,
        "auth",
        agent,
        run,
        role,
        exitCode,
        null);
  }

  private List<String> said() {
    return loop.messages.list("auth", null, 100).stream()
        .filter(message -> MessageStore.SAIL_AUTHOR.equals(message.author()))
        .map(MessageStore.MessageRow::body)
        .toList();
  }

  private List<Map<String, Object>> outcomes() {
    return loop.events(Event.WellKnownTypes.AGENT_LOGIN_OUTCOME).stream().map(Event::data).toList();
  }

  /** Scenario 1: a refused token stops the build, said once with the command that replaces it. */
  @Test
  void aBuildWhoseTokenClaudeCodeRefusedSaysSoOnceInTheRoomAndAsItsEvent() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("auth", "api");
    var build = launched("build", "claude-code", "default", "token");
    loop.container.wrote(build, fixture("claude-code-refused-token.jsonl"));

    loop.onEvent(stop(build, "claude-code", "build", 1));
    loop.onEvent(stop(build, "claude-code", "build", 1));

    var sentence =
        "Claude Code refused node-a's login 'default' (long-lived token), so its run stopped."
            + " Run claude setup-token on a machine where Claude Code is signed in, then sail"
            + " agent login claude-code.";
    assertEquals(List.of(sentence), said());
    assertEquals(
        List.of(
            Map.of(
                "run_id", build,
                "run_role", "build",
                "outcome", "refused",
                "login", "default",
                "login_kind", "token",
                "detail", sentence)),
        outcomes());
    assertEquals(
        SpecStatus.IN_PROGRESS, loop.specStatus("auth"), "a refused build is reviewed by no one");
  }

  /** Scenario 2, RL6: Codex's refused key is named by its kind; the masked key is not repeated. */
  @Test
  void aRunWhoseKeyCodexRefusedNamesItsKindAndRepeatsNothingCodexPrinted() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("auth", "api");
    var fix = launched("fix", "codex", "work", "api-key");
    var transcript = "/home/dev/.codex/sessions/2026/11/14/rollout-refused.jsonl";
    Acting.system(() -> loop.runs.recordSession(fix, "thread-1", "startup", transcript));
    loop.container.wrote(fix, "codex exec writes text, not its report\n");
    loop.container.wroteFile(transcript, fixture("codex-refused-key.jsonl"));

    loop.onEvent(stop(fix, "codex", "fix", 1));

    assertEquals(
        List.of(
            "Codex CLI refused node-a's login 'work' (API key), so its run stopped. Create an API"
                + " key at platform.openai.com/api-keys, then sail agent login codex --name work."),
        said());
    var everything = String.join("\n", said()) + outcomes();
    assertFalse(everything.contains("sk-fake"), everything);
    assertFalse(everything.contains("Incorrect API key"), everything);
  }

  /** Scenario 4, RL3: an override is said once, recorded on the run, and the run goes on. */
  @Test
  void aBuildThatRanOnAnotherCredentialIsSaidOnceRecordedAndReviewedAsAnyOther() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("auth", "api");
    var build = launched("build", "claude-code", "default", "token");
    var log = fixture("claude-code-agent.jsonl");
    loop.container.wrote(
        build, fixture("claude-code-helper-init.jsonl") + log.substring(log.indexOf('\n') + 1));

    loop.onEvent(stop(build, "claude-code", "build", 0));

    assertEquals(
        List.of(
            "Claude Code ran on a credential from apiKeyHelper, not on node-a's login 'default'"
                + " (long-lived token): its container's own settings outrank the login Sail gave"
                + " it."),
        said().stream().filter(line -> line.startsWith("Claude Code ran")).toList());
    assertEquals("apiKeyHelper", loop.runs.findById(build).orElseThrow().loginOverridden());
    assertEquals(SpecStatus.REVIEW, loop.specStatus("auth"), "the build is reviewed as it ran");
  }

  /** Scenario 3: a limit says when it resets and where the work so far is. */
  @Test
  void aBuildTheAccountsQuotaStoppedSaysUntilWhenAndWhereItsWorkIs() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("auth", "api");
    var build = launched("build", "claude-code", "default", "token");
    loop.container.wrote(
        build,
        "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":null,"
            + "\"result\":\"You've hit your session limit · resets 3pm (UTC)\"}\n");

    loop.onEvent(stop(build, "claude-code", "build", 1));

    assertEquals(
        List.of(
            "Claude Code says the account behind node-a's login 'default' (long-lived token) is"
                + " out of quota until 3pm (UTC), so its run stopped; what it committed stays on"
                + " branch feat/test."),
        said());
  }

  /** RL7: an error Sail does not know is an ordinary failure: nothing is said of the login. */
  @Test
  void aFailureNoHarnessCalledALoginsSaysNothingOfIt() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("auth", "api");
    var build = launched("build", "claude-code", "default", "token");
    loop.container.wrote(
        build,
        "{\"type\":\"result\",\"is_error\":true,\"api_error_status\":500,"
            + "\"result\":\"API Error: 500 Internal server error\"}\n");

    loop.onEvent(stop(build, "claude-code", "build", 1));

    assertEquals(List.of(), said());
    assertEquals(List.of(), outcomes());
  }

  /**
   * Scenario 5 and 6: a report its container could not answer claims nothing; the reconciler's late
   * read says the same sentence once, and its next pass adds nothing.
   */
  @Test
  void aReportReadLateIsSaidOnceByTheReadThatRecordedIt() {
    loop = ReviewLoop.staged(tempDir, "codex");
    loop.spec("auth", "api");
    var build = launched("build", "claude-code", "default", "token");
    loop.container.wrote(build, fixture("claude-code-refused-token.jsonl"));
    loop.container.unreachable(true);

    loop.onEvent(stop(build, "claude-code", "build", 1));

    assertNull(loop.runs.findById(build).orElseThrow().usage(), "unread");
    assertEquals(List.of(), said());
    loop.container.unreachable(false);
    var reconciler = loop.reconciler(ReviewLoop.LATER);
    Acting.system(reconciler::readUnreadUsage);
    Acting.system(reconciler::readUnreadUsage);
    loop.settle();

    assertEquals(
        List.of(
            "Claude Code refused node-a's login 'default' (long-lived token), so its run stopped."
                + " Run claude setup-token on a machine where Claude Code is signed in, then sail"
                + " agent login claude-code."),
        said());
    assertEquals(1, outcomes().size());
  }
}
