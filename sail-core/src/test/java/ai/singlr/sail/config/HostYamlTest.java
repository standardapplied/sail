/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.FileMutex;
import ai.singlr.sail.engine.FileMutexTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostYamlTest {

  private static final String NODE =
      """
      incus_version: "6.8"
      sync:
        role: node
        main: sail@main
        handle: node
      """;

  @Test
  void aRewriteWaitsOutAnIdentityWriteAndKeepsWhatItWrote(@TempDir Path dir) throws Exception {
    var database = Files.createFile(dir.resolve("sail.db"));
    var path = Files.writeString(dir.resolve("host.yaml"), NODE);
    var rewrite =
        new FutureTask<>(
            () ->
                HostYaml.rewrite(
                    path, database, host -> Optional.of(host.withServerIp("192.0.2.9"))));

    try (var identityWrite = FileMutex.holdRounds(database)) {
      Files.writeString(path, "");
      FileMutexTest.awaitParked(Thread.ofPlatform().start(rewrite));
      Files.writeString(path, NODE);
    }
    rewrite.get();

    var written = HostYaml.fromMap(YamlUtil.parseFile(path));
    assertEquals("192.0.2.9", written.serverIp());
    assertEquals("node", written.sync().role());
    assertEquals("sail@main", written.sync().main());
  }

  @Test
  void aRewriteThatChangesNothingWritesNothing(@TempDir Path dir) throws Exception {
    var path = Files.writeString(dir.resolve("host.yaml"), NODE);

    assertTrue(HostYaml.rewrite(path, dir.resolve("sail.db"), host -> Optional.empty()).isEmpty());
    assertEquals(NODE, Files.readString(path));
  }

  @ParameterizedTest
  @ValueSource(longs = {10485760, 2147483648L})
  void serverIpChangePreservesConfigurationThroughSerialization(long fileMax) {
    var host =
        HostYaml.fromMap(
            YamlUtil.parseMap(
                """
        storage_backend: dir
        pool: devpool
        bridge: incusbr0
        base_profile: singlr-base
        image: ubuntu/24.04
        incus_version: "6.8"
        server_ip: 192.0.2.1
        initialized_at: "2026-01-01T00:00:00Z"
        webauthn:
          rp_id: sail.example.com
        sync:
          role: main
          box_id: main-box
        limits:
          file_max: %d
        """
                    .formatted(fileMax)));
    var expected = new LinkedHashMap<>(host.toMap());
    expected.put("server_ip", "192.0.2.9");

    var updated = host.withServerIp("192.0.2.9");
    var reloaded = HostYaml.fromMap(YamlUtil.parseMap(YamlUtil.dumpToString(updated.toMap())));

    assertEquals(expected, reloaded.toMap());
    assertEquals(fileMax, reloaded.limits().fileMax());
  }

  @Test
  void parsesHostYaml() throws Exception {
    var yaml =
        """
            storage_backend: zfs
            pool: devpool
            pool_disk: /dev/sdb
            bridge: incusbr0
            base_profile: singlr-base
            image: ubuntu/24.04
            incus_version: "6.21"
            initialized_at: "2026-02-18T01:00:00Z"
            """;

    var host = HostYaml.fromMap(YamlUtil.parseMap(yaml));

    assertEquals("zfs", host.storageBackend());
    assertTrue(host.isZfs());
    assertFalse(host.isDir());
    assertEquals("devpool", host.pool());
    assertEquals("/dev/sdb", host.poolDisk());
    assertEquals("incusbr0", host.bridge());
    assertEquals("singlr-base", host.baseProfile());
    assertEquals("ubuntu/24.04", host.image());
    assertEquals("6.21", host.incusVersion());
    assertEquals("2026-02-18T01:00:00Z", host.initializedAt());
  }

  @Test
  void parsesHostYamlWithDirBackend() throws Exception {
    var yaml =
        """
            storage_backend: dir
            pool: devpool
            bridge: incusbr0
            base_profile: singlr-base
            image: ubuntu/24.04
            incus_version: "6.21"
            initialized_at: "2026-02-18T01:00:00Z"
            """;

    var host = HostYaml.fromMap(YamlUtil.parseMap(yaml));

    assertEquals("dir", host.storageBackend());
    assertTrue(host.isDir());
    assertFalse(host.isZfs());
    assertNull(host.poolDisk());
  }

  @Test
  void missingStorageBackendDefaultsToZfs() throws Exception {
    var yaml =
        """
            pool: devpool
            pool_disk: /dev/sdb
            bridge: incusbr0
            base_profile: singlr-base
            image: ubuntu/24.04
            incus_version: "6.21"
            initialized_at: "2026-02-18T01:00:00Z"
            """;

    var host = HostYaml.fromMap(YamlUtil.parseMap(yaml));

    assertEquals("zfs", host.storageBackend());
    assertTrue(host.isZfs());
  }

  @Test
  void serializesToYaml() throws Exception {
    var host =
        new HostYaml(
            "zfs",
            "devpool",
            "/dev/sdb",
            "incusbr0",
            "singlr-base",
            "ubuntu/24.04",
            "6.21",
            null,
            "2026-02-18T01:00:00Z");

    var yaml = YamlUtil.dumpToString(host.toMap());

    assertTrue(yaml.contains("storage_backend: zfs") || yaml.contains("storage_backend: \"zfs\""));
    assertTrue(yaml.contains("pool: devpool") || yaml.contains("pool: \"devpool\""));
    assertTrue(yaml.contains("pool_disk:"));
    assertTrue(yaml.contains("base_profile:"));
    assertFalse(yaml.contains("webauthn"));
  }

  @Test
  void parsesWebauthnBlock() throws Exception {
    var yaml =
        """
            storage_backend: dir
            incus_version: "6.21"
            initialized_at: "2026-02-18T01:00:00Z"
            webauthn:
              rp_id: sail.acme.dev
              rp_name: Sail
              origins:
                - https://sail.acme.dev
            """;

    var host = HostYaml.fromMap(YamlUtil.parseMap(yaml));

    assertTrue(host.webauthn().isConfigured());
    assertEquals("sail.acme.dev", host.webauthn().rpId());
    assertEquals("Sail", host.webauthn().rpName());
    assertEquals(java.util.List.of("https://sail.acme.dev"), host.webauthn().origins());
  }

  @Test
  void absentWebauthnBlockIsDisabledNotNull() throws Exception {
    var host = HostYaml.fromMap(YamlUtil.parseMap("storage_backend: dir\n"));
    assertNotNull(host.webauthn());
    assertFalse(host.webauthn().isConfigured());
  }

  @Test
  void webauthnBlockSurvivesRoundTrip() throws Exception {
    var host =
        new HostYaml(
            "dir",
            "devpool",
            null,
            "incusbr0",
            "singlr-base",
            "ubuntu/24.04",
            "6.21",
            null,
            "2026-02-18T01:00:00Z",
            new WebauthnConfig(
                "sail.acme.dev", "Sail", java.util.List.of("https://sail.acme.dev")));

    var reparsed = HostYaml.fromMap(YamlUtil.parseMap(YamlUtil.dumpToString(host.toMap())));

    assertEquals(host.webauthn(), reparsed.webauthn());
  }

  @Test
  void partialWebauthnBlockSurvivesRoundTrip() throws Exception {
    var host =
        new HostYaml(
            "dir",
            "devpool",
            null,
            "incusbr0",
            "singlr-base",
            "ubuntu/24.04",
            "6.21",
            null,
            "2026-02-18T01:00:00Z",
            new WebauthnConfig(null, null, null, 48));

    var reparsed = HostYaml.fromMap(YamlUtil.parseMap(YamlUtil.dumpToString(host.toMap())));

    assertEquals(48, reparsed.webauthn().sessionTtlHours());
    assertFalse(reparsed.webauthn().isConfigured());
  }

  @Test
  void syncBlockSurvivesRoundTripAndIsOmittedWhenUnset() throws Exception {
    var pointed =
        new HostYaml(
            "dir",
            "devpool",
            null,
            "incusbr0",
            "singlr-base",
            "ubuntu/24.04",
            "6.21",
            null,
            "2026-02-18T01:00:00Z",
            WebauthnConfig.disabled(),
            new SyncConfig(SyncConfig.ROLE_NODE, "sail@maindevbox", null));

    var reparsed = HostYaml.fromMap(YamlUtil.parseMap(YamlUtil.dumpToString(pointed.toMap())));
    assertEquals("node", reparsed.sync().role());
    assertEquals("sail@maindevbox", reparsed.sync().main());

    var unset = HostYaml.fromMap(YamlUtil.parseMap("storage_backend: dir\n"));
    assertFalse(unset.toMap().containsKey("sync"));
  }

  @Test
  void aStandaloneBoxKeepsTheHandleAndIdItTookWithNoRole() throws Exception {
    var standalone =
        HostYaml.fromMap(YamlUtil.parseMap("storage_backend: dir\n"))
            .withSync(new SyncConfig(null, null, "ada", "devbox"));

    var reparsed = HostYaml.fromMap(YamlUtil.parseMap(YamlUtil.dumpToString(standalone.toMap())));

    assertEquals(standalone.sync(), reparsed.sync());
    assertEquals("ada", reparsed.sync().handle());
    assertEquals("devbox", reparsed.sync().boxId());
  }
}
