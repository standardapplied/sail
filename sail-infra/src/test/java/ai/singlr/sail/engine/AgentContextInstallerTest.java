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
import ai.singlr.sail.gen.AgentAuditFiles;
import ai.singlr.sail.gen.GeneratedFile;
import org.junit.jupiter.api.Test;

class AgentContextInstallerTest {

  private static final String CONTAINER = "light-grid";
  private static final String WORKSPACE = "/home/dev/workspace";
  private static final String SKILL = WORKSPACE + "/.claude/skills/spec-board/SKILL.md";

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

  private static SailYaml configWithAudits() {
    return SailYaml.fromMap(
        YamlUtil.parseMap(
            """
            name: acme
            resources: { cpu: 2, memory: 4GB, disk: 20GB }
            image: ubuntu/24.04
            agent:
              type: claude-code
              install: [claude-code, codex]
              security_audit: { enabled: true }
              code_review: { enabled: true }
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
            .anyMatch(c -> c.contains("mkdir -p " + WORKSPACE + "/.claude/skills/spec-board")),
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
  void mergesBothTheContextFileAndSecurityMdPreservingTheirPersonalRegions() throws Exception {
    var shell =
        okShell()
            .onOk("cat " + WORKSPACE + "/CLAUDE.md", "old body\n\nmy personal note\n")
            .onOk("cat " + WORKSPACE + "/SECURITY.md", "old\n\nmy security note\n");

    var result = AgentContextInstaller.install(shell, CONTAINER, config());

    assertTrue(
        shell.invocations().stream().anyMatch(c -> c.contains("cat " + WORKSPACE + "/CLAUDE.md")),
        "the context file is merged: its existing personal region is read");
    assertTrue(
        shell.invocations().stream().anyMatch(c -> c.contains("cat " + WORKSPACE + "/SECURITY.md")),
        "SECURITY.md is merged the same way, not skipped");
    assertTrue(result.pushed().stream().anyMatch(p -> p.endsWith("/CLAUDE.md")));
    assertTrue(result.pushed().stream().anyMatch(p -> p.endsWith("/SECURITY.md")));
    assertTrue(result.pushed().contains(SKILL), "machinery still refreshes");
  }

  @Test
  void forceResetsMergeFilesWithoutReadingThem() throws Exception {
    var shell = okShell();

    var result = AgentContextInstaller.install(shell, CONTAINER, config(), true);

    assertTrue(result.pushed().stream().anyMatch(p -> p.endsWith("/CLAUDE.md")));
    assertTrue(result.pushed().stream().anyMatch(p -> p.endsWith("/SECURITY.md")));
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.contains("cat " + WORKSPACE)),
        "--force resets the personal region instead of reading and preserving it");
  }

  @Test
  void pushesAFreshContextFileWhenTheContainerHasNone() throws Exception {
    var shell = okShell();

    var result = AgentContextInstaller.install(shell, CONTAINER, config());

    assertTrue(result.pushed().stream().anyMatch(p -> p.endsWith("/CLAUDE.md")));
  }

  @Test
  void treatsAnUnreadableContextFileAsFresh() throws Exception {
    var shell = okShell().onFail("cat " + WORKSPACE + "/CLAUDE.md", "No such file or directory");

    var result = AgentContextInstaller.install(shell, CONTAINER, config());

    assertTrue(
        result.pushed().stream().anyMatch(p -> p.endsWith("/CLAUDE.md")),
        "an absent or unreadable context file is rendered fresh, not skipped");
  }

  @Test
  void marksExecutableAuditFilesExecutableOnPush() throws Exception {
    var config = configWithAudits();
    assertTrue(
        AgentAuditFiles.assemble(config).stream().anyMatch(GeneratedFile::executable),
        "precondition: this config generates an executable orchestrator");
    var shell = okShell();

    AgentContextInstaller.install(shell, CONTAINER, config);

    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("incus file push") && c.contains("--mode 0755")),
        "executable files are pushed with mode 0755");
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
