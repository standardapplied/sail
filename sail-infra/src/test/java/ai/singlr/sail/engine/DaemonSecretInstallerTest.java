/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.DaemonSecretInstaller.DaemonSecret;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DaemonSecretInstallerTest {

  private static final DaemonSecret SECRET = DaemonSecretInstaller.SLACK_TOKEN;

  @Test
  void slackTokenSecretMapsNameToEnvVar() {
    assertEquals("slack-token", SECRET.name());
    assertEquals("SAIL_SLACK_TOKEN_FILE", SECRET.envVar());
  }

  @Test
  void secretRequiresNameAndEnvVar() {
    assertThrows(IllegalArgumentException.class, () -> new DaemonSecret(" ", "ENV"));
    assertThrows(IllegalArgumentException.class, () -> new DaemonSecret("name", ""));
  }

  @Test
  void userModePathsResolveUnderTheHome(@TempDir Path home) {
    var installer = userInstaller(home, new ScriptedShellExecutor());

    assertEquals(home.resolve(".sail/slack-token"), installer.secretFilePath(SECRET));
    assertEquals(
        home.resolve(".config/systemd/user/sail-api.service.d/20-slack-token.conf"),
        installer.dropInPath(SECRET));
  }

  @Test
  void systemModeDropInLivesUnderEtcSystemd(@TempDir Path home) {
    var installer =
        new DaemonSecretInstaller(
            new ScriptedShellExecutor(), SystemdServiceInstaller.Mode.SYSTEM, home);

    assertEquals(home.resolve(".sail/slack-token"), installer.secretFilePath(SECRET));
    assertEquals(
        Path.of("/etc/systemd/system/sail-api.service.d/20-slack-token.conf"),
        installer.dropInPath(SECRET));
  }

  @Test
  void renderDropInPointsTheEnvVarAtTheSecretFile(@TempDir Path home) {
    var installer = userInstaller(home, new ScriptedShellExecutor());

    assertEquals(
        "[Service]\nEnvironment=SAIL_SLACK_TOKEN_FILE=" + home.resolve(".sail/slack-token") + "\n",
        installer.renderDropIn(SECRET));
  }

  @Test
  void installWritesTheSecretOwnerOnlyAndTheDropInThenReloadsAndRestarts(@TempDir Path home)
      throws Exception {
    var shell = okShell();
    var installer = userInstaller(home, shell);

    var applied = installer.install(SECRET, "xoxb-token-1");

    assertEquals("xoxb-token-1\n", Files.readString(applied.secretFile()));
    assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        Files.getPosixFilePermissions(applied.secretFile()));
    assertEquals(installer.renderDropIn(SECRET), Files.readString(applied.dropIn()));
    assertTrue(applied.dropInChanged());
    assertEquals(
        List.of("systemctl --user daemon-reload", "systemctl --user restart sail-api.service"),
        shell.invocations());
  }

  @Test
  void reinstallReplacesTheTokenSkipsDaemonReloadAndStillRestarts(@TempDir Path home)
      throws Exception {
    var shell = okShell();
    var installer = userInstaller(home, shell);

    installer.install(SECRET, "xoxb-token-1");
    var applied = installer.install(SECRET, "xoxb-token-2");

    assertEquals("xoxb-token-2\n", Files.readString(applied.secretFile()));
    assertFalse(applied.dropInChanged());
    assertEquals(1, shell.invocations().stream().filter(c -> c.contains("daemon-reload")).count());
    assertEquals(2, shell.invocations().stream().filter(c -> c.contains("restart")).count());
    try (var entries = Files.list(applied.dropIn().getParent())) {
      assertEquals(1, entries.count(), "reruns must leave exactly one drop-in");
    }
  }

  @Test
  void driftedDropInIsRewrittenAndReloaded(@TempDir Path home) throws Exception {
    var shell = okShell();
    var installer = userInstaller(home, shell);
    installer.install(SECRET, "xoxb-token-1");
    Files.writeString(installer.dropInPath(SECRET), "stale drop-in from an old binary\n");

    var applied = installer.install(SECRET, "xoxb-token-1");

    assertTrue(applied.dropInChanged());
    assertEquals(installer.renderDropIn(SECRET), Files.readString(applied.dropIn()));
    assertEquals(2, shell.invocations().stream().filter(c -> c.contains("daemon-reload")).count());
  }

  @Test
  void preExistingSecretFileIsTightenedToOwnerOnly(@TempDir Path home) throws Exception {
    var installer = userInstaller(home, okShell());
    var file = installer.secretFilePath(SECRET);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "old\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

    installer.install(SECRET, "xoxb-token-1");

    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
  }

  @Test
  void systemModeSystemctlCommandsOmitTheUserFlag(@TempDir Path tmp) throws Exception {
    var shell = okShell();
    var installer =
        new DaemonSecretInstaller(
            shell,
            SystemdServiceInstaller.Mode.SYSTEM,
            tmp.resolve(".sail"),
            tmp.resolve("sail-api.service.d"));

    installer.install(SECRET, "xoxb-token-1");

    assertEquals(
        List.of("systemctl daemon-reload", "systemctl restart sail-api.service"),
        shell.invocations());
  }

  @Test
  void restartFailureExplainsWhatToDo(@TempDir Path home) {
    var shell = new ScriptedShellExecutor().onOk("daemon-reload").onFail("restart", "no D-Bus");
    var installer = userInstaller(home, shell);

    var ex = assertThrows(IOException.class, () -> installer.install(SECRET, "xoxb-token-1"));

    assertTrue(ex.getMessage().contains("no D-Bus"));
    assertTrue(ex.getMessage().contains("sail host service install"));
  }

  @Test
  void daemonReloadFailurePropagates(@TempDir Path home) {
    var shell = new ScriptedShellExecutor().onFail("daemon-reload", "no D-Bus");
    var installer = userInstaller(home, shell);

    var ex = assertThrows(IOException.class, () -> installer.install(SECRET, "xoxb-token-1"));
    assertTrue(ex.getMessage().contains("no D-Bus"));
  }

  @Test
  void blankValueIsRejectedBeforeTouchingDisk(@TempDir Path home) {
    var installer = userInstaller(home, okShell());

    assertThrows(IllegalArgumentException.class, () -> installer.install(SECRET, "  "));
    assertFalse(Files.exists(installer.secretFilePath(SECRET)));
  }

  private static ScriptedShellExecutor okShell() {
    return new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
  }

  private static DaemonSecretInstaller userInstaller(Path home, ShellExec shell) {
    return new DaemonSecretInstaller(shell, SystemdServiceInstaller.Mode.USER, home);
  }
}
