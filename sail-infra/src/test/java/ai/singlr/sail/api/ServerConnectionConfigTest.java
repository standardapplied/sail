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

  @TempDir Path tempDir;

  private Path missingConfig() {
    return tempDir.resolve("missing-config.yaml");
  }

  @Test
  void flagsOverrideEverything() throws IOException {
    var config = ServerConnectionConfig.resolve("http://custom:9090", "my-token", missingConfig());
    assertEquals("http://custom:9090", config.serverUrl());
    assertEquals("my-token", config.token());
  }

  @Test
  void missingTokenWithoutConfigThrows() {
    assertThrows(
        IOException.class,
        () -> ServerConnectionConfig.resolve("http://localhost:7070", null, missingConfig()));
  }

  @Test
  void saveSessionTokenPreservesExistingServerUrl() throws IOException {
    var configPath = tempDir.resolve("config.yaml");
    ServerConnectionConfig.saveLocalConfig("https://sail.acme.dev", "sail_old", configPath);
    ServerConnectionConfig.saveSessionToken("sess_new", configPath);
    var resolved = ServerConnectionConfig.resolve(null, null, configPath);
    assertEquals("https://sail.acme.dev", resolved.serverUrl());
    assertEquals("sess_new", resolved.token());
  }

  @Test
  void saveSessionTokenDefaultsServerUrlWhenNoConfig() throws IOException {
    var configPath = tempDir.resolve("fresh-config.yaml");
    ServerConnectionConfig.saveSessionToken("sess_new", configPath);
    var resolved = ServerConnectionConfig.resolve(null, null, configPath);
    assertEquals("http://localhost:7070", resolved.serverUrl());
    assertEquals("sess_new", resolved.token());
  }

  @Test
  void savePreservesClientConfigKeys() throws IOException {
    var configPath = tempDir.resolve("config.yaml");
    java.nio.file.Files.writeString(configPath, "host: devbox\nuser: sail\n");

    ServerConnectionConfig.saveSessionToken("sess_new", configPath);

    var client = ai.singlr.sail.config.ClientConfig.load(configPath);
    assertEquals("devbox", client.host());
    assertEquals("sail", client.user());
    assertEquals("sess_new", ServerConnectionConfig.resolve(null, null, configPath).token());
  }

  @Test
  void defaultUrlIsLocalhostWhenNotProvided() throws IOException {
    var config = ServerConnectionConfig.resolve(null, "some-token", missingConfig());
    assertEquals("http://localhost:7070", config.serverUrl());
  }

  @Test
  void systemPropertyFallback() throws IOException {
    System.setProperty("SAIL_SERVER", "http://prop-server:8080");
    System.setProperty("SAIL_TOKEN", "prop-token");
    try {
      var config = ServerConnectionConfig.resolve(null, null, missingConfig());
      assertEquals("http://prop-server:8080", config.serverUrl());
      assertEquals("prop-token", config.token());
    } finally {
      System.clearProperty("SAIL_SERVER");
      System.clearProperty("SAIL_TOKEN");
    }
  }

  @Test
  void resolveNoArgThrowsWithoutToken() {
    assertThrows(
        IOException.class, () -> ServerConnectionConfig.resolve(null, null, missingConfig()));
  }

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

  @Test
  void saveLocalConfigWritesYaml() throws IOException {
    var configFile = tempDir.resolve("new-config.yaml");
    ServerConnectionConfig.saveLocalConfig("http://localhost:7070", "test-token", configFile);

    assertTrue(Files.exists(configFile));
    var config = ServerConnectionConfig.resolve(null, null, configFile);
    assertEquals("http://localhost:7070", config.serverUrl());
    assertEquals("test-token", config.token());
  }

  @Test
  void saveLocalConfigWritesTokenFileOwnerOnly() throws IOException {
    var configFile = tempDir.resolve("secret-config.yaml");
    ServerConnectionConfig.saveLocalConfig("http://localhost:7070", "secret-token", configFile);

    var perms = Files.getPosixFilePermissions(configFile);
    assertEquals(
        java.util.Set.of(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
        perms,
        "token file must not be group/world readable");
  }

  @Test
  void saveLocalConfigCreatesParentDirectories() throws IOException {
    var configFile = tempDir.resolve("deep/nested/config.yaml");
    ServerConnectionConfig.saveLocalConfig("http://localhost:7070", "tok", configFile);

    assertTrue(Files.exists(configFile));
  }

  @Test
  void aTokenFileSuppliesTheTokenAndIsTrimmed() throws IOException {
    var tokenFile = tempDir.resolve("token");
    Files.writeString(tokenFile, "  sail_fromfile\n");

    var resolved = ServerConnectionConfig.resolve(null, null, tokenFile, missingConfig());

    assertEquals("sail_fromfile", resolved.token());
  }

  @Test
  void theTokenFlagWinsOverATokenFile() throws IOException {
    var tokenFile = tempDir.resolve("token");
    Files.writeString(tokenFile, "from-file");

    var resolved = ServerConnectionConfig.resolve(null, "from-flag", tokenFile, missingConfig());

    assertEquals("from-flag", resolved.token());
  }

  @Test
  void aBlankTokenFileFallsThroughToTheNextSource() throws IOException {
    var tokenFile = tempDir.resolve("blank");
    Files.writeString(tokenFile, "   \n");

    assertThrows(
        IOException.class,
        () -> ServerConnectionConfig.resolve(null, null, tokenFile, missingConfig()));
  }

  @Test
  void sailTokenFileEnvSuppliesTheToken() throws IOException {
    var tokenFile = tempDir.resolve("env-token");
    Files.writeString(tokenFile, "sail_fromenvfile");
    System.setProperty("SAIL_TOKEN_FILE", tokenFile.toString());
    try {
      var resolved = ServerConnectionConfig.resolve(null, null, null, missingConfig());
      assertEquals("sail_fromenvfile", resolved.token());
    } finally {
      System.clearProperty("SAIL_TOKEN_FILE");
    }
  }

  @Test
  void saveLocalTokenUsesDefaultUrl() throws IOException {
    var configFile = tempDir.resolve("token-only.yaml");
    ServerConnectionConfig.saveLocalToken("my-token", configFile);

    var resolved = ServerConnectionConfig.resolve(null, null, configFile);
    assertEquals("my-token", resolved.token());
    assertEquals("http://localhost:7070", resolved.serverUrl());
  }
}
