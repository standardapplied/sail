/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.SailVersion;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class UpgradeCommandTest {

  @Test
  void helpTextIncludesOptions() {
    var cmd = new CommandLine(new UpgradeCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("upgrade"));
    assertTrue(usage.contains("--check"));
    assertTrue(usage.contains("--target"));
    assertTrue(usage.contains("--dry-run"));
    assertTrue(usage.contains("--json"));
  }

  @Test
  void versionFlagOutputStartsWithSail() {
    var version = SailVersion.version();
    assertNotNull(version);
    assertFalse(version.isBlank());

    var provider = new SailVersion();
    var lines = provider.getVersion();
    assertTrue(lines[0].startsWith("sail "));
  }

  @Test
  void aProvisionedBoxWithoutSailApiIsToldHowToInstallIt() {
    var remediation = UpgradeCommand.missingApiRemediation(true).orElseThrow();
    assertTrue(remediation.contains("sudo sail host service install"));
  }

  @Test
  void anUnprovisionedBoxGetsNoSailApiNag() {
    assertTrue(UpgradeCommand.missingApiRemediation(false).isEmpty());
  }
}
