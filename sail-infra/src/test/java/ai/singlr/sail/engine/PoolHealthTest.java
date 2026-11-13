/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
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
        List.of("replace " + B + ": sudo zpool replace devpool " + B + " <new device>"),
        removed.remedies("devpool"));
    assertEquals(
        "Pool devpool is DEGRADED ("
            + B
            + " is REMOVED), as zpool status says: it runs on the device left, with no redundancy"
            + " until that is mended. Sail mends nothing: replace "
            + B
            + ": sudo zpool replace devpool "
            + B
            + " <new device>.",
        removed.degradedNotice("devpool"));
  }

  @Test
  void aDeviceTheOperatorTookOfflineIsBroughtBackOnlineNotReplaced() throws Exception {
    var offline = read("gcp5/e-501-mirror-offline.zpool-status.txt").orElseThrow();

    assertEquals(new PoolHealth("DEGRADED", Map.of("/dev/sdc1", "OFFLINE")), offline);
    assertEquals(
        List.of("bring /dev/sdc1 back: sudo zpool online devpool /dev/sdc1"),
        offline.remedies("devpool"));
    assertTrue(
        offline
            .degradedNotice("devpool")
            .endsWith(
                "Sail mends nothing: bring /dev/sdc1 back: sudo zpool online devpool /dev/sdc1."));
  }

  @Test
  void aMirrorWithBothDevicesGoneIsNotUsable() throws Exception {
    var suspended = read("gcp5/e-540-mirror-suspended.zpool-status.txt").orElseThrow();
    var faulted = read("handwritten-mirror-faulted.zpool-status.txt").orElseThrow();

    assertEquals(
        new PoolHealth(
            "SUSPENDED",
            Map.of(
                "/dev/disk/by-id/scsi-0Google_PersistentDisk_sail-fleet-mirrorproof-b-n1-part1",
                "REMOVED")),
        suspended,
        "the proof's real SUSPENDED pool: its rows read DEGRADED, the device detached with I/O in"
            + " flight still ONLINE with errors, the other REMOVED");
    assertEquals("FAULTED (" + A + " is UNAVAIL, " + B + " is UNAVAIL)", faulted.said());
    var deviceFaulted =
        read("gcp6/h-900-mirror-suspended-device-faulted.zpool-status.txt").orElseThrow();
    assertEquals(
        new PoolHealth("SUSPENDED", Map.of("/dev/sdb1", "FAULTED")),
        deviceFaulted,
        "the third proof's real SUSPENDED pool: one device FAULTED with too many errors, the other"
            + " ONLINE with write errors");
    assertEquals("SUSPENDED (/dev/sdb1 is FAULTED)", deviceFaulted.said());
    assertFalse(deviceFaulted.usable());
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
    assertFalse(PoolHealth.of(gone, "devpool").orElseThrow().usable());
  }

  @Test
  void aDegradedPoolWhoseMissingDeviceZpoolDoesNotListByPathStaysDegraded() {
    var unnamed =
        DeviceFixtures.read("handwritten-mirror-degraded-removed.zpool-status.txt")
            .replace(
                "/dev/disk/by-id/wwn-0x5000c500e5f6a7b8-part5", "wwn-0x5000c500e5f6a7b8-part5");

    var health = PoolHealth.parsed(unnamed).orElseThrow();

    assertEquals(new PoolHealth("DEGRADED", Map.of()), health);
    assertTrue(health.degraded());
  }

  @Test
  void aZpoolThatDoesNotAnswerInTenSecondsOrFailsIsSaidSoAndNoneWhereItCannotRun()
      throws Exception {
    var hanging = new TimingOutShell(new ScriptedShellExecutor(), "zpool status");

    var timedOut = PoolHealth.of(hanging, "devpool").orElseThrow();

    assertEquals(new PoolHealth("did not answer within 10 s", Map.of()), timedOut);
    assertEquals(List.of(Duration.ofSeconds(10)), hanging.deadlines());
    assertFalse(timedOut.answered() || timedOut.usable());
    assertEquals(
        new PoolHealth("did not answer (permission denied)", Map.of()),
        PoolHealth.of(new ScriptedShellExecutor().onFail("zpool status", "permission denied"), "x")
            .orElseThrow());
    assertEquals(
        new PoolHealth("did not answer with a state", Map.of()),
        PoolHealth.of(new ScriptedShellExecutor().onOk("zpool status", "garbled\n"), "x")
            .orElseThrow());
    assertEquals(
        Optional.empty(),
        PoolHealth.of(
            new ScriptedShellExecutor()
                .on(
                    "zpool status",
                    new ShellExec.Result(127, "", "env: 'zpool': No such file or directory")),
            "x"));
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
