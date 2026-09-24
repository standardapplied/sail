/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.PtyHostUnit;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.engine.SystemdServiceInstaller.Mode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostServiceUninstallCommandTest {

  @Test
  void bothUnitsUninstallWhateverStateTheInstalledBinaryIsIn(@TempDir Path home) throws Exception {
    var services = home.resolve(".sail/services");
    var links = home.resolve(".config/systemd/user");
    Files.createDirectories(services);
    Files.createDirectories(links);
    for (var unit : new String[] {SystemdServiceInstaller.UNIT_NAME, PtyHostUnit.UNIT_NAME}) {
      Files.writeString(services.resolve(unit), "[Service]\nExecStart=/gone/sail\n");
      Files.createSymbolicLink(links.resolve(unit), services.resolve(unit));
    }
    var shell = new ScriptedShellExecutor().onOk("systemctl");

    var removed = HostServiceUninstallCommand.uninstall(shell, Mode.USER, home);

    assertEquals(services.resolve(SystemdServiceInstaller.UNIT_NAME), removed);
    try (var left = Files.list(services)) {
      assertFalse(left.findAny().isPresent(), "unit files remain");
    }
    try (var left = Files.list(links)) {
      assertFalse(left.findAny().isPresent(), "discovery links remain");
    }
    var ran = String.join("\n", shell.invocations());
    assertTrue(ran.contains("systemctl --user disable --now sail-api.service"), ran);
    assertTrue(ran.contains("systemctl --user disable --now sail-pty-host.service"), ran);
  }
}
