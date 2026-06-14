/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.SailVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

  @TempDir Path tempDir;

  @Test
  void readUnitEndpointParsesHostAndPort() throws Exception {
    var unit = tempDir.resolve("sail-api.service");
    Files.writeString(
        unit,
        """
        [Service]
        ExecStart=/usr/local/bin/sail server start --host 10.0.0.5 --port 9999
        """);
    var endpoint = UpgradeCommand.readUnitEndpoint(unit).orElseThrow();
    assertEquals("10.0.0.5", endpoint.host());
    assertEquals(9999, endpoint.port());
  }

  @Test
  void readUnitEndpointHandlesLegacyApiExecStart() throws Exception {
    var unit = tempDir.resolve("legacy.service");
    Files.writeString(
        unit,
        """
        [Service]
        ExecStart=/usr/local/bin/sail api --host 127.0.0.1 --port 7070
        """);
    var endpoint = UpgradeCommand.readUnitEndpoint(unit).orElseThrow();
    assertEquals("127.0.0.1", endpoint.host());
    assertEquals(7070, endpoint.port());
  }

  @Test
  void readUnitEndpointReturnsEmptyWhenFileMissing() {
    assertTrue(UpgradeCommand.readUnitEndpoint(tempDir.resolve("nope.service")).isEmpty());
  }

  @Test
  void readUnitEndpointReturnsEmptyWhenExecStartMissing() throws Exception {
    var unit = tempDir.resolve("no-exec.service");
    Files.writeString(unit, "[Service]\nType=simple\n");
    assertTrue(UpgradeCommand.readUnitEndpoint(unit).isEmpty());
  }

  @Test
  void readUnitEndpointReturnsEmptyOnNonNumericPort() throws Exception {
    var unit = tempDir.resolve("bad-port.service");
    Files.writeString(
        unit,
        """
        [Service]
        ExecStart=/usr/local/bin/sail server start --host 127.0.0.1 --port not-a-number
        """);
    assertTrue(UpgradeCommand.readUnitEndpoint(unit).isEmpty());
  }
}
