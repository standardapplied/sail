/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.LocalIdentity.GitField;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalIdentityTest {

  @TempDir Path dir;

  private static LocalIdentity identity(Map<String, String> gitConfig) {
    return new LocalIdentity(stubGit(gitConfig, new ArrayList<>()));
  }

  @Test
  void aContainerCommitsAsTheBoxsGitIdentity() {
    var identity = identity(Map.of("user.name", "Mady M", "user.email", "mady@example.com"));

    assertEquals(new LocalIdentity.GitIdentity("Mady M", "mady@example.com"), identity.git());
    assertEquals("Mady M", identity.gitValue(GitField.NAME).orElseThrow());
    assertEquals("mady@example.com", identity.gitValue(GitField.EMAIL).orElseThrow());
  }

  @Test
  void anAccountsOwnMissingIdentityNamesItsOwnGitConfig() {
    var error = assertThrows(IllegalStateException.class, () -> identity(Map.of()).git());
    assertEquals(
        "This box has no git user.name set, so a project here can't take on your identity.\n"
            + "  Set it once and re-run: git config --global user.name \"...\"",
        error.getMessage());
    assertEquals(
        "This box has no git user.email set, so a project here can't take on your identity.\n"
            + "  Set it once and re-run: git config --global user.email \"...\"",
        assertThrows(IllegalStateException.class, () -> identity(Map.of("user.name", "Mady")).git())
            .getMessage());
    assertTrue(identity(Map.of()).gitValue(GitField.NAME).isEmpty(), "an unset value, no throw");
  }

  private static String missing(LocalIdentity identity) {
    return assertThrows(IllegalStateException.class, identity::git).getMessage();
  }

  @Test
  void rootsMissingIdentityUnderSudoNamesTheSudoThatSetsRootsGitConfig() {
    var noGit = stubGit(Map.of("user.name", "root"), new ArrayList<>());

    assertEquals(
        "This box has no git user.email set, so a project here can't take on your identity.\n"
            + "  Set it once and re-run: sudo git config --global user.email \"...\"",
        missing(LocalIdentity.detect(noGit, true, dir.resolve("root"), dir.resolve("dev"))));
  }

  @Test
  void anIdentityReadForItsOwnAccountNamesPlainGitConfig() {
    var noGit = stubGit(Map.of("user.name", "dev"), new ArrayList<>());
    var plain =
        "This box has no git user.email set, so a project here can't take on your identity.\n"
            + "  Set it once and re-run: git config --global user.email \"...\"";

    assertEquals(
        plain, missing(LocalIdentity.detect(noGit, true, dir.resolve("dev"), dir.resolve("dev"))));
    assertEquals(
        plain,
        missing(LocalIdentity.detect(noGit, true, dir.resolve("root"), dir.resolve("root"))));
    assertEquals(
        plain,
        missing(LocalIdentity.detect(noGit, false, dir.resolve("dev"), dir.resolve("mady"))));
  }

  @Test
  void anAccountsIdentityReadsTheGitConfigOfItsHome() {
    var home = dir.resolve("dev");
    var commands = new ArrayList<List<String>>();
    var identity = new LocalIdentity(stubGit(Map.of("user.name", "Mady M"), commands), home, false);

    assertEquals("Mady M", identity.gitValue(GitField.NAME).orElseThrow());
    assertEquals(
        List.of(List.of("env", "HOME=" + home, "git", "config", "--global", "--get", "user.name")),
        commands);
  }

  @Test
  void adoptingAGitValueWritesTheAccountsGlobalConfig() throws Exception {
    var home = dir.resolve("root");
    var commands = new ArrayList<List<String>>();
    var identity = new LocalIdentity(stubGit(Map.of(), commands), home, true);

    identity.adoptGit(GitField.NAME, "A B");

    assertEquals(
        List.of(List.of("env", "HOME=" + home, "git", "config", "--global", "user.name", "A B")),
        commands);
  }

  @Test
  void aGitValueGitRefusesToWriteFailsNamingTheCommandThatSetsIt() {
    var identity =
        new LocalIdentity(
            new ScriptedShellExecutor().onFail("git config", "error: could not lock config file"));

    var error =
        assertThrows(IllegalStateException.class, () -> identity.adoptGit(GitField.EMAIL, "a@b"));

    assertEquals(
        "Couldn't set this box's git user.email: error: could not lock config file\n"
            + "  Set it yourself and re-run: git config --global user.email \"...\"",
        error.getMessage());
  }

  @Test
  void aBoxWithoutGitFailsNamingTheCommandThatSetsTheValue() {
    var identity =
        new LocalIdentity(
            new ScriptedShellExecutor()
                .onThrow("git config", new IOException("Cannot run program \"git\"")));

    var error =
        assertThrows(IllegalStateException.class, () -> identity.adoptGit(GitField.NAME, "A B"));

    assertEquals(
        "Couldn't set this box's git user.name: Cannot run program \"git\"\n"
            + "  Set it yourself and re-run: git config --global user.name \"...\"",
        error.getMessage());
    assertTrue(identity.gitValue(GitField.NAME).isEmpty(), "a git that cannot run reads as unset");
  }

  private static ShellExec stubGit(Map<String, String> config, List<List<String>> commands) {
    return new ShellExec() {
      @Override
      public Result exec(List<String> command) {
        commands.add(command);
        return new Result(0, config.getOrDefault(command.getLast(), ""), "");
      }

      @Override
      public Result exec(List<String> command, Path workDir, Duration timeout) {
        return exec(command);
      }

      @Override
      public boolean isDryRun() {
        return false;
      }
    };
  }
}
