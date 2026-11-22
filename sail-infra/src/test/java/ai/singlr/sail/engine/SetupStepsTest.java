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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SetupStepsTest {

  private static final String STAMP = "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d(\\.\\d+)?Z";

  private final AtomicLong nanos = new AtomicLong();
  private final ByteArrayOutputStream said = new ByteArrayOutputStream();
  private final Incus incus = new Incus();
  private final SetupSteps steps =
      new SetupSteps(incus, new PrintStream(said, true, StandardCharsets.UTF_8), nanos::get);

  /**
   * An Incus whose commands take as long as told, and time out when given less; with {@code slow}
   * set, only a command that begins with it takes that long and the rest answer at once. systemd in
   * a container says it is running, with no job queued.
   */
  private final class Incus implements ShellExec {
    final List<Duration> given = Collections.synchronizedList(new ArrayList<>());
    Duration takes = Duration.ZERO;
    String slow;

    @Override
    public Result exec(List<String> command) {
      throw new AssertionError("a command is always given its timeout");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout)
        throws TimeoutException {
      given.add(timeout);
      var line = String.join(" ", command);
      var lasts = slow == null || line.startsWith(slow) ? takes : Duration.ZERO;
      if (lasts.compareTo(timeout) > 0) {
        nanos.addAndGet(timeout.toNanos());
        throw new CommandTimedOut(
            "Command timed out after " + timeout.toSeconds() + "s: " + line, "ps: a listing");
      }
      nanos.addAndGet(lasts.toNanos());
      return new Result(0, said(line), "");
    }

    @Override
    public Result execWithInput(List<String> command, String input, Duration timeout)
        throws TimeoutException {
      return exec(command, null, timeout);
    }

    private static String said(String line) {
      if (line.endsWith("is-system-running")) {
        return "running\n";
      }
      return line.contains("list-jobs") ? "No jobs running.\n" : "";
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
  void aCommandTheBoundCutsOffTimesOutInItsOwnWordsAndTheStepsAndKeepsItsListing() {
    incus.takes = Duration.ofMinutes(10);

    var failed =
        assertThrows(
            CommandTimedOut.class,
            () ->
                steps.step(
                    "launch box",
                    Duration.ofSeconds(60),
                    () -> steps.exec(List.of("incus", "launch", "prepared", "box"))));

    var expected =
        "Command timed out after 60s: incus launch prepared box (setup step 'launch box' did not"
            + " end within its bound of 60 s: it gave `incus launch prepared box` its last 60.0 s"
            + " of it)";
    assertEquals(expected, failed.getMessage());
    assertEquals("ps: a listing", failed.processes());
    assertEquals(
        "Command timed out after 60s: incus launch prepared box", failed.getCause().getMessage());
    var failedLine = lines().get(1);
    assertTrue(failedLine.matches("\\[it-setup\\] " + STAMP + " failed .*"), failedLine);
    assertTrue(failedLine.endsWith(" failed launch box after 60.0 s: " + expected), failedLine);
  }

  @Test
  void aStepWhoseBoundIsSpentBeforeACommandStartsTimesOutNamingThatCommand() {
    incus.takes = Duration.ofSeconds(60);

    var failed =
        assertThrows(
            CommandTimedOut.class,
            () ->
                steps.step(
                    "stop box",
                    Duration.ofSeconds(60),
                    () -> {
                      steps.exec(List.of("incus", "exec", "box", "--", "sync"));
                      return steps.exec(List.of("incus", "stop", "box"));
                    }));

    assertEquals(
        "Command timed out after 0s: incus stop box (setup step 'stop box' did not end within its"
            + " bound of 60 s: it was spent before `incus stop box` began)",
        failed.getMessage());
    assertEquals(1, incus.given.size());
  }

  @Test
  void aStepWhoseWorkGoesOnPastACutFailsAtItsEndAndWhatRunsAfterHasItsOwnTimeout() {
    incus.takes = Duration.ofSeconds(100);

    var failed =
        assertThrows(
            AssertionError.class,
            () ->
                steps.step(
                    "remove leftovers",
                    Duration.ofSeconds(60),
                    () -> {
                      try {
                        steps.exec(List.of("incus", "delete", "--force", "a"));
                      } catch (CommandTimedOut passedOver) {
                      }
                      return steps.exec(List.of("incus", "delete", "--force", "b"));
                    }));

    assertEquals(
        "setup step 'remove leftovers' did not end within its bound of 60 s: it gave `incus delete"
            + " --force a` its last 60.0 s of it",
        failed.getMessage());
    assertEquals(List.of(Duration.ofSeconds(60), SetupSteps.COMMAND_DEFAULT), incus.given);
    assertTrue(lines().get(1).contains(" failed remove leftovers after 160.0 s: "), lines().get(1));
  }

  @Test
  void aStopTheBoundCutsOffIsStillDiagnosedAsAStopThatTimedOut(@TempDir Path home)
      throws Exception {
    incus.takes = Duration.ofMinutes(10);
    incus.slow = "incus stop";
    var log = home.resolve("containers.log");
    var containers =
        new ContainerManager(
            steps, new InstanceTimeoutDiagnosis(steps, log, Duration.ofSeconds(10), nanos::get));

    var failed =
        assertThrows(
            CommandTimedOut.class,
            () ->
                steps.step(
                    "stop t",
                    Duration.ofSeconds(60),
                    () -> {
                      containers.stop("t");
                      return null;
                    }));

    assertEquals(
        "Command timed out after 60s: incus stop t (setup step 'stop t' did not end within its"
            + " bound of 60 s: it gave `incus stop t` its last 60.0 s of it). Just after, systemd"
            + " in t said it is running, with no job queued; Incus's log of t and the rest are in "
            + log
            + ".",
        failed.getMessage());
    assertTrue(Files.readString(log).contains("incus stop t"), Files.readString(log));
    assertEquals(4, incus.given.size(), incus.given.toString());
    assertEquals(Duration.ofSeconds(60), incus.given.getFirst());
    assertEquals(
        List.of(Duration.ofSeconds(10)),
        incus.given.subList(1, 4).stream().distinct().toList(),
        incus.given.toString());
  }

  @Test
  void aCommandAfterAStepHasEndedHasItsOwnTimeoutAgain() throws Exception {
    steps.step("seal", Duration.ofSeconds(50), () -> steps.exec(List.of("incus", "exec", "t")));

    steps.exec(List.of("incus", "list"));

    assertEquals(List.of(Duration.ofSeconds(50), SetupSteps.COMMAND_DEFAULT), incus.given);
  }

  @Test
  void aCommandGivenInputInAStepIsGivenWhatIsLeftOfTheBound() throws Exception {
    steps.step(
        "write the key",
        Duration.ofSeconds(30),
        () -> steps.execWithInput(List.of("incus", "file", "push"), "key", Duration.ofMinutes(5)));

    assertEquals(List.of(Duration.ofSeconds(30)), incus.given);
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
