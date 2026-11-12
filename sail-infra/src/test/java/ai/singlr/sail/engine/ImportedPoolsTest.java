/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The vdevs of {@code zpool list -vHP} as {@link ImportedPools} reads them, on real output. */
class ImportedPoolsTest {

  @Test
  void everyClassOfACapturedPoolIsAMemberAndNoClassHeaderIs() {
    var listed = DeviceFixtures.read("classes.zpool-list.txt").lines().toList();

    assertEquals(
        List.of("/dev/loop0", "/dev/loop4", "/dev/loop1", "/dev/loop2", "/dev/loop3"),
        ImportedPools.vdevs(listed));
  }

  @Test
  void theCacheDeviceOfThePoolOnTheProofsVmIsAMember() {
    assertEquals(
        List.of("/dev/sdd1", "/dev/sdg1"),
        ImportedPools.vdevs(
            DeviceFixtures.read("gcp/handpool-with-cache.zpool-list.txt").lines().toList()));
  }
}
