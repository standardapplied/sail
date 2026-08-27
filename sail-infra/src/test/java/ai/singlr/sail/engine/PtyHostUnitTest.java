/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

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
}
