/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.Spinner;
import picocli.CommandLine.Help.Ansi;

/**
 * Prints styled provisioning progress to stdout using picocli ANSI markup. Shows an animated
 * spinner on the current step while it is running, with its latest progress, then replaces it with
 * the final status line; a step that fails is replaced by its ✗ line, so the reason reported next
 * starts a line of its own.
 */
final class ConsoleProvisionListener implements ProvisionListener {

  static final ConsoleProvisionListener INSTANCE = new ConsoleProvisionListener();

  private volatile Running running;

  private ConsoleProvisionListener() {}

  @Override
  public void onStep(int step, int total, String description) {
    var label = Ansi.AUTO.string("@|bold [" + step + "/" + total + "]|@ ") + description;
    running =
        new Running(
            Spinner.start(System.out, label),
            Banner.stepFailedLine(step, total, description, Ansi.AUTO));
  }

  @Override
  public void onStepDone(int step, int total, String detail) {
    stopSpinner();
    System.out.println(Banner.stepDoneLine(step, total, detail, Ansi.AUTO));
  }

  @Override
  public void onStepSkipped(int step, int total, String detail) {
    stopSpinner();
    System.out.println(Banner.stepSkippedLine(step, total, detail, Ansi.AUTO));
  }

  @Override
  public void onStepProgress(String detail) {
    var r = running;
    if (r != null) {
      r.spinner().detail(detail);
    }
  }

  @Override
  public void onStepFailed() {
    var failed = stopSpinner();
    if (failed != null) {
      System.out.println(failed.failedLine());
    }
  }

  private Running stopSpinner() {
    var r = running;
    if (r != null) {
      r.spinner().stop();
      running = null;
    }
    return r;
  }

  /** The step whose spinner runs, and the line it ends on if it fails. */
  private record Running(Spinner spinner, String failedLine) {}
}
