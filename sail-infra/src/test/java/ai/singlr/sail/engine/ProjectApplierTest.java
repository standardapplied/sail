/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.api.ProjectSkills;
import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
  void applyPackagesInstallsMissingBaselineTools() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("dpkg -s gh", "package 'gh' is not installed")
            .onFail("dpkg -s ripgrep", "package 'ripgrep' is not installed")
            .onOk("dpkg -s")
            .onOk("apt-get update")
            .onOk("apt-get install");
    var applier = applier(shell);

    var result = applier.applyPackages(CONTAINER, null);

    assertEquals(2, result.added());
    assertEquals(ProjectProvisioner.BASELINE_PACKAGES.size() - 2, result.skipped());
    var installCmd =
        shell.invocations().stream()
            .filter(c -> c.contains("apt-get install"))
            .findFirst()
            .orElseThrow();
    assertTrue(installCmd.contains("gh"));
    assertTrue(installCmd.contains("ripgrep"));
    assertFalse(installCmd.contains("curl"), "packages already present are not reinstalled");
  }

  @Test
  void applyPackagesSkipsAptEntirelyWhenAllPresent() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("dpkg -s");
    var applier = applier(shell);

    var result = applier.applyPackages(CONTAINER, List.of("postgresql-client-16"));

    assertEquals(0, result.added());
    assertEquals(ProjectProvisioner.BASELINE_PACKAGES.size() + 1, result.skipped());
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.contains("apt-get")),
        "a current container must not touch apt on converge");
  }

  @Test
  void applyPackagesInstallsMissingConfigPackages() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("dpkg -s postgresql-client-16", "not installed")
            .onOk("dpkg -s")
            .onOk("apt-get update")
            .onOk("apt-get install");
    var applier = applier(shell);

    var result = applier.applyPackages(CONTAINER, List.of("postgresql-client-16"));

    assertEquals(1, result.added());
    var installCmd =
        shell.invocations().stream()
            .filter(c -> c.contains("apt-get install"))
            .findFirst()
            .orElseThrow();
    assertTrue(installCmd.contains("postgresql-client-16"));
  }

  @Test
  void applyPackagesDeduplicatesConfigPackagesAlreadyInBaseline() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("dpkg -s");
    var applier = applier(shell);

    var result = applier.applyPackages(CONTAINER, List.of("git", "ripgrep"));

    assertEquals(ProjectProvisioner.BASELINE_PACKAGES.size(), result.skipped());
  }

  @Test
  void applyPackagesThrowsOnInstallFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onFail("dpkg -s gh", "not installed")
            .onOk("dpkg -s")
            .onOk("apt-get update")
            .onFail("apt-get install", "E: Unable to locate package");
    var applier = applier(shell);

    var ex = assertThrows(Exception.class, () -> applier.applyPackages(CONTAINER, null));

    assertTrue(ex.getMessage().contains("Failed to install packages"));
  }

  @Test
  void applyPackagesThrowsOnAptUpdateFailure() {
    var shell =
        new ScriptedShellExecutor()
            .onFail("dpkg -s gh", "not installed")
            .onOk("dpkg -s")
            .onFail("apt-get update", "no network");
    var applier = applier(shell);

    var ex = assertThrows(Exception.class, () -> applier.applyPackages(CONTAINER, null));

    assertTrue(ex.getMessage().contains("Failed to update package lists"));
  }

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
  void deltaProbesRideTheProbeShellAndMutationsTheMutator() throws Exception {
    var probes =
        new ScriptedShellExecutor()
            .onFail("podman container inspect postgres", "no such container")
            .onFail("test -d", "not found")
            .onFail("which", "not found");
    var mutator = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var applier = new ProjectApplier(probes, mutator, new PrintStream(new ByteArrayOutputStream()));

    var services =
        Map.of("postgres", new SailYaml.Service("postgres:16", List.of(5432), null, null, null));
    applier.applyServices(CONTAINER, services);
    applier.applyAgentTools(CONTAINER, List.of("claude-code"));
    applier.applyPackages(CONTAINER, null);

    assertTrue(
        probes.invocations().stream().anyMatch(c -> c.contains("podman container inspect")),
        "existence probes must observe the live container");
    assertTrue(
        probes.invocations().stream().anyMatch(c -> c.contains("dpkg -s")),
        "package-presence probes must observe the live container");
    assertTrue(
        probes.invocations().stream().noneMatch(c -> c.contains("apt-get")),
        "apt mutations must never ride the probe shell");
    assertTrue(
        mutator.invocations().stream().anyMatch(c -> c.contains("apt-get install")),
        "missing packages install through the mutator shell");
    assertTrue(
        probes.invocations().stream().anyMatch(c -> c.contains("which")),
        "tool-presence probes must observe the live container");
    assertTrue(
        probes.invocations().stream()
            .noneMatch(c -> c.contains("podman run") || c.contains("install")),
        "the probe shell must stay read-only");
    assertTrue(
        mutator.invocations().stream().anyMatch(c -> c.contains("podman run")),
        "mutations must ride the mutator shell, which a dry run swaps for narration");
    assertTrue(
        mutator.invocations().stream().noneMatch(c -> c.contains("podman container inspect")),
        "a dry-run mutator answering probes would report every delta as absent");
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
            .onOk("mkdir -p /home/dev/.sail")
            .onOk("gh auth login")
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
    assertGhLoggedIn(shell, "github.com", "ghp_secret123");
  }

  /**
   * The forge CLI was logged in for {@code host} as the dev user: the token was pushed to a {@code
   * 0600} file under the dev home and consumed by the one login command, which removes the file;
   * the token itself is never an argument.
   */
  static void assertGhLoggedIn(ScriptedShellExecutor shell, String host, String token) {
    var push =
        shell.invocations().stream()
            .filter(c -> c.contains("incus file push") && c.contains("/home/dev/.sail/gh-token-"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no token file pushed: " + shell.invocations()));
    assertTrue(push.contains("--uid 1000 --gid 1000 --mode 0600"), push);
    var file = push.substring(push.lastIndexOf(CONTAINER) + CONTAINER.length());
    var login =
        shell.invocations().stream()
            .filter(c -> c.contains("gh auth login"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no gh login: " + shell.invocations()));
    assertTrue(login.contains("--user 1000 --group 1000 "), login);
    assertTrue(
        login.endsWith(
            "-- sh -c gh auth login --hostname \"$1\" --with-token < \"$2\"; s=$?; rm -f \"$2\";"
                + " exit $s sh "
                + host
                + " "
                + file),
        login);
    assertTrue(
        shell.invocations().stream().noneMatch(c -> c.contains(token)),
        "the token is never an argument: " + shell.invocations());
  }

  @Test
  void applyReposLogsGhInForAGitHubEnterpriseHostMarkedAsGitHubAndNotForGitLab() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onFail("test -d", "not found");
    var applier = applier(shell);

    applier.applyRepos(
        CONTAINER,
        List.of(
            new SailYaml.Repo("https://git.example.com/org/api.git", "api", null, "github"),
            new SailYaml.Repo("https://gitlab.com/org/web.git", "web", null)),
        "dev",
        Map.of("*", "tok_wild"),
        null);

    assertGhLoggedIn(shell, "git.example.com", "tok_wild");
    assertEquals(
        1,
        shell.invocations().stream().filter(c -> c.contains("gh auth login")).count(),
        "gitlab.com gets no gh login");
  }

  @Test
  void applyReposWithOnlyAGitLabTokenNeverTouchesGh() throws Exception {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", "")).onFail("test -d", "not found");
    var applier = applier(shell);

    applier.applyRepos(
        CONTAINER,
        List.of(new SailYaml.Repo("https://gitlab.com/org/web.git", "web", null)),
        "dev",
        Map.of("gitlab.com", "glpat_x"),
        null);

    assertTrue(shell.invocations().stream().noneMatch(c -> c.contains("gh auth login")));
    assertTrue(shell.invocations().stream().noneMatch(c -> c.contains("gh-token-")));
  }

  @Test
  void aFailedGhLoginFailsTheStepNamingTheHost() {
    var shell =
        new ScriptedShellExecutor(new ShellExec.Result(0, "", ""))
            .onFail("gh auth login", "The token in GH_TOKEN is invalid.");
    var applier = applier(shell);

    var failed =
        assertThrows(
            java.io.IOException.class,
            () ->
                applier.applyRepos(
                    CONTAINER,
                    List.of(new SailYaml.Repo("https://github.com/org/api.git", "api", null)),
                    "dev",
                    Map.of("*", "ghp_bad"),
                    null));

    assertEquals(
        "Failed to log gh in for github.com in acme-health: The token in GH_TOKEN is invalid.",
        failed.getMessage());
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

    var result = applier.applyAgentTools(CONTAINER, List.of("codex"));

    assertEquals(1, result.added());
    assertTrue(shell.invocations().stream().anyMatch(c -> c.contains("bash -c")));
  }

  @Test
  void applyAgentToolsSkipsInstalledAgent() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("bash -lc which claude");
    var applier = applier(shell);

    var result = applier.applyAgentTools(CONTAINER, List.of("claude-code"));

    assertEquals(0, result.added());
    assertEquals(1, result.skipped());
  }

  @Test
  void applyAgentToolsUsesLoginShellForWhichCheck() throws Exception {
    var shell = new ScriptedShellExecutor().onOk("bash -lc which claude");
    var applier = applier(shell);

    applier.applyAgentTools(CONTAINER, List.of("claude-code"));

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

    var result = applier.applyAgentTools(CONTAINER, List.of("claude-code", "codex"));

    assertEquals(1, result.added());
    assertEquals(1, result.skipped());
  }

  @Test
  void applyAgentToolsReturnsEmptyWhenNull() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyAgentTools(CONTAINER, null);

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
  void applySkillsInstallsSailsSkillForTheProjectsHarnessAndWritesNoContextFile() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var applier = applier(shell);

    var result = applier.applySkills(CONTAINER, minimalConfig("claude-code"), ProjectSkills.none());

    assertEquals(1, result.added(), "spec-board");
    var pushes = shell.invocations().stream().filter(c -> c.contains("incus file push")).toList();
    assertTrue(
        pushes.stream()
            .anyMatch(
                c ->
                    c.contains("/home/dev/.claude/.sail-stage-build-spec-board.")
                        && c.endsWith("/SKILL.md")),
        pushes.toString());
    assertTrue(
        pushes.stream().noneMatch(c -> c.contains("CLAUDE.md") || c.contains("AGENTS.md")),
        "sail writes no context file: " + pushes);
    assertTrue(
        pushes.stream().noneMatch(c -> c.contains("/workspace/")),
        "and nothing into the engineer's workspace: " + pushes);
    assertTrue(
        shell.invocations().stream().anyMatch(c -> c.contains("flock /home/dev/.claude/skills")),
        "the folder is put in place under the skills directory's lock");
  }

  @Test
  void applySkillsInstallsForEveryHarnessTheProjectInstalls() throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
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
            new SailYaml.Agent(
                "claude-code",
                true,
                "sail/",
                true,
                List.of("claude-code", "codex"),
                null,
                null,
                null,
                null),
            null);

    var result = applier.applySkills(CONTAINER, config, ProjectSkills.none());

    assertEquals(2, result.added());
    for (var dir : List.of("/home/dev/.claude/", "/home/dev/.agents/")) {
      assertTrue(
          shell.invocations().stream()
              .anyMatch(c -> c.contains(dir + ".sail-stage-build-spec-board.")),
          dir);
    }
  }

  @Test
  void applySkillsInstallsTheProjectsSkillsAndWarnsOfAFolderThatIsNone() throws Exception {
    try (var db = Sqlite.open(tempDir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
      var files = new FileStore(db);
      Acting.system(
          () -> {
            files.put(
                "test",
                ".sail/skills/e2e/SKILL.md",
                new ByteArrayInputStream("Run the suite.\n".getBytes(StandardCharsets.UTF_8)),
                0644);
            files.put(
                "test",
                ".sail/skills/e2e/run.sh",
                new ByteArrayInputStream("#!/bin/sh\n".getBytes(StandardCharsets.UTF_8)),
                0755);
            files.put(
                "test",
                ".sail/skills/broken/notes.md",
                new ByteArrayInputStream("no manifest".getBytes(StandardCharsets.UTF_8)),
                0644);
          });
      var skills =
          new ProjectSkills(
              project ->
                  new SharedProjectFiles(
                      files, tempDir.resolve("projects"), project, FileLimits.defaults()));
      var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
      var out = new ByteArrayOutputStream();
      var applier = new ProjectApplier(shell, new PrintStream(out));

      var result = applier.applySkills("test", minimalConfig("codex"), skills);

      assertEquals(2, result.added(), "e2e and spec-board");
      assertEquals(
          List.of(
              "skill folder 'broken' skipped: .sail/skills/broken/SKILL.md is missing or is not a"
                  + " text file."),
          result.warnings());
      assertTrue(
          shell.invocations().stream()
              .anyMatch(
                  c ->
                      c.contains("--mode 0755")
                          && c.contains("/home/dev/.agents/.sail-stage-build-e2e.")
                          && c.endsWith("/run.sh")),
          shell.invocations().toString());
      assertTrue(
          out.toString(StandardCharsets.UTF_8)
              .contains("[apply] Skills \u2192 ~/.agents/skills/ (e2e, spec-board)"),
          out.toString(StandardCharsets.UTF_8));
    }
  }

  @Test
  void applySkillsSkipsWhenNoAgent() throws Exception {
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
            null);

    var result = applier.applySkills(CONTAINER, config, ProjectSkills.none());

    assertEquals(0, result.added());
    assertTrue(shell.invocations().isEmpty());
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
            Exception.class, () -> applier.applyAgentTools(CONTAINER, List.of("claude-code")));

    assertTrue(ex.getMessage().contains("Failed to install agent"));
  }

  @Test
  void applyAgentToolsReturnsEmptyWhenEmpty() throws Exception {
    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyAgentTools(CONTAINER, List.of());

    assertEquals(0, result.added());
  }

  @Test
  void applyAgentToolsSkipsNodeCheckForClaudeCode() throws Exception {
    var shell =
        new ScriptedShellExecutor().onFail("bash -lc which claude", "not found").onOk("bash -c");
    var applier = applier(shell);

    var result = applier.applyAgentTools(CONTAINER, List.of("claude-code"));

    assertEquals(1, result.added());
    assertFalse(shell.invocations().stream().anyMatch(c -> c.contains("which node")));
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
        new SailYaml.Agent("claude-code", true, "sail/", true, null, null, null, null, null);
    var config =
        new SailYaml("test", null, null, null, null, null, null, null, null, null, agent, null);

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
    WorkspaceFiles.mode(filesDir.resolve("setup.sh"), 0755);
    var sailYaml = projectDir.resolve("sail.yaml");
    Files.writeString(sailYaml, "name: test");

    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var applier = applier(shell);

    var result = applier.applyWorkspaceFiles(CONTAINER, sailYaml, "dev");

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
  void applyWorkspaceFilesLeavesTheProjectsSkillsOutOfTheWorkspace() throws Exception {
    var projectDir = tempDir.resolve("skilled");
    var filesDir = projectDir.resolve("files");
    Files.createDirectories(filesDir.resolve(".sail/skills/acme-review"));
    Files.writeString(filesDir.resolve(".sail/skills/acme-review/SKILL.md"), "Judge.");
    Files.writeString(filesDir.resolve("notes.md"), "Notes.");
    var sailYaml = projectDir.resolve("sail.yaml");
    Files.writeString(sailYaml, "name: test");
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    var result = applier(shell).applyWorkspaceFiles(CONTAINER, sailYaml, "dev");

    var pushes = shell.invocations().stream().filter(c -> c.contains("incus file push")).toList();
    assertEquals(1, result.added());
    assertEquals(1, pushes.size(), pushes.toString());
    assertTrue(pushes.getFirst().endsWith("/home/dev/workspace/notes.md"), pushes.getFirst());
    assertTrue(
        shell.invocations().stream().noneMatch(c -> c.contains(".sail/skills")),
        "nothing under .sail/skills/ reaches ~/workspace");
  }

  @Test
  void applyWorkspaceFilesPushesNothingWhenTheOnlyFilesAreSkills() throws Exception {
    var projectDir = tempDir.resolve("only-skills");
    var filesDir = projectDir.resolve("files");
    Files.createDirectories(filesDir.resolve(".sail/skills/acme-review"));
    Files.writeString(filesDir.resolve(".sail/skills/acme-review/SKILL.md"), "Judge.");
    var sailYaml = projectDir.resolve("sail.yaml");
    Files.writeString(sailYaml, "name: test");
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));

    var result = applier(shell).applyWorkspaceFiles(CONTAINER, sailYaml, "dev");

    assertEquals(0, result.added());
    assertTrue(shell.invocations().isEmpty(), shell.invocations().toString());
  }

  @Test
  void applyWorkspaceFilesReturnsEmptyWhenNoFilesDir() throws Exception {
    var sailYaml = tempDir.resolve("sail.yaml");
    Files.writeString(sailYaml, "name: test");

    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyWorkspaceFiles(CONTAINER, sailYaml, "dev");

    assertEquals(0, result.added());
    assertTrue(shell.invocations().isEmpty());
  }

  @Test
  void applyWorkspaceFilesReturnsEmptyWhenFilesDirEmpty() throws Exception {
    Files.createDirectories(tempDir.resolve("files"));
    var sailYaml = tempDir.resolve("sail.yaml");
    Files.writeString(sailYaml, "name: test");

    var shell = new ScriptedShellExecutor();
    var applier = applier(shell);

    var result = applier.applyWorkspaceFiles(CONTAINER, sailYaml, "dev");

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
    var sailYaml = tempDir.resolve("sail.yaml");
    Files.writeString(sailYaml, "name: test");

    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var applier = applier(shell);

    applier.applyWorkspaceFiles(CONTAINER, sailYaml, "dev");

    assertTrue(
        shell.invocations().stream()
            .anyMatch(
                c ->
                    c.contains("incus file push")
                        && c.contains(CONTAINER + "/home/dev/workspace/")));
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
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.contains("cleanup-agents.sh")),
        "the manual agent-process killer is no longer generated");
  }

  @Test
  void applyCleanupCronSkipsWhenAlreadyCurrent() throws Exception {
    var cronWithScript = "*/15 * * * * /home/dev/.sail/cleanup-containers.sh >/dev/null 2>&1\n";
    var shell =
        new ScriptedShellExecutor()
            .onOk("crontab -l", cronWithScript)
            .onOk("test -f /home/dev/.sail/cleanup-containers.sh");
    var applier = applier(shell);

    var result = applier.applyCleanupCron(CONTAINER, "dev");

    assertEquals(0, result.added());
    assertEquals(1, result.skipped());
    assertFalse(shell.invocations().stream().anyMatch(c -> c.contains("incus file push")));
  }

  @Test
  void applyCleanupCronUpgradesAnOlderHourlyCadence() throws Exception {
    var hourly = "0 * * * * /home/dev/.sail/cleanup-containers.sh >/dev/null 2>&1\n";
    var shell =
        new ScriptedShellExecutor()
            .onOk("crontab -l", hourly)
            .onOk("test -f /home/dev/.sail/cleanup-containers.sh")
            .onOk("incus file push")
            .onOk("mktemp", "/tmp/sail-crontab.x")
            .onOk("crontab -u")
            .onOk("rm -f");
    var applier = applier(shell);

    var result = applier.applyCleanupCron(CONTAINER, "dev");

    assertEquals(1, result.added(), "a box on the old hourly cadence is upgraded, not skipped");
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
    var agent = new SailYaml.Agent(agentType, true, "sail/", true, null, null, null, null, null);
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
        null);
  }
}
