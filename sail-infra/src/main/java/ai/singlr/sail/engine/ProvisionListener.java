/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

/** Callback for reporting progress during multi-step provisioning operations. */
public interface ProvisionListener {

  void onStep(int step, int total, String description);

  void onStepDone(int step, int total, String detail);

  default void onStepSkipped(int step, int total, String detail) {}

  /** The step running moved on: {@code detail} says how far, until the next one replaces it. */
  default void onStepProgress(String detail) {}

  /** The step running, if one is, failed: its failure is reported after this returns. */
  default void onStepFailed() {}

  /** A no-op listener that silently discards all events. */
  ProvisionListener NOOP =
      new ProvisionListener() {
        @Override
        public void onStep(int step, int total, String description) {}

        @Override
        public void onStepDone(int step, int total, String detail) {}
      };
}
