/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.commands.InitPlan.Step;
import java.util.List;
import org.junit.jupiter.api.Test;

class InitPlanTest {

  @Test
  void freshMainProvisionsInstallsSystemApiThenTakesIdentity() {
    assertEquals(
        List.of(Step.PROVISION, Step.INSTALL_API_SYSTEM, Step.SSH_IDENTITY, Step.SYNC_AS_MAIN),
        InitPlan.plan(new InitIntent.Main(), false));
  }

  @Test
  void freshNodeProvisionsInstallsSystemApiThenJoins() {
    assertEquals(
        List.of(Step.PROVISION, Step.INSTALL_API_SYSTEM, Step.JOIN_MAIN),
        InitPlan.plan(new InitIntent.Node("sail@main"), false));
  }

  @Test
  void anAlreadyProvisionedBoxSkipsProvisioning() {
    assertEquals(
        List.of(Step.INSTALL_API_SYSTEM, Step.SSH_IDENTITY, Step.SYNC_AS_MAIN),
        InitPlan.plan(new InitIntent.Main(), true));
    assertEquals(
        List.of(Step.INSTALL_API_SYSTEM, Step.JOIN_MAIN),
        InitPlan.plan(new InitIntent.Node("sail@main"), true));
  }

  @Test
  void theApiIsOnlyEverTheSystemService() {
    assertEquals(
        List.of(
            Step.PROVISION,
            Step.INSTALL_API_SYSTEM,
            Step.SSH_IDENTITY,
            Step.SYNC_AS_MAIN,
            Step.JOIN_MAIN),
        List.of(Step.values()));
  }
}
