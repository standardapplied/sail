/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemdServiceInstallerTest {

  private static final Path SAIL_BINARY = Path.of("/usr/local/bin/sail");
  private static final String HOST = "127.0.0.1";
  private static final int PORT = 7070;
  private static final String USER = "dev";

  @Test
  void constructorRejectsNullShell(@TempDir Path home) {
    assertThrows(
        NullPointerException.class,
        () -> new SystemdServiceInstaller(null, home, SAIL_BINARY, HOST, PORT, USER));
  }

  @Test
  void constructorRejectsNullHome() {
    assertThrows(
        NullPointerException.class,
        () ->
            new SystemdServiceInstaller(
                new ScriptedShellExecutor(), null, SAIL_BINARY, HOST, PORT, USER));
  }

  @Test
  void constructorRejectsNullBinary(@TempDir Path home) {
    assertThrows(
        NullPointerException.class,
        () ->
            new SystemdServiceInstaller(new ScriptedShellExecutor(), home, null, HOST, PORT, USER));
  }

  @Test
  void constructorRejectsBlankBindAddress(@TempDir Path home) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemdServiceInstaller(
                new ScriptedShellExecutor(), home, SAIL_BINARY, "", PORT, USER));
  }

  @Test
  void constructorRejectsInvalidPort(@TempDir Path home) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemdServiceInstaller(
                new ScriptedShellExecutor(), home, SAIL_BINARY, HOST, 0, USER));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemdServiceInstaller(
                new ScriptedShellExecutor(), home, SAIL_BINARY, HOST, 70000, USER));
  }

  @Test
  void constructorRejectsBlankUsername(@TempDir Path home) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemdServiceInstaller(
                new ScriptedShellExecutor(), home, SAIL_BINARY, HOST, PORT, " "));
  }

  @Test
  void serviceFileLivesUnderDotSail(@TempDir Path home) {
    var installer = installer(home);

    assertEquals(home.resolve(".sail/services/sail-api.service"), installer.serviceFilePath());
  }

  @Test
  void systemdLinkLivesUnderConfigSystemdUser(@TempDir Path home) {
    var installer = installer(home);

    assertEquals(
        home.resolve(".config/systemd/user/sail-api.service"), installer.systemdLinkPath());
  }

  @Test
  void isInstalledIsFalseInitially(@TempDir Path home) {
    var installer = installer(home);

    assertFalse(installer.isInstalled());
  }

  @Test
  void isInstalledRequiresBothFileAndSymlink(@TempDir Path home) throws Exception {
    var installer = installer(home);
    Files.createDirectories(installer.serviceFilePath().getParent());
    Files.writeString(installer.serviceFilePath(), "x");

    assertFalse(installer.isInstalled(), "service file alone is not enough — link must also exist");

    Files.createDirectories(installer.systemdLinkPath().getParent());
    Files.createSymbolicLink(installer.systemdLinkPath(), installer.serviceFilePath());
    assertTrue(installer.isInstalled());
  }

  @Test
  void renderUnitContainsExecStartAndPort(@TempDir Path home) {
    var installer = installer(home);

    var unit = installer.renderUnit();

    assertTrue(
        unit.contains("ExecStart=" + SAIL_BINARY + " api --host " + HOST + " --port " + PORT));
    assertTrue(unit.contains("Restart=on-failure"));
    assertTrue(unit.contains("WantedBy=default.target"));
    assertTrue(unit.contains("LimitNOFILE=4096"));
  }

  @Test
  void installWritesUnitUnderDotSailAndCreatesSymlink(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var installer = installer(home, shell);

    installer.install();

    assertTrue(Files.exists(installer.serviceFilePath()));
    assertEquals(installer.renderUnit(), Files.readString(installer.serviceFilePath()));
    assertTrue(Files.isSymbolicLink(installer.systemdLinkPath()));
    assertEquals(installer.serviceFilePath(), Files.readSymbolicLink(installer.systemdLinkPath()));
    var cmds = shell.invocations();
    assertEquals(2, cmds.size());
    assertEquals("systemctl --user daemon-reload", cmds.get(0));
    assertEquals("systemctl --user enable --now sail-api.service", cmds.get(1));
  }

  @Test
  void installReplacesExistingSymlink(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var installer = installer(home, shell);
    Files.createDirectories(installer.systemdLinkPath().getParent());
    Files.createSymbolicLink(installer.systemdLinkPath(), home.resolve("stale-target"));

    installer.install();

    assertTrue(Files.isSymbolicLink(installer.systemdLinkPath()));
    assertEquals(installer.serviceFilePath(), Files.readSymbolicLink(installer.systemdLinkPath()));
  }

  @Test
  void installThrowsWhenDaemonReloadFails(@TempDir Path home) {
    var shell = new ScriptedShellExecutor().onFail("daemon-reload", "no D-Bus");
    var installer = installer(home, shell);

    var ex = assertThrows(IOException.class, installer::install);
    assertTrue(ex.getMessage().contains("reload"));
    assertTrue(ex.getMessage().contains("no D-Bus"));
  }

  @Test
  void installThrowsWhenEnableFails(@TempDir Path home) {
    var shell =
        new ScriptedShellExecutor().onOk("daemon-reload").onFail("enable --now", "unit not found");
    var installer = installer(home, shell);

    var ex = assertThrows(IOException.class, installer::install);
    assertTrue(ex.getMessage().contains("enable+start"));
  }

  @Test
  void uninstallStopsDisablesRemovesAndReloads(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var installer = installer(home, shell);
    Files.createDirectories(installer.serviceFilePath().getParent());
    Files.writeString(installer.serviceFilePath(), "old content");
    Files.createDirectories(installer.systemdLinkPath().getParent());
    Files.createSymbolicLink(installer.systemdLinkPath(), installer.serviceFilePath());

    installer.uninstall();

    assertFalse(Files.exists(installer.serviceFilePath()));
    assertFalse(Files.exists(installer.systemdLinkPath()));
    var cmds = shell.invocations();
    assertEquals("systemctl --user disable --now sail-api.service", cmds.get(0));
    assertEquals("systemctl --user daemon-reload", cmds.get(1));
  }

  @Test
  void uninstallSwallowsDisableFailureToStayIdempotent(@TempDir Path home) throws Exception {
    var shell =
        new ScriptedShellExecutor().onFail("disable --now", "not loaded").onOk("daemon-reload");
    var installer = installer(home, shell);

    installer.uninstall();
  }

  @Test
  void startInvokesSystemctlStart(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    installer(home, shell).start();
    assertEquals("systemctl --user start sail-api.service", shell.invocations().getFirst());
  }

  @Test
  void startPropagatesFailure(@TempDir Path home) {
    var shell = new ScriptedShellExecutor().onFail("start sail-api", "boom");
    var ex = assertThrows(IOException.class, () -> installer(home, shell).start());
    assertTrue(ex.getMessage().contains("Failed to start"));
  }

  @Test
  void stopInvokesSystemctlStop(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    installer(home, shell).stop();
    assertEquals("systemctl --user stop sail-api.service", shell.invocations().getFirst());
  }

  @Test
  void restartInvokesSystemctlRestart(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    installer(home, shell).restart();
    assertEquals("systemctl --user restart sail-api.service", shell.invocations().getFirst());
  }

  @Test
  void statusParsesShowOutput(@TempDir Path home) throws Exception {
    var output =
        """
        LoadState=loaded
        ActiveState=active
        SubState=running
        MainPID=12345
        """;
    var shell = new ScriptedShellExecutor().onOk("systemctl --user show", output);

    var status = installer(home, shell).status();

    assertEquals("loaded", status.loadState());
    assertEquals("active", status.activeState());
    assertEquals("running", status.subState());
    assertEquals(12345, status.pid());
    assertTrue(status.running());
  }

  @Test
  void statusFillsUnknownWhenFieldsMissing(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("systemctl --user show", "");

    var status = installer(home, shell).status();

    assertEquals("unknown", status.loadState());
    assertEquals("unknown", status.activeState());
    assertNull(status.pid());
    assertFalse(status.running());
  }

  @Test
  void statusZeroPidTreatedAsNull(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("systemctl --user show", "MainPID=0\n");

    var status = installer(home, shell).status();

    assertNull(status.pid());
  }

  @Test
  void statusGarbagePidTreatedAsNull(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("systemctl --user show", "MainPID=abc\n");

    assertNull(installer(home, shell).status().pid());
  }

  @Test
  void runningPredicateRejectsInactive(@TempDir Path home) throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "systemctl --user show",
                "ActiveState=inactive\nSubState=dead\nMainPID=0\nLoadState=loaded\n");

    assertFalse(installer(home, shell).status().running());
  }

  @Test
  void journalReturnsStdout(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("journalctl --user", "log line 1\nlog line 2\n");

    assertEquals("log line 1\nlog line 2\n", installer(home, shell).journal(100));
  }

  @Test
  void journalRejectsZeroLines(@TempDir Path home) {
    assertThrows(IllegalArgumentException.class, () -> installer(home).journal(0));
  }

  @Test
  void journalRejectsNegativeLines(@TempDir Path home) {
    assertThrows(IllegalArgumentException.class, () -> installer(home).journal(-1));
  }

  @Test
  void journalThrowsOnFailure(@TempDir Path home) {
    var shell = new ScriptedShellExecutor().onFail("journalctl", "permission denied");

    var ex = assertThrows(IOException.class, () -> installer(home, shell).journal(50));
    assertTrue(ex.getMessage().contains("permission denied"));
  }

  @Test
  void lingerStatusParsesLoginctlValue(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("loginctl show-user", "yes\n");

    assertEquals("yes", installer(home, shell).lingerStatus());
  }

  @Test
  void lingerStatusReturnsUnknownOnFailure(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onFail("loginctl show-user", "no such user");

    assertEquals("unknown", installer(home, shell).lingerStatus());
  }

  @Test
  void isLingerEnabledTrueForYes(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("loginctl show-user", "yes\n");

    assertTrue(installer(home, shell).isLingerEnabled());
  }

  @Test
  void isLingerEnabledFalseForNo(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("loginctl show-user", "no\n");

    assertFalse(installer(home, shell).isLingerEnabled());
  }

  @Test
  void enableLingerCommandContainsUsername(@TempDir Path home) {
    assertTrue(installer(home).enableLingerCommand().contains(USER));
    assertTrue(installer(home).enableLingerCommand().startsWith("sudo loginctl enable-linger"));
  }

  private static SystemdServiceInstaller installer(Path home) {
    return installer(home, new ScriptedShellExecutor());
  }

  private static SystemdServiceInstaller installer(Path home, ShellExec shell) {
    return new SystemdServiceInstaller(shell, home, SAIL_BINARY, HOST, PORT, USER);
  }
}
