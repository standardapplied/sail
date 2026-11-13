/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.harness.RunUsage;
import ai.singlr.sail.store.RunStore;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Reads what a finished run cost from its harness's own report, inside the run's container: the
 * last lines of the file its harness reports in ({@link Harness#reportFile}) holding each of its
 * markers ({@link Harness#reportMarkers}), which its harness reads ({@link Harness#usage}). What
 * the container answered is final, so it is recorded: a report that does not parse is none, said
 * once. Only a container that did not answer leaves the report unread: the run's directory is then
 * kept and the read tried again (M3).
 */
public final class RunUsageReader {

  /** How many of the last lines holding a marker are read: a report may pass over a few. */
  static final int LINES = 16;

  /** How long one read waits on the container before the report is left unread. */
  static final Duration TIMEOUT = Duration.ofSeconds(10);

  /**
   * For each marker after file {@code $1} and count {@code $2}, the last {@code $2} lines of the
   * file holding it, newest first: each read from the file's end, stopping at its last, as text
   * whatever bytes it holds, so a marker written often never crowds out one written seldom. It
   * exits 0 whatever it found, a file not there included, so only a container that did not answer
   * fails it.
   */
  static final String LAST_MARKED =
      "f=$1; n=$2; shift 2; for m in \"$@\"; do tac -- \"$f\" | grep -m \"$n\" -a -F -e \"$m\";"
          + " done; exit 0";

  private final ShellExec shell;

  public RunUsageReader(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * What run {@code run}'s harness reported: {@link RunUsage#NONE} when its report file is not
   * there or cannot be read, holds no line with a marker, says nothing or does not parse, or its
   * harness is one this binary does not know; null, unread, only when the container could not be
   * asked (stopped, missing, or no answer within {@link #TIMEOUT}).
   */
  public RunUsage read(RunStore.RunRow run) {
    Harness harness;
    try {
      harness = Harnesses.of(run.agent());
    } catch (IllegalArgumentException unknown) {
      return RunUsage.NONE;
    }
    var path =
        harness.reportFile() == Harness.ReportFile.TRANSCRIPT
            ? run.transcriptPath()
            : AgentUnit.readableLogPath(run.id(), run.logPath());
    if (Strings.isBlank(path)) {
      return RunUsage.NONE;
    }
    try {
      var marked = lastMarked(run.project(), path, harness.reportMarkers());
      return marked == null ? null : parsed(run, harness.usage(inFileOrder(marked)));
    } catch (IOException | TimeoutException unanswered) {
      return null;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private static RunUsage parsed(RunStore.RunRow run, RunUsage usage) {
    if (usage != null) {
      return usage;
    }
    System.err.println(
        "  [usage] the report of run " + run.id() + " could not be parsed: recorded none");
    return RunUsage.NONE;
  }

  /** Each marker's lines in the file's order, the markers in the reverse of theirs. */
  private static String inFileOrder(String newestFirst) {
    return String.join("\n", newestFirst.lines().toList().reversed());
  }

  /**
   * The last {@link #LINES} lines of {@code path} in {@code container} holding each of {@code
   * markers}, newest first: empty when none does or the file is not there, and null when the
   * container did not answer.
   */
  private String lastMarked(String container, String path, List<String> markers)
      throws IOException, InterruptedException, TimeoutException {
    var read =
        shell.exec(
            ContainerExec.asDevUser(container, null, Map.of("LC_ALL", "C"), command(path, markers)),
            null,
            TIMEOUT);
    return read.ok() ? read.stdout() : null;
  }

  private static List<String> command(String path, List<String> markers) {
    var command =
        new ArrayList<>(List.of("sh", "-c", LAST_MARKED, "sh", path, String.valueOf(LINES)));
    command.addAll(markers);
    return command;
  }
}
