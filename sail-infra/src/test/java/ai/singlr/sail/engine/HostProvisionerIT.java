/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code host init}'s own steps on the runner's Incus, as root: a box made with a dir pool, a
 * container launched on its base profile, then a ZFS pool in a file added beside it. The pool is
 * Incus's, and the container, the base profile and {@code host.yaml}'s storage stay on the dir
 * pool.
 */
class HostProvisionerIT extends AbstractIncusIT {

  private static final String DIR_POOL = "sail-it-dir";
  private static final String ZFS_POOL = "sail-it-added";
  private static final String CONTAINER = "sail-it-on-dir";

  @TempDir Path dir;

  @AfterEach
  void removeWhatInitMade() {
    deleteContainerQuietly(CONTAINER);
    for (var command :
        List.of(
            List.of("incus", "profile", "delete", HostYaml.DEFAULT_PROFILE),
            List.of("incus", "storage", "delete", ZFS_POOL),
            List.of("incus", "storage", "delete", DIR_POOL))) {
      try {
        shell.exec(command);
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  void aDirBoxAddsAZfsPoolBesideItsOwnAndItsContainerStaysWhereItIs() throws Exception {
    ensureIncusOrSkip();
    var provisioner = new HostProvisioner(new RootShell(), null, OperatorCommand.SUDO);
    var memory = new HostDetector().detect().memoryMb();
    var hostYaml = dir.resolve("host.yaml");

    var first =
        provisioner.provision(
            new PoolSource.Dir(), DIR_POOL, "incusbr0", null, memory, hostYaml, dir.resolve("db"));
    launch(CONTAINER, "--profile", HostYaml.DEFAULT_PROFILE);
    var second =
        provisioner.provision(
            new PoolSource.File(4096),
            ZFS_POOL,
            "incusbr0",
            null,
            memory,
            hostYaml,
            dir.resolve("db"));

    assertEquals(List.of("dir", DIR_POOL), List.of(first.storageBackend(), first.pool()));
    assertEquals(List.of("dir", DIR_POOL), List.of(second.storageBackend(), second.pool()));
    var added = new IncusStorage(shell).find(ZFS_POOL).orElseThrow();
    assertEquals("zfs", added.driver());
    assertEquals("/var/lib/incus/disks/" + ZFS_POOL + ".img", added.source());
    assertEquals(
        DIR_POOL,
        shell
            .exec(
                List.of(
                    "incus", "profile", "device", "get", HostYaml.DEFAULT_PROFILE, "root", "pool"))
            .stdout()
            .strip());
    var instance =
        YamlUtil.parseMap(
            shell.exec(List.of("incus", "query", "/1.0/instances/" + CONTAINER)).stdout());
    assertEquals("Running", instance.get("status"));
    assertEquals(
        DIR_POOL,
        ((Map<?, ?>) ((Map<?, ?>) instance.get("expanded_devices")).get("root")).get("pool"));
    assertTrue(
        new RootShell()
            .exec(List.of("cat", "/etc/modprobe.d/sail-zfs.conf"))
            .stdout()
            .contains("options zfs zfs_arc_max=" + ZfsCacheCap.forMemory(memory).bytes()));
  }
}
