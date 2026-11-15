/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.harness;

import static ai.singlr.sail.harness.HarnessUsageTest.CLAUDE_LOG;
import static ai.singlr.sail.harness.HarnessUsageTest.CODEX_ROLLOUT;
import static ai.singlr.sail.harness.HarnessUsageTest.fixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What each harness's own report says of the login a run gave it (RL1-RL3, RL6, RL7). The Claude
 * Code fixtures are real lines of Claude Code 2.1.322 run with fake credentials (init trimmed to
 * the fields read here): a fake token and a fake key each get a 401, and "Not logged in" comes only
 * from a run given no credential at all (or an empty one). The Codex one is a real rollout of
 * codex-cli 0.163.0 with a fake key, which it prints masked. No harness run has shown a limit:
 * those lines are built from the words the spec records, and Codex's from its source's wire enum
 * and its retry timestamp format ({@code %b %-d<suffix>, %Y %-I:%M %p}).
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

  /** A Claude Code result line saying its account is out of quota, resetting at {@code when}. */
  static String claudeLimited(String when) {
    return "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":true,"
        + "\"api_error_status\":null,\"result\":\"You've hit your session limit · resets "
        + when
        + "\"}\n";
  }

  /** A Codex turn its usage limit ended, its message saying to try again at {@code when}. */
  static String codexLimited(String when) {
    return "{\"type\":\"turn_context\",\"payload\":{\"turn_id\":\"turn-1\"}}\n"
        + "{\"type\":\"event_msg\",\"payload\":{\"type\":\"task_complete\",\"turn_id\":\"turn-1\","
        + "\"error\":{\"message\":\"You’ve hit your usage limit. Upgrade to Pro, or try again at "
        + when
        + ".\",\"codex_error_info\":\"usage_limit_exceeded\"}}}\n";
  }

  /**
   * A later run's turn of the same Codex session, resumed into the same rollout, ended before it
   * wrote a {@code task_complete} of its own (B1).
   */
  static final String RESUMED_UNENDED =
      "{\"type\":\"event_msg\",\"payload\":{\"type\":\"task_started\",\"turn_id\":\"turn-2\"}}\n"
          + "{\"type\":\"turn_context\",\"payload\":{\"turn_id\":\"turn-2\",\"model\":\"gpt-6.1\"}}\n"
          + "{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":"
          + "{\"total_token_usage\":{\"input_tokens\":10,\"cached_input_tokens\":0,"
          + "\"output_tokens\":5}}}}\n";

  /** A recorded log's lines after its {@code init}, as a run started some other way would go on. */
  private static String afterInit(String log) {
    return String.join("\n", log.lines().skip(1).toList()) + "\n";
  }

  private static String withSource(String log, String source) {
    return log.replace("\"apiKeySource\":\"none\"", "\"apiKeySource\":\"" + source + "\"");
  }

  static Stream<Object[]> outcomes() {
    return Stream.of(
        new Object[] {CLAUDE_CODE, TOKEN, CLAUDE_TOKEN_REFUSED, new LoginOutcome.Refused()},
        new Object[] {CLAUDE_CODE, ANTHROPIC_KEY, CLAUDE_KEY_REFUSED, new LoginOutcome.Refused()},
        new Object[] {CLAUDE_CODE, TOKEN, CLAUDE_NOT_LOGGED_IN, new LoginOutcome.Undelivered()},
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
          new LoginOutcome.Refused()
        },
        new Object[] {
          CLAUDE_CODE,
          TOKEN,
          withSource(CLAUDE_LOG, "/login managed key"),
          new LoginOutcome.Overridden("/login managed key")
        },
        new Object[] {
          CLAUDE_CODE,
          ANTHROPIC_KEY,
          withSource(CLAUDE_LOG, "apiKeyHelper"),
          new LoginOutcome.Overridden("apiKeyHelper")
        },
        new Object[] {
          CLAUDE_CODE,
          TOKEN,
          CLAUDE_TOKEN_REFUSED.lines().findFirst().orElseThrow()
              + "\n"
              + claudeLimited("3pm (UTC)"),
          new LoginOutcome.Limited("15:00 UTC")
        },
        new Object[] {
          CLAUDE_CODE,
          TOKEN,
          claudeLimited("Nov 16 at 9:30am"),
          new LoginOutcome.Limited("11-16 09:30")
        },
        new Object[] {CODEX, CODEX_KEY, CODEX_KEY_REFUSED, new LoginOutcome.Refused()},
        new Object[] {
          CODEX,
          CODEX_KEY,
          codexLimited("Nov 16th, 2026 3:04 PM"),
          new LoginOutcome.Limited("2026-11-16 15:04")
        },
        new Object[] {
          CODEX, CODEX_KEY, codexLimited("3:04 PM"), new LoginOutcome.Limited("15:04")
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
          "{\"type\":\"result\",\"is_error\":false,\"result\":\"Not logged in yet, so it asked\"}"
        },
        new Object[] {CLAUDE_CODE, ANTHROPIC_KEY, CLAUDE_LOG},
        new Object[] {CLAUDE_CODE, TOKEN, withSource(CLAUDE_LOG, "CLAUDE_CODE_OAUTH_TOKEN")},
        new Object[] {CLAUDE_CODE, TOKEN, withSource(CLAUDE_LOG, "oauth_token")},
        new Object[] {
          CLAUDE_CODE, TOKEN, withSource(CLAUDE_LOG, "sk-ant-api03-AbCdEf0123456789xyz")
        },
        new Object[] {CODEX, CODEX_KEY, CODEX_ROLLOUT},
        new Object[] {CODEX, CODEX_KEY, null},
        new Object[] {CODEX, CODEX_KEY, CODEX_KEY_REFUSED + RESUMED_UNENDED},
        new Object[] {CODEX, CODEX_KEY, codexLimited("3:04 PM") + RESUMED_UNENDED},
        new Object[] {
          CODEX,
          CODEX_KEY,
          CODEX_KEY_REFUSED.replace("\"http_status_code\":401", "\"http_status_code\":500")
        },
        new Object[] {
          CODEX,
          CODEX_KEY,
          CODEX_KEY_REFUSED.replace("\"http_status_code\":401", "\"http_status_code\":403")
        },
        new Object[] {
          CODEX,
          CODEX_KEY,
          codexLimited("3:04 PM").replace("usage_limit_exceeded", "rate_limit_exceeded")
        });
  }

  /**
   * RL7 and B1: what a harness did not say of this run's login is an ordinary end; nothing is
   * guessed, and an end an earlier run of a resumed Codex session wrote speaks for that run only.
   */
  @ParameterizedTest
  @MethodSource("ordinary")
  void anythingElseIsAnOrdinaryEnd(Harness harness, LoginKind given, String report) {
    assertEquals(Optional.empty(), harness.loginOutcome(report, given));
  }

  /**
   * Ruling 7: a newer harness's report, with a field added, renamed or gone, throws nothing; an
   * added field changes nothing, and one renamed or gone means nothing is said.
   */
  @Test
  void aNewerHarnessesReportWithAFieldAddedRenamedOrGoneIsReadOrLeftAsAnOrdinaryEnd() {
    var refusedResult = afterInit(CLAUDE_TOKEN_REFUSED);
    assertEquals(
        Optional.of(new LoginOutcome.Refused()),
        CLAUDE_CODE.loginOutcome(
            refusedResult.replace("\"is_error\":true", "\"is_error\":true,\"retryable\":{\"x\":1}"),
            TOKEN));
    assertEquals(
        Optional.empty(),
        CLAUDE_CODE.loginOutcome(
            refusedResult.replace("\"api_error_status\"", "\"apiErrorStatus\""), TOKEN));
    assertEquals(
        Optional.empty(),
        CLAUDE_CODE.loginOutcome(refusedResult.replace("\"is_error\":true,", ""), TOKEN));
    assertEquals(
        Optional.of(new LoginOutcome.Refused()),
        CODEX.loginOutcome(
            CODEX_KEY_REFUSED.replace(
                "\"http_status_code\":401", "\"http_status_code\":401,\"retry_after\":3"),
            CODEX_KEY));
    assertEquals(
        Optional.empty(),
        CODEX.loginOutcome(
            CODEX_KEY_REFUSED.replace("http_connection_failed", "http_request_failed"), CODEX_KEY));
    assertEquals(
        Optional.empty(),
        CODEX.loginOutcome(CODEX_KEY_REFUSED.replace("\"turn_id\":", "\"turnId\":"), CODEX_KEY));
  }

  /**
   * RL6, ruling 2: a reset is said only as Sail's rendering of a time it parsed; text of any other
   * shape, a credential's included, gives a limit with no time, and is never carried.
   */
  @Test
  void aResetIsSailsRenderingOfATimeAndNothingElseOfWhatTheHarnessPrinted() {
    for (var hostile :
        new String[] {
          "ghp_0123456789abcdefghijABCDEFGHIJklmnop",
          "AKIAIOSFODNN7EXAMPLE",
          "sk-ant-api03-AbCdEf0123456789xyzXYZ01234",
          "Authorization: Bearer sk-proj-abcdef0123456789",
          "sk-fake-************0000",
          "3pm (UTC) and sk-ant-api03-x",
          "13pm",
          "3:61pm",
          "Feb 30 3pm",
          "Nov 16 3pm (Mars/Olympus)"
        }) {
      assertEquals(
          Optional.of(new LoginOutcome.Limited(null)),
          CLAUDE_CODE.loginOutcome(claudeLimited(hostile), TOKEN),
          hostile);
      assertEquals(
          Optional.of(new LoginOutcome.Limited(null)),
          CODEX.loginOutcome(codexLimited(hostile), CODEX_KEY),
          hostile);
    }
    assertNull(LoginOutcome.resetTime(null));
  }
}
