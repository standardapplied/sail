/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RunRetentionTest {

  private static final class RecordingShell implements ShellExec {
    final List<List<String>> commands = new ArrayList<>();
    boolean fail;

    @Override
    public Result exec(List<String> command) {
      commands.add(command);
      return new Result(fail ? 1 : 0, "", "");
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }
  }

  private static String runId(int i) {
    return String.format("00000000-0000-7000-8000-%012d", i);
  }

  private static List<String> runIds(int n) {
    return IntStream.range(0, n).mapToObj(RunRetentionTest::runId).toList();
  }

  @Test
  void keepsTheNewestAndPrunesTheRest() throws Exception {
    var shell = new RecordingShell();

    var pruned = RunRetention.prune(shell, "acme", runIds(5), 2);

    assertEquals(List.of(runId(2), runId(3), runId(4)), pruned);
    assertEquals(3, shell.commands.size());
    assertTrue(
        shell.commands.get(0).contains(AgentUnit.runDir(runId(2))),
        "the removal targets the run's dir");
    assertTrue(shell.commands.get(0).contains("rm"));
  }

  @Test
  void rejectsARunIdThatEscapesTheRunsRoot() {
    var shell = new RecordingShell();

    assertThrows(
        IllegalArgumentException.class,
        () -> RunRetention.prune(shell, "acme", List.of("../../.ssh"), 0),
        "a traversal run id must never reach rm -rf");
    assertTrue(shell.commands.isEmpty(), "nothing is removed for an invalid id");
  }

  @Test
  void nothingToPruneWhenWithinTheKeepWindow() throws Exception {
    var shell = new RecordingShell();

    assertEquals(List.of(), RunRetention.prune(shell, "acme", runIds(2), 20));
    assertTrue(shell.commands.isEmpty());
  }

  @Test
  void aFailedRemovalIsSkippedNotCounted() throws Exception {
    var shell = new RecordingShell();
    shell.fail = true;

    assertEquals(List.of(), RunRetention.prune(shell, "acme", runIds(3), 1));
    assertEquals(2, shell.commands.size(), "it still attempted each old run");
  }

  @Test
  void aNegativeKeepIsTreatedAsZeroAndPrunesEverything() throws Exception {
    var shell = new RecordingShell();

    assertEquals(3, RunRetention.prune(shell, "acme", runIds(3), -1).size());
  }
}
