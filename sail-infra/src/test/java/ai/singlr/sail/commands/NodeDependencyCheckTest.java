/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.ProjectDefaults;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NodeDependencyCheckTest {

  private InputStream originalStdin = System.in;

  @AfterEach
  void restoreStdin() {
    System.setIn(originalStdin);
  }

  @Test
  void noAgentConfigReturnsEmpty() {
    var config = parseConfig(YAML_NO_AGENT);

    var result = NodeDependencyCheck.findNodeDependentAgents(config);

    assertTrue(result.isEmpty());
  }

  @Test
  void claudeCodeAloneNeverTriggersCheck() {
    var config = parseConfig(YAML_CLAUDE_ONLY);

    var result = NodeDependencyCheck.findNodeDependentAgents(config);

    assertTrue(result.isEmpty());
  }

  @Test
  void claudeCodeWithNodeDoesNotTrigger() {
    var config = parseConfig(YAML_CLAUDE_WITH_NODE);

    assertTrue(NodeDependencyCheck.hasNodeRuntime(config));
    assertTrue(NodeDependencyCheck.findNodeDependentAgents(config).isEmpty());
  }

  @Test
  void codexWithoutNodeDetected() {
    var config = parseConfig(YAML_CODEX_NO_NODE);

    var agents = NodeDependencyCheck.findNodeDependentAgents(config);

    assertEquals(1, agents.size());
    assertEquals(AgentCli.CODEX, agents.getFirst());
    assertFalse(NodeDependencyCheck.hasNodeRuntime(config));
  }

  @Test
  void singleCodexAgentDetectedTwice() {
    var config = parseConfig(YAML_MULTI_AGENT_NO_NODE);

    var agents = NodeDependencyCheck.findNodeDependentAgents(config);

    assertEquals(1, agents.size());
    assertEquals(AgentCli.CODEX, agents.getFirst());
  }

  @Test
  void codexWithNodeReturnsUnchanged() {
    var config = parseConfig(YAML_CODEX_WITH_NODE);

    var resolution = NodeDependencyCheck.resolve(config, false);

    assertInstanceOf(NodeDependencyCheck.Resolution.Unchanged.class, resolution);
  }

  @Test
  void autoAcceptAddsNode() {
    var config = parseConfig(YAML_CODEX_NO_NODE);

    var resolution = NodeDependencyCheck.resolve(config, true);

    assertInstanceOf(NodeDependencyCheck.Resolution.NodeAdded.class, resolution);
    var added = (NodeDependencyCheck.Resolution.NodeAdded) resolution;
    assertEquals(ProjectDefaults.DEFAULT_NODE_VERSION, added.config().runtimes().node());
  }

  @Test
  void interactiveAcceptAddsNode() {
    simulateStdin("y\n");
    var config = parseConfig(YAML_CODEX_NO_NODE);

    var resolution = NodeDependencyCheck.resolve(config, false);

    assertInstanceOf(NodeDependencyCheck.Resolution.NodeAdded.class, resolution);
    var added = (NodeDependencyCheck.Resolution.NodeAdded) resolution;
    assertEquals(ProjectDefaults.DEFAULT_NODE_VERSION, added.config().runtimes().node());
  }

  @Test
  void interactiveDeclineThenAcceptDropsAgents() {
    simulateStdin("n\ny\n");
    var config = parseConfig(YAML_CODEX_NO_NODE);

    var resolution = NodeDependencyCheck.resolve(config, false);

    assertInstanceOf(NodeDependencyCheck.Resolution.AgentsDropped.class, resolution);
    var dropped = (NodeDependencyCheck.Resolution.AgentsDropped) resolution;
    assertEquals(List.of(AgentCli.CODEX), dropped.dropped());
    assertNull(dropped.config().runtimes().node());
  }

  @Test
  void interactiveDeclineBothAborts() {
    simulateStdin("n\nn\n");
    var config = parseConfig(YAML_CODEX_NO_NODE);

    var resolution = NodeDependencyCheck.resolve(config, false);

    assertInstanceOf(NodeDependencyCheck.Resolution.Aborted.class, resolution);
  }

  @Test
  void failNonInteractiveThrowsWhenMismatch() {
    var config = parseConfig(YAML_CODEX_NO_NODE);

    var ex =
        assertThrows(
            IllegalStateException.class, () -> NodeDependencyCheck.failNonInteractive(config));

    assertTrue(ex.getMessage().contains("Node.js"));
    assertTrue(ex.getMessage().contains(ProjectDefaults.DEFAULT_NODE_VERSION));
  }

  @Test
  void failNonInteractiveDoesNothingWhenNoMismatch() {
    var config = parseConfig(YAML_CODEX_WITH_NODE);

    assertDoesNotThrow(() -> NodeDependencyCheck.failNonInteractive(config));
  }

  @Test
  void withNodeRuntimePreservesExistingRuntimes() {
    var config = parseConfig(YAML_CODEX_NO_NODE);
    var updated = config.withNodeRuntime("22.0.0");

    assertEquals(25, updated.runtimes().jdk());
    assertEquals("22.0.0", updated.runtimes().node());
  }

  @Test
  void withAgentInstallFiltersAgents() {
    var config = parseConfig(YAML_MULTI_AGENT_NO_NODE);
    var updated = config.withAgentInstall(List.of("claude-code"));

    assertEquals(List.of("claude-code"), updated.agent().install());
    assertEquals("claude-code", updated.agent().type());
  }

  @Test
  void defaultInstallListUsesAgentType() {
    var config = parseConfig(YAML_CODEX_DEFAULT_INSTALL);

    var agents = NodeDependencyCheck.findNodeDependentAgents(config);

    assertEquals(1, agents.size());
    assertEquals(AgentCli.CODEX, agents.getFirst());
  }

  private void simulateStdin(String input) {
    System.setIn(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)));
    try {
      var field = ConsoleHelper.class.getDeclaredField("stdinReader");
      field.setAccessible(true);
      field.set(null, null);
    } catch (Exception ignored) {
    }
  }

  private static SailYaml parseConfig(String yaml) {
    return SailYaml.fromMap(YamlUtil.parseMap(yaml));
  }

  private static final String YAML_NO_AGENT =
      """
      name: test
      resources:
        cpu: 2
        memory: 4GB
        disk: 20GB
      """;

  private static final String YAML_CLAUDE_ONLY =
      """
      name: test
      resources:
        cpu: 2
        memory: 4GB
        disk: 20GB
      agent:
        type: claude-code
      """;

  private static final String YAML_CLAUDE_WITH_NODE =
      """
      name: test
      resources:
        cpu: 2
        memory: 4GB
        disk: 20GB
      runtimes:
        node: "22.0.0"
      agent:
        type: claude-code
      """;

  private static final String YAML_CODEX_NO_NODE =
      """
      name: test
      resources:
        cpu: 2
        memory: 4GB
        disk: 20GB
      runtimes:
        jdk: 25
      agent:
        type: codex
        install:
          - codex
      """;

  private static final String YAML_CODEX_DEFAULT_INSTALL =
      """
      name: test
      resources:
        cpu: 2
        memory: 4GB
        disk: 20GB
      agent:
        type: codex
      """;

  private static final String YAML_MULTI_AGENT_NO_NODE =
      """
      name: test
      resources:
        cpu: 2
        memory: 4GB
        disk: 20GB
      runtimes:
        jdk: 25
      agent:
        type: claude-code
        install:
          - claude-code
          - codex
      """;

  private static final String YAML_CODEX_WITH_NODE =
      """
      name: test
      resources:
        cpu: 2
        memory: 4GB
        disk: 20GB
      runtimes:
        jdk: 25
        node: "24.14.1"
      agent:
        type: codex
        install:
          - codex
      """;
}
