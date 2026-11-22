/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SetupStepsTest {

  private static final String STAMP = "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?Z";

  private final AtomicLong nanos = new AtomicLong();
  private final ByteArrayOutputStream said = new ByteArrayOutputStream();
  private final Incus incus = new Incus();
  private final SetupSteps steps =
      new SetupSteps(incus, new PrintStream(said, true, StandardCharsets.UTF_8), nanos::get);

  /** An Incus whose commands take as long as told, and time out when given less. */
  private final class Incus implements ShellExec {
    final List<Duration> given = new ArrayList<>();
    Duration takes = Duration.ZERO;

    @Override
    public Result exec(List<String> command) {
      throw new AssertionError("a command is always given its timeout");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout)
        throws TimeoutException {
      given.add(timeout);
      if (takes.compareTo(timeout) > 0) {
        nanos.addAndGet(timeout.toNanos());
        throw new CommandTimedOut(
            "Command timed out after " + timeout.toSeconds() + "s: " + String.join(" ", command),
            "");
      }
      nanos.addAndGet(takes.toNanos());
      return new Result(0, "", "");
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  private List<String> lines() {
    return said.toString(StandardCharsets.UTF_8).lines().toList();
  }

  @Test
  void aStepSaysWhenItStartsAndEndsWithItsBoundAndHowLongItTook() throws Exception {
    incus.takes = Duration.ofMillis(1500);

    steps.step("launch box", Duration.ofSeconds(120), () -> steps.exec(List.of("incus", "launch")));

    var lines = lines();
    assertEquals(2, lines.size(), lines.toString());
    assertTrue(
        lines.get(0).matches("\\[it-setup\\] " + STAMP + " start launch box \\(bound 120 s\\)"),
        lines.get(0));
    assertTrue(
        lines.get(1).matches("\\[it-setup\\] " + STAMP + " done launch box in 1\\.5 s"),
        lines.get(1));
  }

  @Test
  void aCommandInAStepIsGivenWhatIsLeftOfTheStepsBound() throws Exception {
    incus.takes = Duration.ofSeconds(30);

    steps.step(
        "seal",
        Duration.ofSeconds(100),
        () -> {
          steps.exec(List.of("incus", "exec", "t", "--", "true"));
          steps.exec(List.of("incus", "exec", "t", "--", "true"), null, Duration.ofSeconds(40));
          return steps.exec(List.of("incus", "exec", "t", "--", "true"));
        });

    assertEquals(
        List.of(Duration.ofSeconds(100), Duration.ofSeconds(40), Duration.ofSeconds(40)),
        incus.given);
  }

  @Test
  void aStepWhoseBoundCutsACommandOffFailsNamingTheCommandItWasWaitingFor() {
    incus.takes = Duration.ofMinutes(10);

    var failed =
        assertThrows(
            AssertionError.class,
            () ->
                steps.step(
                    "launch box",
                    Duration.ofSeconds(120),
                    () -> steps.exec(List.of("incus", "launch", "prepared", "box"))));

    assertEquals(
        "setup step 'launch box' did not end within its bound of 120 s: it was waiting for"
            + " `incus launch prepared box`",
        failed.getMessage());
    var lines = lines();
    assertTrue(
        lines
            .get(1)
            .matches(
                "\\[it-setup\\] "
                    + STAMP
                    + " failed launch box after 120\\.0 s: setup step 'launch box' did not end"
                    + " within its bound of 120 s: it was waiting for `incus launch prepared box`"),
        lines.get(1));
  }

  @Test
  void aStepWhoseBoundIsSpentBeforeACommandStartsFailsNamingThatCommand() {
    incus.takes = Duration.ofSeconds(60);

    var failed =
        assertThrows(
            AssertionError.class,
            () ->
                steps.step(
                    "stop box",
                    Duration.ofSeconds(60),
                    () -> {
                      steps.exec(List.of("incus", "exec", "box", "--", "sync"));
                      return steps.exec(List.of("incus", "stop", "box"));
                    }));

    assertEquals(
        "setup step 'stop box' did not end within its bound of 60 s: it was waiting for `incus"
            + " stop box`",
        failed.getMessage());
  }

  @Test
  void aCommandsOwnShorterTimeoutStaysItsOwnFailure() {
    incus.takes = Duration.ofSeconds(20);

    var failed =
        assertThrows(
            CommandTimedOut.class,
            () ->
                steps.step(
                    "seal",
                    Duration.ofSeconds(120),
                    () -> steps.exec(List.of("incus", "exec", "t"), null, Duration.ofSeconds(5))));

    assertEquals("Command timed out after 5s: incus exec t", failed.getMessage());
  }

  @Test
  void outsideAStepACommandIsGivenWhatItAsksOrTheShellsDefault() throws Exception {
    steps.exec(List.of("incus", "list"));
    steps.exec(List.of("incus", "list"), null, Duration.ofMinutes(10));

    assertEquals(List.of(SetupSteps.COMMAND_DEFAULT, Duration.ofMinutes(10)), incus.given);
    assertEquals(List.of(), lines());
  }

  @Test
  void aStepInsideAStepRunsWithinTheOuterBoundAndSaysNothingOfItsOwn() throws Exception {
    incus.takes = Duration.ofSeconds(1);

    var answer =
        steps.step(
            "make the box",
            Duration.ofSeconds(50),
            () -> steps.step("launch", Duration.ofSeconds(500), () -> steps.exec(List.of("x"))));

    assertEquals(0, answer.exitCode());
    assertEquals(List.of(Duration.ofSeconds(50)), incus.given);
    assertEquals(2, lines().size(), lines().toString());
  }
}
