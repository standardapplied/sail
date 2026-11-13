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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Reads what a finished run cost from its harness's own report, inside the run's container: the
 * last lines of the file its harness reports in ({@link Harness#reportFile}) that hold one of its
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
   * The last {@code $2} lines of file {@code $1} holding one of the newline-separated markers
   * {@code $3}, newest first, read from its end and stopping at the last, as text whatever bytes it
   * holds: grep exits 1, saying nothing, when no line holds one.
   */
  private static final String LAST_MARKED = "tac -- \"$1\" | grep -m \"$2\" -a -F -e \"$3\"";

  /** What {@code tac}, run in the C locale, ends with for a file not there. */
  private static final String MISSING = "No such file or directory";

  private final ShellExec shell;

  public RunUsageReader(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * What run {@code run}'s harness reported: {@link RunUsage#NONE} when its report file is not
   * there, holds no line with a marker, says nothing or does not parse, or its harness is one this
   * binary does not know; null, unread, only when the container could not be asked (stopped,
   * missing, or no answer within {@link #TIMEOUT}).
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

  private static String inFileOrder(String newestFirst) {
    return String.join("\n", newestFirst.lines().toList().reversed());
  }

  /**
   * The last {@link #LINES} lines of {@code path} in {@code container} holding one of {@code
   * markers}, newest first: empty when none does or the file is not there, and null for any other
   * failure, which is the container's, not the file's.
   */
  private String lastMarked(String container, String path, List<String> markers)
      throws IOException, InterruptedException, TimeoutException {
    var read =
        shell.exec(
            ContainerExec.asDevUser(
                container,
                null,
                Map.of("LC_ALL", "C"),
                List.of(
                    "sh",
                    "-c",
                    LAST_MARKED,
                    "sh",
                    path,
                    String.valueOf(LINES),
                    String.join("\n", markers))),
            null,
            TIMEOUT);
    if (read.ok()) {
      return read.stdout();
    }
    var said = Objects.toString(read.stderr(), "").strip();
    var nothingMarked = said.isEmpty() || (said.startsWith("tac: ") && said.endsWith(MISSING));
    return read.exitCode() == 1 && nothingMarked ? "" : null;
  }
}
