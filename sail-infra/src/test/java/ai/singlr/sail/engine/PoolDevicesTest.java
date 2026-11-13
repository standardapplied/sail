/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Every layout here is one a real lsblk printed (this devbox; a GCP VM's two spare disks taken
 * through blank, partitioned, RAID1 with swap and a mount, stopped, recreated and udev-reassembled
 * states), or the one hand-written layout of spare disks no machine at hand had ({@code
 * devices/README.txt}). Probe answers are the GCP VM's captured {@code wipefs} listings and busy
 * opens; where a hand-written disk needs one, it is a captured listing.
 */
class PoolDevicesTest {

  private static final String LSBLK = DeviceFixtures.LSBLK;
  private static final String DEVBOX = "devbox-raid1-nvme.lsblk.json";
  private static final String BLANK = "blank-disks";
  private static final String PARTITIONED = "gpt-3-raid-partitions-no-arrays";
  private static final String OWNER = "raid1-md0-swap-active-md1-ext4-mounted-md2-empty";
  private static final String STOPPED = "raid-members-arrays-stopped";
  private static final String SPARES = DeviceFixtures.SPARES;
  private static final String PROOF = "handwritten-gcp-proof-layouts.lsblk.json";
  private static final String A_INITIAL = "gcp2/a-layout-initial.lsblk.json";
  private static final String BTRFS_ROOT = "gcp2/e-root-on-btrfs-raid1-pivot.lsblk.json";
  private static final String ZFS_ROOT = "gcp2/e-root-on-zfs-mirror-pivot.lsblk.json";
  private static final String TYPES = "handwritten-review3-layouts.lsblk.json";
  private static final String PROBES = "handwritten-review4-layouts.lsblk.json";
  private static final String HOLDERS = "gcp4/a-holders.lsblk.json";
  private static final String OVERLAPS = "gcp4/a-overlap-and-past-end.lsblk.json";
  private static final String LISTED = "DEVICE OFFSET TYPE UUID LABEL\n";
  private static final String ACCEPTED =
      ", not Linux filesystem data (0fc63daf-8483-4772-8e79-3d69d8477de4, 0x83) or ZFS"
          + " (6a898cc3-1dd2-11b2-99a6-080020736631, 0xbf)";
  private static final List<String> RAID_LAYOUTS =
      List.of(
          OWNER,
          "raid1-md0-swap-active-md1-ext4-unmounted-md2-empty",
          "raid1-md0-swap-inactive-md1-ext4-unmounted-md2-empty",
          STOPPED,
          "raid1-arrays-recreated-stale-swap-ext4-from-earlier-array",
          "raid1-degraded-auto-assembled-by-udev-md125-127");
  private static final List<String> SYSTEM_DISK =
      List.of("/dev/sda", "/dev/sda1", "/dev/sda14", "/dev/sda15", "/dev/sda16");
  private static final List<String> SPARE_DISKS =
      List.of(
          "/dev/sdb",
          "/dev/sdb1",
          "/dev/sdb2",
          "/dev/sdb3",
          "/dev/sdc",
          "/dev/sdc1",
          "/dev/sdc2",
          "/dev/sdc3");
  private static final List<String> DEVBOX_DEVICES =
      List.of(
          "/dev/nvme0n1",
          "/dev/nvme0n1p1",
          "/dev/nvme0n1p2",
          "/dev/nvme0n1p3",
          "/dev/nvme0n1p4",
          "/dev/nvme1n1",
          "/dev/nvme1n1p1",
          "/dev/nvme1n1p2",
          "/dev/nvme1n1p3",
          "/dev/nvme1n1p4");
  private static final String EXT4 = DeviceFixtures.listings(OWNER).get("/dev/md1");
  private static final String RAID_MEMBER = DeviceFixtures.listings(OWNER).get("/dev/sdb1");

  @Test
  void theExclusiveOpenFiltersEveryType() {
    assertEquals(
        List.of("wipefs", "--all", "--no-act", "--types", "sail_exclusive_probe", "--", "/dev/sdb"),
        PoolDevices.exclusiveOpen("/dev/sdb"));
  }

  @Test
  void everyDeviceOfThisDevboxIsRefusedBeforeAnyProbe() throws Exception {
    for (var device : DEVBOX_DEVICES) {
      var shell = new Box(DEVBOX).name(device, device).shell();
      var refused =
          assertThrows(IllegalArgumentException.class, () -> devices(shell).cleared(device));
      assertTrue(refused.getMessage().startsWith(device + " is refused: "), refused.getMessage());
      assertEquals(List.of("realpath -e -- " + device, LSBLK), shell.invocations().subList(0, 2));
      assertTrue(
          shell.invocations().stream().noneMatch(c -> c.startsWith("wipefs")),
          "refused on what lsblk shows, before a probe: " + shell.invocations());
    }
  }

  @Test
  void theDevboxRefusalsNameWhatHoldsEachDevice() {
    var untyped =
        " has no partition type lsblk reads: run sudo udevadm settle, then run this again";
    var disk = refusal(DEVBOX, "/dev/nvme0n1");
    for (var reason :
        List.of(
            "/dev/md0 (raid1) is built on it",
            "/dev/md2 is mounted at /;",
            "/dev/md2 (raid1) is built on it")) {
      assertTrue(disk.contains(reason), disk);
    }
    assertEquals(
        "/dev/nvme1n1p1 is refused: /dev/nvme1n1p1" + untyped + ".",
        refusal(DEVBOX, "/dev/nvme1n1p1"));
    assertEquals(
        "/dev/md0 is refused: it is an md array (raid1), not a disk or a partition.",
        refusal(DEVBOX, "/dev/md0"));
  }

  @Test
  void theSystemDiskOfEveryCapturedLayoutIsRefused() {
    for (var layout : gcpLayouts()) {
      for (var device : SYSTEM_DISK) {
        assertTrue(
            refusal(gcp(layout), device).startsWith(device + " is refused: "),
            layout + " " + device);
      }
    }
    assertEquals(
        "/dev/sda14 is refused: /dev/sda14 has partition type"
            + " 21686148-6449-6e6f-744e-656564454649"
            + ACCEPTED
            + ".",
        refusal(gcp(BLANK), "/dev/sda14"));
    assertEquals(
        "/dev/sda (/dev/disk/by-id/google-persistent-disk-0) is refused: /dev/sda1 is mounted at /;"
            + " /dev/sda15 is mounted at /boot/efi; /dev/sda16 is mounted at /boot.",
        refusal(
            gcp(BLANK).name("/dev/disk/by-id/google-persistent-disk-0", "/dev/sda"),
            "/dev/disk/by-id/google-persistent-disk-0"));
  }

  @Test
  void everyDeviceOfEveryRaidLayoutIsRefused() {
    for (var layout : RAID_LAYOUTS) {
      for (var device : SPARE_DISKS) {
        assertTrue(
            refusal(gcp(layout), device).startsWith(device + " is refused: "),
            layout + " " + device);
      }
    }
  }

  @Test
  void theOwnerLayoutsRefusalsNameTheArraysTheSwapAndTheMount() {
    var disk = refusal(gcp(OWNER), "/dev/sdb");
    for (var reason :
        List.of(
            "/dev/sdb1 is a RAID member: stop its array and clear it with mdadm --zero-superblock"
                + " /dev/sdb1 yourself",
            "/dev/md0 is in use as swap",
            "/dev/md1 is mounted at /mnt/sailtrial-boot",
            "/dev/md2 (raid1) is built on it")) {
      assertTrue(disk.contains(reason), disk);
    }
    assertFalse(disk.contains("wipefs"), disk);
  }

  @Test
  void aRaidMemberUdevDoesNotKnowIsRefusedOnWhatItsListingShows() {
    assertEquals(
        "/dev/sda4 is refused: /dev/sda4 is a RAID member: stop its array and clear it with mdadm"
            + " --zero-superblock /dev/sda4 yourself.",
        refusal(names(new Box(PROBES)).holds("/dev/sda4", RAID_MEMBER), "/dev/sda4"));
  }

  @Test
  void flatListsFromLsblkAreUnreadableAndRefused() {
    var flats =
        new ArrayList<>(
            List.of(
                "devbox-flat.lsblk.json",
                "github-runner-flat.lsblk.json",
                "handwritten-flat-whole-disk-raid.lsblk.json"));
    gcpLayouts().forEach(layout -> flats.add("gcp/" + layout + ".lsblk-flat.json"));
    for (var flat : flats) {
      var shell = new Box(flat).name("/dev/sdb", "/dev/sdb").shell();

      var cleared = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb"));
      var offered = assertThrows(IOException.class, () -> devices(shell).candidates());

      assertEquals(
          "lsblk printed a flat list, not a device tree, so what is built on a device cannot be"
              + " read; nothing was written.",
          cleared.getMessage(),
          flat);
      assertEquals(cleared.getMessage(), offered.getMessage());
    }
  }

  @Test
  void anLsblkThatFailsNamesTheUtilLinuxItNeeds() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("realpath", "/dev/sdb\n")
            .onFail(LSBLK, "lsblk: unknown column: MOUNTPOINTS");

    var failed = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb"));

    assertEquals(
        "lsblk failed: lsblk: unknown column: MOUNTPOINTS. Sail reads devices with util-linux 2.37"
            + " or newer (lsblk --json --tree with MOUNTPOINTS); nothing was written.",
        failed.getMessage());
  }

  @Test
  void aBlankDiskIsClearedAfterEveryProbe() throws Exception {
    for (var layout : List.of(BLANK, "after-teardown-blank-again")) {
      var shell = gcp(layout).shell();

      assertEquals(
          new PoolSource.Device("/dev/sdb", "42949672960"), devices(shell).cleared("/dev/sdb"));
      assertEquals(
          List.of(
              "realpath -e -- /dev/sdb",
              LSBLK,
              "bash -c " + PoolDevices.OPEN_FILES,
              "wipefs -- /dev/sdb",
              "which zpool",
              String.join(" ", PoolDevices.exclusiveOpen("/dev/sdb"))),
          shell.invocations());
    }
  }

  @Test
  void aProcessHoldingTheWholeDiskRefusesThePartitionNamed() throws Exception {
    for (var partition : List.of("/dev/sdg6", "/dev/sdg9")) {
      assertEquals(
          partition + " is refused: process 23334 (python3) has /dev/sdg open.",
          refusal(names(new Box(HOLDERS)).open(23334, "python3", "/dev/sdg"), partition));
    }
    assertEquals("/dev/sdg6", devices(names(new Box(HOLDERS)).shell()).cleared("/dev/sdg6").path());
  }

  @Test
  void theWalkNamesAHolderBeforeAnyProbeAndADisksPartitionsAreProbedBeforeIt() throws Exception {
    var vanished =
        "wipefs: error: /dev/sdg1: probing initialization failed: No such file or directory";
    assertEquals(
        "/dev/sdg is refused: process 23334 (python3) has /dev/sdg open.",
        refusal(
            names(new Box(HOLDERS)).open(23334, "python3", "/dev/sdg").busy("/dev/sdg1", vanished),
            "/dev/sdg"));

    var shell = names(new Box(HOLDERS)).shell();
    assertThrows(IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdg"));
    var probes =
        shell.invocations().stream()
            .filter(c -> c.startsWith("wipefs --all") || c.startsWith("udevadm"))
            .toList();
    var partitions =
        IntStream.rangeClosed(1, 10)
            .mapToObj(n -> String.join(" ", PoolDevices.exclusiveOpen("/dev/sdg" + n)))
            .toList();
    var expected = new ArrayList<>(partitions);
    expected.add(String.join(" ", PoolDevices.exclusiveOpen("/dev/sdg")));
    expected.add("udevadm settle --timeout=10");
    assertEquals(expected, probes);
    assertTrue(
        shell.invocations().indexOf("bash -c " + PoolDevices.OPEN_FILES)
            < shell.invocations().indexOf("wipefs -- /dev/sdg"),
        shell.invocations().toString());
  }

  @Test
  void aWholeDiskThatVanishesWhileProbedCannotBeJudged() {
    var failed =
        assertThrows(
            IOException.class,
            () ->
                devices(
                        gcp(BLANK)
                            .busy(
                                "/dev/sdb",
                                "wipefs: error: /dev/sdb: probing initialization failed: No such"
                                    + " file or directory")
                            .shell())
                    .cleared("/dev/sdb"));

    assertEquals(
        "Cannot tell whether /dev/sdb is in use: wipefs: error: /dev/sdb: probing initialization"
            + " failed: No such file or directory",
        failed.getMessage());
  }

  @Test
  void aPartitionThatVanishesWhileProbedOrAnUnsettledTableSaysTheTableIsChanging() {
    assertEquals(
        "/dev/sdg is refused: /dev/sdg1 vanished while Sail probed it, so the partition table of"
            + " /dev/sdg is changing: run this again.",
        refusal(
            names(new Box(HOLDERS))
                .busy(
                    "/dev/sdg1",
                    "wipefs: error: /dev/sdg1: probing initialization failed: No such file or"
                        + " directory"),
            "/dev/sdg"));
    assertEquals(
        "/dev/sdg is refused: udev did not settle within 10 s after Sail probed /dev/sdg, so its"
            + " partition table is changing: run this again.",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    devices(
                            names(new Box(HOLDERS))
                                .shell(
                                    new ScriptedShellExecutor()
                                        .onFail("udevadm settle", "timeout")))
                        .cleared("/dev/sdg"))
            .getMessage());
  }

  @Test
  void aPartitionedDiskIsRefusedNamingItsTableItsPartitionsAndTheCommandThatErasesIt() {
    assertEquals(
        "/dev/sdb holds gpt, partition /dev/sdb1. Sail erases nothing: if nothing on it is wanted,"
            + " erase it yourself with sudo wipefs --all /dev/sdb, then run this again.",
        refusal(PROBES, "/dev/sdb"));
  }

  @Test
  void aBlankPartitionOfAnotherTypeIsRefusedNamingItsType() {
    assertEquals(
        "/dev/sdb1 is refused: /dev/sdb1 has partition type a19d880f-05fc-4d3b-a006-743f0f84911e"
            + ACCEPTED
            + ".",
        refusal(gcp(PARTITIONED), "/dev/sdb1"));
  }

  @Test
  void whatSitsOnARefusedDeviceIsRefusedAndNoZfsVolumeIsOffered() throws Exception {
    assertEquals(
        "/dev/md2p1 is refused: it is a partition of /dev/md2 (raid1), not of a disk.",
        refusal("gcp2/a-layout-md-array-partitioned.lsblk.json", "/dev/md2p1"));
    assertEquals(
        "/dev/md2p1 is refused: it is a partition of /dev/md2 (raid1), not of a disk; it holds"
            + " ext4.",
        refusal("gcp2/a-layout-loop-partitioned.lsblk.json", "/dev/md2p1"));
    assertEquals(
        "/dev/loop3p1 is refused: it is a partition of /dev/loop3 (loop), not of a disk.",
        refusal("gcp2/a-layout-loop-partitioned.lsblk.json", "/dev/loop3p1"));
    assertEquals(
        "/dev/zd0 is refused: it is a ZFS volume.",
        refusal("gcp2/a-layout-zvol.lsblk.json", "/dev/zd0"));
    assertEquals(
        "/dev/zd0p1 is refused: it is a ZFS volume.",
        refusal("gcp2/a-layout-zvol-partitioned.lsblk.json", "/dev/zd0p1"));
    assertTrue(
        devices(names(new Box("gcp2/a-layout-zvol.lsblk.json")).shell()).candidates().stream()
            .noneMatch(candidate -> candidate.device().equals("/dev/zd0")));
  }

  @Test
  void aPartitionBesideADeviceOfAnImportedPoolIsRefused() {
    var listed =
        fixture("raw-file-pool.zpool-list.txt")
            .replace("/tmp/sail-raw.img", "/dev/sdd1")
            .replace("sail-raw", "handpool");
    assertEquals(
        "/dev/sdd2 is refused: /dev/sdd1, on the same disk, is in the"
            + " imported zpool handpool, and no pool's device is taken: export or destroy the"
            + " pool yourself first.",
        refusal(names(new Box(PROOF)).zpool("handpool", listed, "/dev/sdd1"), "/dev/sdd2"));
  }

  @Test
  void aMapperPathIsRefusedAsTheDeviceItNames() {
    assertEquals(
        "/dev/mapper/proofluks is refused: it is an open LUKS volume, not a disk or a partition.",
        refusal(
            names(new Box(A_INITIAL)).name("/dev/mapper/proofluks", "/dev/dm-0"),
            "/dev/mapper/proofluks"));
    assertEquals(
        "/dev/mapper/proofvg-prooflv (/dev/proofvg/prooflv) is refused: it is an LVM logical"
            + " volume, not a disk or a partition.",
        refusal(
            names(new Box(A_INITIAL)).name("/dev/proofvg/prooflv", "/dev/dm-1"),
            "/dev/proofvg/prooflv"));
  }

  @Test
  void aNameNoDeviceHasIsNotMatchedToADeviceListedWithoutItsKernelName() {
    assertEquals(
        "/dev/null is not a block device lsblk lists.",
        refusal(gcp(BLANK).name("/dev/null", "/dev/null"), "/dev/null"));
  }

  @Test
  void anArrayIsRefusedAsAnArrayNamingWhatItHolds() {
    assertEquals(
        "/dev/md1 is refused: it is an md array (raid1), not a disk or a partition; it holds ext4.",
        refusal("gcp2/a-layout-md-unmounted-everywhere.lsblk.json", "/dev/md1"));
  }

  @Test
  void aSparePartitionBesideLuksAndLvmIsCleared() throws Exception {
    assertEquals(
        "/dev/sde4", devices(names(new Box(A_INITIAL)).shell()).cleared("/dev/sde4").path());
  }

  @Test
  void devicesThatAreNotSpareDisksAreRefused() {
    assertEquals("/dev/sde is refused: /dev/sde is removable.", refusal(spares(), "/dev/sde"));
    assertEquals("/dev/zram0 is refused: it is a zram device.", refusal(spares(), "/dev/zram0"));
    assertEquals(
        "/dev/nbd0 is refused: it has no size; it is an nbd device.",
        refusal(spares(), "/dev/nbd0"));
    assertEquals(
        "/dev/sdf is refused: /dev/sdf is an LVM physical volume: move its"
            + " volume group off it and clear it with pvremove /dev/sdf yourself; /dev/mapper/vg-data"
            + " is mounted at /srv; /dev/mapper/vg-data (lvm) is built on it.",
        refusal(spares(), "/dev/sdf"));
    assertEquals(
        "/dev/sdg is refused: /dev/sdg holds a LUKS volume: close it and"
            + " erase it with cryptsetup erase /dev/sdg yourself; /dev/mapper/secret (crypt) is built"
            + " on it.",
        refusal(spares(), "/dev/sdg"));
    assertEquals(
        "/dev/loop0 is refused: it is a loop device, not a disk or a"
            + " partition; /dev/loop0 is mounted at /snap/core24/2124.",
        refusal(gcp(BLANK), "/dev/loop0"));
  }

  @Test
  void anLvmOrLuksMemberUdevDoesNotKnowIsRefusedOnWhatItsListingShows() {
    var members =
        Map.of(
            "LVM2_member",
            "/dev/sdh is refused: /dev/sdh is an LVM physical volume: move"
                + " its volume group off it and clear it with pvremove /dev/sdh yourself.",
            "crypto_LUKS",
            "/dev/sdh is refused: /dev/sdh holds a LUKS volume: close it and"
                + " erase it with cryptsetup erase /dev/sdh yourself.");
    members.forEach(
        (type, message) ->
            assertEquals(
                message,
                refusal(
                    spares().holds("/dev/sdh", RAID_MEMBER.replace("linux_raid_member", type)),
                    "/dev/sdh")));
  }

  @Test
  void aDeviceHeldExclusivelyByAHolderTheWalkCannotFindSaysSo() {
    assertEquals(
        "/dev/sdk is refused: /dev/sdk is held exclusively by something Sail cannot name: a mount"
            + " in another namespace, an array, a pool, or a process that opened it exclusively.",
        refusal(spares().busy("/dev/sdk"), "/dev/sdk"));
    assertEquals(
        "/dev/sdk is refused: process 31 (qemu-system-x86) has /dev/sdk open.",
        refusal(spares().busy("/dev/sdk").open(31, "qemu-system-x86", "/dev/sdk"), "/dev/sdk"));
  }

  @Test
  void anExclusiveOpenThatFailsOtherwiseRefusesTheDevice() {
    var shell =
        gcp(BLANK)
            .shell(
                new ScriptedShellExecutor()
                    .onFail(
                        "wipefs --all",
                        "wipefs: error: /dev/sdb: probing initialization failed: Permission denied"));

    var failed = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb"));

    assertEquals(
        "Cannot tell whether /dev/sdb is in use: wipefs: error: /dev/sdb: probing initialization"
            + " failed: Permission denied",
        failed.getMessage());
  }

  @Test
  void aDeviceInAnImportedPoolIsRefusedWhateverThePoolHolds() {
    assertEquals(
        "/dev/sdi1 is refused: it is in the imported zpool sail-raw, and no pool's device is"
            + " taken: export or destroy the pool yourself first.",
        refusal(imported(), "/dev/sdi1"));
  }

  @Test
  void aPoolWithFilesInItsTopDatasetIsRefusedAndNeverHandedOver() {
    var shell = imported().shell();

    assertThrows(IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdi1"));
    assertTrue(
        shell.invocations().stream().noneMatch(c -> c.startsWith("zfs ")),
        "what a pool holds is not read: any pool's device is refused");
  }

  @Test
  void aDeviceInTwoImportedPoolsNamesBoth() {
    var raw = fixture("raw-file-pool.zpool-list.txt");
    var box =
        spares()
            .zpool("sail-raw", raw.replace("/tmp/sail-raw.img", "/dev/sdi1"), "/dev/sdi1")
            .zpool(
                "other",
                raw.replace("sail-raw", "other").replace("/tmp/sail-raw.img", "/dev/sdi1"),
                "/dev/sdi1");

    assertEquals(
        "/dev/sdi1 is refused: it is in the imported zpool sail-raw, and no"
            + " pool's device is taken: export or destroy the pool yourself first; it is in the"
            + " imported zpool other, and no pool's device is taken: export or destroy the pool"
            + " yourself first.",
        refusal(box, "/dev/sdi1"));
  }

  @Test
  void aPoolIncusHoldsIsNamedAsIncuss() {
    var box =
        imported()
            .incus(
                "[{\"name\": \"tank\", \"driver\": \"zfs\", \"config\": {\"source\": \"sail-raw\","
                    + " \"zfs.pool_name\": \"sail-raw\"}}]");

    assertEquals(
        "/dev/sdi1 is refused: it is in zpool sail-raw, Incus's pool 'tank'.",
        refusal(box, "/dev/sdi1"));
  }

  @Test
  void anExportedPoolsDeviceIsRefusedNamingThePoolAndNoErase() {
    var refused =
        refusal(
            names(new Box(PROBES))
                .holds("/dev/sda5", LISTED + "sda5 0x3f000 zfs_member 1234567890 oldtank\n")
                .importable("oldtank"),
            "/dev/sda5");

    assertEquals(
        "/dev/sda5 is refused: /dev/sda5 is a device of zpool oldtank, which is not imported:"
            + " import it with sudo zpool import oldtank, or, if nothing on it is wanted, clear it"
            + " yourself with sudo zpool labelclear -f /dev/sda5.",
        refused);
    assertFalse(refused.contains("wipefs"), refused);
    assertEquals(
        "/dev/sdb is refused: /dev/sdb1 is a device of zpool exported, which is not imported:"
            + " import it with sudo zpool import exported, or, if nothing on it is wanted, clear it"
            + " yourself with sudo zpool labelclear -f /dev/sdb1.",
        refusal(
            names(new Box(PROBES))
                .holds("/dev/sdb1", LISTED + "sdb1 0x3f000 zfs_member 1234567890 exported\n")
                .importable("exported"),
            "/dev/sdb"));
  }

  @Test
  void aPoolMemberOnlyLsblkShowsIsRefusedWithNoErase() {
    assertEquals(
        "/dev/sdd1 is refused: /dev/sdd1 is a device of a zpool, which is not imported: import it"
            + " with sudo zpool import, or, if nothing on it is wanted, clear it yourself with sudo"
            + " zpool labelclear -f /dev/sdd1.",
        refusal("gcp2/a-layout-final.lsblk.json", "/dev/sdd1"));
  }

  @Test
  void aMemberOfAnythingIsRefusedWithNoErase() {
    for (var listing :
        List.of(
            "sda4 0x1fffffe00 isw_raid_member\n",
            "sda4 0xfffffe00 ddf_raid_member\n",
            "sda4 0x218 LVM1_member\n",
            "sda4 0x1018 bcache abc\n",
            "sda4 0x0 ceph_bluestore\n")) {
      var type = listing.split(" ")[2].strip();
      var refused =
          refusal(names(new Box(PROBES)).holds("/dev/sda4", LISTED + listing), "/dev/sda4");

      assertEquals(
          "/dev/sda4 is refused: /dev/sda4 carries "
              + type
              + " metadata: free it with the tool that made it, then run this again.",
          refused);
      assertFalse(refused.contains("wipefs"), refused);
    }
  }

  @Test
  void onlyLinuxDataAndZfsPartitionTypesAreTaken() throws Exception {
    Map.of(
            "/dev/sda2", "e3c9e316-0b5c-4db8-817d-f92df00215ae",
            "/dev/sda3", "9e1a2d38-c612-4316-aa26-8b49521e5a8b",
            "/dev/sda6", "0657fd6d-a4ab-43c4-84e5-0933c84b4f4f")
        .forEach(
            (partition, type) ->
                assertEquals(
                    partition
                        + " is refused: "
                        + partition
                        + " has partition type "
                        + type
                        + ACCEPTED
                        + ".",
                    refusal(PROBES, partition)));
    for (var partition : List.of("/dev/sdc1", "/dev/sdd1", "/dev/sdd2")) {
      assertEquals(partition, devices(names(new Box(PROBES)).shell()).cleared(partition).path());
    }
  }

  @Test
  void aDiskIsRefusedWhileAProcessHasOneOfItsPartitionsOpen() {
    assertEquals(
        "/dev/sdb is refused: process 5 (qemu-system-x86) has /dev/sdb1 open.",
        refusal(names(new Box(PROBES)).open(5, "qemu-system-x86", "/dev/sdb1"), "/dev/sdb"));
  }

  @Test
  void aWalkOfTheOpenFilesThatFailsRefusesTheDevice() {
    var shell =
        gcp(BLANK).shell(new ScriptedShellExecutor().onFail("for d in /proc", "bash: no /proc"));

    var failed = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb"));

    assertEquals(
        "Cannot read which processes have devices open: bash: no /proc", failed.getMessage());
  }

  @Test
  void aDeviceAProcessHasOpenIsRefusedNamingIt() {
    assertEquals(
        "/dev/sdk is refused: process 4242 (qemu-system-x86) has /dev/sdk open.",
        refusal(spares().open(4242, "qemu-system-x86", "/dev/sdk"), "/dev/sdk"));
    assertEquals(
        "/dev/sdh is refused: process 99 (dd) has /dev/sdh open.",
        refusal(spares().open(99, "dd", "/dev/sdh"), "/dev/sdh"));
    assertEquals(
        "/dev/sdk is refused: Sail cannot read the files process 77 has open, so whether it holds"
            + " /dev/sdk cannot be told: run this as root.",
        refusal(spares().unreadable(77), "/dev/sdk"));
  }

  @Test
  void aCacheDeviceIsAMemberOfItsPool() {
    var listed =
        fixture("classes.zpool-list.txt")
            .replace("sail-it-classes", "tank")
            .replace("/dev/loop0", "/dev/sdb1")
            .replace("/dev/loop2", "/dev/sdh");

    assertEquals(
        "/dev/sdh is refused: it is in the imported zpool tank, and no"
            + " pool's device is taken: export or destroy the pool yourself first.",
        refusal(
            spares()
                .zpool("tank", listed, "/dev/sdb1\n/dev/loop4\n/dev/loop1\n/dev/sdh\n/dev/loop3"),
            "/dev/sdh"));
  }

  @Test
  void importedPoolsThatCannotBeListedRefuseTheDevice() {
    var shell =
        gcp(BLANK)
            .shell(
                new ScriptedShellExecutor()
                    .onOk("which zpool")
                    .onFail("zpool list", "no permission"));

    var failed = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb"));

    assertEquals("Cannot list the imported pools: no permission", failed.getMessage());
  }

  @Test
  void bootPartitionsAreRefusedMountedOrNotOnAnyDisk() {
    var reasons =
        Map.of(
            "/dev/sdh1", "c12a7328-f81f-11d2-ba4b-00a0c93ec93b" + ACCEPTED + "; it holds vfat",
            "/dev/sdh2", "21686148-6449-6e6f-744e-656564454649" + ACCEPTED,
            "/dev/sdh4", "bc13c2ff-59e6-4262-a352-b275fd6f7172" + ACCEPTED + "; it holds vfat",
            "/dev/sdi1", "0xef" + ACCEPTED + "; it holds vfat");
    reasons.forEach(
        (device, reason) ->
            assertEquals(
                device + " is refused: " + device + " has partition type " + reason + ".",
                refusal(A_INITIAL, device)));
    assertEquals(
        "/dev/sdb1 is refused: /dev/sdb1 has partition type c12a7328-f81f-11d2-ba4b-00a0c93ec93b"
            + ACCEPTED
            + "; it holds vfat.",
        refusal(BTRFS_ROOT, "/dev/sdb1"));
    assertEquals(
        "/dev/sdb9 is refused: /dev/sdb9 has partition type 6a945a3b-1dd2-11b2-99a6-080020736631"
            + ACCEPTED
            + ".",
        refusal("gcp3/f-layout-after-devpool.lsblk.json", "/dev/sdb9"));
  }

  @Test
  void theSecondDeviceOfABtrfsRootIsRefusedNamingTheBtrfsItHolds() {
    assertEquals(
        "/dev/sdd2 is refused: /dev/sdd2 holds btrfs and is held exclusively by something Sail"
            + " cannot name: a mount in another namespace, an array, a pool, or a process that"
            + " opened it exclusively.",
        refusal(names(new Box(BTRFS_ROOT)).busy("/dev/sdd2"), "/dev/sdd2"));
  }

  @Test
  void aBlankSpareDiskIsOfferedAndTaken() throws Exception {
    for (var root : List.of(BTRFS_ROOT, ZFS_ROOT)) {
      assertEquals(
          List.of("/dev/sdc"),
          devices(new Box(root).shell()).candidates().stream()
              .map(PoolDevices.Candidate::device)
              .toList());
      assertEquals("/dev/sdc", devices(names(new Box(root)).shell()).cleared("/dev/sdc").path());
    }
  }

  @Test
  void aDeviceMapperDeviceWithNoParentIsNotAFlatList() throws Exception {
    var shell = names(new Box("handwritten-gcp-blank-with-dm-zero.lsblk.json")).shell();

    assertEquals("/dev/sdb", devices(shell).cleared("/dev/sdb").path());
  }

  @Test
  void twoSpellingsOfOneDeviceAreOneDevice() throws Exception {
    var byId = "/dev/disk/by-id/google-spare";
    var shell = gcp(BLANK).name(byId, "/dev/sdb").shell();

    assertEquals(new PoolSource.Device("/dev/sdb", "42949672960"), devices(shell).cleared(byId));
  }

  @Test
  void namesThatResolveToNothingAreRefused() {
    var unnamed =
        new ScriptedShellExecutor().onFail("realpath", "realpath: /dev/sdz: No such file");

    assertEquals(
        "--disk /dev/sdz names no device on this box: realpath: /dev/sdz: No such file.",
        assertThrows(IllegalArgumentException.class, () -> devices(unnamed).cleared("/dev/sdz"))
            .getMessage());
  }

  @Test
  void aFileIsNotABlockDeviceLsblkLists() {
    assertEquals(
        "/srv/pool.img is not a block device lsblk lists.",
        refusal(gcp(BLANK).name("/srv/pool.img", "/srv/pool.img"), "/srv/pool.img"));
  }

  @Test
  void signaturesThatCannotBeReadRefuseTheDevice() {
    var shell =
        gcp(BLANK)
            .shell(
                new ScriptedShellExecutor()
                    .onFail(
                        "wipefs -- ", "wipefs: error: /dev/sdb: probing initialization failed"));

    var failed = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb"));

    assertEquals(
        "Cannot read the signatures on /dev/sdb: wipefs: error: /dev/sdb: probing initialization"
            + " failed",
        failed.getMessage());
  }

  @Test
  void onlySpareWholeDisksAreOffered() throws Exception {
    assertEquals(
        List.of(
            new PoolDevices.Candidate("/dev/sdb", "40.0 GiB", "PersistentDisk"),
            new PoolDevices.Candidate("/dev/sdc", "40.0 GiB", "PersistentDisk")),
        devices(gcp(BLANK).shell()).candidates());
    assertEquals(
        List.of("/dev/sdb", "/dev/sdc", "/dev/sdh", "/dev/sdk"),
        devices(spares().shell()).candidates().stream()
            .map(PoolDevices.Candidate::device)
            .toList());
    assertEquals(List.of(), devices(new Box(DEVBOX).shell()).candidates());
    for (var layout : RAID_LAYOUTS) {
      assertEquals(List.of(), devices(gcp(layout).shell()).candidates(), layout);
    }
  }

  @Test
  void twoBlankDisksAreAMirrorOfTwoDevicesEachClearedAsOneIs() throws Exception {
    var shell = gcp(BLANK).shell();

    var mirror = devices(shell).mirror("/dev/sdb", "/dev/sdc");

    assertEquals(
        new PoolSource.Mirror(
            devices(gcp(BLANK).shell()).cleared("/dev/sdb"),
            devices(gcp(BLANK).shell()).cleared("/dev/sdc"),
            42949672960L,
            42949672960L),
        mirror);
    assertEquals("zfs on a mirror of /dev/sdb and /dev/sdc", mirror.said());
    assertEquals(
        List.of(
            "realpath -e -- /dev/sdb",
            "realpath -e -- /dev/sdc",
            LSBLK,
            "bash -c " + PoolDevices.OPEN_FILES,
            "wipefs -- /dev/sdb",
            "which zpool",
            "wipefs -- /dev/sdc",
            "which zpool",
            String.join(" ", PoolDevices.exclusiveOpen("/dev/sdb")),
            String.join(" ", PoolDevices.exclusiveOpen("/dev/sdc"))),
        shell.invocations(),
        "lsblk and the walk are read once, every slow read of both comes before either device's"
            + " probe, and the probes of both are the last commands");
  }

  @Test
  void blankPartitionsBesideARaid1OnTwoDisksAreAMirrorAndNothingIsWritten() throws Exception {
    var shell = names(new Box("gcp4/b-local-nvme-ssd.lsblk.json")).shell();

    var mirror = devices(shell).mirror("/dev/sdb2", "/dev/sdc2");

    assertEquals(
        List.of("/dev/sdb2", "/dev/sdc2"),
        mirror.devices().stream().map(PoolSource.Device::path).toList());
    assertEquals("zfs on a mirror of /dev/sdb2 and /dev/sdc2", mirror.said());
    assertEquals(
        List.of(),
        shell.arguments().stream()
            .filter(argv -> argv.getFirst().equals("wipefs"))
            .filter(argv -> argv.contains("--all") && !argv.contains("--no-act"))
            .toList(),
        "every probe of either device only reads");
  }

  @Test
  void theOwnersTwoRaid1DisksOfferNoDeviceToAMirror() {
    var refused =
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    devices(names(new Box(DEVBOX)).shell())
                        .mirror("/dev/nvme0n1p4", "/dev/nvme1n1p4"))
            .getMessage();

    assertTrue(refused.startsWith("/dev/nvme0n1p4 is refused: "), refused);
    assertTrue(refused.contains("/dev/md2 is mounted at /;"), refused);
  }

  @Test
  void twoPartitionsOfOneDiskAreRefusedAsAMirrorBeforeAnyProbe() {
    var shell = names(new Box(HOLDERS)).shell();

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).mirror("/dev/sdg1", "/dev/sdg2"));

    assertEquals(
        "/dev/sdg1 and /dev/sdg2 cannot be a mirror: both are on /dev/sdg, and a mirror on one"
            + " disk dies with it.",
        refused.getMessage());
    assertEquals(
        List.of("realpath -e -- /dev/sdg1", "realpath -e -- /dev/sdg2", LSBLK),
        shell.invocations());
    assertEquals(
        "/dev/sdg1 and /dev/sdg1 cannot be a mirror: both name /dev/sdg1.",
        assertThrows(
                IllegalArgumentException.class,
                () -> devices(names(new Box(HOLDERS)).shell()).mirror("/dev/sdg1", "/dev/sdg1"))
            .getMessage());
  }

  @Test
  void twoNamesOfOnePhysicalDiskAreRefusedAsAMirror() {
    var layouts = "handwritten-mirror-layouts.lsblk.json";
    assertEquals(
        "/dev/nvme0n2 and /dev/nvme0n3 cannot be a mirror: /dev/nvme0n2 and /dev/nvme0n3 are"
            + " namespaces of one NVMe controller, nvme0, which dies with both.",
        mirrorRefusal(layouts, "/dev/nvme0n2", "/dev/nvme0n3"));
    assertEquals(
        "/dev/sdb and /dev/sdc cannot be a mirror: /dev/sdb and /dev/sdc have one WWN,"
            + " 0x5000c500a1b2c3d4: they are two paths to one disk.",
        mirrorRefusal(layouts, "/dev/sdb", "/dev/sdc"));
    assertEquals(
        "/dev/sdd and /dev/sde cannot be a mirror: /dev/sdd and /dev/sde report one serial,"
            + " ZA1B2C3E: they are one disk seen twice.",
        mirrorRefusal(layouts, "/dev/sdd", "/dev/sde"));
  }

  @Test
  void aBlankDeviceBesideOneHoldingExt4IsRefusedNamingTheSecondAndWhatItHolds() {
    var shell = spares().shell();

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).mirror("/dev/sdh", "/dev/sdd"));

    assertEquals(
        "/dev/sdd holds ext4. Sail erases nothing: if nothing on it is wanted, erase it yourself"
            + " with sudo wipefs --all /dev/sdd, then run this again.",
        refused.getMessage());
    assertNothingWritten(shell);
  }

  @Test
  void aDeviceAProcessHoldsRefusesTheMirrorNamingTheHolderBeforeEitherIsProbed() {
    var shell = spares().open(4242, "python3", "/dev/sdk").shell();

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).mirror("/dev/sdh", "/dev/sdk"));

    assertEquals(
        "/dev/sdk is refused: process 4242 (python3) has /dev/sdk open.", refused.getMessage());
    assertTrue(
        shell.invocations().stream()
            .noneMatch(c -> c.startsWith("wipefs") && c.endsWith("/dev/sdk")),
        "the held device is never probed: " + shell.invocations());
  }

  @Test
  void devicesOfSizesZpoolMirrorsOnlyWithForceAreRefusedAndANearMatchSaysWhatGoesUnused()
      throws Exception {
    var layouts = "handwritten-mirror-layouts.lsblk.json";
    var refuses =
        ", and zpool create refuses, without the force Sail never passes, a mirror whose devices"
            + " differ by more than 16 MiB: name two devices of one size, such as a partition of"
            + " that size on each disk.";
    assertEquals(
        "/dev/sdh and /dev/sdi cannot be a mirror: /dev/sdh is 40.0 GiB and /dev/sdi 60.0 GiB,"
            + " 20480.0 MiB apart"
            + refuses,
        mirrorRefusal(layouts, "/dev/sdh", "/dev/sdi"));
    assertEquals(
        "/dev/sdj and /dev/sdk cannot be a mirror: /dev/sdj is 20.0 GiB (21474836480 bytes) and"
            + " /dev/sdk 20.0 GiB (21492662272 bytes), 17.0 MiB apart"
            + refuses,
        mirrorRefusal(layouts, "/dev/sdj", "/dev/sdk"),
        "a pair only just past zpool's 16 MiB is refused, its sizes in bytes as they round alike");
    assertEquals(
        "/dev/sdj and /dev/sdl cannot be a mirror: /dev/sdj is 20.0 GiB and /dev/sdl 20.1 GiB,"
            + " 100.0 MiB apart"
            + refuses,
        mirrorRefusal(layouts, "/dev/sdj", "/dev/sdl"));
    assertEquals(
        "zfs on a mirror of /dev/sdf and /dev/sdg, 8.0 MiB of /dev/sdg unused",
        devices(names(new Box(layouts)).shell()).mirror("/dev/sdf", "/dev/sdg").said());
  }

  @Test
  void aWholeDiskBesideAPartitionIsSaidAsZfsSizesItNotByTheirRawBytes() throws Exception {
    var layouts = "handwritten-mirror-layouts.lsblk.json";

    var mirror = devices(names(new Box(layouts)).shell()).mirror("/dev/sdm", "/dev/sdn1");

    assertEquals(
        "zfs on a mirror of /dev/sdm and /dev/sdn1, a whole disk and a partition: ZFS sizes it to"
            + " the smaller of the partition and the disk's first partition, about 9 MiB less than"
            + " the disk",
        mirror.said(),
        "equal raw sizes, yet ZFS partitions the whole disk, so no figure of what goes unused is"
            + " true");
  }

  private static final String LEFT = "gcp5/d-225-destroyed-mirror-on-two-whole-disks.lsblk.json";

  /** The proof's two whole disks after the destroyed mirror zpend, with their real listings. */
  private static Box leftovers(String layout) {
    var box = names(new Box(layout));
    for (var disk : List.of("sdf", "sdg")) {
      box.holds("/dev/" + disk, fixture("gcp5/d-225-wipefs-" + disk + ".txt"));
      if (layout.equals(LEFT)) {
        box.holds("/dev/" + disk + "1", fixture("gcp5/d-225-wipefs-" + disk + "1.txt"));
      }
    }
    return box;
  }

  @Test
  void theRemedyForWhatAZpoolLeftNamesBothDisksAndEndsInAMirrorAfterOneRefusal() throws Exception {
    var refused =
        assertThrows(
                IllegalArgumentException.class,
                () -> devices(leftovers(LEFT).shell()).mirror("/dev/sdf", "/dev/sdg"))
            .getMessage();

    assertEquals(
        "/dev/sdf holds only what a zpool left: its data partition /dev/sdf1, carrying the labels"
            + " of zpool zpend, which cannot be imported, and its reserved partition /dev/sdf9. Sail"
            + " erases nothing: if nothing on it is wanted, return it to blank yourself with sudo"
            + " zpool labelclear /dev/sdf1, then sudo wipefs --all /dev/sdf, then run this again.\n"
            + "/dev/sdg holds only what a zpool left: its data partition /dev/sdg1, carrying the labels"
            + " of zpool zpend, which cannot be imported, and its reserved partition /dev/sdg9. Sail"
            + " erases nothing: if nothing on it is wanted, return it to blank yourself with sudo"
            + " zpool labelclear /dev/sdg1, then sudo wipefs --all /dev/sdg, then run this again.",
        refused);
    var made =
        devices(
                names(new Box("gcp5/handwritten-d-225-after-the-printed-remedy.lsblk.json"))
                    .shell())
            .mirror("/dev/sdf", "/dev/sdg");
    assertEquals("zfs on a mirror of /dev/sdf and /dev/sdg", made.said());
  }

  @Test
  void aDiskWhoseZpoolLabelsAreClearedNeedsOnlyItsTableErased() {
    assertEquals(
        "/dev/sdf holds only what a zpool left: its data partition /dev/sdf1, which carries"
            + " nothing, and its reserved partition /dev/sdf9. Sail erases nothing: if nothing on it"
            + " is wanted, return it to blank yourself with sudo wipefs --all /dev/sdf, then run this"
            + " again.",
        refusal(leftovers("gcp5/d-226-labels-cleared.lsblk.json"), "/dev/sdf"));
  }

  @Test
  void theLeftoversOfAPoolZpoolCanStillImportAreNeverOfferedAnErase() {
    var refused = refusal(leftovers(LEFT).importable("zpend"), "/dev/sdf");

    assertEquals(
        "/dev/sdf is refused: /dev/sdf1 is a device of zpool zpend, which is not imported: import it"
            + " with sudo zpool import zpend, or, if nothing on it is wanted, clear it yourself with"
            + " sudo zpool labelclear -f /dev/sdf1.",
        refused);
  }

  @Test
  void oneRefusalNamesWhatIsWrongWithEachDeviceInTheOrderNamed() {
    var refused =
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    devices(
                            spares()
                                .holds("/dev/sdh", EXT4)
                                .open(4242, "python3", "/dev/sdk")
                                .shell())
                        .mirror("/dev/sdh", "/dev/sdk"))
            .getMessage();

    assertEquals(
        "/dev/sdh holds ext4. Sail erases nothing: if nothing on it is wanted, erase it yourself"
            + " with sudo wipefs --all /dev/sdh, then run this again.\n/dev/sdk is refused: process"
            + " 4242 (python3) has /dev/sdk open.",
        refused);
  }

  @Test
  void aSecondDeviceSomethingHoldsExclusivelyIsRefusedNamingWhatItsOwnProbesFound() {
    var shell = spares().holds("/dev/sdk", EXT4).busy("/dev/sdk").shell();

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).mirror("/dev/sdh", "/dev/sdk"));

    assertEquals(
        "/dev/sdk is refused: /dev/sdk holds ext4 and is held exclusively by something Sail cannot"
            + " name: a mount in another namespace, an array, a pool, or a process that opened it"
            + " exclusively.",
        refused.getMessage());
  }

  private static String mirrorRefusal(String layout, String one, String other) {
    return assertThrows(
            IllegalArgumentException.class,
            () -> devices(names(new Box(layout)).shell()).mirror(one, other))
        .getMessage();
  }

  private static void assertNothingWritten(ScriptedShellExecutor shell) {
    assertEquals(
        List.of(),
        shell.arguments().stream()
            .filter(argv -> !argv.getFirst().equals("wipefs") || !argv.contains("--no-act"))
            .filter(
                argv ->
                    argv.getFirst().equals("wipefs") && argv.contains("--all")
                        || argv.getFirst().equals("zpool") && argv.contains("create")
                        || argv.contains("storage") && argv.contains("create"))
            .toList());
  }

  private static PoolDevices devices(ShellExec shell) {
    return new PoolDevices(shell);
  }

  private static List<String> gcpLayouts() {
    var layouts = new ArrayList<>(RAID_LAYOUTS);
    layouts.addAll(List.of(BLANK, PARTITIONED, "after-teardown-blank-again"));
    return layouts;
  }

  /** The GCP VM's {@code layout}: its lsblk tree, and each device's listing and busy open. */
  @Test
  void aBlankPartitionOnTheSystemDiskIsTakenAndSoIsOneBesideSwapInUse() throws Exception {
    assertEquals(
        "/dev/sda2", devices(names(new Box(A_INITIAL)).shell()).cleared("/dev/sda2").path());
    assertEquals(
        "/dev/sdd3",
        devices(names(new Box("gcp2/b-layout-initial.lsblk.json")).shell())
            .cleared("/dev/sdd3")
            .path());
  }

  @Test
  void aDiskThatHoldsAFilesystemIsRefusedNamingItAndTheCommandThatErasesIt() {
    assertEquals(
        "/dev/sdd holds ext4. Sail erases nothing: if nothing on it is wanted, erase it yourself"
            + " with sudo wipefs --all /dev/sdd, then run this again.",
        refusal(spares().holds("/dev/sdd", EXT4), "/dev/sdd"));
  }

  @Test
  void aPartitionWithoutATypeLsblkReadsOrWithAnyOtherTypeIsRefused() {
    var reasons =
        Map.of(
            "/dev/sdd1",
            " has no partition type lsblk reads: run sudo udevadm settle, then run this again",
            "/dev/sdd2",
            " has partition type C12A7328-F81F-11D2-BA4B-00A0C93EC93B"
                + ACCEPTED
                + "; it holds vfat",
            "/dev/sde2",
            " has partition type 0xea" + ACCEPTED,
            "/dev/sde4",
            " has partition type 0x5" + ACCEPTED,
            "/dev/sdf1",
            " has partition type 0xf" + ACCEPTED,
            "/dev/sdf2",
            " has partition type 0x85" + ACCEPTED);
    reasons.forEach(
        (device, reason) ->
            assertEquals(device + " is refused: " + device + reason + ".", refusal(TYPES, device)));
  }

  @Test
  void whatLsblkShowsADeviceHoldsCountsWhereItsListingShowsNothing() throws Exception {
    assertEquals(
        "/dev/sdc holds ext4. Sail erases nothing: if nothing on it is wanted, erase it yourself"
            + " with sudo wipefs --all /dev/sdc, then run this again.",
        refusal("gcp2/d-layout-initial.lsblk.json", "/dev/sdc"));
    assertEquals(
        "/dev/sdg holds gpt. Sail erases nothing: if nothing on it is wanted, erase it yourself"
            + " with sudo wipefs --all /dev/sdg, then run this again.",
        refusal(TYPES, "/dev/sdg"));
    assertTrue(
        devices(new Box(TYPES).shell()).candidates().stream()
            .noneMatch(candidate -> candidate.device().equals("/dev/sdg")));
  }

  @Test
  void aPartitionOverlappingAnotherOrPastItsDiskIsRefusedNamingWhy() {
    var overlap = "gcp3/g-layout-overlapping-mbr.lsblk.json";

    assertEquals(
        "/dev/sdb2 is refused: /dev/sdb2 overlaps /dev/sdb1 on /dev/sdb.",
        refusal(overlap, "/dev/sdb2"));
    assertTrue(
        refusal(overlap, "/dev/sdb1").contains("/dev/sdb1 overlaps /dev/sdb2 on /dev/sdb"),
        refusal(overlap, "/dev/sdb1"));
    assertEquals(
        "/dev/sde1 is refused: /dev/sde1 reaches past the end of /dev/sde.",
        refusal(PROBES, "/dev/sde1"));
  }

  @Test
  void everyPartitionOfADiskWithAnOverlappingPairIsRefusedAndOneWithNoStart() {
    var first = partition("/dev/sdx1", 2048L, 1L << 30);
    var second = partition("/dev/sdx2", 4096L, 1L << 30);
    var third = partition("/dev/sdx3", 10_000_000L, 1L << 30);
    var startless = partition("/dev/sdx4", null, 1L << 20);
    var disk =
        new BlockDevice(
            "/dev/sdx",
            "sdx",
            "disk",
            null,
            "gpt",
            null,
            List.of(),
            20L << 30,
            null,
            null,
            false,
            null,
            null,
            "8:240",
            List.of(first, second, third, startless));

    assertEquals(
        List.of("/dev/sdx1 overlaps /dev/sdx2 on /dev/sdx"), DeviceRules.extents(third, disk));
    assertEquals(
        List.of("/dev/sdx4 has no start lsblk reads: run sudo udevadm settle, then run this again"),
        DeviceRules.extents(startless, disk));
  }

  @Test
  void aWholeDiskHoldingABootPartitionIsRefusedByItsTypeNeverOfferedAnErase() {
    var refused = refusal("gcp3/c-layout-final.lsblk.json", "/dev/sdh");

    assertTrue(
        refused.startsWith(
            "/dev/sdh is refused: /dev/sdh1 has partition type c12a7328-f81f-11d2-ba4b-00a0c93ec93b"),
        refused);
    assertFalse(refused.contains("wipefs"), refused);
  }

  @Test
  void aWholeDiskWithAnOverlappingPairNamesTheOverlap() {
    assertEquals(
        "/dev/sdb is refused: /dev/sdb1 overlaps /dev/sdb2 on /dev/sdb; /dev/sdb1 is mounted at"
            + " /mnt/ovl.",
        refusal(OVERLAPS, "/dev/sdb"));
    assertEquals(
        "/dev/sdc is refused: /dev/sdc1 overlaps /dev/sdc2 on /dev/sdc.",
        refusal(OVERLAPS, "/dev/sdc"));
    assertEquals(
        "/dev/sdd is refused: /dev/sdd1 overlaps /dev/sdd2 on /dev/sdd.",
        refusal(OVERLAPS, "/dev/sdd"));
    assertEquals(
        "/dev/loop3 is refused: it is a loop device, not a disk or a partition; /dev/loop3p1"
            + " reaches past the end of /dev/loop3.",
        refusal(OVERLAPS, "/dev/loop3"));
  }

  @Test
  void aPartitionTheKernelCutAtItsDisksEndIsRefusedAsReachingPastIt() {
    var cut =
        "/dev/sde1 reaches past the end of /dev/sde: the kernel cut it at the disk's end, so udev"
            + " reads no type for it.";
    assertEquals("/dev/sde1 is refused: " + cut, refusal(OVERLAPS, "/dev/sde1"));
    assertEquals("/dev/sde is refused: " + cut, refusal(OVERLAPS, "/dev/sde"));
  }

  @Test
  void anNvmeDiskWithAVendorIdIsHandedToIncusByItsNvmeName() throws Exception {
    var id = "nvme.1ae0-6e766d655f636172642d7064-6e766d655f636172642d7064-00000002";
    var named = "/dev/disk/by-id/nvme-" + id;
    var shell = names(new Box("gcp4/c-nvme-pd.lsblk.json")).name(named, "/dev/nvme0n2").shell();

    assertEquals(
        new PoolSource.Device(named, id + " 259:5 21474836480"),
        devices(shell).cleared("/dev/nvme0n2"));
    assertEquals(
        "realpath -e -- " + named, shell.invocations().getLast(), shell.invocations().toString());
    assertEquals(
        "/dev/nvme0n1",
        devices(names(new Box("gcp4/b-local-nvme-ssd.lsblk.json")).shell())
            .cleared("/dev/nvme0n1")
            .path(),
        "a by-id name that does not resolve to the device is not handed over");
  }

  private static BlockDevice partition(String path, Long start, long size) {
    return new BlockDevice(
        path,
        path.substring(5),
        "part",
        null,
        "gpt",
        "0fc63daf-8483-4772-8e79-3d69d8477de4",
        List.of(),
        size,
        start,
        null,
        false,
        null,
        null,
        null,
        List.of());
  }

  @Test
  void aDeviceWithAHardwareIdentityIsHandedToIncusByItsPersistentPath() throws Exception {
    var wwn = "/dev/disk/by-id/wwn-0x5000c500a1b2c3d4-part2";
    assertEquals(
        new PoolSource.Device(wwn, "0x5000c500a1b2c3d4 8:2 10737418240"),
        devices(names(new Box(TYPES)).name(wwn, "/dev/sda2").shell()).cleared("/dev/sda2"));
    var eui = "/dev/disk/by-id/nvme-eui.0025388b91b0f1a2";
    assertEquals(
        eui,
        devices(names(new Box(PROBES)).name(eui, "/dev/nvme1n1").shell())
            .cleared("/dev/nvme1n1")
            .path());
    assertEquals(
        "/dev/sda2",
        devices(names(new Box(TYPES)).shell()).cleared("/dev/sda2").path(),
        "a persistent path that names no device is not handed over");
  }

  @Test
  void theWholeDiskOfAPoolNamesThePoolNotItsReservedPartition() {
    var devpool =
        fixture("raw-file-pool.zpool-list.txt")
            .replace("sail-raw", "devpool")
            .replace("/tmp/devpool.img", "/dev/sdb1");
    assertEquals(
        "/dev/sdb is refused: it is in zpool devpool, Incus's pool 'devpool'.",
        refusal(
            names(new Box("gcp3/f-layout-after-devpool.lsblk.json"))
                .zpool("devpool", devpool, "/dev/sdb1")
                .incus(
                    "[{\"name\": \"devpool\", \"driver\": \"zfs\", \"config\":"
                        + " {\"source\": \"devpool\", \"zfs.pool_name\": \"devpool\"}}]"),
            "/dev/sdb"));
    assertEquals(
        "/dev/sdd is refused: /dev/sdd1 is a device of zpool handpool, which is not imported:"
            + " import it with sudo zpool import handpool, or, if nothing on it is wanted, clear it"
            + " yourself with sudo zpool labelclear -f /dev/sdd1.",
        refusal(
            names(new Box("gcp3/c-layout-exported-pool.lsblk.json"))
                .holds("/dev/sdd1", LISTED + "sdd1 0x3f000 zfs_member 1234567890 handpool\n")
                .importable("handpool"),
            "/dev/sdd"));
  }

  @Test
  void theLabelsOfAPoolThatCannotBeImportedAreClearedNeverImported() {
    var refused =
        refusal(
            names(new Box(PROBES))
                .holds("/dev/sda5", LISTED + "sda5 0x3f000 zfs_member 1234567890 oldtank\n")
                .importable("othertank"),
            "/dev/sda5");

    assertEquals(
        "/dev/sda5 is refused: /dev/sda5 carries the labels of zpool oldtank, which cannot be"
            + " imported (destroyed, or its other devices are gone): clear them yourself with sudo"
            + " zpool labelclear /dev/sda5.",
        refused);
    assertFalse(refused.contains("zpool import"), refused);
  }

  @Test
  void aDiskTypeDeviceBuiltOnAnotherIsNotAPlainDisk() {
    assertEquals(
        "/dev/bcache0 is refused: it is a disk built on /dev/sdb, not a plain disk.",
        refusal(TYPES, "/dev/bcache0"));
  }

  @Test
  void theRootDiskSeenThroughASecondPathIsRefusedOnWhatItHolds() {
    assertEquals(
        "/dev/sdc1 holds ext4. Sail erases nothing: if nothing on it is wanted, erase it yourself"
            + " with sudo wipefs --all /dev/sdc1, then run this again.",
        refusal(names(new Box(TYPES)).holds("/dev/sdc1", EXT4), "/dev/sdc1"));
  }

  @Test
  void onARootOnZfsMirrorBoxTheRootPoolsDisksAreRefused() {
    var rpool =
        fixture("raw-file-pool.zpool-list.txt")
            .replace("sail-raw", "rpool")
            .replace("/tmp/rpool.img", "/dev/sdb2");
    assertEquals(
        "/dev/sdb2 is refused: it is in the imported zpool rpool, and no pool's device is taken:"
            + " export or destroy the pool yourself first.",
        refusal(names(new Box(ZFS_ROOT)).zpool("rpool", rpool, "/dev/sdb2"), "/dev/sdb2"));
    assertEquals(
        "/dev/sdd1 is refused: /dev/sdd1 has partition type c12a7328-f81f-11d2-ba4b-00a0c93ec93b"
            + ACCEPTED
            + "; it holds vfat.",
        refusal(ZFS_ROOT, "/dev/sdd1"));
  }

  private static Box gcp(String layout) {
    var box = names(new Box("gcp/" + layout + ".lsblk-tree.json"));
    DeviceFixtures.listings(layout).forEach(box::holds);
    DeviceFixtures.busy(layout).forEach(box::busy);
    return box;
  }

  private static Box spares() {
    return names(new Box(SPARES));
  }

  /** {@code box} with every device of its layout resolving to itself. */
  private static Box names(Box box) {
    box.paths().forEach(path -> box.name(path, path));
    return box;
  }

  /** The spares, /dev/sdi's first partition a vdev of the imported zpool sail-raw. */
  private static Box imported() {
    return spares()
        .holds("/dev/sdi", DeviceFixtures.listings(PARTITIONED).get("/dev/sdb"))
        .holds("/dev/sdi1", RAID_MEMBER.replace("linux_raid_member", "zfs_member"))
        .zpool(
            "sail-raw",
            fixture("raw-file-pool.zpool-list.txt").replace("/tmp/sail-raw.img", "/dev/sdi1"),
            "/dev/sdi1");
  }

  private static String refusal(String layout, String named) {
    return refusal(names(new Box(layout)), named);
  }

  private static String refusal(Box box, String named) {
    return assertThrows(IllegalArgumentException.class, () -> devices(box.shell()).cleared(named))
        .getMessage();
  }

  private static String fixture(String name) {
    return DeviceFixtures.read(name);
  }

  /**
   * A box whose lsblk prints {@code layout}: each name resolves as given, each device lists what it
   * holds (nothing unless scripted) and opens free unless scripted busy, ZFS is not installed
   * unless a pool is listed, and a test's own scripts, passed to {@link
   * #shell(ScriptedShellExecutor)}, come first.
   */
  private static final class Box {
    private final String layout;
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, String> listings = new LinkedHashMap<>();
    private final Map<String, String> busy = new LinkedHashMap<>();
    private final Map<String, List<String>> pools = new LinkedHashMap<>();
    private final StringBuilder opened = new StringBuilder();
    private final StringBuilder importable = new StringBuilder();
    private final Map<Integer, String> comms = new LinkedHashMap<>();
    private String incus;

    Box(String layout) {
      this.layout = layout;
    }

    List<String> paths() {
      return BlockDevice.listed(YamlUtil.parseMap(fixture(layout)).get("blockdevices")).stream()
          .flatMap(BlockDevice::subtree)
          .map(BlockDevice::path)
          .distinct()
          .toList();
    }

    Box name(String named, String path) {
      if (named != null) {
        names.put(named, path);
      }
      return this;
    }

    Box holds(String path, String listing) {
      listings.put(path, listing);
      return this;
    }

    Box busy(String path) {
      return busy(
          path,
          "wipefs: error: " + path + ": probing initialization failed: Device or resource busy");
    }

    Box busy(String path, String said) {
      busy.put(path, said);
      return this;
    }

    /** Process {@code pid}, named {@code comm}, holding {@code path} open as /proc shows it. */
    Box open(int pid, String comm, String path) {
      var number =
          BlockDevice.listed(YamlUtil.parseMap(fixture(layout)).get("blockdevices")).stream()
              .flatMap(BlockDevice::subtree)
              .filter(device -> device.path().equals(path))
              .findFirst()
              .orElseThrow()
              .majmin()
              .split(":");
      opened
          .append(pid)
          .append('\t')
          .append(Integer.toHexString(Integer.parseInt(number[0])))
          .append(':')
          .append(Integer.toHexString(Integer.parseInt(number[1])))
          .append('\n');
      comms.put(pid, comm);
      return this;
    }

    /** The pools {@code zpool import} lists as there to import. */
    Box importable(String... pools) {
      for (var pool : pools) {
        importable.append("   pool: ").append(pool).append('\n');
      }
      return this;
    }

    /** Process {@code pid}, whose open files cannot be read. */
    Box unreadable(int pid) {
      opened.append("unreadable\t").append(pid).append('\n');
      return this;
    }

    /** An imported pool {@code pool}: its {@code zpool list -vHP} and where its vdevs resolve. */
    Box zpool(String pool, String listed, String resolvedVdevs) {
      pools.put(pool, List.of(listed, resolvedVdevs));
      return this;
    }

    Box incus(String pools) {
      incus = pools;
      return this;
    }

    ScriptedShellExecutor shell() {
      return shell(new ScriptedShellExecutor());
    }

    ScriptedShellExecutor shell(ScriptedShellExecutor shell) {
      longestFirst(names.keySet())
          .forEach(name -> shell.onOk("realpath -e -- " + name, names.get(name) + "\n"));
      shell.onOk(LSBLK, fixture(layout));
      var paths = new ArrayList<>(paths());
      paths.addAll(listings.keySet());
      for (var path : longestFirst(paths)) {
        shell.onOk("wipefs -- " + path, listings.getOrDefault(path, DeviceFixtures.NOTHING_HELD));
        var open = String.join(" ", PoolDevices.exclusiveOpen(path));
        if (busy.containsKey(path)) {
          shell.on(open, new ShellExec.Result(1, "", busy.get(path)));
        } else {
          shell.onOk(open);
        }
      }
      if (!pools.isEmpty()) {
        shell.onOk("which zpool").onOk("zpool list -H -o name", String.join("\n", pools.keySet()));
        for (var pool : longestFirst(pools.keySet())) {
          var listed = pools.get(pool).getFirst();
          shell.onOk("zpool list -vHP " + pool, listed);
          shell.onOk(
              "realpath -m -- " + String.join(" ", ImportedPools.vdevs(listed.lines().toList())),
              pools.get(pool).getLast() + "\n");
        }
      }
      if (incus != null) {
        shell.onOk("incus storage list", incus);
      }
      comms.forEach((pid, comm) -> shell.onOk("cat /proc/" + pid + "/comm", comm + "\n"));
      if (!importable.isEmpty()) {
        shell.onOk("which zpool").onOk("zpool import", importable.toString());
        if (pools.isEmpty()) {
          shell.onOk("zpool list -H -o name", "");
        }
      }
      shell.onOk("/proc/[0-9]*", opened.toString());
      shell.onOk(String.join(" ", PoolDevices.SETTLE));
      return shell;
    }

    private static List<String> longestFirst(Collection<String> keys) {
      return keys.stream()
          .distinct()
          .sorted(Comparator.comparing(String::length).reversed())
          .toList();
    }
  }
}
