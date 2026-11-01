/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class BranchPolicyTest {

  @Test
  void nullWhenAgentMissing() {
    assertNull(BranchPolicy.branchName(configWithAgent(null), spec("oauth-flow", null)));
  }

  @Test
  void nullWhenAutoBranchDisabled() {
    var config = configWithAgent(agent(false, "sail/"));

    assertNull(BranchPolicy.branchName(config, spec("oauth-flow", null)));
  }

  @Test
  void nullWhenConfigItselfIsNull() {
    assertNull(BranchPolicy.branchName(null, spec("oauth-flow", null)));
  }

  @Test
  void prefixesSpecIdWithCustomPrefix() {
    var config = configWithAgent(agent(true, "feat/"));

    assertEquals("feat/oauth-flow", BranchPolicy.branchName(config, spec("oauth-flow", null)));
  }

  @Test
  void fallsBackToSailPrefixWhenPrefixIsNull() {
    var config = configWithAgent(agent(true, null));

    assertEquals("sail/oauth-flow", BranchPolicy.branchName(config, spec("oauth-flow", null)));
  }

  @Test
  void fallsBackToSailPrefixWhenPrefixIsBlank() {
    var config = configWithAgent(agent(true, "  "));

    assertEquals("sail/oauth-flow", BranchPolicy.branchName(config, spec("oauth-flow", null)));
  }

  @Test
  void specBranchOverridesPrefix() {
    var config = configWithAgent(agent(true, "feat/"));

    assertEquals(
        "engineer-chose-this",
        BranchPolicy.branchName(config, spec("oauth-flow", "engineer-chose-this")));
  }

  @Test
  void blankSpecBranchFallsBackToComputedName() {
    var config = configWithAgent(agent(true, "feat/"));

    assertEquals("feat/oauth-flow", BranchPolicy.branchName(config, spec("oauth-flow", "   ")));
  }

  private static ProjectDefinition.Agent agent(boolean autoBranch, String prefix) {
    return new ProjectDefinition.Agent(
        "claude-code", autoBranch, prefix, false, null, null, null, null, null, null);
  }

  private static ProjectDefinition configWithAgent(ProjectDefinition.Agent agent) {
    return new ProjectDefinition(
        "test",
        null,
        new ProjectDefinition.Resources(2, "4GB", "50GB"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        agent,
        null);
  }

  private static Spec spec(String id, String branch) {
    return new Spec(
        id,
        "test-project",
        id,
        SpecStatus.PENDING,
        null,
        List.of(),
        List.of(),
        null,
        null,
        null,
        branch);
  }
}
