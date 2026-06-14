/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeModeTest {

  @TempDir Path tempDir;

  @Test
  void detectsHostWhenHostYamlExists() throws Exception {
    var hostPath = tempDir.resolve("host.yaml");
    Files.writeString(hostPath, "storage_backend: dir\n");
    var clientPath = tempDir.resolve("config.yaml");

    var mode = RuntimeMode.detect(hostPath, clientPath);

    assertInstanceOf(RuntimeMode.Host.class, mode);
  }

  @Test
  void detectsClientWhenOnlyClientConfigExists() throws Exception {
    var hostPath = tempDir.resolve("host.yaml");
    var clientPath = tempDir.resolve("config.yaml");
    Files.writeString(clientPath, "host: 192.168.1.100\nuser: root\n");

    var mode = RuntimeMode.detect(hostPath, clientPath);

    assertInstanceOf(RuntimeMode.Client.class, mode);
    var client = (RuntimeMode.Client) mode;
    assertEquals("192.168.1.100", client.config().host());
  }

  @Test
  void prefersHostWhenBothExist() throws Exception {
    var hostPath = tempDir.resolve("host.yaml");
    Files.writeString(hostPath, "storage_backend: dir\n");
    var clientPath = tempDir.resolve("config.yaml");
    Files.writeString(clientPath, "host: 10.0.0.1\n");

    var mode = RuntimeMode.detect(hostPath, clientPath);

    assertInstanceOf(RuntimeMode.Host.class, mode);
  }

  @Test
  void defaultsToHostWhenNeitherExists() {
    var hostPath = tempDir.resolve("host.yaml");
    var clientPath = tempDir.resolve("config.yaml");

    var mode = RuntimeMode.detect(hostPath, clientPath);

    assertInstanceOf(RuntimeMode.Host.class, mode);
  }

  @Test
  void defaultsToHostWhenClientConfigInvalid() throws Exception {
    var hostPath = tempDir.resolve("host.yaml");
    var clientPath = tempDir.resolve("config.yaml");
    Files.writeString(clientPath, "user: root\n");

    var mode = RuntimeMode.detect(hostPath, clientPath);

    assertInstanceOf(RuntimeMode.Host.class, mode);
  }

  @Test
  void clientConfigWithSshAlias() throws Exception {
    var hostPath = tempDir.resolve("host.yaml");
    var clientPath = tempDir.resolve("config.yaml");
    Files.writeString(clientPath, "host: kubera-server\n");

    var mode = RuntimeMode.detect(hostPath, clientPath);

    assertInstanceOf(RuntimeMode.Client.class, mode);
    var client = (RuntimeMode.Client) mode;
    assertEquals("kubera-server", client.config().host());
  }
}
