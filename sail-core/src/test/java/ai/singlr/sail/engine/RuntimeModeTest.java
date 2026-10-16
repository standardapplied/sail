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

    var mode = detect(hostPath, clientPath);

    assertInstanceOf(RuntimeMode.Host.class, mode);
  }

  @Test
  void detectsClientWhenOnlyClientConfigExists() throws Exception {
    var hostPath = tempDir.resolve("host.yaml");
    var clientPath = tempDir.resolve("config.yaml");
    Files.writeString(clientPath, "host: 192.168.1.100\nuser: root\n");

    var mode = detect(hostPath, clientPath);

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

    var mode = detect(hostPath, clientPath);

    assertInstanceOf(RuntimeMode.Host.class, mode);
  }

  @Test
  void defaultsToHostWhenNeitherExists() {
    var hostPath = tempDir.resolve("host.yaml");
    var clientPath = tempDir.resolve("config.yaml");

    var mode = detect(hostPath, clientPath);

    assertInstanceOf(RuntimeMode.Host.class, mode);
  }

  @Test
  void defaultsToHostWhenClientConfigInvalid() throws Exception {
    var hostPath = tempDir.resolve("host.yaml");
    var clientPath = tempDir.resolve("config.yaml");
    Files.writeString(clientPath, "user: root\n");

    var mode = detect(hostPath, clientPath);

    assertInstanceOf(RuntimeMode.Host.class, mode);
  }

  @Test
  void clientConfigWithSshAlias() throws Exception {
    var hostPath = tempDir.resolve("host.yaml");
    var clientPath = tempDir.resolve("config.yaml");
    Files.writeString(clientPath, "host: kubera-server\n");

    var mode = detect(hostPath, clientPath);

    assertInstanceOf(RuntimeMode.Client.class, mode);
    var client = (RuntimeMode.Client) mode;
    assertEquals("kubera-server", client.config().host());
  }

  private RuntimeMode detect(Path hostPath, Path clientPath) {
    return RuntimeMode.detect(hostPath, clientPath, tempDir.resolve("no-unit"), false, null);
  }

  private Path systemUnit() throws Exception {
    var unit = tempDir.resolve("sail-api.service");
    Files.writeString(unit, "[Unit]\n");
    return unit;
  }

  @Test
  void aNonRootAccountSeeingNoHostConfigOnARootBoxIsRefused() throws Exception {
    var mode =
        RuntimeMode.detect(
            tempDir.resolve("host.yaml"),
            tempDir.resolve("config.yaml"),
            systemUnit(),
            false,
            null);

    assertInstanceOf(RuntimeMode.Refused.class, mode);
  }

  @Test
  void anAccountThatSeesTheSharedHostConfigIsHost() throws Exception {
    var shared = tempDir.resolve("host.yaml");
    Files.writeString(shared, "storage_backend: dir\n");

    var mode =
        RuntimeMode.detect(shared, tempDir.resolve("config.yaml"), systemUnit(), false, null);

    assertInstanceOf(RuntimeMode.Host.class, mode);
  }

  @Test
  void anAccountWithItsOwnHostConfigKeepsItsOwnBox() throws Exception {
    var own = tempDir.resolve("home/.sail/host.yaml");
    Files.createDirectories(own.getParent());
    Files.writeString(own, "storage_backend: dir\n");

    var mode =
        RuntimeMode.detect(own, own.resolveSibling("config.yaml"), systemUnit(), false, null);

    assertInstanceOf(RuntimeMode.Host.class, mode);
  }

  @Test
  void aStrayConfigHoldingOnlyAServerAndTokenIsNoClientConfigAndIsRefused() throws Exception {
    var stray = tempDir.resolve("config.yaml");
    Files.writeString(stray, "server: http://127.0.0.1:7070\ntoken: abc\n");

    var mode = RuntimeMode.detect(tempDir.resolve("host.yaml"), stray, systemUnit(), false, null);

    assertInstanceOf(RuntimeMode.Refused.class, mode);
  }

  @Test
  void aClientConfigStillForwardsOnARootBox() throws Exception {
    var client = tempDir.resolve("config.yaml");
    Files.writeString(client, "host: maindevbox\n");

    var mode = RuntimeMode.detect(tempDir.resolve("host.yaml"), client, systemUnit(), false, null);

    assertInstanceOf(RuntimeMode.Client.class, mode);
  }

  @Test
  void withoutTheSystemUnitANonRootAccountIsHost() {
    var mode =
        RuntimeMode.detect(
            tempDir.resolve("host.yaml"),
            tempDir.resolve("config.yaml"),
            tempDir.resolve("sail-api.service"),
            false,
            null);

    assertInstanceOf(RuntimeMode.Host.class, mode);
  }

  @Test
  void rootAndAnAccountNamingItsDataDirAreHostOnARootBox() throws Exception {
    var unit = systemUnit();
    var host = tempDir.resolve("host.yaml");
    var client = tempDir.resolve("config.yaml");

    assertInstanceOf(RuntimeMode.Host.class, RuntimeMode.detect(host, client, unit, true, null));
    assertInstanceOf(
        RuntimeMode.Host.class, RuntimeMode.detect(host, client, unit, false, "/var/lib/sail"));
    assertInstanceOf(RuntimeMode.Refused.class, RuntimeMode.detect(host, client, unit, false, " "));
  }
}
