/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectEnvironmentTest {

  @TempDir Path tempDir;

  @Test
  void readsTheServerIpFromHostYaml() throws Exception {
    var hostYaml = tempDir.resolve("host.yaml");
    Files.writeString(hostYaml, "server_ip: 203.0.113.7\n");

    var environment = ConnectEnvironment.detect(hostYaml, true);

    assertEquals("203.0.113.7", environment.serverIp());
    assertEquals(System.getProperty("user.name"), environment.serverUser());
    assertTrue(environment.workstationKeySet());
  }

  @Test
  void degradesToNoServerIpWhenHostYamlIsMissing() {
    var environment = ConnectEnvironment.detect(tempDir.resolve("absent.yaml"), false);

    assertNull(environment.serverIp());
    assertFalse(environment.workstationKeySet());
  }

  @Test
  void degradesToNoServerIpWhenHostYamlIsUnreadable() throws Exception {
    var hostYaml = tempDir.resolve("host.yaml");
    Files.writeString(hostYaml, "{not yaml");

    assertNull(ConnectEnvironment.detect(hostYaml, false).serverIp());
  }

  @Test
  void detectReflectsTheCurrentProcessUser() {
    assertEquals(System.getProperty("user.name"), ConnectEnvironment.detect().serverUser());
  }
}
