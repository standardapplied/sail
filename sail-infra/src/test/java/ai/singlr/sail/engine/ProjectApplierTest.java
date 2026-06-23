/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SecurityAudit;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectApplierTest {

  private static final String CONTAINER = "acme-health";

  @TempDir Path tempDir;

  @Test
  void applyServicesStartsNewService() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("podman container inspect postgres", "no such container")
            .onOk("podman run");
    var applier = applier(shell);

    var services =
        Map.of("postgres", new SailYaml.Service("postgres:16", List.of(5432), null, null, null));
    var result = applier.applyServices(CONTAINER, services);

    assertEquals(1, result.added());
    assertEquals(0, result.skipped());
    assertTrue(shell.invocations().stream().anyMatch(c -> c.contains("podman run")));
  }

  @Test
  void applyServicesSkipsExistingService() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("podman container inspect postgres");
    var applier = applier(shell);

    var services =
        Map.of("postgres", new SailYaml.Service("postgres:16", List.of(5432), null, null, null));
    var result = applier.applyServices(CONTAINER, services);

    assertEquals(0, result.added());
    assertEquals(1, result.skipped());
    assertFalse(shell.invocations().stream().anyMatch(c -> c.contains("podman run")));
  }

  @Test
  void applyServicesMixedNewAndExisting() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("podman container inspect postgres")
            .onFail("podman container inspect redis", "no such container")
            .onOk("podman run");
    var applier = applier(shell);

    var services = new LinkedHashMap<String, SailYaml.Service>();
    services.put("postgres", new SailYaml.Service("postgres:16", List.of(5432), null, null, null));
    services.put("redis", new SailYaml.Service("redis:7", List.of(6379), null, null, null));
    var result = applier.applyServices(CONTAINER, services);

    assertEquals(1, result.added());
    assertEquals(1, result.skipped());
  }

  @Test
  void applyServicesReturnsEmptyWhenNull() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyServices(CONTAINER, null);

    assertEquals(0, result.added());
    assertEquals(0, result.skipped());
  }

  @Test
  void applyReposClonesNewRepo() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("test -d /home/dev/workspace/backend", "not found")
            .onOk("git clone");
    var applier = applier(shell);

    var repos = List.of(new SailYaml.Repo("https://github.com/org/backend.git", "backend", null));
    var result = applier.applyRepos(CONTAINER, repos, "dev", null, null);

    assertEquals(1, result.added());
    assertTrue(shell.invocations().stream().anyMatch(c -> c.contains("git clone")));
  }

  @Test
  void applyReposSkipsExistingRepo() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("test -d /home/dev/workspace/backend");
    var applier = applier(shell);

    var repos = List.of(new SailYaml.Repo("https://github.com/org/backend.git", "backend", null));
    var result = applier.applyRepos(CONTAINER, repos, "dev", null, null);

    assertEquals(0, result.added());
    assertEquals(1, result.skipped());
  }

  @Test
  void applyReposRefreshesCredentialStoreInsteadOfInjectingToken() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus file push")
            .onOk("chown")
            .onOk("git config")
            .onFail("test -d", "not found")
            .onOk("git clone");
    var applier = applier(shell);

    var repos = List.of(new SailYaml.Repo("https://github.com/org/private.git", "private", null));
    applier.applyRepos(CONTAINER, repos, "dev", Map.of("*", "ghp_secret123"), null);

    var cloneCmd = shell.invocations().stream().filter(c -> c.contains("git clone")).findFirst();
    assertTrue(cloneCmd.isPresent());
    assertFalse(
        cloneCmd.get().contains("ghp_secret123"),
        "Token must NOT appear in git clone command (visible in /proc/*/cmdline)");
    assertTrue(
        shell.invocations().stream().anyMatch(c -> c.contains("credential.helper")),
        "Should configure credential.helper store");
  }

  @Test
  void applyReposUsesSpecifiedBranch() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("test -d", "not found").onOk("git clone");
    var applier = applier(shell);

    var repos = List.of(new SailYaml.Repo("https://github.com/org/repo.git", "repo", "develop"));
    applier.applyRepos(CONTAINER, repos, "dev", null, null);

    var cloneCmd = shell.invocations().stream().filter(c -> c.contains("git clone")).findFirst();
    assertTrue(cloneCmd.isPresent());
    assertTrue(cloneCmd.get().contains("--branch develop"));
  }

  @Test
  void applyReposReturnsEmptyWhenNull() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyRepos(CONTAINER, null, "dev", null, null);

    assertEquals(0, result.added());
  }

  @Test
  void applyAgentToolsInstallsMissingAgent() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("bash -lc which codex", "not found")
            .onOk("which node")
            .onOk("bash -c");
    var applier = applier(shell);

    var result = applier.applyAgentTools(CONTAINER, List.of("codex"), null);

    assertEquals(1, result.added());
    assertTrue(shell.invocations().stream().anyMatch(c -> c.contains("bash -c")));
  }

  @Test
  void applyAgentToolsSkipsInstalledAgent() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("bash -lc which claude");
    var applier = applier(shell);

    var result = applier.applyAgentTools(CONTAINER, List.of("claude-code"), null);

    assertEquals(0, result.added());
    assertEquals(1, result.skipped());
  }

  @Test
  void applyAgentToolsUsesLoginShellForWhichCheck() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("bash -lc which claude");
    var applier = applier(shell);

    applier.applyAgentTools(CONTAINER, List.of("claude-code"), null);

    assertTrue(
        shell.invocations().stream().anyMatch(c -> c.contains("bash -lc which claude")),
        "Agent check must use login shell to find binaries on extended PATH");
  }

  @Test
  void applyAgentToolsInstallsNewAndSkipsExisting() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("bash -lc which claude")
            .onFail("bash -lc which codex", "not found")
            .onOk("which node")
            .onOk("bash -c");
    var applier = applier(shell);

    var result = applier.applyAgentTools(CONTAINER, List.of("claude-code", "codex"), null);

    assertEquals(1, result.added());
    assertEquals(1, result.skipped());
  }

  @Test
  void applyAgentToolsThrowsWhenNodeMissingForNpmAgent() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("bash -lc which codex", "not found")
            .onFail("which node", "not found");
    var applier = applier(shell);

    var ex =
        assertThrows(
            Exception.class, () -> applier.applyAgentTools(CONTAINER, List.of("codex"), null));

    assertTrue(ex.getMessage().contains("requires Node.js"));
  }

  @Test
  void applyAgentToolsReturnsEmptyWhenNull() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyAgentTools(CONTAINER, null, null);

    assertEquals(0, result.added());
  }

  @Test
  void applyGitConfigSetsIdentity() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var applier = applier(shell);

    var result =
        applier.applyGitConfig(
            CONTAINER, new SailYaml.Git("John", "john@acme.com", "token", null), "dev");

    assertEquals(1, result.added());
    assertTrue(
        shell.invocations().stream().anyMatch(c -> c.contains("user.name") && c.contains("John")));
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("user.email") && c.contains("john@acme.com")));
  }

  @Test
  void applyGitConfigReturnsEmptyWhenNull() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyGitConfig(CONTAINER, null, "dev");

    assertEquals(0, result.added());
  }

  @Test
  void applyAgentContextCreatesParentDirsBeforePush() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var applier = applier(shell);
    var config = minimalConfig("claude-code");

    applier.applyAgentContext(CONTAINER, config);

    var invocations = shell.invocations();
    var mkdirCmds = invocations.stream().filter(c -> c.contains("mkdir -p")).toList();
    var pushCmds =
        invocations.stream()
            .filter(c -> c.contains("incus file push") && c.contains("spec-board/"))
            .toList();
    assertFalse(
        mkdirCmds.isEmpty(), "Should create parent directories before pushing context files");
    assertFalse(pushCmds.isEmpty(), "Should push skill files");
  }

  @Test
  void applyAgentContextPushesClaudeMd() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var applier = applier(shell);
    var config = minimalConfig("claude-code");

    var result = applier.applyAgentContext(CONTAINER, config);

    assertTrue(result.added() > 0);
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("incus file push") && c.contains("CLAUDE.md")));
  }

  @Test
  void applyAgentContextPushesAgentsMdForCodex() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var applier = applier(shell);
    var config = minimalConfig("codex");

    var result = applier.applyAgentContext(CONTAINER, config);

    assertTrue(result.added() > 0);
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("incus file push") && c.contains("AGENTS.md")));
  }

  @Test
  void applyAgentContextSkipsWhenNoAgent() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    var result = applier.applyAgentContext(CONTAINER, config);

    assertEquals(0, result.added());
  }

  @Test
  void applyAgentContextMergesTheContextFileAndSecurityMd() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onOk("cat /home/dev/workspace/CLAUDE.md", "old\n\nmy note\n")
            .onOk("cat /home/dev/workspace/SECURITY.md", "old\n\nmy security note\n");
    var applier = applier(shell);
    var config = minimalConfig("claude-code");

    var result = applier.applyAgentContext(CONTAINER, config);

    assertEquals(
        0, result.skipped(), "a delta apply merges the editable files rather than skipping");
    assertTrue(
        shell.invocations().stream().anyMatch(c -> c.contains("cat /home/dev/workspace/CLAUDE.md")),
        "the context file is merged on apply, preserving the engineer's personal region");
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("cat /home/dev/workspace/SECURITY.md")),
        "SECURITY.md is merged the same way");
    assertTrue(result.added() > 0, "machinery (the spec-board skill) still refreshes");
  }

  @Test
  void applyServicesThrowsOnStartFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onFail("podman container inspect postgres", "no such container")
            .onFail("podman run", "image pull failed");
    var applier = applier(shell);

    var services =
        Map.of("postgres", new SailYaml.Service("postgres:16", List.of(5432), null, null, null));
    var ex = assertThrows(Exception.class, () -> applier.applyServices(CONTAINER, services));

    assertTrue(ex.getMessage().contains("Failed to start service"));
  }

  @Test
  void applyServicesReturnsEmptyWhenEmpty() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyServices(CONTAINER, Map.of());

    assertEquals(0, result.added());
    assertEquals(0, result.skipped());
  }

  @Test
  void applyReposThrowsOnCloneFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onFail("test -d", "not found")
            .onFail("git clone", "authentication failed");
    var applier = applier(shell);

    var repos = List.of(new SailYaml.Repo("https://github.com/org/backend.git", "backend", null));
    var ex =
        assertThrows(
            Exception.class, () -> applier.applyRepos(CONTAINER, repos, "dev", null, null));

    assertTrue(ex.getMessage().contains("Failed to clone"));
  }

  @Test
  void applyReposReturnsEmptyWhenEmpty() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyRepos(CONTAINER, List.of(), "dev", null, null);

    assertEquals(0, result.added());
  }

  @Test
  void applyReposTokenNeverInCloneUrlForAnyHost() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus file push")
            .onOk("chown")
            .onOk("git config")
            .onFail("test -d", "not found")
            .onOk("git clone");
    var applier = applier(shell);

    var repos = List.of(new SailYaml.Repo("https://gitlab.com/org/repo.git", "repo", null));
    applier.applyRepos(CONTAINER, repos, "dev", Map.of("*", "ghp_token"), null);

    var cloneCmd = shell.invocations().stream().filter(c -> c.contains("git clone")).findFirst();
    assertTrue(cloneCmd.isPresent());
    assertFalse(cloneCmd.get().contains("ghp_token"));
    assertTrue(cloneCmd.get().contains("gitlab.com"));
  }

  @Test
  void applyReposPushesSshKeyWhenGitAuthIsSsh() throws Exception {
    var keyFile = tempDir.resolve("id_ed25519");
    Files.writeString(keyFile, "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END-----\n");
    var pubFile = tempDir.resolve("id_ed25519.pub");
    Files.writeString(pubFile, "ssh-ed25519 AAAA... test@host");

    var shell =
        new ScriptedShellExecutor()
            .onOk("mkdir")
            .onOk("chmod")
            .onOk("incus file push")
            .onOk("chown")
            .onFail("test -d", "not found")
            .onOk("git clone");
    var applier = applier(shell);

    var repos = List.of(new SailYaml.Repo("git@github.com:org/repo.git", "repo", null));
    var git = new SailYaml.Git("Dev", "dev@test.com", "ssh", keyFile.toString());
    applier.applyRepos(CONTAINER, repos, "dev", null, git);

    var cmds = shell.invocations();
    assertTrue(
        cmds.stream().anyMatch(c -> c.contains("incus file push") && c.contains("id_ed25519")),
        "Should push SSH private key into container");
    assertTrue(
        cmds.stream().anyMatch(c -> c.contains("id_ed25519.pub")),
        "Should push public key when .pub file exists");
  }

  @Test
  void applyReposThrowsWhenSshKeyMissing() {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var repos = List.of(new SailYaml.Repo("git@github.com:org/repo.git", "repo", null));
    var git = new SailYaml.Git("Dev", "dev@test.com", "ssh", "/nonexistent/key");
    var ex =
        assertThrows(Exception.class, () -> applier.applyRepos(CONTAINER, repos, "dev", null, git));
    assertTrue(ex.getMessage().contains("SSH key not found"));
  }

  @Test
  void applyReposSkipsSshKeyWhenAuthIsToken() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("test -d", "not found").onOk("git clone");
    var applier = applier(shell);

    var repos = List.of(new SailYaml.Repo("https://github.com/org/repo.git", "repo", null));
    var git = new SailYaml.Git("Dev", "dev@test.com", "token", null);
    applier.applyRepos(CONTAINER, repos, "dev", null, git);

    var cmds = shell.invocations();
    assertFalse(
        cmds.stream().anyMatch(c -> c.contains("id_ed25519")),
        "Should NOT push SSH key when auth is token");
  }

  @Test
  void applyAgentToolsThrowsOnInstallFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onFail("bash -lc which claude", "not found")
            .onFail("bash -c", "npm install failed");
    var applier = applier(shell);

    var ex =
        assertThrows(
            Exception.class,
            () -> applier.applyAgentTools(CONTAINER, List.of("claude-code"), null));

    assertTrue(ex.getMessage().contains("Failed to install agent"));
  }

  @Test
  void applyAgentToolsReturnsEmptyWhenEmpty() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyAgentTools(CONTAINER, List.of(), null);

    assertEquals(0, result.added());
  }

  @Test
  void applyAgentToolsSkipsNodeCheckForClaudeCode() throws Exception {
    var shell =
        new ScriptedShellExecutor().onFail("bash -lc which claude", "not found").onOk("bash -c");
    var applier = applier(shell);

    var result = applier.applyAgentTools(CONTAINER, List.of("claude-code"), null);

    assertEquals(1, result.added());
    assertFalse(shell.invocations().stream().anyMatch(c -> c.contains("which node")));
  }

  @Test
  void applyAgentContextPushesAuditFilesWhenEnabled() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var applier = applier(shell);
    var audit = new SecurityAudit(true, "codex");
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            List.of("codex"),
            null,
            null,
            null,
            audit,
            null,
            null);
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            agent,
            null,
            null);

    var result = applier.applyAgentContext(CONTAINER, config);

    assertEquals(4, result.added());
    assertTrue(shell.invocations().stream().anyMatch(c -> c.contains("security-audit.sh")));
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("incus file push") && c.contains("--mode 0755")),
        "executable audit files are pushed with mode 0755");
    assertTrue(shell.invocations().stream().anyMatch(c -> c.contains("mkdir") && c.contains("-p")));
  }

  @Test
  void checkUnsupportedChangesWarnsWhenLiveLimitsDiffer() {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);
    var config = minimalConfig("claude-code");

    var warnings =
        applier.checkUnsupportedChanges(config, new ContainerManager.ResourceLimits("4", "12GB"));

    assertFalse(warnings.isEmpty());
    assertTrue(warnings.getFirst().contains("project resources set"));
  }

  @Test
  void checkUnsupportedChangesReturnsEmptyWhenNoResources() {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null);
    var config =
        new SailYaml(
            "test", null, null, null, null, null, null, null, null, null, agent, null, null);

    var warnings = applier.checkUnsupportedChanges(config, null);

    assertTrue(warnings.isEmpty());
  }

  @Test
  void removeServicesStopsAndRemovesContainer() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("podman container inspect postgres")
            .onOk("podman stop")
            .onOk("podman rm");
    var applier = applier(shell);

    var result = applier.removeServices(CONTAINER, List.of("postgres"));

    assertEquals(1, result.removed());
    assertEquals(0, result.skipped());
    assertTrue(shell.invocations().stream().anyMatch(c -> c.contains("podman stop")));
    assertTrue(shell.invocations().stream().anyMatch(c -> c.contains("podman rm")));
  }

  @Test
  void removeServicesSkipsWhenNotFound() throws Exception {
    var shell =
        new ScriptedShellExecutor().onFail("podman container inspect redis", "no such container");
    var applier = applier(shell);

    var result = applier.removeServices(CONTAINER, List.of("redis"));

    assertEquals(0, result.removed());
    assertEquals(1, result.skipped());
  }

  @Test
  void removeServicesReturnsEmptyWhenNull() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.removeServices(CONTAINER, null);

    assertEquals(0, result.removed());
  }

  @Test
  void removeServicesReturnsEmptyWhenEmpty() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.removeServices(CONTAINER, List.of());

    assertEquals(0, result.removed());
  }

  @Test
  void reconcileServicesRemovesOrphans() throws Exception {
    var podmanPsJson =
        """
        [{"Names":["postgres"]},{"Names":["redis"]}]
        """;
    var shell =
        new ScriptedShellExecutor()
            .onOk("podman ps --format json", podmanPsJson)
            .onOk("podman stop")
            .onOk("podman rm");
    var applier = applier(shell);

    var services =
        Map.of("postgres", new SailYaml.Service("postgres:16", List.of(5432), null, null, null));
    var result = applier.reconcileServices(CONTAINER, services);

    assertEquals(1, result.removed());
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("podman stop") && c.contains("redis")));
  }

  @Test
  void reconcileServicesNoOpsWhenAllMatch() throws Exception {
    var podmanPsJson =
        """
        [{"Names":["postgres"]}]
        """;
    var shell = new ScriptedShellExecutor().onOk("podman ps --format json", podmanPsJson);
    var applier = applier(shell);

    var services =
        Map.of("postgres", new SailYaml.Service("postgres:16", List.of(5432), null, null, null));
    var result = applier.reconcileServices(CONTAINER, services);

    assertEquals(0, result.removed());
  }

  @Test
  void reconcileServicesReturnsEmptyWhenNothingRunning() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("podman ps", "error");
    var applier = applier(shell);

    var services =
        Map.of("postgres", new SailYaml.Service("postgres:16", List.of(5432), null, null, null));
    var result = applier.reconcileServices(CONTAINER, services);

    assertEquals(0, result.removed());
  }

  @Test
  void reconcileServicesRemovesAllWhenConfigNull() throws Exception {
    var podmanPsJson =
        """
        [{"Names":["postgres"]},{"Names":["redis"]}]
        """;
    var shell =
        new ScriptedShellExecutor()
            .onOk("podman ps --format json", podmanPsJson)
            .onOk("podman stop")
            .onOk("podman rm");
    var applier = applier(shell);

    var result = applier.reconcileServices(CONTAINER, null);

    assertEquals(2, result.removed());
  }

  @Test
  void queryRunningServiceNamesExtractsNames() throws Exception {
    var podmanPsJson =
        """
        [{"Names":["postgres"],"State":"running"},{"Names":["redis"],"State":"running"}]
        """;
    var shell = new ScriptedShellExecutor().onOk("podman ps --format json", podmanPsJson);
    var applier = applier(shell);

    var names = applier.queryRunningServiceNames(CONTAINER);

    assertEquals(2, names.size());
    assertTrue(names.contains("postgres"));
    assertTrue(names.contains("redis"));
  }

  @Test
  void queryRunningServiceNamesReturnsEmptyOnFailure() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("podman ps", "error");
    var applier = applier(shell);

    var names = applier.queryRunningServiceNames(CONTAINER);

    assertTrue(names.isEmpty());
  }

  @Test
  void applyWorkspaceFilesPushesRecursively() throws Exception {
    var projectDir = tempDir.resolve("myproject");
    var filesDir = projectDir.resolve("files");
    Files.createDirectories(filesDir.resolve("outline"));
    Files.writeString(filesDir.resolve("outline/.env"), "KEY=VALUE");
    Files.writeString(filesDir.resolve("setup.sh"), "#!/bin/bash");
    var singYaml = projectDir.resolve("sail.yaml");
    Files.writeString(singYaml, "name: test");

    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var applier = applier(shell);

    var result = applier.applyWorkspaceFiles(CONTAINER, singYaml, "dev");

    assertEquals(2, result.added());
    assertTrue(
        shell.invocations().stream()
            .anyMatch(
                c ->
                    c.contains("incus file push")
                        && c.contains("--uid 1000")
                        && c.contains("--gid 1000")));
    assertTrue(
        shell.invocations().stream()
            .anyMatch(
                c ->
                    c.contains("incus file push")
                        && c.contains("setup.sh")
                        && c.contains("--mode 0755")),
        "Shell scripts should be pushed with --mode 0755");
    assertTrue(
        shell.invocations().stream()
            .anyMatch(
                c ->
                    c.contains("incus file push")
                        && c.contains(".env")
                        && !c.contains("--mode 0755")),
        "Non-script files should not have --mode 0755");
  }

  @Test
  void applyWorkspaceFilesReturnsEmptyWhenNoFilesDir() throws Exception {
    var singYaml = tempDir.resolve("sail.yaml");
    Files.writeString(singYaml, "name: test");

    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyWorkspaceFiles(CONTAINER, singYaml, "dev");

    assertEquals(0, result.added());
    assertTrue(shell.invocations().isEmpty());
  }

  @Test
  void applyWorkspaceFilesReturnsEmptyWhenFilesDirEmpty() throws Exception {
    Files.createDirectories(tempDir.resolve("files"));
    var singYaml = tempDir.resolve("sail.yaml");
    Files.writeString(singYaml, "name: test");

    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyWorkspaceFiles(CONTAINER, singYaml, "dev");

    assertEquals(0, result.added());
  }

  @Test
  void applyWorkspaceFilesReturnsEmptyWhenSailYamlPathNull() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyWorkspaceFiles(CONTAINER, null, "dev");

    assertEquals(0, result.added());
  }

  @Test
  void applyWorkspaceFilesPushesToCorrectWorkspacePath() throws Exception {
    var filesDir = tempDir.resolve("files");
    Files.createDirectories(filesDir);
    Files.writeString(filesDir.resolve("config.env"), "X=1");
    var singYaml = tempDir.resolve("sail.yaml");
    Files.writeString(singYaml, "name: test");

    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var applier = applier(shell);

    applier.applyWorkspaceFiles(CONTAINER, singYaml, "dev");

    assertTrue(
        shell.invocations().stream()
            .anyMatch(
                c ->
                    c.contains("incus file push")
                        && c.contains(CONTAINER + "/home/dev/workspace/")));
  }

  @Test
  void applySpecsScaffoldCreatesWhenDirDoesNotExist() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("test -d /home/dev/workspace/specs", "not found")
            .onOk("mkdir")
            .onOk("incus file push")
            .onOk("chown");
    var applier = applier(shell);
    var config = configWithSpecsDir("specs");

    var result = applier.applySpecsScaffold(CONTAINER, config);

    assertEquals(1, result.added());
    assertEquals(0, result.skipped());
    assertTrue(
        shell.invocations().stream().anyMatch(c -> c.contains("mkdir") && c.contains("specs")));
    assertFalse(shell.invocations().stream().anyMatch(c -> c.contains("incus file push")));
  }

  @Test
  void applySpecsScaffoldSkipsWhenDirExists() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("test -d /home/dev/workspace/specs");
    var applier = applier(shell);
    var config = configWithSpecsDir("specs");

    var result = applier.applySpecsScaffold(CONTAINER, config);

    assertEquals(0, result.added());
    assertEquals(1, result.skipped());
  }

  @Test
  void applySpecsScaffoldReturnsEmptyWhenSpecsDirNull() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null);
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            agent,
            null,
            null);

    var result = applier.applySpecsScaffold(CONTAINER, config);

    assertEquals(0, result.added());
    assertEquals(0, result.skipped());
    assertTrue(shell.invocations().isEmpty());
  }

  @Test
  void applyCleanupCronInstallsScriptsAndCron() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("crontab -l", "")
            .onFail("test -f", "not found")
            .onOk("mkdir")
            .onOk("incus file push")
            .onOk("mktemp", "/tmp/sail-crontab.abc123\n")
            .onOk("crontab -u")
            .onOk("rm -f");
    var applier = applier(shell);

    var result = applier.applyCleanupCron(CONTAINER, "dev");

    assertEquals(1, result.added());
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("incus file push") && c.contains("cleanup-containers.sh")));
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("incus file push") && c.contains("cleanup-agents.sh")));
  }

  @Test
  void applyCleanupCronSkipsWhenAlreadyUpgraded() throws Exception {
    var cronWithScript = "0 * * * * /home/dev/.sail/cleanup-containers.sh >/dev/null 2>&1\n";
    var shell =
        new ScriptedShellExecutor()
            .onOk("crontab -l", cronWithScript)
            .onOk("test -f /home/dev/workspace/cleanup-agents.sh");
    var applier = applier(shell);

    var result = applier.applyCleanupCron(CONTAINER, "dev");

    assertEquals(0, result.added());
    assertEquals(1, result.skipped());
    assertFalse(shell.invocations().stream().anyMatch(c -> c.contains("incus file push")));
  }

  @Test
  void applyCleanupCronReplacesLegacyCron() throws Exception {
    var legacyCron = "0 * * * * podman system prune -f --filter \"until=1h\" >/dev/null 2>&1\n";
    var shell =
        new ScriptedShellExecutor()
            .onOk("crontab -l", legacyCron)
            .onFail("test -f", "not found")
            .onOk("mkdir")
            .onOk("incus file push")
            .onOk("mktemp", "/tmp/sail-crontab.abc123\n")
            .onOk("crontab -u")
            .onOk("rm -f");
    var applier = applier(shell);

    var result = applier.applyCleanupCron(CONTAINER, "dev");

    assertEquals(1, result.added());
  }

  @Test
  void applyCleanupCronPreservesOtherCronEntries() throws Exception {
    var existingCron =
        "30 2 * * * /usr/local/bin/backup.sh\n0 * * * * podman system prune -f --filter \"until=1h\" >/dev/null 2>&1\n";
    var shell =
        new ScriptedShellExecutor()
            .onOk("crontab -l", existingCron)
            .onFail("test -f", "not found")
            .onOk("mkdir")
            .onOk("incus file push")
            .onOk("mktemp", "/tmp/sail-crontab.abc123\n")
            .onOk("crontab -u")
            .onOk("rm -f");
    var applier = applier(shell);

    applier.applyCleanupCron(CONTAINER, "dev");

    var pushCmds =
        shell.invocations().stream()
            .filter(c -> c.contains("incus file push") && c.contains("sail-crontab"))
            .toList();
    assertFalse(pushCmds.isEmpty());
  }

  @Test
  void applyCleanupCronThrowsOnCrontabInstallFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("crontab -l", "")
            .onFail("test -f", "not found")
            .onOk("mkdir")
            .onOk("incus file push")
            .onOk("mktemp", "/tmp/sail-crontab.abc123\n")
            .onFail("crontab -u", "permission denied");
    var applier = applier(shell);

    var ex = assertThrows(Exception.class, () -> applier.applyCleanupCron(CONTAINER, "dev"));

    assertTrue(ex.getMessage().contains("Failed to install crontab"));
  }

  private static ProjectApplier applier(ShellExec shell) {
    return new ProjectApplier(shell, new PrintStream(new ByteArrayOutputStream()));
  }

  private static SailYaml minimalConfig(String agentType) {
    var agent =
        new SailYaml.Agent(
            agentType, true, "sail/", true, null, null, null, "specs", null, null, null);
    return new SailYaml(
        "test",
        null,
        new SailYaml.Resources(2, "4GB", "50GB"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        agent,
        null,
        null);
  }

  private static SailYaml configWithSpecsDir(String specsDir) {
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, specsDir, null, null, null);
    return new SailYaml(
        "test",
        null,
        new SailYaml.Resources(2, "4GB", "50GB"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        agent,
        null,
        null);
  }
}
