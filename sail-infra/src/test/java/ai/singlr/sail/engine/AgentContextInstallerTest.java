/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import org.junit.jupiter.api.Test;

class AgentContextInstallerTest {

  private static final String CONTAINER = "light-grid";
  private static final String HOME = "/home/dev";
  private static final String CLAUDE = HOME + "/.claude/CLAUDE.md";
  private static final String SKILL = HOME + "/.claude/skills/spec-board/SKILL.md";

  private static SailYaml config() {
    return SailYaml.fromMap(
        YamlUtil.parseMap(
            """
            name: acme
            resources: { cpu: 2, memory: 4GB, disk: 20GB }
            image: ubuntu/24.04
            agent:
              type: claude-code
            ssh: { user: dev, authorized_keys: [] }
            """));
  }

  private static ScriptedShellExecutor okShell() {
    return new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
  }

  @Test
  void createsTheNestedSkillParentBeforePushingIt() throws Exception {
    var shell = okShell();

    var result = AgentContextInstaller.install(shell, CONTAINER, config());

    var commands = shell.invocations();
    assertTrue(
        commands.stream()
            .anyMatch(c -> c.contains("mkdir -p " + HOME + "/.claude/skills/spec-board")),
        "the nested skill directory must be created before the push");
    assertTrue(
        commands.stream().anyMatch(c -> c.contains("incus file push") && c.contains(SKILL)),
        "the spec-board skill must be pushed");
    assertTrue(result.pushed().contains(SKILL));
  }

  @Test
  void pushesGeneratedFilesOwnedByTheDevUser() throws Exception {
    var shell = okShell();

    AgentContextInstaller.install(shell, CONTAINER, config());

    assertTrue(
        shell.invocations().stream()
            .filter(c -> c.contains("incus file push"))
            .allMatch(c -> c.contains("--uid 1000 --gid 1000")),
        "every pushed file lands owned by the dev user");
  }

  @Test
  void installsTheHomeContextFileForClaude() throws Exception {
    var shell = okShell();

    var result = AgentContextInstaller.install(shell, CONTAINER, config());

    assertTrue(
        result.pushed().contains(CLAUDE),
        "sail installs its home-level CLAUDE.md, ~/.claude/CLAUDE.md");
  }

  @Test
  void overwritesEveryGeneratedFileWithoutCheckingExistence() throws Exception {
    var shell = okShell();

    var result = AgentContextInstaller.install(shell, CONTAINER, config());

    assertTrue(result.pushed().contains(CLAUDE));
    assertTrue(result.pushed().contains(SKILL));
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.contains("test -f")),
        "sail owns its home namespace outright, so it never checks whether a file exists");
  }

  @Test
  void neverWritesIntoTheEngineerWorkspace() throws Exception {
    var shell = okShell();

    var result = AgentContextInstaller.install(shell, CONTAINER, config());

    assertFalse(result.isEmpty());
    assertFalse(
        result.pushed().stream().anyMatch(p -> p.contains("/workspace/")),
        "sail never writes into the engineer's workspace");
  }

  @Test
  void returnsEmptyWhenNoAgentIsConfigured() throws Exception {
    var config =
        SailYaml.fromMap(
            YamlUtil.parseMap(
                """
                name: acme
                resources: { cpu: 2, memory: 4GB, disk: 20GB }
                image: ubuntu/24.04
                ssh: { user: dev, authorized_keys: [] }
                """));
    var shell = okShell();

    var result = AgentContextInstaller.install(shell, CONTAINER, config);

    assertTrue(result.isEmpty());
    assertTrue(result.pushed().isEmpty());
    assertTrue(
        shell.invocations().stream().noneMatch(c -> c.contains("incus file push")),
        "nothing is pushed when there is no agent context");
  }

  @Test
  void rejectsAnInvalidContainerName() {
    var shell = okShell();
    assertThrows(Exception.class, () -> AgentContextInstaller.install(shell, "../bad", config()));
  }
}
