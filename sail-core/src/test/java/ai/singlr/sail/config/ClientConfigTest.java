/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientConfigTest {

  @TempDir Path tempDir;

  @Test
  void fromMapParsesHost() {
    var config = ClientConfig.fromMap(Map.<String, Object>of("host", "192.168.1.100"));

    assertEquals("192.168.1.100", config.host());
  }

  @Test
  void fromMapAcceptsSshAlias() {
    var config = ClientConfig.fromMap(Map.<String, Object>of("host", "kubera-server"));

    assertEquals("kubera-server", config.host());
  }

  @Test
  void fromMapThrowsWhenHostMissing() {
    var map = new LinkedHashMap<String, Object>();

    var ex = assertThrows(IllegalArgumentException.class, () -> ClientConfig.fromMap(map));

    assertTrue(ex.getMessage().contains("host"));
  }

  @Test
  void fromMapThrowsWhenHostBlank() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ClientConfig.fromMap(Map.<String, Object>of("host", "   ")));
  }

  @Test
  void toMapRoundTrips() {
    var original = new ClientConfig("192.168.1.100");

    var rebuilt = ClientConfig.fromMap(original.toMap());

    assertEquals(original, rebuilt);
  }

  @Test
  void userDefaultsToGatewayUser() {
    var config = ClientConfig.fromMap(Map.<String, Object>of("host", "devbox"));

    assertEquals(ClientConfig.GATEWAY_USER, config.user());
    assertTrue(config.gatewayEnabled());
    assertEquals("sail@devbox", config.gatewayTarget());
  }

  @Test
  void blankUserDisablesGateway() {
    var config = ClientConfig.fromMap(Map.<String, Object>of("host", "devbox", "user", ""));

    assertFalse(config.gatewayEnabled());
  }

  @Test
  void explicitUserOverridesGatewayTarget() {
    var config = ClientConfig.fromMap(Map.<String, Object>of("host", "devbox", "user", "gw"));

    assertEquals("gw@devbox", config.gatewayTarget());
  }

  @Test
  void loadFromFile() throws Exception {
    var yaml = "host: 192.168.1.50\n";
    var file = tempDir.resolve("config.yaml");
    Files.writeString(file, yaml);

    var config = ClientConfig.load(file);

    assertEquals("192.168.1.50", config.host());
  }

  @Test
  void loadThrowsWhenFileNotFound() {
    assertThrows(IOException.class, () -> ClientConfig.load(tempDir.resolve("nonexistent.yaml")));
  }

  @Test
  void loadFromFileWithSshAlias() throws Exception {
    var yaml = "host: my-server\n";
    var file = tempDir.resolve("config.yaml");
    Files.writeString(file, yaml);

    var config = ClientConfig.load(file);

    assertEquals("my-server", config.host());
  }
}
