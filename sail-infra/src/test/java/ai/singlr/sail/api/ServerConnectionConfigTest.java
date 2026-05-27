/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerConnectionConfigTest {

  @Test
  void flagsOverrideEverything() throws IOException {
    var config = ServerConnectionConfig.resolve("http://custom:9090", "my-token");
    assertEquals("http://custom:9090", config.serverUrl());
    assertEquals("my-token", config.token());
  }

  @Test
  void missingTokenWithoutConfigThrows() {
    assertThrows(
        IOException.class, () -> ServerConnectionConfig.resolve("http://localhost:7070", null));
  }

  @Test
  void defaultUrlIsLocalhostWhenNotProvided() throws IOException {
    var config = ServerConnectionConfig.resolve(null, "some-token");
    assertEquals("http://localhost:7070", config.serverUrl());
  }

  @Test
  void systemPropertyFallback() throws IOException {
    System.setProperty("SAIL_SERVER", "http://prop-server:8080");
    System.setProperty("SAIL_TOKEN", "prop-token");
    try {
      var config = ServerConnectionConfig.resolve(null, null);
      assertEquals("http://prop-server:8080", config.serverUrl());
      assertEquals("prop-token", config.token());
    } finally {
      System.clearProperty("SAIL_SERVER");
      System.clearProperty("SAIL_TOKEN");
    }
  }

  @Test
  void resolveNoArgThrowsWithoutToken() {
    assertThrows(IOException.class, ServerConnectionConfig::resolve);
  }

  @TempDir Path tempDir;

  @Test
  void resolvesFromConfigFile() throws IOException {
    var configFile = tempDir.resolve("config.yaml");
    Files.writeString(configFile, "server: http://file-server:9090\ntoken: file-token\n");

    var config = ServerConnectionConfig.resolve(null, null, configFile);
    assertEquals("http://file-server:9090", config.serverUrl());
    assertEquals("file-token", config.token());
  }

  @Test
  void flagsOverrideConfigFile() throws IOException {
    var configFile = tempDir.resolve("config.yaml");
    Files.writeString(configFile, "server: http://file-server:9090\ntoken: file-token\n");

    var config = ServerConnectionConfig.resolve("http://flag:8080", "flag-token", configFile);
    assertEquals("http://flag:8080", config.serverUrl());
    assertEquals("flag-token", config.token());
  }

  @Test
  void missingConfigFileUsesDefaults() throws IOException {
    var config =
        ServerConnectionConfig.resolve(null, "explicit-token", tempDir.resolve("nonexistent.yaml"));
    assertEquals("http://localhost:7070", config.serverUrl());
    assertEquals("explicit-token", config.token());
  }

  @Test
  void partialConfigFileFillsGaps() throws IOException {
    var configFile = tempDir.resolve("partial.yaml");
    Files.writeString(configFile, "token: from-file\n");

    var config = ServerConnectionConfig.resolve(null, null, configFile);
    assertEquals("http://localhost:7070", config.serverUrl());
    assertEquals("from-file", config.token());
  }
}
