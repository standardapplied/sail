/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostInitCommandTest {

  @TempDir Path home;

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
