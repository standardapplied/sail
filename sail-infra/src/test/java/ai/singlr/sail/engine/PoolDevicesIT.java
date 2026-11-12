/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link PoolDevices} against the runner's own disks, read-only: lsblk, wipefs without writing and
 * realpath, as root. The runner has one disk and the system runs from it, so every disk and
 * partition must be refused, with the force flag and without, and none offered. Nothing here
 * creates a pool or passes a wipe to anything.
 */
class PoolDevicesIT extends AbstractIncusIT {

  @Test
  void everyDiskAndPartitionOfTheRunnerIsRefusedWithAndWithoutTheForceFlag() throws Exception {
    ensureIncusOrSkip();
    var root = new RootShell();
    var devices = new PoolDevices(root);
    var listed = root.exec(List.of("lsblk", "-nrpo", "PATH,TYPE"));
    assertTrue(listed.ok(), listed.reason());
    var paths =
        listed
            .stdout()
            .lines()
            .map(line -> line.split(" "))
            .filter(row -> row[1].equals("disk") || row[1].equals("part"))
            .map(row -> row[0])
            .filter(path -> !path.startsWith("/dev/loop"))
            .toList();
    assertFalse(paths.isEmpty(), listed.stdout());

    var refusals = new ArrayList<String>();
    for (var path : paths) {
      for (var force : new String[] {null, path}) {
        var refused =
            assertThrows(
                IllegalArgumentException.class,
                () -> devices.cleared(path, force),
                path + " was cleared with force=" + force);
        assertTrue(
            refused.getMessage().startsWith(path + " is refused, even with --force-wipe: "),
            refused.getMessage());
        refusals.add("REFUSED " + path + " force=" + force + ": " + refused.getMessage());
      }
    }
    refusals.forEach(System.out::println);

    assertEquals(List.of(), devices.candidates());
  }
}
