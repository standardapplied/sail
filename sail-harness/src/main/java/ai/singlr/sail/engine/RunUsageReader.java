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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Reads what a finished run cost from its harness's own report, inside the run's container: the
 * last bytes of the file its harness reports in ({@link Harness#reportFile}), which its harness
 * reads ({@link Harness#usage}). A report this cannot read is left unread, never taken for none:
 * the run's directory is then kept and the read tried again (M3).
 */
public final class RunUsageReader {

  /** How much of the file is read from its end: a harness writes its final report last. */
  static final int TAIL_BYTES = 4 << 20;

  /** How long one read waits on the container before the report is left unread. */
  static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** What {@code tail} and {@code stat}, run in the C locale, end with for a file not there. */
  private static final String MISSING = "No such file or directory";

  private final ShellExec shell;

  public RunUsageReader(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * What run {@code run}'s harness reported: {@link RunUsage#NONE} when its report file is not
   * there or says nothing, or its harness is one this binary does not know; null, unread, when the
   * container could not be asked (stopped, missing, or no answer within {@link #TIMEOUT}), when the
   * last report does not parse, or when the file is longer than {@link #TAIL_BYTES} and its last
   * bytes hold no report, so the report may lie before them.
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
      var tail =
          printed(run.project(), List.of("tail", "-c", String.valueOf(TAIL_BYTES), "--", path));
      if (tail == null) {
        return null;
      }
      var usage = harness.usage(tail);
      if (usage == null || usage.reported() || !mayBeLonger(tail)) {
        return usage;
      }
      var size = printed(run.project(), List.of("stat", "-c", "%s", "--", path));
      return size == null || Long.parseLong(size.strip()) > TAIL_BYTES ? null : usage;
    } catch (IOException | TimeoutException | NumberFormatException unanswered) {
      return null;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  /**
   * Whether {@code tail} may be a cut window: its text, encoded again, is at least {@link
   * #TAIL_BYTES} long. Undecodable bytes only lengthen the estimate, so a shorter one is a whole
   * file.
   */
  private static boolean mayBeLonger(String tail) {
    return tail.getBytes(StandardCharsets.UTF_8).length >= TAIL_BYTES;
  }

  /**
   * What {@code command} printed in the container: empty when it says the file it reads is not
   * there, and null for any other failure, which is the container's, not the file's.
   */
  private String printed(String container, List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    var read =
        shell.exec(
            ContainerExec.asDevUser(container, null, Map.of("LC_ALL", "C"), command),
            null,
            TIMEOUT);
    if (read.ok()) {
      return read.stdout();
    }
    var said = Objects.toString(read.stderr(), "").strip();
    return said.startsWith(command.getFirst() + ": ") && said.endsWith(MISSING) ? "" : null;
  }
}
