/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A mirror's health read from {@code zpool status -P}, in the output OpenZFS 2.2.2 prints ({@code
 * zpool_main.c} {@code status_callback}, {@code print_status_config}; the hand-written fixtures
 * under {@code devices/}).
 */
class PoolHealthTest {

  private static final String A = "/dev/disk/by-id/wwn-0x5000c500a1b2c3d4-part5";
  private static final String B = "/dev/disk/by-id/wwn-0x5000c500e5f6a7b8-part5";

  @Test
  void anOnlineMirrorIsOnlineWithNoDeviceNamed() throws Exception {
    var health = read("handwritten-mirror-online.zpool-status.txt").orElseThrow();

    assertEquals(new PoolHealth("ONLINE", Map.of()), health);
    assertEquals("ONLINE", health.said());
    assertTrue(health.usable());
    assertFalse(health.degraded());
  }

  @Test
  void aDegradedMirrorNamesTheDeviceItRunsWithout() throws Exception {
    var removed = read("handwritten-mirror-degraded-removed.zpool-status.txt").orElseThrow();
    var atImport = read("handwritten-mirror-degraded-at-import.zpool-status.txt").orElseThrow();

    assertEquals(new PoolHealth("DEGRADED", Map.of(B, "REMOVED")), removed);
    assertEquals("DEGRADED (" + B + " is REMOVED)", removed.said());
    assertEquals(
        new PoolHealth("DEGRADED", Map.of(B, "UNAVAIL")),
        atImport,
        "a device absent at import is listed by its GUID and named by the path it was");
    assertTrue(removed.usable() && removed.degraded());
    assertEquals(
        "Pool devpool is DEGRADED ("
            + B
            + " is REMOVED), as zpool status says: it runs on the device left, with no redundancy"
            + " until the missing one is replaced. Sail replaces nothing: put a new device in its"
            + " place with sudo zpool replace devpool <missing device> <new device>.",
        removed.degradedNotice("devpool"));
  }

  @Test
  void aMirrorWithBothDevicesGoneIsNotUsable() throws Exception {
    var suspended = read("handwritten-mirror-suspended.zpool-status.txt").orElseThrow();
    var faulted = read("handwritten-mirror-faulted.zpool-status.txt").orElseThrow();

    assertEquals(new PoolHealth("SUSPENDED", Map.of(A, "REMOVED", B, "REMOVED")), suspended);
    assertEquals("FAULTED (" + A + " is UNAVAIL, " + B + " is UNAVAIL)", faulted.said());
    assertFalse(suspended.usable());
    assertFalse(faulted.usable());
  }

  @Test
  void aPoolZpoolHasNotImportedIsSaidSoAndOneZpoolCannotTellOfIsNone() throws Exception {
    var gone =
        new ScriptedShellExecutor().onFail("zpool status", "cannot open 'devpool': no such pool");

    assertEquals(
        Optional.of(new PoolHealth(PoolHealth.NOT_IMPORTED, Map.of())),
        PoolHealth.of(gone, "devpool"));
    assertEquals(Optional.empty(), PoolHealth.of(new ScriptedShellExecutor(), "devpool"));
    assertEquals(
        Optional.empty(),
        PoolHealth.of(
            new ScriptedShellExecutor().onThrow("zpool", new IOException("no zpool")), "devpool"));
    assertEquals(
        Optional.empty(), PoolHealth.of(new ScriptedShellExecutor().onOk("zpool status"), "x"));
    assertFalse(PoolHealth.of(gone, "devpool").orElseThrow().usable());
  }

  @Test
  void theStatusIsAskedOfTheZpoolInTheCLocale() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "zpool status", DeviceFixtures.read("handwritten-mirror-online.zpool-status.txt"));

    PoolHealth.of(shell, "devpool");

    assertEquals(
        List.of(List.of("env", "LC_ALL=C", "zpool", "status", "-P", "devpool")), shell.arguments());
  }

  private static Optional<PoolHealth> read(String fixture) throws Exception {
    return PoolHealth.of(
        new ScriptedShellExecutor().onOk("zpool status -P devpool", DeviceFixtures.read(fixture)),
        "devpool");
  }
}
