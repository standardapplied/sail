/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PtyHostUnitTest {

  private static final Path BINARY = Path.of("/usr/local/bin/sail");

  @Test
  void rendersAUnitThatRunsThePtyHostWithNoBindSurface(@TempDir Path home) {
    var user =
        new PtyHostUnit(
            new ScriptedShellExecutor(), SystemdServiceInstaller.Mode.USER, home, BINARY);
    var unit = user.renderUnit();

    assertTrue(unit.contains("ExecStart=/usr/local/bin/sail _pty-host"));
    assertTrue(unit.contains("WantedBy=default.target"));
    assertFalse(unit.contains("User="), "user mode carries no User= clause");
    assertFalse(unit.contains("--host"), "the pty host has no bind surface");

    var system =
        new PtyHostUnit(
            new ScriptedShellExecutor(), SystemdServiceInstaller.Mode.SYSTEM, home, BINARY);
    assertTrue(system.renderUnit().contains("User=root"));
    assertTrue(system.renderUnit().contains("WantedBy=multi-user.target"));
  }

  @Test
  void theUnitLetsSessionsOutliveTheHostInBothModes(@TempDir Path home) throws Exception {
    for (var mode : SystemdServiceInstaller.Mode.values()) {
      var unit = new PtyHostUnit(new ScriptedShellExecutor(), mode, home, BINARY).renderUnit();
      assertTrue(unit.contains("NotifyAccess=main\n"), mode + ": " + unit);
      assertTrue(
          unit.contains(
              "FileDescriptorStoreMax="
                  + ai.singlr.sail.pty.PtySessionHost.Limits.DEFAULTS.sessions()
                  + "\n"),
          mode + ": the store is sized to the host's session cap: " + unit);
      assertTrue(unit.contains("KillMode=process\n"), mode + ": " + unit);
      assertTrue(unit.contains("TimeoutStopSec=15\n"), mode + ": " + unit);
      assertTrue(unit.contains("Restart=on-failure\n"), mode + ": " + unit);
      assertTrue(unit.contains("SuccessExitStatus=143\n"), mode + ": " + unit);
      assertTrue(unit.contains("Type=simple\n"), mode + ": " + unit);
    }
  }

  @Test
  void aUnitWithoutTheStorePredatesLiveHandoff(@TempDir Path home) throws Exception {
    var unit =
        new PtyHostUnit(
            new ScriptedShellExecutor(), SystemdServiceInstaller.Mode.USER, home, BINARY);
    assertFalse(
        PtyHostUnit.predatesLiveHandoff(unit.serviceFilePath()), "no unit: nothing to predate");

    Files.createDirectories(unit.serviceFilePath().getParent());
    Files.writeString(
        unit.serviceFilePath(), "[Service]\nExecStart=/usr/local/bin/sail _pty-host\n");
    assertTrue(PtyHostUnit.predatesLiveHandoff(unit.serviceFilePath()));

    Files.writeString(unit.serviceFilePath(), unit.renderUnit());
    assertFalse(PtyHostUnit.predatesLiveHandoff(unit.serviceFilePath()));
  }

  @Test
  void installWritesUnitSymlinkAndEnablesUnderUserSystemd(@TempDir Path home) throws Exception {
    var shell = new ScriptedShellExecutor(new ShellExec.Result(0, "", ""));
    var unit = new PtyHostUnit(shell, SystemdServiceInstaller.Mode.USER, home, BINARY);

    unit.install();

    assertTrue(Files.exists(home.resolve(".sail/services/sail-pty-host.service")));
    assertTrue(Files.isSymbolicLink(home.resolve(".config/systemd/user/sail-pty-host.service")));
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("systemctl --user enable sail-pty-host.service")));
    assertTrue(
        shell.invocations().stream()
            .anyMatch(c -> c.contains("systemctl --user restart sail-pty-host.service")),
        "restart, so an upgrade's new binary takes effect");

    unit.uninstall();
    assertFalse(Files.exists(home.resolve(".sail/services/sail-pty-host.service")));
    assertFalse(Files.exists(home.resolve(".config/systemd/user/sail-pty-host.service")));
  }

  @Test
  void aDryRunNeitherWritesNorRemovesTheUnit(@TempDir Path home) throws Exception {
    var dry =
        new PtyHostUnit(new ShellExecutor(true), SystemdServiceInstaller.Mode.USER, home, BINARY);
    var file = home.resolve(".sail/services/sail-pty-host.service");

    dry.install();
    assertFalse(Files.exists(file));

    Files.createDirectories(file.getParent());
    Files.writeString(file, "kept");
    dry.uninstall();
    assertEquals("kept", Files.readString(file));
  }

  @Test
  void removingTheUnitNeverAsksWhereSailIsInstalled(@TempDir Path home) throws Exception {
    var unit =
        new PtyHostUnit(
            new ScriptedShellExecutor(new ShellExec.Result(0, "", "")),
            SystemdServiceInstaller.Mode.USER,
            home,
            () -> {
              throw new AssertionError("only rendering the unit names the binary");
            });

    assertDoesNotThrow(unit::uninstall);
  }
}
