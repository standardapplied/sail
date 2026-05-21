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
 * spinner on the current step while it is running, then replaces it with the final status line.
 */
final class ConsoleProvisionListener implements ProvisionListener {

  static final ConsoleProvisionListener INSTANCE = new ConsoleProvisionListener();

  private volatile Spinner spinner;

  private ConsoleProvisionListener() {}

  @Override
  public void onStep(int step, int total, String description) {
    var label = Ansi.AUTO.string("@|bold [" + step + "/" + total + "]|@ ") + description;
    spinner = Spinner.start(System.out, label);
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

  private void stopSpinner() {
    var s = spinner;
    if (s != null) {
      s.stop();
      spinner = null;
    }
  }
}
