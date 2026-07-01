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
