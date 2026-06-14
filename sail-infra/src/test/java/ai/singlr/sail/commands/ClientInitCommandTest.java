/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ClientConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientInitCommandTest {

  @TempDir Path tempDir;

  @Test
  void writesClientConfigPointingAtHost() throws Exception {
    var configPath = tempDir.resolve("config.yaml");
    var hostConfigPath = tempDir.resolve("host.yaml");

    ClientInitCommand.writeClientConfig("kubera-server", configPath, hostConfigPath);

    assertTrue(Files.exists(configPath));
    assertEquals("kubera-server", ClientConfig.load(configPath).host());
  }

  @Test
  void createsParentDirectoryWhenMissing() throws Exception {
    var configPath = tempDir.resolve("nested/config.yaml");
    var hostConfigPath = tempDir.resolve("host.yaml");

    ClientInitCommand.writeClientConfig("10.0.0.5", configPath, hostConfigPath);

    assertEquals("10.0.0.5", ClientConfig.load(configPath).host());
  }

  @Test
  void refusesOnHostAndDoesNotClobberServerCredentials() throws Exception {
    var configPath = tempDir.resolve("config.yaml");
    var hostConfigPath = tempDir.resolve("host.yaml");
    Files.writeString(hostConfigPath, "role: host\n");
    var hostCredentials = "server: http://localhost:7070\ntoken: secret-host-token\n";
    Files.writeString(configPath, hostCredentials);

    var ex =
        assertThrows(
            IllegalStateException.class,
            () -> ClientInitCommand.writeClientConfig("some-host", configPath, hostConfigPath));
    assertTrue(ex.getMessage().contains("host"));
    assertEquals(
        hostCredentials, Files.readString(configPath), "Host credentials must be left untouched");
  }
}
