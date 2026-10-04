/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class GuardrailTriggerTest {

  private static final AgentUnit UNIT = AgentUnit.forRun("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
  private static final Instant AT = Instant.parse("2026-10-03T12:45:01Z");

  private static GuardrailChecker.GuardrailResult.Triggered limit(String action) {
    return new GuardrailChecker.GuardrailResult.Triggered(
        "max_duration", "Agent running for 46m (limit: 45m)", action, "45m");
  }

  @Test
  void aTriggerIsWrittenBesideItsRunSayingWhyTheRunEnded() throws Exception {
    var shell = new ScriptedShellExecutor();

    GuardrailTrigger.of(limit("stop"), AT).write(shell, "acme", UNIT);

    var written = shell.invocations().getLast();
    assertTrue(written.contains(UNIT.guardrailTriggerPath()), written);
    assertTrue(written.contains("triggered_at: 2026-10-03T12:45:01Z"), written);
    assertTrue(written.contains("reason: max_duration"), written);
    assertTrue(written.contains("action: stop"), written);
    assertTrue(written.contains("cause: time limit (45m)"), written);
  }

  @Test
  void aTriggerReadsBackAsItWasWritten() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "cat " + UNIT.guardrailTriggerPath(),
                """
                triggered_at: '2026-10-03T12:45:01Z'
                reason: stall
                detail: 'No progress for 21m (limit: 20m)'
                action: snapshot-and-stop
                cause: stall (20m)
                """);

    var trigger = GuardrailTrigger.read(shell, "acme", UNIT).orElseThrow();

    assertEquals(
        new GuardrailTrigger(
            "2026-10-03T12:45:01Z",
            "stall",
            "No progress for 21m (limit: 20m)",
            "snapshot-and-stop",
            "stall (20m)"),
        trigger);
    assertTrue(trigger.stops());
  }

  @Test
  void aRunNoLimitEndedHasNoTrigger() throws Exception {
    var none = new ScriptedShellExecutor().onFail("cat " + UNIT.guardrailTriggerPath(), "No such");
    var empty = new ScriptedShellExecutor().onOk("cat " + UNIT.guardrailTriggerPath(), " \n");

    assertTrue(GuardrailTrigger.read(none, "acme", UNIT).isEmpty());
    assertTrue(GuardrailTrigger.read(empty, "acme", UNIT).isEmpty());
  }

  @Test
  void aTriggerAnOlderWatcherWroteWithNoCauseStillSaysWhichLimitEndedTheRun() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "cat " + UNIT.guardrailTriggerPath(),
                """
                triggered_at: '2026-10-03T12:45:01Z'
                reason: max_duration
                detail: 'Agent running for 46m (limit: 45m)'
                action: stop
                """);

    var trigger = GuardrailTrigger.read(shell, "acme", UNIT).orElseThrow();

    assertEquals(
        "max_duration", trigger.cause(), "a kill recorded before causes were is still a kill");
  }

  @Test
  void aLimitThatOnlyNotifiesEndsNothing() {
    assertFalse(GuardrailTrigger.of(limit("notify"), AT).stops());
    assertTrue(GuardrailTrigger.of(limit("snapshot-and-stop"), AT).stops());
    assertFalse(limit("notify").stops());
    assertTrue(limit("stop").stops());
  }
}
