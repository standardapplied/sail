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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Reads what a finished run cost from its harness's own report, inside the run's container: the
 * last bytes of its {@code agent.log} and of its transcript, which its harness reads ({@link
 * Harness#usage}). Read once, as the run finishes, before the transaction that finishes it (M1).
 */
public final class RunUsageReader {

  /** How much of each file is read from its end: a harness writes its final report last. */
  static final int TAIL_BYTES = 4 << 20;

  private final ShellExec shell;

  public RunUsageReader(ShellExec shell) {
    this.shell = Objects.requireNonNull(shell, "shell");
  }

  /**
   * What run {@code run}'s harness reported: {@link RunUsage#NONE} when its log and transcript are
   * missing or say nothing, or its harness is one this binary does not know; null when the
   * container could not be asked, so the run's usage stays unread.
   */
  public RunUsage read(RunStore.RunRow run) {
    Harness harness;
    try {
      harness = Harnesses.of(run.agent());
    } catch (IllegalArgumentException unknown) {
      return RunUsage.NONE;
    }
    try {
      var log = tail(run.project(), AgentUnit.readableLogPath(run.id(), run.logPath()));
      var transcript =
          Strings.isBlank(run.transcriptPath()) ? null : tail(run.project(), run.transcriptPath());
      return harness.usage(log, transcript).orElse(RunUsage.NONE);
    } catch (IOException | TimeoutException unanswered) {
      return null;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private String tail(String container, String path)
      throws IOException, InterruptedException, TimeoutException {
    var read =
        shell.exec(
            ContainerExec.asDevUser(
                container, List.of("tail", "-c", String.valueOf(TAIL_BYTES), "--", path)));
    return read.ok() ? read.stdout() : "";
  }
}
