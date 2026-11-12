/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ZfsCacheCapTest {

  @Test
  void theCapIsATenthOfMemory() {
    assertEquals(6871947673L, ZfsCacheCap.forMemory(65536).bytes());
    assertEquals(13743895347L, ZfsCacheCap.forMemory(131072).bytes());
  }

  @Test
  void theCapIsNeverBelowWhatZfsHonours() {
    assertEquals(64L << 20, ZfsCacheCap.forMemory(512).bytes());
    assertEquals(64L << 20, ZfsCacheCap.forMemory(640).bytes());
    assertEquals(67213721L, ZfsCacheCap.forMemory(641).bytes());
  }

  @Test
  void theCapIsAModuleOptionAndIsWrittenToTheLoadedModule() {
    var dropIn = ZfsCacheCap.forMemory(65536).dropIn();

    assertEquals("/etc/modprobe.d/sail-zfs.conf", dropIn.path());
    assertEquals(
        "# Managed by sail: caps the ZFS read cache, so the rest of memory is the containers'.\n"
            + "options zfs zfs_arc_max=6871947673\n",
        dropIn.content());
    assertEquals(
        "\nif [ -e /sys/module/zfs/parameters/zfs_arc_max ]; then printf '%s' 6871947673 >"
            + " /sys/module/zfs/parameters/zfs_arc_max; fi\n",
        dropIn.reload());
    assertEquals("ZFS cache capped at 6.4 GiB", ZfsCacheCap.forMemory(65536).said());
  }
}
