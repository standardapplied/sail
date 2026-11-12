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
            "/dev/nvme0n1p1" + untyped,
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
            + " /dev/sda14 has partition type 21686148-6449-6e6f-744e-656564454649"
            + ACCEPTED
            + "; /dev/sda15 has partition type c12a7328-f81f-11d2-ba4b-00a0c93ec93b"
            + ACCEPTED
            + "; /dev/sda15 is mounted at /boot/efi; /dev/sda16 has partition type"
            + " bc13c2ff-59e6-4262-a352-b275fd6f7172"
            + ACCEPTED
            + "; /dev/sda16 is mounted at /boot.",
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
            "/dev/md2 (raid1) is built on it",
            "/dev/sdb3 has partition type a19d880f-05fc-4d3b-a006-743f0f84911e" + ACCEPTED)) {
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
              "wipefs -- /dev/sdb",
              "which zpool",
              String.join(" ", PoolDevices.exclusiveOpen("/dev/sdb")),
              "bash -c " + PoolDevices.OPEN_FILES),
          shell.invocations());
    }
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
  void aDeviceAnotherHolderHasOpenIsRefused() {
    assertEquals(
        "/dev/sdk is refused: /dev/sdk is held open by something lsblk" + " does not show.",
        refusal(spares().busy("/dev/sdk"), "/dev/sdk"));
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
                .holds("/dev/sda5", LISTED + "sda5 0x3f000 zfs_member 1234567890 oldtank\n"),
            "/dev/sda5");

    assertEquals(
        "/dev/sda5 is refused: /dev/sda5 is a device of zpool oldtank, which is not imported:"
            + " import it with sudo zpool import oldtank, or clear it yourself with sudo zpool"
            + " labelclear /dev/sda5.",
        refused);
    assertFalse(refused.contains("wipefs"), refused);
    assertEquals(
        "/dev/sdb is refused: /dev/sdb1 is a device of zpool exported, which is not imported:"
            + " import it with sudo zpool import exported, or clear it yourself with sudo zpool"
            + " labelclear /dev/sdb1.",
        refusal(
            names(new Box(PROBES))
                .holds("/dev/sdb1", LISTED + "sdb1 0x3f000 zfs_member 1234567890 exported\n"),
            "/dev/sdb"));
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
        refusal("gcp2/b-layout-after-devpool.lsblk.json", "/dev/sdb9"));
  }

  @Test
  void theSecondDeviceOfABtrfsRootIsRefusedNamingTheBtrfsItHolds() {
    assertEquals(
        "/dev/sdd2 is refused: /dev/sdd2 holds btrfs and is held open by something lsblk does not"
            + " show.",
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
      opened.append(pid).append('\t').append(path).append('\n');
      comms.put(pid, comm);
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
      shell.onOk("/proc/[0-9]*", opened.toString());
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
