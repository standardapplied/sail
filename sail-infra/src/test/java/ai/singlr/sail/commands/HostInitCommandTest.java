/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class HostInitCommandTest {

  @TempDir Path home;

  @Test
  void anInvalidWorkstationKeyAbortsBeforeAnyProvisioning() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exit = cmd.execute("host", "init", "--ssh-public-key", "not-a-real-key", "--dry-run");

    assertNotEquals(0, exit, "a malformed workstation key must fail host init fast");
  }

  @Test
  void apiServiceIsNotInstalledOnAFreshHome() {
    assertFalse(HostInitCommand.apiServiceInstalled(home));
  }

  @Test
  void apiServiceIsNotInstalledForANullHome() {
    assertFalse(HostInitCommand.apiServiceInstalled(null));
  }

  @Test
  void apiServiceIsInstalledWhenBothUnitFilesExist() throws Exception {
    var unit = home.resolve(".sail/services/sail-api.service");
    var link = home.resolve(".config/systemd/user/sail-api.service");
    Files.createDirectories(unit.getParent());
    Files.createDirectories(link.getParent());
    Files.writeString(unit, "[Unit]\n");
    Files.writeString(link, "[Unit]\n");

    assertTrue(HostInitCommand.apiServiceInstalled(home));
  }

  @Test
  void apiServiceIsNotInstalledWhenOnlyTheUnitFileExistsWithoutTheUserLink() throws Exception {
    var unit = home.resolve(".sail/services/sail-api.service");
    Files.createDirectories(unit.getParent());
    Files.writeString(unit, "[Unit]\n");

    assertFalse(HostInitCommand.apiServiceInstalled(home));
  }
}
