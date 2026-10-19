/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.ssh.TestSshKeys;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalIdentityTest {

  @TempDir Path dir;
  private Path keyPath;

  @BeforeEach
  void setUp() {
    keyPath = dir.resolve("workstation_key.pub");
  }

  private LocalIdentity identity(Map<String, String> gitConfig) {
    return new LocalIdentity(stubGit(gitConfig), keyPath, OperatorCommand.PLAIN);
  }

  @Test
  void resolvesGitIdentityFromGitConfig() {
    var identity = identity(Map.of("user.name", "Mady M", "user.email", "mady@example.com"));
    assertEquals("Mady M", identity.valueFor("GIT_NAME"));
    assertEquals("mady@example.com", identity.valueFor("GIT_EMAIL"));
  }

  private static final String VALID_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAILDpT0mMcK mady@box";

  @Test
  void resolvesSshKeyFromTheBoxIdentityFile() throws IOException {
    Files.writeString(keyPath, VALID_KEY + "\n");
    assertEquals(VALID_KEY, identity(Map.of()).valueFor("SSH_PUBLIC_KEY"));
  }

  @Test
  void failsLoudRatherThanInjectingAnUnparseableKey() throws IOException {
    Files.writeString(keyPath, "this is not a valid ssh key\n");
    var error =
        assertThrows(
            IllegalStateException.class, () -> identity(Map.of()).valueFor("SSH_PUBLIC_KEY"));
    assertTrue(error.getMessage().contains(" is not an SSH public key: "), error.getMessage());
  }

  @Test
  void anAccountsOwnMissingIdentityNamesItsOwnGitConfig() {
    var error =
        assertThrows(IllegalStateException.class, () -> identity(Map.of()).valueFor("GIT_NAME"));
    assertEquals(
        "This box has no git user.name set, so a project here can't take on your identity.\n"
            + "  Set it once and re-run: git config --global user.name \"...\"",
        error.getMessage());
  }

  private static String missing(LocalIdentity identity) {
    return assertThrows(IllegalStateException.class, () -> identity.valueFor("GIT_EMAIL"))
        .getMessage();
  }

  @Test
  void rootsMissingIdentityUnderSudoNamesTheSudoThatSetsRootsGitConfig() {
    var noGit = stubGit(Map.of(), new ArrayList<>());

    assertEquals(
        "This box has no git user.email set, so a project here can't take on your identity.\n"
            + "  Set it once and re-run: sudo git config --global user.email \"...\"",
        missing(
            LocalIdentity.detect(
                noGit, true, dir.resolve("root"), dir.resolve("dev"), OperatorCommand.PLAIN)));
  }

  @Test
  void anIdentityReadForItsOwnAccountNamesPlainGitConfig() {
    var noGit = stubGit(Map.of(), new ArrayList<>());
    var plain =
        "This box has no git user.email set, so a project here can't take on your identity.\n"
            + "  Set it once and re-run: git config --global user.email \"...\"";

    assertEquals(
        plain,
        missing(
            LocalIdentity.detect(
                noGit, true, dir.resolve("dev"), dir.resolve("dev"), OperatorCommand.PLAIN)));
    assertEquals(
        plain,
        missing(
            LocalIdentity.detect(
                noGit, true, dir.resolve("root"), dir.resolve("root"), OperatorCommand.PLAIN)));
    assertEquals(
        plain,
        missing(
            LocalIdentity.detect(
                noGit, false, dir.resolve("dev"), dir.resolve("mady"), OperatorCommand.PLAIN)));
  }

  @Test
  void adoptingAGitValueWritesTheAccountsGlobalConfig() throws Exception {
    var home = dir.resolve("root");
    var commands = new ArrayList<List<String>>();
    var identity =
        new LocalIdentity(
            stubGit(Map.of(), commands),
            home.resolve("key.pub"),
            home,
            true,
            OperatorCommand.PLAIN);

    identity.adoptGit("GIT_NAME", "A B");

    assertEquals(
        List.of(List.of("env", "HOME=" + home, "git", "config", "--global", "user.name", "A B")),
        commands);
  }

  @Test
  void aGitValueGitRefusesToWriteFailsNamingTheCommandThatSetsIt() {
    var identity =
        new LocalIdentity(
            new ScriptedShellExecutor().onFail("git config", "error: could not lock config file"),
            keyPath,
            OperatorCommand.PLAIN);

    var error =
        assertThrows(IllegalStateException.class, () -> identity.adoptGit("GIT_EMAIL", "a@b"));

    assertEquals(
        "Couldn't set this box's git user.email: error: could not lock config file\n"
            + "  Set it yourself and re-run: git config --global user.email \"...\"",
        error.getMessage());
    assertThrows(IllegalArgumentException.class, () -> identity.adoptGit("SSH_PUBLIC_KEY", "k"));
  }

  @Test
  void aBoxWithoutGitFailsNamingTheCommandThatSetsTheValue() {
    var identity =
        new LocalIdentity(
            new ScriptedShellExecutor()
                .onThrow("git config", new IOException("Cannot run program \"git\"")),
            keyPath,
            OperatorCommand.PLAIN);

    var error =
        assertThrows(IllegalStateException.class, () -> identity.adoptGit("GIT_NAME", "A B"));

    assertEquals(
        "Couldn't set this box's git user.name: Cannot run program \"git\"\n"
            + "  Set it yourself and re-run: git config --global user.name \"...\"",
        error.getMessage());
  }

  @Test
  void failsLoudWhenTheIdentityKeyIsMissing() {
    var error =
        assertThrows(
            IllegalStateException.class, () -> identity(Map.of()).valueFor("SSH_PUBLIC_KEY"));
    assertTrue(
        error.getMessage().contains("sail host config set ssh-public-key"),
        "must point at the workstation-key setter, not the machine sync key: "
            + error.getMessage());
  }

  @Test
  void failsLoudWhenTheIdentityKeyIsBlank() throws IOException {
    Files.writeString(keyPath, "   \n");
    assertThrows(IllegalStateException.class, () -> identity(Map.of()).valueFor("SSH_PUBLIC_KEY"));
  }

  @Test
  void rejectsAnUnknownPlaceholder() {
    assertThrows(IllegalArgumentException.class, () -> identity(Map.of()).valueFor("MYSTERY"));
  }

  @Test
  void gitValueReadsTheConfiguredIdentityWithoutThrowing() {
    var identity = identity(Map.of("user.name", "Mady M", "user.email", "mady@example.com"));
    assertEquals("Mady M", identity.gitValue("GIT_NAME").orElseThrow());
    assertEquals("mady@example.com", identity.gitValue("GIT_EMAIL").orElseThrow());
  }

  @Test
  void gitValueIsEmptyWhenUnsetOrNotAGitPlaceholder() {
    assertTrue(identity(Map.of()).gitValue("GIT_NAME").isEmpty(), "unset identity, no throw");
    assertTrue(identity(Map.of()).gitValue("SSH_PUBLIC_KEY").isEmpty(), "not a git field");
  }

  @Test
  void anAccountsIdentityReadsItsGitConfigAndItsWorkstationKey() throws IOException {
    var home = dir.resolve("dev");
    Files.createDirectories(home.resolve(".sail"));
    Files.writeString(home.resolve(".sail/workstation_key.pub"), VALID_KEY + "\n");
    var commands = new ArrayList<List<String>>();
    var shell = stubGit(Map.of("user.name", "Mady M"), commands);

    var identity =
        new LocalIdentity(
            shell, home.resolve(".sail/workstation_key.pub"), home, false, OperatorCommand.PLAIN);

    assertEquals("Mady M", identity.valueFor("GIT_NAME"));
    assertEquals(VALID_KEY, identity.valueFor("SSH_PUBLIC_KEY"));
    assertEquals(
        List.of(List.of("env", "HOME=" + home, "git", "config", "--global", "--get", "user.name")),
        commands);
  }

  @Test
  void aSudoApplyOnAnAccountsOwnBoxRecordsTheDigestItsPlainApplyDoes() throws IOException {
    var dev = dir.resolve("srv/dev");
    var passwd =
        new ScriptedShellExecutor()
            .onOk("getent passwd -- dev", "dev:x:1001:1001::" + dev + ":/bin/bash\n");
    Function<String, Path> homeOf = user -> SailPaths.accountHome(passwd, user);
    var root = dir.resolve("root");
    Files.createDirectories(dev.resolve(".sail"));
    Files.createDirectories(root.resolve(".sail"));
    Files.writeString(dev.resolve(".sail/host.yaml"), "storage_backend: dir\n");
    Files.writeString(dev.resolve(".sail/workstation_key.pub"), VALID_KEY + "\n");
    Files.writeString(
        root.resolve(".sail/workstation_key.pub"), TestSshKeys.ed25519("root", "root@box") + "\n");
    var plain = inputs(SailPaths.identityHome(false, null, homeOf, dev), dev);
    var sudo = inputs(SailPaths.identityHome(true, "dev", homeOf, root), dev);

    assertEquals(plain, sudo);
  }

  private static String inputs(Path home, Path dev) {
    var git =
        home.equals(dev)
            ? Map.of("user.name", "Mady M", "user.email", "mady@example.com")
            : Map.of("user.name", "root", "user.email", "root@box");
    var config =
        ProjectDefinitions.resolveForProvisioning(
            "name: acme\n"
                + "git:\n  name: ${GIT_NAME}\n  email: ${GIT_EMAIL}\n"
                + "ssh:\n  user: dev\n  authorized_keys:\n    - ${SSH_PUBLIC_KEY}\n",
            home(home, git),
            OperatorCommand.PLAIN);
    return ProjectProvisioner.inputs(config, Optional.empty());
  }

  private static LocalIdentity home(Path home, Map<String, String> git) {
    return new LocalIdentity(
        stubGit(git, new ArrayList<>()),
        home.resolve(".sail/workstation_key.pub"),
        home,
        false,
        OperatorCommand.PLAIN);
  }

  private static ShellExec stubGit(Map<String, String> config, List<List<String>> commands) {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) {
        commands.add(command);
        return new Result(0, config.getOrDefault(command.getLast(), ""), "");
      }

      @Override
      public Result exec(List<String> command, Path workDir, java.time.Duration timeout) {
        return exec(command);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }

  private static ShellExec stubGit(Map<String, String> config) {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) {
        var key = command.getLast();
        return new Result(0, config.getOrDefault(key, ""), "");
      }

      @Override
      public Result exec(List<String> command, Path workDir, java.time.Duration timeout) {
        return exec(command);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }
}
