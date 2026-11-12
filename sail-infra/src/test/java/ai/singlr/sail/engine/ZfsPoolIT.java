/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A ZFS pool Incus makes from a file, as {@code host init --storage zfs --pool-file-size} has it
 * made, on the runner's own Incus: the runner has no spare disk, and its Incus started before the
 * ZFS tools were installed (the incus action installs them after), so the pool's creation also
 * shows that Incus needs no restart to see them. The ZFS commands that read what Incus made run as
 * root, as ZFS requires.
 */
class ZfsPoolIT extends AbstractIncusIT {

  private static final String POOL = "sail-it-zfs";
  private static final String CONTAINER = "sail-it-zfs-c";
  private static final String CLONE = "sail-it-zfs-clone";
  private static final long ONE_GIB = 1L << 30;

  private final IncusStorage storage = new IncusStorage(shell);
  private final ContainerManager containers = new ContainerManager(shell);

  @BeforeEach
  void makeThePool() throws Exception {
    ensureIncusOrSkip();
    removeThePool();
    var made = storage.create(POOL, new PoolSource.File(10240));
    assertTrue(made.ok(), "Incus could not make a file-backed zfs pool: " + made.reason());
  }

  @AfterEach
  void removeThePool() {
    deleteContainerQuietly(CLONE);
    deleteContainerQuietly(CONTAINER);
    try {
      shell.exec(List.of("incus", "storage", "delete", POOL));
    } catch (Exception ignored) {
    }
  }

  @Test
  void aFileBackedPoolIsIncussAndAContainerLaunchesOnIt() throws Exception {
    var pool = storage.find(POOL).orElseThrow();
    assertEquals("zfs", pool.driver());
    assertEquals("/var/lib/incus/disks/" + POOL + ".img", pool.source());
    var space = pool.space().orElseThrow();
    assertTrue(space.total() > 9 * ONE_GIB && space.total() <= 10 * ONE_GIB, pool.toString());

    launch(CONTAINER, "--storage", POOL);

    assertTrue(containers.queryState(CONTAINER) instanceof ContainerState.Running);
    assertEquals(
        POOL + "/containers/" + CONTAINER, zfs("get", "-H", "-o", "name", "name", dataset()));
  }

  @Test
  void aDiskLimitIsEnforcedByThePoolInsideTheContainer() throws Exception {
    launch(CONTAINER, "--storage", POOL);
    containers.setDiskQuota(CONTAINER, "1GiB");

    var write =
        exec(
            CONTAINER,
            List.of("dd", "if=/dev/urandom", "of=/fill", "bs=1M", "count=1536", "status=none"));

    assertNotEquals(0, write.exitCode(), "a write past the limit fails inside the container");
    assertTrue(write.stderr().contains("Disk quota exceeded"), write.stderr());
    assertEquals(
        String.valueOf(ONE_GIB), zfs("get", "-H", "-p", "-o", "value", "quota", dataset()));
    assertTrue(containers.queryState(CONTAINER) instanceof ContainerState.Running);
    assertTrue(exec(CONTAINER, List.of("rm", "-f", "/fill")).ok(), "the container still works");
  }

  @Test
  void aCopyFromASnapshotIsACloneThatUsesAlmostNoSpace() throws Exception {
    launch(CONTAINER, "--storage", POOL);
    new SnapshotManager(shell).create(CONTAINER, "s1");

    var copied = shell.exec(List.of("incus", "copy", CONTAINER + "/s1", CLONE));

    assertTrue(copied.ok(), copied.reason());
    var clone = POOL + "/containers/" + CLONE;
    assertEquals(dataset() + "@snapshot-s1", zfs("get", "-H", "-o", "value", "origin", clone));
    var used = Long.parseLong(zfs("get", "-H", "-p", "-o", "value", "used", clone));
    assertTrue(used < 16L << 20, "a clone holds only what it writes: " + used + " bytes");
  }

  @Test
  void aPausedContainerReadsAsFrozenAndStartResumesIt() throws Exception {
    launch(CONTAINER, "--storage", POOL);

    assertTrue(shell.exec(List.of("incus", "pause", CONTAINER)).ok());
    assertEquals(new ContainerState.Frozen(), containers.queryState(CONTAINER));

    containers.start(CONTAINER);
    assertTrue(containers.queryState(CONTAINER) instanceof ContainerState.Running);
  }

  private static String dataset() {
    return POOL + "/containers/" + CONTAINER;
  }

  private String zfs(String... args) throws Exception {
    var command = new ArrayList<>(List.of("sudo", "-n", "zfs"));
    command.addAll(List.of(args));
    var result = shell.exec(command);
    assertTrue(result.ok(), String.join(" ", command) + ": " + result.reason());
    return result.stdout().strip();
  }
}
