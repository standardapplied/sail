/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static ai.singlr.sail.engine.DeviceFixtures.K1;
import static ai.singlr.sail.engine.DeviceFixtures.K2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What a pool is on as {@link PoolMembers} reads ZFS's listings of it: its data vdevs, and apart
 * from them the log, cache, spare, special and dedup devices an operator added by hand.
 */
class PoolMembersTest {

  private static PoolMembers of(String zpool, ScriptedShellExecutor shell)
      throws InterruptedException {
    return PoolMembers.of(
        shell,
        new IncusStorage.Pool(
            zpool, "zfs", "mirror=/dev/sdd,/dev/sde", zpool, null, Optional.empty()));
  }

  private static PoolMembers zg(String paths, String names) throws InterruptedException {
    return of(
        "zg",
        DeviceFixtures.zpoolLists(
            new ScriptedShellExecutor(), "zg", "gcp6/" + paths, "gcp6/" + names));
  }

  @Test
  void aLogDeviceTheOperatorAddedIsSaidApartFromTheMirror() throws Exception {
    var on = zg("g-701-mirror-with-log.zpool-list-P.txt", "g-701-mirror-with-log.zpool-list.txt");

    assertEquals(List.of(K1, K2), on.devices());
    assertTrue(on.mirror());
    assertEquals("a mirror of " + K1 + " and " + K2, on.said());
    assertEquals(Map.of("logs", List.of("/dev/sde")), on.auxiliary());
    assertEquals("plus a log device /dev/sde", on.besides());
  }

  @Test
  void aCacheDeviceBesideTheLogIsSaidApartFromTheMirrorToo() throws Exception {
    var on =
        zg(
            "g-705-mirror-with-log-and-cache.zpool-list-P.txt",
            "handwritten-g-705-mirror-with-log-and-cache.zpool-list.txt");

    assertEquals(List.of(K1, K2), on.devices());
    assertEquals("a mirror of " + K1 + " and " + K2, on.said());
    assertEquals(
        List.of("logs", "cache"), List.copyOf(on.auxiliary().keySet()), "in the order zpool lists");
    assertEquals("plus a log device /dev/sde and a cache device /dev/sdf", on.besides());
  }

  @Test
  void aSpareIsSaidApartFromTheMirror() throws Exception {
    var on =
        zg(
            "handwritten-mirror-with-spare.zpool-list-P.txt",
            "handwritten-mirror-with-spare.zpool-list.txt");

    assertEquals(List.of(K1, K2), on.devices());
    assertEquals("plus a spare /dev/sdg", on.besides());
  }

  @Test
  void aPoolOfOneDataDeviceIsOnItAloneWhateverItsOtherClassesHold() throws Exception {
    var on =
        of(
            "sail-it-classes",
            new ScriptedShellExecutor()
                .onOk(
                    "zpool list -vHP sail-it-classes",
                    DeviceFixtures.read("classes.zpool-list.txt")));

    assertEquals(List.of("/dev/loop0"), on.devices());
    assertFalse(on.mirror());
    assertEquals("/dev/loop0", on.said());
    assertEquals(
        "plus a special device /dev/loop4, a log device /dev/loop1, a cache device /dev/loop2"
            + " and a spare /dev/loop3",
        on.besides());
  }

  @Test
  void aMirroredLogMakesNoMirrorOfAPoolOnOneDataDevice() throws Exception {
    var on =
        of(
            "sail-it-classes",
            new ScriptedShellExecutor()
                .onOk(
                    "zpool list -vHP sail-it-classes",
                    DeviceFixtures.read("classes.zpool-list.txt")
                        .replace(
                            "\t/dev/loop1\t",
                            "\tmirror-1\t128M\t0\t112M\t-\t-\t0%\t0.00%\t-\tONLINE\n"
                                + "\t/dev/loop1\t")));

    assertFalse(on.mirror());
    assertEquals("/dev/loop0", on.said());
    assertEquals(List.of("/dev/loop1"), on.auxiliary().get("logs"));
  }

  @Test
  void aPoolWithoutOtherClassesSaysNothingBesideItsDevices() throws Exception {
    var bare =
        of(
            "zg",
            new ScriptedShellExecutor()
                .onOk("zpool list -vHP zg", firstLines("g-701-mirror-with-log.zpool-list-P.txt"))
                .onOk("zpool list -vH zg", firstLines("g-701-mirror-with-log.zpool-list.txt")));

    assertEquals(List.of(K1, K2), bare.devices());
    assertEquals(Map.of(), bare.auxiliary());
    assertEquals("", bare.besides());
  }

  @Test
  void aClassOfTwoDevicesIsSaidInThePlural() throws Exception {
    var on =
        of(
            "zg",
            new ScriptedShellExecutor()
                .onOk(
                    "zpool list -vHP zg",
                    DeviceFixtures.read("gcp6/g-701-mirror-with-log.zpool-list-P.txt")
                        + "\t/dev/sdh1\t9.99G\t0\t9.50G\t-\t-\t0%\t0.00%\t-\tONLINE\n"));

    assertEquals("plus log devices /dev/sde1 and /dev/sdh1", on.besides());
  }

  /** The pool's line and its data vdevs' rows of a fixture: those before its first class header. */
  private static String firstLines(String name) {
    return String.join("\n", DeviceFixtures.read("gcp6/" + name).lines().limit(4).toList()) + "\n";
  }
}
