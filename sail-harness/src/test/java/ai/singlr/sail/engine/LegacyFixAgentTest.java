/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LegacyFixAgentTest {

  private static final String REVIEW = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  private static final String RUN = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

  private final List<Process> started = new ArrayList<>();

  @AfterEach
  void endEveryProcess() {
    started.forEach(Process::destroyForcibly);
  }

  /** A process whose environment holds {@code name=value}, as an agent's does. */
  private Process processWith(String name, String value) throws IOException {
    var builder = new ProcessBuilder("sleep", "300");
    builder.environment().put(name, value);
    var process = builder.start();
    started.add(process);
    return process;
  }

  private static boolean killed(Process process) throws InterruptedException {
    return process.waitFor(10, TimeUnit.SECONDS);
  }

  @Test
  void aProcessAnOlderServerStartedForTheReviewIsKilled() throws Exception {
    var legacy = processWith("SAIL_RUN_ID", REVIEW);

    LegacyFixAgent.stop(new Here(), "acme", REVIEW);

    assertTrue(killed(legacy), "the fix agent started for this review by an older server is gone");
    assertEquals(137, legacy.exitValue(), "by SIGKILL");
  }

  @Test
  void aProcessOfAnotherRunIsLeftUntouched() throws Exception {
    var legacy = processWith("SAIL_RUN_ID", REVIEW);
    var otherRun = processWith("SAIL_RUN_ID", RUN);
    var longerId = processWith("SAIL_RUN_ID", REVIEW + "0");
    var otherVariable = processWith("OTHER_SAIL_RUN_ID", REVIEW);
    var namedInAValue = processWith("NOTE", "SAIL_RUN_ID=" + REVIEW + " was here");

    LegacyFixAgent.stop(new Here(), "acme", REVIEW);

    assertTrue(killed(legacy));
    assertTrue(otherRun.isAlive(), "a run this server started exports its own run id");
    assertTrue(longerId.isAlive(), "only the exact entry matches, never a prefix of another id");
    assertTrue(otherVariable.isAlive(), "nor the tail of another variable's name");
    assertTrue(namedInAValue.isAlive(), "nor the same text inside another variable's value");
  }

  @Test
  void theReviewIdTravelsAsAnArgumentNeverAsScriptText() throws Exception {
    var here = new Here();

    LegacyFixAgent.stop(here, "acme", REVIEW);

    var command = here.commands.getFirst();
    assertEquals(REVIEW, command.getLast());
    var script = command.get(command.indexOf("-c") + 1);
    assertFalse(script.contains(REVIEW), script);
    assertTrue(script.contains("\"SAIL_RUN_ID=$1\""), script);
    assertEquals(List.of("incus", "exec", "acme"), command.subList(0, 3));
    assertEquals(1, here.commands.size(), "one exec");
  }

  @Test
  void anIdThatIsNotACanonicalUuidIsRefusedBeforeAnythingRuns() {
    var here = new Here();

    assertThrows(
        IllegalArgumentException.class, () -> LegacyFixAgent.stop(here, "acme", "x; kill -9 -1"));

    assertTrue(here.commands.isEmpty());
  }

  @Test
  void aCheckThatIsInterruptedOrTimesOutFailsLoudAndKeepsTheInterrupt() {
    ShellExec interrupted =
        new Here() {
          @Override
          public Result exec(List<String> command) throws InterruptedException {
            throw new InterruptedException("shutting down");
          }
        };
    ShellExec slow =
        new Here() {
          @Override
          public Result exec(List<String> command) throws TimeoutException {
            throw new TimeoutException("no answer");
          }
        };

    try {
      var failure =
          assertThrows(IOException.class, () -> LegacyFixAgent.stop(interrupted, "acme", REVIEW));
      assertTrue(failure.getMessage().contains("interrupted"), failure.getMessage());
      assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is the caller's to see");
    } finally {
      Thread.interrupted();
    }
    var timedOut = assertThrows(IOException.class, () -> LegacyFixAgent.stop(slow, "acme", REVIEW));
    assertTrue(timedOut.getMessage().contains("timed out"), timedOut.getMessage());
  }

  @Test
  void aContainerThatDoesNotRunTheCheckFailsLoudSoNoFixAgentStartsBesideOne() {
    var unreachable = new ScriptedShellExecutor().onFail("incus exec", "Instance is not running");

    var failure =
        assertThrows(IOException.class, () -> LegacyFixAgent.stop(unreachable, "acme", REVIEW));

    assertTrue(failure.getMessage().contains("Instance is not running"), failure.getMessage());
    assertTrue(failure.getMessage().contains(REVIEW), failure.getMessage());
  }
}
