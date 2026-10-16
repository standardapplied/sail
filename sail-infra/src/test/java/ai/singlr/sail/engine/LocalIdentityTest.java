/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    return new LocalIdentity(stubGit(gitConfig), keyPath);
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
    assertTrue(error.getMessage().contains("sail host config set ssh-public-key"));
  }

  @Test
  void failsLoudWhenGitNameIsUnset() {
    var error =
        assertThrows(IllegalStateException.class, () -> identity(Map.of()).valueFor("GIT_NAME"));
    assertTrue(error.getMessage().contains("git config --global user.name"));
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

    var identity = new LocalIdentity(shell, home.resolve(".sail/workstation_key.pub"), home);

    assertEquals("Mady M", identity.valueFor("GIT_NAME"));
    assertEquals(VALID_KEY, identity.valueFor("SSH_PUBLIC_KEY"));
    assertEquals(
        List.of(List.of("env", "HOME=" + home, "git", "config", "--global", "--get", "user.name")),
        commands);
  }

  @Test
  void aSudoApplyOnAnAccountsOwnBoxResolvesWhatItsPlainApplyDoes() throws IOException {
    var home = dir.resolve("home/dev");
    Files.createDirectories(home.resolve(".sail"));
    Files.writeString(home.resolve(".sail/host.yaml"), "storage_backend: dir\n");
    Files.writeString(home.resolve(".sail/workstation_key.pub"), VALID_KEY + "\n");
    var git = Map.of("user.name", "Mady M", "user.email", "mady@example.com");
    var plain = home(SailPaths.identityHome(false, null, false, dir.resolve("home"), home), git);
    var sudo =
        home(
            SailPaths.identityHome(true, "dev", false, dir.resolve("home"), dir.resolve("r")), git);

    for (var placeholder : List.of("GIT_NAME", "GIT_EMAIL", "SSH_PUBLIC_KEY")) {
      assertEquals(plain.valueFor(placeholder), sudo.valueFor(placeholder), placeholder);
    }
  }

  private static LocalIdentity home(Path home, Map<String, String> git) {
    return new LocalIdentity(
        stubGit(git, new ArrayList<>()), home.resolve(".sail/workstation_key.pub"), home);
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
