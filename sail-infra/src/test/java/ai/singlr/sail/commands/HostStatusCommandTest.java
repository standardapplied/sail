/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ListenConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.DeviceFixtures;
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.TimingOutShell;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HostStatusCommandTest {

  private static final HostYaml RECORDED_AT_INIT =
      new HostYaml(
          "dir",
          "devpool",
          null,
          "incusbr0",
          "singlr-base",
          "ubuntu/24.04",
          "6.8",
          null,
          "2026-02-18T01:00:00Z");

  private static final HostDetector.HostInfo HOST =
      new HostDetector.HostInfo("main", "ubuntu", "24.04", "Ubuntu 24.04 LTS", 4, 8, 16384, true);

  @Test
  void theIncusVersionIsTheOneInstalledNowNotTheOneRecordedAtInit() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "Client version: 6.21\nServer version: 6.21\n");

    assertEquals("6.21", json(shell).get("incus_version"));
    assertTrue(text(shell).contains("│  Incus:       6.21 "));
  }

  @Test
  void aBoxSetToOpenNoPrivateDoorReportsAsItDidBeforeThereWereAny() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "Client version: 6.21\nServer version: 6.21\n");

    assertFalse(json(shell).containsKey("listen"));
    assertFalse(text(shell).contains("-listen"));
  }

  @Test
  void anIncusThatDoesNotAnswerReadsUnknown() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onFail("incus version", "cannot connect to the incus daemon");

    assertEquals("unknown", json(shell).get("incus_version"));
    assertTrue(text(shell).contains("│  Incus:       unknown "));
  }

  @Test
  void runningAndStoppedContainersAreCounted() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus list",
                """
                [{"name": "acme", "type": "container", "status": "Running", "state": {}},
                 {"name": "fintech", "type": "container", "status": "Stopped", "state": {}}]
                """)
            .onOk("incus version", "Client version: 6.21\nServer version: 6.21\n");

    var status = json(shell);
    assertEquals(2, status.get("containers_total"));
    assertEquals(1, status.get("containers_running"));
    assertEquals(1, status.get("containers_stopped"));
    assertTrue(text(shell).contains("2 total (1 running, 1 stopped)"));
  }

  @Test
  void aFrozenContainerIsCountedAsFrozen() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus list",
                """
                [{"name": "acme", "type": "container", "status": "Frozen", "state": {}},
                 {"name": "fintech", "type": "container", "status": "Stopped", "state": {}}]
                """)
            .onOk("incus version", "Client version: 6.21\nServer version: 6.21\n");

    assertEquals(1, json(shell).get("containers_frozen"));
    assertTrue(text(shell).contains("2 total (0 running, 1 frozen, 1 stopped)"));
  }

  @Test
  void aDaemonThatDoesNotAnswerStillReportsAndNeverClaimsZeroContainers() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onFail("incus list", "cannot connect to the incus daemon")
            .onFail("incus version", "cannot connect to the incus daemon");

    var status = json(shell);
    assertEquals("unknown", status.get("incus_version"));
    assertEquals(
        "incus list failed: cannot connect to the incus daemon", status.get("containers_error"));
    assertFalse(status.containsKey("containers_total"));
    var text = text(shell);
    assertTrue(text.contains("│  Incus:       unknown "), text);
    assertTrue(
        text.contains("unavailable (incus list failed: cannot connect to the incus daemon)"), text);
    assertFalse(text.contains("0 total"), text);
  }

  @Test
  void aStoppedDaemonsErrorIsCutToFitTheTerminalAndWholeInTheJson() throws Exception {
    var stderr =
        "Error: The incus daemon doesn't appear to be started (socket path:"
            + " /var/lib/incus/unix.socket)\nSee 'incus admin init'.\n";
    var shell =
        new ScriptedShellExecutor().onFail("incus list", stderr).onFail("incus version", stderr);

    assertEquals("incus list failed: " + stderr, json(shell).get("containers_error"));
    var box =
        text(shell)
            .lines()
            .dropWhile(line -> !line.startsWith("  ╭"))
            .takeWhile(line -> !line.isEmpty())
            .toList();
    assertEquals(
        List.of(100), box.stream().map(String::length).distinct().toList(), String.join("\n", box));
    assertEquals(
        List.of(
            "  │  Containers:  unavailable (incus list failed: Error: The incus daemon doesn't"
                + " appear to be sta…│"),
        box.stream().filter(line -> line.contains("Containers:")).toList());
  }

  @Test
  void anIncusAptWouldMoveNamesTheCandidateAndTheCommandThatMovesIt() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "Client version: 6.17\nServer version: 6.17\n")
            .onOk(
                "apt-cache policy incus",
                "incus:\n  Installed: 1:6.17-ubuntu24.04-202510010000\n"
                    + "  Candidate: 1:6.18-ubuntu24.04-202511010000\n")
            .onOk("dpkg --compare-versions");

    var status = json(shell);
    assertEquals("1:6.17-ubuntu24.04-202510010000", status.get("incus_installed"));
    assertEquals("1:6.18-ubuntu24.04-202511010000", status.get("incus_candidate"));
    assertEquals("6.17", status.get("incus_version"));
    assertEquals(
        List.of(
            "  │  Incus:       6.17 (6.18 available)",
            "  │  Upgrade:     sudo sail host incus upgrade"),
        rows(shell, "Incus:", "Upgrade:"));
  }

  @Test
  void aRebuildOfTheSameReleaseIsNamedWholeSoTheRowShowsTheMove() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "6.17\n")
            .onOk(
                "apt-cache policy incus",
                "incus:\n  Installed: 1:6.17-ubuntu24.04-202510010000\n"
                    + "  Candidate: 1:6.17-ubuntu24.04-202510150000\n")
            .onOk("dpkg --compare-versions");

    var status = json(shell);
    assertEquals("1:6.17-ubuntu24.04-202510010000", status.get("incus_installed"));
    assertEquals("1:6.17-ubuntu24.04-202510150000", status.get("incus_candidate"));
    assertEquals(
        List.of(
            "  │  Incus:       1:6.17-ubuntu24.04-202510010000 (1:6.17-ubuntu24.04-202510150000"
                + " available)",
            "  │  Upgrade:     sudo sail host incus upgrade"),
        rows(shell, "Incus:", "Upgrade:"));
  }

  @Test
  void aCandidateOlderThanTheInstalledIncusLeavesTheRowAlone() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "6.19\n")
            .onOk(
                "apt-cache policy incus",
                "incus:\n  Installed: 1:6.19-ubuntu24.04-1\n  Candidate: 1:6.18-ubuntu24.04-1\n")
            .onFail("dpkg --compare-versions", "");

    assertEquals("1:6.18-ubuntu24.04-1", json(shell).get("incus_candidate"));
    assertEquals(List.of("  │  Incus:       6.19"), rows(shell, "Incus:", "Upgrade:"));
  }

  @Test
  void anIncusThatIsCurrentOrUnknownToAptKeepsItsRow() throws Exception {
    var current =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "6.18\n")
            .onOk(
                "apt-cache policy incus",
                "incus:\n  Installed: 1:6.18-ubuntu24.04-1\n  Candidate: 1:6.18-ubuntu24.04-1\n");
    var unknown =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk("incus version", "6.18\n")
            .onOk("apt-cache policy incus", "");

    assertEquals("1:6.18-ubuntu24.04-1", json(current).get("incus_installed"));
    assertEquals("1:6.18-ubuntu24.04-1", json(current).get("incus_candidate"));
    assertTrue(json(unknown).containsKey("incus_candidate"));
    assertNull(json(unknown).get("incus_candidate"));
    for (var shell : List.of(current, unknown)) {
      assertEquals(List.of("  │  Incus:       6.18"), rows(shell, "Incus:", "Upgrade:"));
    }
  }

  @Test
  void everyPoolIsNamedWithItsDriverItsSourceAndItsFreeSpace() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk(
                "incus storage list",
                """
                [{"name": "devpool", "driver": "dir",
                  "config": {"source": "/var/lib/incus/storage-pools/devpool"}},
                 {"name": "tank", "driver": "zfs",
                  "config": {"source": "tank", "volatile.initial_source": "/dev/nvme0n1p5"}}]
                """)
            .onOk(
                "incus query /1.0/storage-pools/devpool/resources",
                "{\"space\": {\"used\": 128849018880, \"total\": 959925207040}}")
            .onFail("incus query /1.0/storage-pools/tank/resources", "not found");

    assertEquals(
        List.of(
            "  │  Pool:        devpool (dir, /var/lib/incus/storage-pools/devpool): 774.0 GiB free"
                + " of 894.0 GiB",
            "  │  Pool:        tank (zfs, /dev/nvme0n1p5): free space unknown",
            "  │  Note:        Disk limits on a dir pool are advisory"),
        rows(shell, "Pool:", "Note:"));
    assertEquals(
        List.of(
            Map.of(
                "name",
                "devpool",
                "driver",
                "dir",
                "source",
                "/var/lib/incus/storage-pools/devpool",
                "used_bytes",
                128849018880L,
                "total_bytes",
                959925207040L),
            Map.of("name", "tank", "driver", "zfs", "source", "/dev/nvme0n1p5")),
        json(shell).get("pools"));
  }

  @Test
  void aZfsBoxHasNoAdvisoryNote() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onOk(
                "incus storage list",
                "[{\"name\": \"tank\", \"driver\": \"zfs\", \"config\":"
                    + " {\"source\": \"/var/lib/incus/disks/tank.img\"}}]")
            .onOk(
                "incus query /1.0/storage-pools/tank/resources",
                "{\"space\": {\"used\": 0, \"total\": 53687091200}}");

    assertEquals(
        List.of(
            "  │  Pool:        tank (zfs, /var/lib/incus/disks/tank.img): 50.0 GiB free of 50.0"
                + " GiB"),
        rows(shell, "Pool:", "Note:"));
  }

  private static final String A = "/dev/disk/by-id/wwn-0x5000c500a1b2c3d4-part5";
  private static final String B = "/dev/disk/by-id/wwn-0x5000c500e5f6a7b8-part5";

  /** A row under a pool's first, as the banner prints it. */
  private static final String CONTINUED = "  │" + " ".repeat(15);

  /** A box whose Incus holds devpool, a mirror of A and B, and whose zpool says {@code status}. */
  private static ScriptedShellExecutor mirror(String status) {
    return new ScriptedShellExecutor()
        .onOk("incus list", "[]")
        .onOk(
            "incus storage list",
            "[{\"name\": \"devpool\", \"driver\": \"zfs\", \"config\": {\"source\": \"devpool\","
                + " \"volatile.initial_source\": \"mirror="
                + A
                + ","
                + B
                + "\", \"zfs.pool_name\": \"devpool\"}}]")
        .onOk(
            "incus query /1.0/storage-pools/devpool/resources",
            "{\"space\": {\"used\": 4294967296, \"total\": 21474836480}}")
        .on(
            "zpool status -P devpool",
            status.startsWith("cannot")
                ? new ShellExec.Result(1, "", status)
                : new ShellExec.Result(0, DeviceFixtures.read(status), ""));
  }

  @Test
  void aMirrorIsSaidAsItsTwoDevicesWithItsHealthAsZfsSaysIt() throws Exception {
    var online = mirror("handwritten-mirror-online.zpool-status.txt");

    var rows = rows(online, "Pool:", "health ");
    assertEquals(2, rows.size(), rows.toString());
    assertTrue(
        rows.getFirst().startsWith("  │  Pool:        devpool (zfs, a mirror of " + A),
        rows.toString());
    assertEquals(CONTINUED + "health ONLINE", rows.getLast());
    var pool = ((List<?>) json(online).get("pools")).getFirst();
    assertEquals(
        Map.of(
            "name",
            "devpool",
            "driver",
            "zfs",
            "source",
            "mirror=" + A + "," + B,
            "used_bytes",
            4294967296L,
            "total_bytes",
            21474836480L,
            "health",
            "ONLINE",
            "unhealthy",
            List.of()),
        pool);
  }

  @Test
  void aDegradedMirrorNamesTheMissingDeviceAndTheReplaceThatMendsIt() throws Exception {
    var degraded = mirror("handwritten-mirror-degraded-at-import.zpool-status.txt");

    var rows = rows(degraded, "health ", " UNAVAIL", "zpool replace");
    assertEquals(
        List.of(CONTINUED + "health DEGRADED", CONTINUED + B + " UNAVAIL"), rows.subList(0, 2));
    assertTrue(
        rows.get(2).startsWith(CONTINUED + "replace " + B + ": sudo zpool replace devpool"),
        rows.toString());
    var pool = (Map<?, ?>) ((List<?>) json(degraded).get("pools")).getFirst();
    assertEquals("DEGRADED", pool.get("health"));
    assertEquals(List.of(Map.of("device", B, "state", "UNAVAIL")), pool.get("unhealthy"));
  }

  @Test
  void aDeviceTakenOfflineNamesTheOnlineThatBringsItBack() throws Exception {
    assertEquals(
        List.of(
            CONTINUED + "health DEGRADED",
            CONTINUED + "/dev/sdc1 OFFLINE",
            CONTINUED + "bring /dev/sdc1 back: sudo zpool online devpool /dev/sdc1"),
        rows(
            mirror("gcp5/e-501-mirror-offline.zpool-status.txt"), "health ", " OFFLINE", "zpool "));
  }

  @Test
  void aFaultedOrUnimportedPoolIsSaidSo() throws Exception {
    assertEquals(
        List.of(
            CONTINUED + "health FAULTED", CONTINUED + A + " UNAVAIL", CONTINUED + B + " UNAVAIL"),
        rows(mirror("handwritten-mirror-faulted.zpool-status.txt"), "health ", " UNAVAIL"));
    assertEquals(
        List.of(CONTINUED + "health not imported"),
        rows(mirror("cannot open 'devpool': no such pool"), "health "));
  }

  @Test
  void aZpoolThatDoesNotAnswerIsSaidSoAndTheStatusGoesOn() throws Exception {
    var hanging =
        new TimingOutShell(mirror("handwritten-mirror-online.zpool-status.txt"), "zpool status");
    var out = new ByteArrayOutputStream();

    HostStatusCommand.report(
        RECORDED_AT_INIT,
        HOST,
        hanging,
        false,
        OperatorCommand.SUDO,
        new PrintStream(out, true, StandardCharsets.UTF_8));

    var text = out.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains(CONTINUED + "health did not answer within 10 s"), text);
    assertTrue(text.contains("Containers:"), text);
  }

  @Test
  void aPoolIsSaidOnTheDevicesZfsListsNowNotTheNamesIncusRecorded() throws Exception {
    var replaced =
        mirror("handwritten-mirror-online.zpool-status.txt")
            .onOk(
                "zpool list -vHP devpool",
                "devpool\t19.5G\t1M\t19.5G\t-\t-\t0%\t0%\t1.00x\tONLINE\t-\n"
                    + "\tmirror-0\t19.5G\t1M\t19.5G\t-\t-\t0%\t0.00%\t-\tONLINE\n"
                    + "\t/dev/sdb1\t20.0G\t-\t-\t-\t-\t-\t-\t-\tONLINE\n"
                    + "\t/dev/sdk1\t20.0G\t-\t-\t-\t-\t-\t-\t-\tONLINE\n")
            .onOk(
                "zpool list -vH devpool",
                "devpool\t19.5G\t1M\t19.5G\t-\t-\t0%\t0%\t1.00x\tONLINE\t-\n"
                    + "\tmirror-0\t19.5G\t1M\t19.5G\t-\t-\t0%\t0.00%\t-\tONLINE\n"
                    + "\tsdb\t20.0G\t-\t-\t-\t-\t-\t-\t-\tONLINE\n"
                    + "\tsdk\t20.0G\t-\t-\t-\t-\t-\t-\t-\tONLINE\n");

    assertEquals(
        List.of(
            "  │  Pool:        devpool (zfs, a mirror of /dev/sdb and /dev/sdk): 16.0 GiB free of"
                + " 20.0 GiB"),
        rows(replaced, "Pool:"));
    var pool = (Map<?, ?>) ((List<?>) json(replaced).get("pools")).getFirst();
    assertEquals(List.of("/dev/sdb", "/dev/sdk"), pool.get("devices"));
    assertEquals("zfs", pool.get("devices_from"));
    assertEquals("mirror=" + A + "," + B, pool.get("source"));
  }

  @Test
  void storageIncusCannotListIsSaidAndNeverReadAsNoPools() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("incus list", "[]")
            .onFail("incus storage list", "cannot connect to the incus daemon");

    var status = json(shell);
    assertEquals(
        "incus storage list failed: cannot connect to the incus daemon",
        status.get("storage_error"));
    assertFalse(status.containsKey("pools"));
    assertEquals(
        List.of(
            "  │  Pool:        unavailable (incus storage list failed: cannot connect to the"
                + " incus daemon)"),
        rows(shell, "Pool:", "Note:"));
  }

  /** The banner's rows labelled any of {@code labels}, the padding before their border dropped. */
  private static List<String> rows(ScriptedShellExecutor shell, String... labels) throws Exception {
    return text(shell)
        .lines()
        .filter(line -> List.of(labels).stream().anyMatch(line::contains))
        .map(line -> line.replaceAll(" *│$", ""))
        .toList();
  }

  private static Map<String, Object> json(ScriptedShellExecutor shell) throws Exception {
    return YamlUtil.parseMap(report(shell, true));
  }

  private static String text(ScriptedShellExecutor shell) throws Exception {
    return report(shell, false);
  }

  private static String report(ScriptedShellExecutor shell, boolean json) throws Exception {
    var out = new ByteArrayOutputStream();
    HostStatusCommand.report(
        RECORDED_AT_INIT,
        HOST,
        shell,
        json,
        OperatorCommand.SUDO,
        new PrintStream(out, true, StandardCharsets.UTF_8));
    return out.toString(StandardCharsets.UTF_8);
  }

  @Test
  void eachDoorIsShownAsTheRunningServiceSaysItListensAndAsItIsSet() {
    var up = ListenConfig.address(ListenConfig.API, "100.64.0.7:7071");
    var says = Map.of(ListenConfig.API, "100.64.0.7:7071");

    assertEquals(
        List.of(
            new HostStatusCommand.Door("api-listen", "listening on 100.64.0.7:7071"),
            new HostStatusCommand.Door(
                "terminal-listen",
                "refused at start: 'terminal-listen' is '0.0.0.0:7072', a wildcard, which would"
                    + " listen on every network the box is on. Name the box's address on the"
                    + " private network, e.g. 100.64.0.7:7071.")),
        HostStatusCommand.doors(
            new ListenConfig("100.64.0.7:7071", "0.0.0.0:7072"),
            setting -> Optional.ofNullable(says.get(setting)),
            up::equals));
    assertEquals(
        List.of(
            new HostStatusCommand.Door(
                "api-listen", "listening on 100.64.0.7:7071; set to none, restart to apply")),
        HostStatusCommand.doors(
            ListenConfig.none(), setting -> Optional.ofNullable(says.get(setting)), up::equals),
        "a door still listening after its setting was closed is shown until the restart");
    assertEquals(
        List.of(new HostStatusCommand.Door("api-listen", "not listening (set to 100.64.0.7:7071)")),
        HostStatusCommand.doors(
            new ListenConfig("100.64.0.7:7071", null),
            setting -> Optional.ofNullable(says.get(setting)),
            address -> false),
        "a service that said it listens but no longer answers is not listening");
    assertEquals(
        List.of(),
        HostStatusCommand.doors(ListenConfig.none(), setting -> Optional.empty(), address -> true));
  }
}
