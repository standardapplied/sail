/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered steps that bring a box fully online, decided without I/O so the sequencing across
 * heterogeneous box states is unit-tested. A box already provisioned skips provisioning; sail-api
 * is always installed as the system service, whoever invoked {@code sudo}; then the box takes on
 * its identity — a main publishes its SSH identity and starts as the org's source of truth, a node
 * joins an existing main.
 */
final class InitPlan {

  enum Step {
    PROVISION,
    INSTALL_API_SYSTEM,
    SSH_IDENTITY,
    SYNC_AS_MAIN,
    JOIN_MAIN
  }

  private InitPlan() {}

  static List<Step> plan(InitIntent intent, boolean provisioned) {
    var steps = new ArrayList<Step>();
    if (!provisioned) {
      steps.add(Step.PROVISION);
    }
    steps.add(Step.INSTALL_API_SYSTEM);
    switch (intent) {
      case InitIntent.Main ignored -> {
        steps.add(Step.SSH_IDENTITY);
        steps.add(Step.SYNC_AS_MAIN);
      }
      case InitIntent.Node ignored -> steps.add(Step.JOIN_MAIN);
    }
    return List.copyOf(steps);
  }
}
