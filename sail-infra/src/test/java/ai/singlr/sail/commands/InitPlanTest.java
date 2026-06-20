/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import ai.singlr.sail.commands.InitPlan.Step;
import java.util.List;
import org.junit.jupiter.api.Test;

class InitPlanTest {

  @Test
  void freshMainProvisionsInstallsSystemApiThenTakesIdentity() {
    assertEquals(
        List.of(Step.PROVISION, Step.INSTALL_API_SYSTEM, Step.SSH_IDENTITY, Step.SYNC_AS_MAIN),
        InitPlan.plan(new InitIntent.Main(), false, false));
  }

  @Test
  void freshNodeProvisionsInstallsSystemApiThenJoins() {
    assertEquals(
        List.of(Step.PROVISION, Step.INSTALL_API_SYSTEM, Step.JOIN_MAIN),
        InitPlan.plan(new InitIntent.Node("sail@main"), false, false));
  }

  @Test
  void anAlreadyProvisionedBoxSkipsProvisioning() {
    var plan = InitPlan.plan(new InitIntent.Main(), true, false);
    assertFalse(plan.contains(Step.PROVISION));
    assertEquals(Step.INSTALL_API_SYSTEM, plan.getFirst());
  }

  @Test
  void underSudoTheApiIsInstalledPerUser() {
    assertEquals(
        List.of(Step.INSTALL_API_USER, Step.JOIN_MAIN),
        InitPlan.plan(new InitIntent.Node("sail@main"), true, true));
  }

  @Test
  void onlyMainPublishesAnSshIdentityAndSyncs() {
    var node = InitPlan.plan(new InitIntent.Node("sail@main"), true, false);
    assertFalse(node.contains(Step.SSH_IDENTITY));
    assertFalse(node.contains(Step.SYNC_AS_MAIN));
  }
}
