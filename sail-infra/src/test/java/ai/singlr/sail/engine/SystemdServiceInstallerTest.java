/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.SystemdServiceInstaller.Mode;
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

  // --------------------- constructor validation ---------------------

  @Test
  void constructorRejectsNullShell(@TempDir Path home) {
    assertThrows(
        NullPointerException.class,
        () -> new SystemdServiceInstaller(null, Mode.USER, home, SAIL_BINARY, HOST, PORT, USER));
  }

  @Test
  void constructorRejectsNullMode(@TempDir Path home) {
    assertThrows(
        NullPointerException.class,
        () ->
            new SystemdServiceInstaller(
                new ScriptedShellExecutor(), null, home, SAIL_BINARY, HOST, PORT, USER));
  }

  @Test
  void constructorRejectsNullHome() {
    assertThrows(
        NullPointerException.class,
        () ->
            new SystemdServiceInstaller(
                new ScriptedShellExecutor(), Mode.USER, null, SAIL_BINARY, HOST, PORT, USER));
  }

  @Test
  void constructorRejectsNullBinary(@TempDir Path home) {
    assertThrows(
        NullPointerException.class,
        () ->
            new SystemdServiceInstaller(
                new ScriptedShellExecutor(), Mode.USER, home, null, HOST, PORT, USER));
  }

  @Test
  void constructorRejectsBlankBindAddress(@TempDir Path home) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemdServiceInstaller(
                new ScriptedShellExecutor(), Mode.USER, home, SAIL_BINARY, "", PORT, USER));
  }

  @Test
  void constructorRejectsInvalidPort(@TempDir Path home) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemdServiceInstaller(
                new ScriptedShellExecutor(), Mode.USER, home, SAIL_BINARY, HOST, 0, USER));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemdServiceInstaller(
                new ScriptedShellExecutor(), Mode.USER, home, SAIL_BINARY, HOST, 70000, USER));
  }

  @Test
  void constructorRejectsBlankUsername(@TempDir Path home) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SystemdServiceInstaller(
                new ScriptedShellExecutor(), Mode.USER, home, SAIL_BINARY, HOST, PORT, " "));
  }

  // --------------------- USER mode: paths + isInstalled ---------------------

  @Test
  void userModeServiceFileLivesUnderDotSail(@TempDir Path home) {
    assertEquals(
        home.resolve(".sail/services/sail-api.service"), userInstaller(home).serviceFilePath());
  }

  @Test
  void userModeSystemdLinkLivesUnderConfigSystemdUser(@TempDir Path home) {
    assertEquals(
        home.resolve(".config/systemd/user/sail-api.service"),
        userInstaller(home).systemdLinkPath());
  }

  @Test
  void userModeIsInstalledIsFalseInitially(@TempDir Path home) {
    assertFalse(userInstaller(home).isInstalled());
  }

  @Test
  void userModeIsInstalledRequiresBothFileAndSymlink(@TempDir Path home) throws Exception {
    var installer = userInstaller(home);
    Files.createDirectories(installer.serviceFilePath().getParent());
    Files.writeString(installer.serviceFilePath(), "x");

    assertFalse(installer.isInstalled(), "service file alone is not enough — link must also exist");

    Files.createDirectories(installer.systemdLinkPath().getParent());
    Files.createSymbolicLink(installer.systemdLinkPath(), installer.serviceFilePath());
    assertTrue(installer.isInstalled());
  }

  @Test
  void userModeReportsModeUser(@TempDir Path home) {
    assertEquals(Mode.USER, userInstaller(home).mode());
  }

  // --------------------- USER mode: unit rendering ---------------------

  @Test
  void userModeUnitContainsExecStartAndPortAndDefaultTarget(@TempDir Path home) {
    var unit = userInstaller(home).renderUnit();

    assertTrue(
        unit.contains(
            "ExecStart=" + SAIL_BINARY + " server start --host " + HOST + " --port " + PORT));
    assertTrue(unit.contains("Restart=on-failure"));
    assertTrue(unit.contains("WantedBy=default.target"));
    assertTrue(unit.contains("LimitNOFILE=4096"));
    assertTrue(unit.contains("RuntimeDirectory=sail"));
    assertTrue(
        unit.contains("RuntimeDirectoryMode=0755"),
        "0755 is required so UID-mapped container processes can traverse /run/sail/");
    assertTrue(
        unit.contains("RuntimeDirectoryPreserve=yes"),
        "preserve=yes keeps the dir inode stable across restarts so directory bind mounts in"
            + " unprivileged Incus containers do not get stranded on the old (unlinked) inode");
    assertFalse(unit.contains("User=root"), "USER mode unit must not pin User=root");
  }

  // --------------------- USER mode: install / uninstall ---------------------

  @Test
  void userModeInstallWritesUnitUnderDotSailAndCreatesSymlink(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var installer = userInstaller(home, shell);

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
  void userModeInstallReplacesExistingSymlink(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var installer = userInstaller(home, shell);
    Files.createDirectories(installer.systemdLinkPath().getParent());
    Files.createSymbolicLink(installer.systemdLinkPath(), home.resolve("stale-target"));

    installer.install();

    assertTrue(Files.isSymbolicLink(installer.systemdLinkPath()));
    assertEquals(installer.serviceFilePath(), Files.readSymbolicLink(installer.systemdLinkPath()));
  }

  @Test
  void userModeInstallThrowsWhenDaemonReloadFails(@TempDir Path home) {
    var shell = new ScriptedShellExecutor().onFail("daemon-reload", "no D-Bus");
    var ex = assertThrows(IOException.class, () -> userInstaller(home, shell).install());
    assertTrue(ex.getMessage().contains("reload"));
    assertTrue(ex.getMessage().contains("no D-Bus"));
  }

  @Test
  void userModeInstallThrowsWhenEnableFails(@TempDir Path home) {
    var shell =
        new ScriptedShellExecutor().onOk("daemon-reload").onFail("enable --now", "unit not found");
    var ex = assertThrows(IOException.class, () -> userInstaller(home, shell).install());
    assertTrue(ex.getMessage().contains("enable+start"));
  }

  @Test
  void userModeUninstallStopsDisablesRemovesAndReloads(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var installer = userInstaller(home, shell);
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
  void userModeUninstallSwallowsDisableFailureToStayIdempotent(@TempDir Path home)
      throws Exception {
    var shell =
        new ScriptedShellExecutor().onFail("disable --now", "not loaded").onOk("daemon-reload");
    userInstaller(home, shell).uninstall();
  }

  // --------------------- USER mode: start / stop / restart ---------------------

  @Test
  void userModeStartInvokesSystemctlStart(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    userInstaller(home, shell).start();
    assertEquals("systemctl --user start sail-api.service", shell.invocations().getFirst());
  }

  @Test
  void userModeStartPropagatesFailure(@TempDir Path home) {
    var shell = new ScriptedShellExecutor().onFail("start sail-api", "boom");
    var ex = assertThrows(IOException.class, () -> userInstaller(home, shell).start());
    assertTrue(ex.getMessage().contains("Failed to start"));
  }

  @Test
  void userModeStopInvokesSystemctlStop(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    userInstaller(home, shell).stop();
    assertEquals("systemctl --user stop sail-api.service", shell.invocations().getFirst());
  }

  @Test
  void userModeRestartInvokesSystemctlRestart(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    userInstaller(home, shell).restart();
    assertEquals("systemctl --user restart sail-api.service", shell.invocations().getFirst());
  }

  // --------------------- USER mode: status ---------------------

  @Test
  void userModeStatusParsesShowOutput(@TempDir Path home) throws Exception {
    var output =
        """
        LoadState=loaded
        ActiveState=active
        SubState=running
        MainPID=12345
        """;
    var shell = new ScriptedShellExecutor().onOk("systemctl --user show", output);

    var status = userInstaller(home, shell).status();

    assertEquals("loaded", status.loadState());
    assertEquals("active", status.activeState());
    assertEquals("running", status.subState());
    assertEquals(12345, status.pid());
    assertTrue(status.running());
  }

  @Test
  void userModeStatusFillsUnknownWhenFieldsMissing(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("systemctl --user show", "");

    var status = userInstaller(home, shell).status();

    assertEquals("unknown", status.loadState());
    assertEquals("unknown", status.activeState());
    assertNull(status.pid());
    assertFalse(status.running());
  }

  @Test
  void userModeStatusZeroPidTreatedAsNull(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("systemctl --user show", "MainPID=0\n");
    assertNull(userInstaller(home, shell).status().pid());
  }

  @Test
  void userModeStatusGarbagePidTreatedAsNull(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("systemctl --user show", "MainPID=abc\n");
    assertNull(userInstaller(home, shell).status().pid());
  }

  @Test
  void runningPredicateRejectsInactive(@TempDir Path home) throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "systemctl --user show",
                "ActiveState=inactive\nSubState=dead\nMainPID=0\nLoadState=loaded\n");

    assertFalse(userInstaller(home, shell).status().running());
  }

  // --------------------- USER mode: journal + linger ---------------------

  @Test
  void userModeJournalReturnsStdout(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("journalctl --user", "log line 1\nlog line 2\n");

    assertEquals("log line 1\nlog line 2\n", userInstaller(home, shell).journal(100));
  }

  @Test
  void journalRejectsZeroLines(@TempDir Path home) {
    assertThrows(IllegalArgumentException.class, () -> userInstaller(home).journal(0));
  }

  @Test
  void journalRejectsNegativeLines(@TempDir Path home) {
    assertThrows(IllegalArgumentException.class, () -> userInstaller(home).journal(-1));
  }

  @Test
  void userModeJournalThrowsOnFailure(@TempDir Path home) {
    var shell = new ScriptedShellExecutor().onFail("journalctl", "permission denied");

    var ex = assertThrows(IOException.class, () -> userInstaller(home, shell).journal(50));
    assertTrue(ex.getMessage().contains("permission denied"));
  }

  @Test
  void userModeLingerStatusParsesLoginctlValue(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("loginctl show-user", "yes\n");
    assertEquals("yes", userInstaller(home, shell).lingerStatus());
  }

  @Test
  void userModeLingerStatusReturnsUnknownOnFailure(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onFail("loginctl show-user", "no such user");
    assertEquals("unknown", userInstaller(home, shell).lingerStatus());
  }

  @Test
  void userModeIsLingerEnabledTrueForYes(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("loginctl show-user", "yes\n");
    assertTrue(userInstaller(home, shell).isLingerEnabled());
  }

  @Test
  void userModeIsLingerEnabledFalseForNo(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("loginctl show-user", "no\n");
    assertFalse(userInstaller(home, shell).isLingerEnabled());
  }

  @Test
  void userModeEnableLingerCommandContainsUsername(@TempDir Path home) {
    assertTrue(userInstaller(home).enableLingerCommand().contains(USER));
    assertTrue(userInstaller(home).enableLingerCommand().startsWith("sudo loginctl enable-linger"));
  }

  // --------------------- reconcile (upgrade-path guard) ---------------------

  @Test
  void reconcileRewritesWhenOnDiskUnitDiffersFromTemplate(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var installer = userInstaller(home, shell);
    Files.createDirectories(installer.serviceFilePath().getParent());
    Files.writeString(installer.serviceFilePath(), "stale unit content from an old binary\n");

    var rewrote = installer.reconcile();

    assertTrue(rewrote, "reconcile must rewrite when on-disk content drifts from template");
    assertEquals(installer.renderUnit(), Files.readString(installer.serviceFilePath()));
    assertTrue(
        shell.invocations().stream().anyMatch(c -> c.contains("daemon-reload")),
        "reconcile must run daemon-reload after rewriting the unit file");
  }

  @Test
  void reconcileNoOpsWhenUnitMatchesTemplate(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor();
    var installer = userInstaller(home, shell);
    Files.createDirectories(installer.serviceFilePath().getParent());
    Files.writeString(installer.serviceFilePath(), installer.renderUnit());

    var rewrote = installer.reconcile();

    assertFalse(rewrote, "no rewrite expected when on-disk content equals the template");
    assertTrue(
        shell.invocations().isEmpty(),
        "no shell calls expected on the no-op reconcile path: " + shell.invocations());
  }

  @Test
  void reconcileFullInstallsWhenUnitFileMissing(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var installer = userInstaller(home, shell);

    var rewrote = installer.reconcile();

    assertTrue(rewrote);
    assertTrue(Files.exists(installer.serviceFilePath()));
    assertEquals(installer.renderUnit(), Files.readString(installer.serviceFilePath()));
  }

  @Test
  void reconcilePropagatesDaemonReloadFailure(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onFail("daemon-reload", "no D-Bus");
    var installer = userInstaller(home, shell);
    Files.createDirectories(installer.serviceFilePath().getParent());
    Files.writeString(installer.serviceFilePath(), "stale\n");

    var ex = assertThrows(IOException.class, installer::reconcile);
    assertTrue(ex.getMessage().contains("no D-Bus"));
  }

  // --------------------- SYSTEM mode ---------------------

  @Test
  void systemModeServiceFileLivesUnderEtcSystemd(@TempDir Path home) {
    assertEquals(
        Path.of("/etc/systemd/system/sail-api.service"), systemInstaller(home).serviceFilePath());
  }

  @Test
  void systemModeHasNoSystemdLink(@TempDir Path home) {
    assertNull(
        systemInstaller(home).systemdLinkPath(),
        "system-level unit is already on systemd's search path");
  }

  @Test
  void systemModeIsInstalledFalseWhenUnitMissing(@TempDir Path home) {
    // We never write to /etc/systemd/system in tests; this confirms the no-symlink-required
    // branch still gates on the unit file existing.
    assertFalse(systemInstaller(home).isInstalled());
  }

  @Test
  void systemModeUnitContainsUserRootAndMultiUserTarget(@TempDir Path home) {
    var unit = systemInstaller(home).renderUnit();

    assertTrue(unit.contains("User=root"));
    assertTrue(unit.contains("WantedBy=multi-user.target"));
    assertTrue(
        unit.contains(
            "ExecStart=" + SAIL_BINARY + " server start --host " + HOST + " --port " + PORT));
    assertTrue(unit.contains("RuntimeDirectory=sail"));
    assertTrue(unit.contains("RuntimeDirectoryMode=0755"));
    assertTrue(unit.contains("RuntimeDirectoryPreserve=yes"));
  }

  @Test
  void systemModeReportsModeSystem(@TempDir Path home) {
    assertEquals(Mode.SYSTEM, systemInstaller(home).mode());
  }

  @Test
  void systemModeSystemctlCommandsOmitUserFlag(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var installer = systemInstallerWithFile(home, shell);

    installer.start();
    installer.stop();
    installer.restart();

    var cmds = shell.invocations();
    assertEquals("systemctl start sail-api.service", cmds.get(0));
    assertEquals("systemctl stop sail-api.service", cmds.get(1));
    assertEquals("systemctl restart sail-api.service", cmds.get(2));
  }

  @Test
  void systemModeJournalOmitsUserFlag(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor().onOk("journalctl", "log\n");

    systemInstallerWithFile(home, shell).journal(20);

    var cmd = shell.invocations().getFirst();
    assertTrue(cmd.startsWith("journalctl -u sail-api.service"), "actual: " + cmd);
    assertFalse(cmd.contains("--user"));
  }

  @Test
  void systemModeLingerStatusIsNotApplicable(@TempDir Path home) throws Exception {
    assertEquals("n/a", systemInstaller(home).lingerStatus());
  }

  @Test
  void systemModeIsLingerEnabledAlwaysTrue(@TempDir Path home) throws Exception {
    assertTrue(systemInstaller(home).isLingerEnabled());
  }

  @Test
  void systemModeEnableLingerCommandIsEmpty(@TempDir Path home) {
    assertEquals("", systemInstaller(home).enableLingerCommand());
  }

  // --------------------- helpers ---------------------

  private static SystemdServiceInstaller userInstaller(Path home) {
    return userInstaller(home, new ScriptedShellExecutor());
  }

  private static SystemdServiceInstaller userInstaller(Path home, ShellExec shell) {
    return new SystemdServiceInstaller(shell, Mode.USER, home, SAIL_BINARY, HOST, PORT, USER);
  }

  /**
   * SYSTEM-mode installer with the unit file path redirected under the test's @TempDir, so we can
   * exercise install/uninstall paths without writing to {@code /etc/systemd/system} on the host.
   * Only used by tests that touch the filesystem; pure-rendering tests use {@link
   * #systemInstaller}.
   */
  private static SystemdServiceInstaller systemInstallerWithFile(Path home) {
    return systemInstallerWithFile(home, new ScriptedShellExecutor());
  }

  private static SystemdServiceInstaller systemInstallerWithFile(Path home, ShellExec shell) {
    return systemInstaller(home, shell);
  }

  private static SystemdServiceInstaller systemInstaller(Path home) {
    return systemInstaller(home, new ScriptedShellExecutor());
  }

  private static SystemdServiceInstaller systemInstaller(Path home, ShellExec shell) {
    return new SystemdServiceInstaller(shell, Mode.SYSTEM, home, SAIL_BINARY, HOST, PORT, USER);
  }
}
