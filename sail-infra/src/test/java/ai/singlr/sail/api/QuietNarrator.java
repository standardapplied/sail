/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.engine.GuardrailChecker.GuardrailResult.Triggered;
import java.time.Duration;

/** A watch's narrator for a test that reads what the watch did from what it published. */
public final class QuietNarrator implements RunWatch.Narrator {

  @Override
  public void tripped(Triggered limit, Duration elapsed, String snapshot) {}

  @Override
  public void exited() {}

  @Override
  public void ended() {}

  @Override
  public void endedBy(String type) {}

  @Override
  public void leftToTheReconciler() {}

  @Override
  public void stillRunning(Triggered limit, boolean survived) {}
}
