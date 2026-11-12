/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  void theDeviceTreeIsAskedForExplicitly() {
    assertEquals(
        List.of(
            "lsblk",
            "--json",
            "--tree",
            "--output",
            "PATH,TYPE,FSTYPE,PTTYPE,MOUNTPOINTS,SIZE,MODEL,RM"),
        PoolDevices.LSBLK);
    assertEquals(
        List.of("wipefs", "--all", "--no-act", "--types", "sail_exclusive_probe", "--", "/dev/sdb"),
        PoolDevices.exclusiveOpen("/dev/sdb"));
  }

  @Test
  void everyDeviceOfThisDevboxIsRefusedWithAndWithoutTheForceFlagBeforeAnyProbe() throws Exception {
    for (var device : DEVBOX_DEVICES) {
      for (var force : new String[] {null, device}) {
        var shell = new Box(DEVBOX).name(device, device).shell();
        var refused =
            assertThrows(
                IllegalArgumentException.class, () -> devices(shell).cleared(device, force));
        assertTrue(
            refused.getMessage().startsWith(device + " is refused, even with --force-wipe: "),
            refused.getMessage());
        assertEquals(List.of("realpath -e -- " + device, LSBLK), shell.invocations().subList(0, 2));
        assertTrue(
            shell.invocations().stream().noneMatch(c -> c.startsWith("wipefs")),
            "refused on what lsblk shows, before a probe: " + shell.invocations());
      }
    }
  }

  @Test
  void theDevboxRefusalsNameWhatHoldsEachDevice() {
    assertEquals(
        "/dev/nvme0n1 is refused, even with --force-wipe: /dev/md0 (raid1) is built on it;"
            + " /dev/md1 (raid1) is built on it; /dev/md2 is mounted at /var/lib/sail/run; /dev/md2"
            + " is mounted at /dev/.incus-systemd-credentials; /dev/md2 is mounted at /, the"
            + " running system; /dev/md2 (raid1) is built on it.",
        refusal(DEVBOX, "/dev/nvme0n1", "/dev/nvme0n1"));
    assertEquals(
        "/dev/nvme1n1p1 is refused, even with --force-wipe: it is a partition of /dev/nvme1n1,"
            + " which carries the running system (/dev/md2 is mounted at /, the running system).",
        refusal(DEVBOX, "/dev/nvme1n1p1", "/dev/nvme1n1p1"));
    assertEquals(
        "/dev/nvme0n1p4 is refused, even with --force-wipe: /dev/md2 is mounted at"
            + " /var/lib/sail/run; /dev/md2 is mounted at /dev/.incus-systemd-credentials; /dev/md2"
            + " is mounted at /, the running system; /dev/md2 (raid1) is built on it.",
        refusal(DEVBOX, "/dev/nvme0n1p4", null));
    assertEquals(
        "/dev/md0 is refused, even with --force-wipe: it is a raid1 device, not a disk or a"
            + " partition; it is a partition of /dev/nvme0n1, which carries the running system"
            + " (/dev/md2 is mounted at /, the running system).",
        refusal(DEVBOX, "/dev/md0", "/dev/md0"));
  }

  @Test
  void theSystemDiskOfEveryCapturedLayoutIsRefusedWithAndWithoutTheForceFlag() {
    for (var layout : gcpLayouts()) {
      for (var device : SYSTEM_DISK) {
        for (var force : new String[] {null, device}) {
          assertTrue(
              refusal(gcp(layout), device, force)
                  .startsWith(device + " is refused, even with --force-wipe: "),
              layout + " " + device);
        }
      }
    }
    assertEquals(
        "/dev/sda14 is refused, even with --force-wipe: it is a partition of /dev/sda, which"
            + " carries the running system (/dev/sda1 is mounted at /, the running system).",
        refusal(gcp(BLANK), "/dev/sda14", "/dev/sda14"));
    assertEquals(
        "/dev/sda (/dev/disk/by-id/google-persistent-disk-0) is refused, even with --force-wipe:"
            + " /dev/sda1 is mounted at /, the running system; /dev/sda15 is mounted at /boot/efi,"
            + " the running system; /dev/sda16 is mounted at /boot, the running system.",
        refusal(
            gcp(BLANK).name("/dev/disk/by-id/google-persistent-disk-0", "/dev/sda"),
            "/dev/disk/by-id/google-persistent-disk-0",
            "/dev/sda"));
  }

  @Test
  void everyDeviceOfEveryRaidLayoutIsRefusedWithAndWithoutTheForceFlag() {
    for (var layout : RAID_LAYOUTS) {
      for (var device : SPARE_DISKS) {
        for (var force : new String[] {null, device}) {
          assertTrue(
              refusal(gcp(layout), device, force)
                  .startsWith(device + " is refused, even with --force-wipe: "),
              layout + " " + device);
        }
      }
    }
  }

  @Test
  void theOwnerLayoutsRefusalsNameTheArraysTheSwapAndTheMount() {
    assertEquals(
        "/dev/sdb is refused, even with --force-wipe: /dev/sdb1 is a RAID member: stop its array"
            + " and clear it with mdadm --zero-superblock /dev/sdb1 yourself; /dev/md0 is in use as"
            + " swap; /dev/md0 (raid1) is built on it; /dev/sdb2 is a RAID member: stop its array"
            + " and clear it with mdadm --zero-superblock /dev/sdb2 yourself; /dev/md1 is mounted"
            + " at /mnt/sailtrial-boot; /dev/md1 (raid1) is built on it; /dev/sdb3 is a RAID"
            + " member: stop its array and clear it with mdadm --zero-superblock /dev/sdb3"
            + " yourself; /dev/md2 (raid1) is built on it.",
        refusal(gcp(OWNER), "/dev/sdb", "/dev/sdb"));
    assertEquals(
        "/dev/sdb3 is refused, even with --force-wipe: it is a partition of /dev/sdb, which"
            + " carries the running system (/dev/md0 is in use as swap); /dev/sdb3 is a RAID"
            + " member: stop its array and clear it with mdadm --zero-superblock /dev/sdb3"
            + " yourself; /dev/md2 (raid1) is built on it.",
        refusal(gcp(OWNER), "/dev/sdb3", "/dev/sdb3"));
  }

  @Test
  void aRaidMemberUdevDoesNotKnowIsRefusedOnWhatItsListingShows() {
    assertEquals(
        "/dev/sdc1 is refused, even with --force-wipe: /dev/sdc1 is a RAID member: stop its array"
            + " and clear it with mdadm --zero-superblock /dev/sdc1 yourself.",
        refusal(gcp(STOPPED), "/dev/sdc1", "/dev/sdc1"));
  }

  @Test
  void flatListsFromLsblkAreUnreadableAndRefused() {
    var flats = new ArrayList<>(List.of("devbox-flat.lsblk.json", "github-runner-flat.lsblk.json"));
    gcpLayouts().forEach(layout -> flats.add("gcp/" + layout + ".lsblk-flat.json"));
    for (var flat : flats) {
      var shell = new Box(flat).name("/dev/sdb", "/dev/sdb").shell();

      var cleared =
          assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb", "/dev/sdb"));
      var offered = assertThrows(IOException.class, () -> devices(shell).candidates());

      assertEquals(
          "lsblk printed a flat list, not a device tree, so what is built on a device cannot be"
              + " read; nothing was wiped.",
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

    var failed = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb", null));

    assertEquals(
        "lsblk failed: lsblk: unknown column: MOUNTPOINTS. Sail reads devices with util-linux 2.37"
            + " or newer (lsblk --json --tree with MOUNTPOINTS); nothing was wiped.",
        failed.getMessage());
  }

  @Test
  void aBlankDiskIsClearedAfterEveryProbe() throws Exception {
    for (var layout : List.of(BLANK, "after-teardown-blank-again")) {
      var shell = gcp(layout).shell();

      assertEquals(
          new PoolSource.Device("/dev/sdb", false), devices(shell).cleared("/dev/sdb", null));
      assertEquals(
          List.of(
              "realpath -e -- /dev/sdb",
              LSBLK,
              "wipefs -- /dev/sdb",
              "which zpool",
              String.join(" ", PoolDevices.exclusiveOpen("/dev/sdb"))),
          shell.invocations());
    }
  }

  @Test
  void aPartitionedDiskNamesItsTableAndIsClearedWhenNamedAgain() throws Exception {
    assertEquals(
        "/dev/sdb holds gpt, PMBR. Nothing on it is erased unless you name it again: add"
            + " --force-wipe /dev/sdb to erase it and make the pool there.",
        refusal(gcp(PARTITIONED), "/dev/sdb", null));
    assertEquals(
        new PoolSource.Device("/dev/sdb", true),
        devices(gcp(PARTITIONED).shell()).cleared("/dev/sdb", "/dev/sdb"));
  }

  @Test
  void aSparePartitionIsCleared() throws Exception {
    assertEquals(
        new PoolSource.Device("/dev/sdb1", false),
        devices(gcp(PARTITIONED).shell()).cleared("/dev/sdb1", null));
  }

  @Test
  void aDiskThatHoldsAFilesystemIsRefusedNamingItAndClearedWhenNamedAgain() throws Exception {
    assertEquals(
        "/dev/sdd holds ext4. Nothing on it is erased unless you name it again: add --force-wipe"
            + " /dev/sdd to erase it and make the pool there.",
        refusal(spares().holds("/dev/sdd", EXT4), "/dev/sdd", null));
    assertEquals(
        new PoolSource.Device("/dev/sdd", true),
        devices(spares().holds("/dev/sdd", EXT4).shell()).cleared("/dev/sdd", "/dev/sdd"));
  }

  @Test
  void aPartitionBesideSwapInUseIsRefusedEvenWithTheForceFlag() {
    assertEquals(
        "/dev/sdl2 is refused, even with --force-wipe: it is a partition of /dev/sdl, which"
            + " carries the running system (/dev/sdl1 is in use as swap).",
        refusal(spares(), "/dev/sdl2", "/dev/sdl2"));
  }

  @Test
  void devicesThatAreNotSpareDisksAreRefusedEvenWithTheForceFlag() {
    assertEquals(
        "/dev/sde is refused, even with --force-wipe: /dev/sde is removable.",
        refusal(spares(), "/dev/sde", "/dev/sde"));
    assertEquals(
        "/dev/zram0 is refused, even with --force-wipe: it is a zram device.",
        refusal(spares(), "/dev/zram0", "/dev/zram0"));
    assertEquals(
        "/dev/nbd0 is refused, even with --force-wipe: it has no size; it is a nbd device.",
        refusal(spares(), "/dev/nbd0", "/dev/nbd0"));
    assertEquals(
        "/dev/sdf is refused, even with --force-wipe: /dev/sdf is an LVM physical volume: move its"
            + " volume group off it and clear it with pvremove /dev/sdf yourself; /dev/mapper/vg-data"
            + " is mounted at /srv; /dev/mapper/vg-data (lvm) is built on it.",
        refusal(spares(), "/dev/sdf", "/dev/sdf"));
    assertEquals(
        "/dev/sdg is refused, even with --force-wipe: /dev/sdg holds a LUKS volume: close it and"
            + " erase it with cryptsetup erase /dev/sdg yourself; /dev/mapper/secret (crypt) is built"
            + " on it.",
        refusal(spares(), "/dev/sdg", "/dev/sdg"));
    assertEquals(
        "/dev/loop0 is refused, even with --force-wipe: it is a loop device, not a disk or a"
            + " partition; /dev/loop0 is mounted at /snap/core24/2124.",
        refusal(gcp(BLANK), "/dev/loop0", "/dev/loop0"));
  }

  @Test
  void anLvmOrLuksMemberUdevDoesNotKnowIsRefusedOnWhatItsListingShows() {
    var members =
        Map.of(
            "LVM2_member",
            "/dev/sdh is refused, even with --force-wipe: /dev/sdh is an LVM physical volume: move"
                + " its volume group off it and clear it with pvremove /dev/sdh yourself.",
            "crypto_LUKS",
            "/dev/sdh is refused, even with --force-wipe: /dev/sdh holds a LUKS volume: close it and"
                + " erase it with cryptsetup erase /dev/sdh yourself.");
    members.forEach(
        (type, message) ->
            assertEquals(
                message,
                refusal(
                    spares().holds("/dev/sdh", RAID_MEMBER.replace("linux_raid_member", type)),
                    "/dev/sdh",
                    "/dev/sdh")));
  }

  @Test
  void aDeviceAnotherHolderHasOpenIsRefusedEvenWithTheForceFlag() {
    assertEquals(
        "/dev/sdk is refused, even with --force-wipe: /dev/sdk is held open by something lsblk"
            + " does not show.",
        refusal(spares().busy("/dev/sdk"), "/dev/sdk", "/dev/sdk"));
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

    var failed = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb", null));

    assertEquals(
        "Cannot tell whether /dev/sdb is in use: wipefs: error: /dev/sdb: probing initialization"
            + " failed: Permission denied",
        failed.getMessage());
  }

  @Test
  void anEmptyImportedPoolsDeviceIsNeverWipedButHandedToIncusAsItIs() throws Exception {
    assertEquals(
        new PoolSource.Zpool("sail-raw"),
        devices(imported(fixture("raw-file-pool.zfs-list.txt")).shell()).cleared("/dev/sdi", null));
    assertEquals(
        "/dev/sdi is refused, even with --force-wipe: it is in the imported zpool sail-raw, which"
            + " is never wiped: drop --force-wipe to hand the empty pool to Incus, or destroy the"
            + " pool yourself.",
        refusal(imported(fixture("raw-file-pool.zfs-list.txt")), "/dev/sdi", "/dev/sdi"));
  }

  @Test
  void aDeviceInTwoImportedPoolsIsRefused() {
    var pools =
        fixture("raw-file-pool.zpool-list.txt").replace("/tmp/sail-raw.img", "/dev/sdi1")
            + fixture("raw-file-pool.zpool-list.txt")
                .replace("sail-raw", "other")
                .replace("/tmp/other.img", "/dev/sdi9");

    assertEquals(
        "/dev/sdi is refused, even with --force-wipe: it holds devices of the imported pools"
            + " sail-raw, other.",
        refusal(spares().zpool(pools, "/dev/sdi1\n/dev/sdi9"), "/dev/sdi", null));
  }

  @Test
  void anImportedPoolThatHoldsDatasetsIsRefusedNamingThem() {
    var box = imported(fixture("incus-file-pool.zfs-list.txt").replace("sail-capture", "sail-raw"));

    assertEquals(
        "/dev/sdi is refused, even with --force-wipe: it is in the imported zpool sail-raw, which"
            + " holds sail-raw/buckets, sail-raw/containers, sail-raw/custom, sail-raw/deleted,"
            + " sail-raw/deleted/buckets, sail-raw/deleted/containers, sail-raw/deleted/custom,"
            + " sail-raw/deleted/images, sail-raw/deleted/virtual-machines, sail-raw/images,"
            + " sail-raw/virtual-machines: only an empty pool is handed to Incus, and none is"
            + " wiped.",
        refusal(box, "/dev/sdi", null));
  }

  @Test
  void aPoolIncusHoldsIsRefused() {
    var box =
        imported(fixture("raw-file-pool.zfs-list.txt"))
            .incus(
                "[{\"name\": \"tank\", \"driver\": \"zfs\", \"config\": {\"source\": \"sail-raw\","
                    + " \"zfs.pool_name\": \"sail-raw\"}}]");

    assertEquals(
        "/dev/sdi is refused, even with --force-wipe: it is in zpool sail-raw, Incus's pool"
            + " 'tank'.",
        refusal(box, "/dev/sdi", null));
  }

  @Test
  void anExportedPoolsDeviceIsErasedOnlyWhenNamedAgain() throws Exception {
    var box =
        spares()
            .holds("/dev/sdj", DeviceFixtures.listings(PARTITIONED).get("/dev/sdb"))
            .holds("/dev/sdj1", RAID_MEMBER.replace("linux_raid_member", "zfs_member"))
            .zpool(fixture("raw-file-pool.zpool-list.txt"), "/tmp/sail-raw.img");

    assertEquals(
        "/dev/sdj holds gpt, PMBR, zfs_member on /dev/sdj1. Nothing on it is erased unless you"
            + " name it again: add --force-wipe /dev/sdj to erase it and make the pool there.",
        refusal(box, "/dev/sdj", null));
    assertEquals(
        new PoolSource.Device("/dev/sdj", true),
        devices(box.shell()).cleared("/dev/sdj", "/dev/sdj"));
  }

  @Test
  void importedPoolsThatCannotBeListedRefuseTheDevice() {
    var shell =
        gcp(BLANK)
            .shell(
                new ScriptedShellExecutor()
                    .onOk("which zpool")
                    .onFail("zpool list", "no permission"));

    var failed = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb", null));

    assertEquals("Cannot list the imported pools: no permission", failed.getMessage());
  }

  @Test
  void aForceFlagNamingAnotherDeviceIsRefused() {
    assertEquals(
        "--force-wipe names /dev/sdc, not the pool's device /dev/sdb. Name the same device to"
            + " both.",
        refusal(gcp(PARTITIONED), "/dev/sdb", "/dev/sdc"));
  }

  @Test
  void twoSpellingsOfOneDeviceAreOneDevice() throws Exception {
    var byId = "/dev/disk/by-id/google-spare";
    var shell = gcp(PARTITIONED).name(byId, "/dev/sdb").shell();

    assertEquals(new PoolSource.Device("/dev/sdb", true), devices(shell).cleared(byId, "/dev/sdb"));
  }

  @Test
  void namesThatResolveToNothingAreRefused() {
    var unnamed =
        new ScriptedShellExecutor().onFail("realpath", "realpath: /dev/sdz: No such file");
    var unforced =
        gcp(PARTITIONED)
            .shell(
                new ScriptedShellExecutor()
                    .onFail("realpath -e -- /dev/sdq", "realpath: /dev/sdq: No such file"));

    assertEquals(
        "--disk /dev/sdz names no device on this box: realpath: /dev/sdz: No such file.",
        assertThrows(
                IllegalArgumentException.class, () -> devices(unnamed).cleared("/dev/sdz", null))
            .getMessage());
    assertEquals(
        "--force-wipe /dev/sdq names no device on this box: realpath: /dev/sdq: No such file.",
        assertThrows(
                IllegalArgumentException.class,
                () -> devices(unforced).cleared("/dev/sdb", "/dev/sdq"))
            .getMessage());
  }

  @Test
  void aFileIsNotABlockDeviceLsblkLists() {
    assertEquals(
        "/srv/pool.img is not a block device lsblk lists.",
        refusal(gcp(BLANK).name("/srv/pool.img", "/srv/pool.img"), "/srv/pool.img", null));
  }

  @Test
  void signaturesThatCannotBeReadRefuseTheDevice() {
    var shell =
        gcp(BLANK)
            .shell(
                new ScriptedShellExecutor()
                    .onFail(
                        "wipefs -- ", "wipefs: error: /dev/sdb: probing initialization failed"));

    var failed = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb", null));

    assertEquals(
        "Cannot read the signatures on /dev/sdb: wipefs: error: /dev/sdb: probing initialization"
            + " failed",
        failed.getMessage());
  }

  @Test
  void onlySpareWholeDisksAreOffered() throws Exception {
    assertEquals(
        List.of(
            new PoolDevices.Candidate("/dev/sdb", "40G", "PersistentDisk"),
            new PoolDevices.Candidate("/dev/sdc", "40G", "PersistentDisk")),
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

  /** The spares, /dev/sdi's first partition in the imported zpool sail-raw, holding {@code zfs}. */
  private static Box imported(String datasets) {
    return spares()
        .holds("/dev/sdi", DeviceFixtures.listings(PARTITIONED).get("/dev/sdb"))
        .holds("/dev/sdi1", RAID_MEMBER.replace("linux_raid_member", "zfs_member"))
        .zpool(
            fixture("raw-file-pool.zpool-list.txt").replace("/tmp/sail-raw.img", "/dev/sdi1"),
            "/dev/sdi1")
        .datasets("sail-raw", datasets);
  }

  private static String refusal(String layout, String named, String force) {
    return refusal(names(new Box(layout)), named, force);
  }

  private static String refusal(Box box, String named, String force) {
    return assertThrows(
            IllegalArgumentException.class, () -> devices(box.shell()).cleared(named, force))
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
    private final Map<String, String> datasets = new LinkedHashMap<>();
    private String zpools;
    private String vdevs;
    private String incus;

    Box(String layout) {
      this.layout = layout;
    }

    List<String> paths() {
      return PoolDevices.BlockDevice.listed(YamlUtil.parseMap(fixture(layout)).get("blockdevices"))
          .stream()
          .flatMap(PoolDevices.BlockDevice::subtree)
          .map(PoolDevices.BlockDevice::path)
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

    Box zpool(String listed, String resolvedVdevs) {
      zpools = listed;
      vdevs = resolvedVdevs;
      return this;
    }

    Box datasets(String pool, String listed) {
      datasets.put(pool, listed);
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
      if (zpools != null) {
        shell.onOk("which zpool").onOk("zpool list -vHP", zpools).onOk("realpath -m", vdevs + "\n");
      }
      datasets.forEach((pool, listed) -> shell.onOk("zfs list -H -r -o name " + pool, listed));
      if (incus != null) {
        shell.onOk("incus storage list", incus);
      }
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
