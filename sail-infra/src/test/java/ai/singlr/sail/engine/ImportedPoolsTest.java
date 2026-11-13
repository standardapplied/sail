/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
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
  void eachVdevIsReadUnderTheClassZpoolListsItUnderItsDataVdevsBeforeAnyHeader() {
    var listed = DeviceFixtures.read("classes.zpool-list.txt").lines().toList();

    assertEquals(
        Map.of(
            ImportedPools.DATA,
            List.of("/dev/loop0"),
            "special",
            List.of("/dev/loop4"),
            "logs",
            List.of("/dev/loop1"),
            "cache",
            List.of("/dev/loop2"),
            "spare",
            List.of("/dev/loop3")),
        ImportedPools.classes(listed));
    assertEquals(
        Map.of(
            ImportedPools.DATA,
            List.of("mirror-0", DeviceFixtures.K1 + "-part1", DeviceFixtures.K2 + "-part1"),
            "logs",
            List.of("/dev/sde1"),
            "cache",
            List.of("/dev/sdf1")),
        ImportedPools.classes(
            DeviceFixtures.read("gcp6/g-705-mirror-with-log-and-cache.zpool-list-P.txt")
                .lines()
                .toList()));
  }

  @Test
  void theCacheDeviceOfThePoolOnTheProofsVmIsAMember() {
    assertEquals(
        List.of("/dev/sdd1", "/dev/sdg1"),
        ImportedPools.vdevs(
            DeviceFixtures.read("gcp/handpool-with-cache.zpool-list.txt").lines().toList()));
  }
}
