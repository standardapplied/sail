/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import static ai.singlr.sail.harness.HarnessUsageTest.CLAUDE_LOG;
import static ai.singlr.sail.harness.HarnessUsageTest.CODEX_ROLLOUT;
import static ai.singlr.sail.harness.HarnessUsageTest.fixture;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What each harness's own report says of the login a run gave it (RL1-RL3, RL7). The Claude Code
 * fixtures are real lines of Claude Code 2.1.322 run with fake credentials (init trimmed to the
 * fields read here); the Codex one is a real rollout of codex-cli 0.163.0 with a fake key, which it
 * prints masked. No harness run has shown a limit: those lines are built from the words the spec
 * records, and Codex's {@code codex_error_info} from its source's wire enum.
 */
class HarnessLoginOutcomeTest {

  private static final Harness CLAUDE_CODE = Harnesses.of("claude-code");
  private static final Harness CODEX = Harnesses.of("codex");
  private static final LoginKind TOKEN = CLAUDE_CODE.loginKind("token");
  private static final LoginKind ANTHROPIC_KEY = CLAUDE_CODE.loginKind("api-key");
  private static final LoginKind CODEX_KEY = CODEX.loginKind("api-key");

  static final String CLAUDE_TOKEN_REFUSED = fixture("claude-code-refused-token.jsonl");
  static final String CLAUDE_KEY_REFUSED = fixture("claude-code-refused-key.jsonl");
  static final String CLAUDE_NOT_LOGGED_IN = fixture("claude-code-not-logged-in.jsonl");
  static final String CLAUDE_HELPER_INIT = fixture("claude-code-helper-init.jsonl");
  static final String CODEX_KEY_REFUSED = fixture("codex-refused-key.jsonl");

  /**
   * A Claude Code result line saying its account is out of quota, in the words the spec records.
   */
  static final String CLAUDE_LIMITED =
      "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,\"api_error_status\":null,"
          + "\"result\":\"You've hit your session limit · resets 3pm (UTC)\"}\n";

  /** A Codex end of a turn its usage limit stopped, shaped as its error wire writes one. */
  static final String CODEX_LIMITED =
      "{\"type\":\"event_msg\",\"payload\":{\"type\":\"task_complete\",\"error\":{\"message\":"
          + "\"You’ve hit your usage limit. Upgrade to Pro, or try again at Nov 16th, 2026 3:04 PM."
          + "\",\"codex_error_info\":\"usage_limit_exceeded\"}}}\n";

  /** A recorded log's lines after its {@code init}, as a run started some other way would go on. */
  private static String afterInit(String log) {
    return String.join("\n", log.lines().skip(1).toList()) + "\n";
  }

  static Stream<Object[]> outcomes() {
    return Stream.of(
        new Object[] {CLAUDE_CODE, TOKEN, CLAUDE_TOKEN_REFUSED, new LoginOutcome.Refused()},
        new Object[] {CLAUDE_CODE, ANTHROPIC_KEY, CLAUDE_KEY_REFUSED, new LoginOutcome.Refused()},
        new Object[] {CLAUDE_CODE, TOKEN, CLAUDE_NOT_LOGGED_IN, new LoginOutcome.Refused()},
        new Object[] {
          CLAUDE_CODE,
          TOKEN,
          CLAUDE_HELPER_INIT + afterInit(CLAUDE_LOG),
          new LoginOutcome.Overridden("apiKeyHelper")
        },
        new Object[] {
          CLAUDE_CODE,
          TOKEN,
          CLAUDE_HELPER_INIT + afterInit(CLAUDE_TOKEN_REFUSED),
          new LoginOutcome.Overridden("apiKeyHelper")
        },
        new Object[] {CLAUDE_CODE, ANTHROPIC_KEY, CLAUDE_LOG, new LoginOutcome.Overridden("none")},
        new Object[] {
          CLAUDE_CODE,
          TOKEN,
          CLAUDE_TOKEN_REFUSED.lines().findFirst().orElseThrow() + "\n" + CLAUDE_LIMITED,
          new LoginOutcome.Limited("3pm (UTC)")
        },
        new Object[] {CODEX, CODEX_KEY, CODEX_KEY_REFUSED, new LoginOutcome.Refused()},
        new Object[] {
          CODEX, CODEX_KEY, CODEX_LIMITED, new LoginOutcome.Limited("Nov 16th, 2026 3:04 PM")
        });
  }

  /** Each harness says it in its own report, and the lines holding a marker are all it needs. */
  @ParameterizedTest
  @MethodSource("outcomes")
  void eachHarnessSaysWhatCameOfTheLoginInItsOwnReport(
      Harness harness, LoginKind given, String report, LoginOutcome expected) {
    assertEquals(Optional.of(expected), harness.loginOutcome(report, given));
    assertEquals(
        Optional.of(expected),
        harness.loginOutcome(HarnessUsageTest.marked(harness, report), given),
        "the reader asks a container only for the lines holding a marker");
  }

  static Stream<Object[]> ordinary() {
    return Stream.of(
        new Object[] {CLAUDE_CODE, TOKEN, CLAUDE_LOG},
        new Object[] {CLAUDE_CODE, TOKEN, null},
        new Object[] {CLAUDE_CODE, TOKEN, ""},
        new Object[] {
          CLAUDE_CODE,
          TOKEN,
          "{\"type\":\"result\",\"is_error\":true,\"api_error_status\":500,"
              + "\"result\":\"API Error: 500 Internal server error\"}"
        },
        new Object[] {
          CLAUDE_CODE,
          TOKEN,
          "{\"type\":\"result\",\"is_error\":false,\"result\":\"Invalid API key handling is fixed\"}"
        },
        new Object[] {
          CLAUDE_CODE,
          TOKEN,
          "{\"type\":\"system\",\"subtype\":\"init\",\"apiKeySource\":\"sk-ant-api03-a b\\\"$x\"}"
        },
        new Object[] {CODEX, CODEX_KEY, CODEX_ROLLOUT},
        new Object[] {CODEX, CODEX_KEY, null},
        new Object[] {
          CODEX,
          CODEX_KEY,
          "{\"type\":\"event_msg\",\"payload\":{\"type\":\"task_complete\",\"error\":{\"message\":"
              + "\"stream disconnected\",\"codex_error_info\":{\"http_connection_failed\":"
              + "{\"http_status_code\":500}}}}}"
        },
        new Object[] {
          CODEX,
          CODEX_KEY,
          "{\"type\":\"event_msg\",\"payload\":{\"type\":\"task_complete\",\"error\":{\"message\":"
              + "\"slow down\",\"codex_error_info\":\"rate_limit_exceeded\"}}}"
        });
  }

  /** RL7: what a harness did not say of its login is an ordinary end; nothing is guessed. */
  @ParameterizedTest
  @MethodSource("ordinary")
  void anythingElseIsAnOrdinaryEnd(Harness harness, LoginKind given, String report) {
    assertEquals(Optional.empty(), harness.loginOutcome(report, given));
  }

  /** The last word decides: a refusal a later turn got past is no refusal. */
  @Test
  void onlyTheLastEndOfTheRunSpeaks() {
    assertEquals(
        Optional.empty(), CLAUDE_CODE.loginOutcome(CLAUDE_TOKEN_REFUSED + CLAUDE_LOG, TOKEN));
    assertEquals(
        Optional.of(new LoginOutcome.Refused()),
        CODEX.loginOutcome(CODEX_ROLLOUT + CODEX_KEY_REFUSED, CODEX_KEY));
  }

  /**
   * RL6: a time or a source is kept only as a plain short label, never the harness's other words.
   */
  @Test
  void aLimitWhoseResetIsNoPlainLabelIsSaidWithoutOne() {
    assertEquals(
        Optional.of(new LoginOutcome.Limited(null)),
        CODEX.loginOutcome(
            CODEX_LIMITED.replace("Nov 16th, 2026 3:04 PM", "`rm -rf ~`"), CODEX_KEY));
    assertEquals(
        Optional.of(new LoginOutcome.Limited(null)),
        CLAUDE_CODE.loginOutcome(CLAUDE_LIMITED.replace(" · resets 3pm (UTC)", ""), TOKEN));
  }
}
