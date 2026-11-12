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
 * Every device here is one real lsblk printed (this devbox, a GitHub runner) or the one
 * hand-written layout of spare disks, kept in the captured format ({@code devices/README.txt}).
 * Probe answers are the runner's captured wipefs and zpool output, or hand-written in their shape
 * where a machine at hand holds no such device.
 */
class PoolDevicesTest {

  private static final String LSBLK = DeviceFixtures.LSBLK;
  private static final String DEVBOX = "devbox-raid1-nvme.lsblk.json";
  private static final String RUNNER = "github-runner.lsblk.json";
  private static final String SPARES = DeviceFixtures.SPARES;
  private static final String EMPTY = fixture("github-runner-sda14.wipefs.json");
  private static final String EXT4 = fixture("github-runner-sda1.wipefs.json");
  private static final String GPT = fixture("github-runner-sda.wipefs.json");
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
  private static final List<String> RUNNER_DEVICES =
      List.of("/dev/sda", "/dev/sda1", "/dev/sda14", "/dev/sda15", "/dev/sda16");

  @Test
  void theDeviceTreeIsAskedForExplicitly() {
    assertEquals(
        List.of(
            "lsblk",
            "--json",
            "--tree",
            "--bytes",
            "--output",
            "NAME,PATH,TYPE,FSTYPE,PTTYPE,MOUNTPOINTS,SIZE,MODEL,RM"),
        PoolDevices.LSBLK);
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
        "/dev/nvme0n1p2 is refused, even with --force-wipe: it is a partition of /dev/nvme0n1,"
            + " which carries the running system (/dev/md2 is mounted at /, the running system); /dev/md0"
            + " (raid1) is built on it.",
        refusal(DEVBOX, "/dev/nvme0n1p2", null));
    assertEquals(
        "/dev/md0 is refused, even with --force-wipe: it is a raid1 device, not a disk or a"
            + " partition; it is a partition of /dev/nvme0n1, which carries the running system"
            + " (/dev/md2 is mounted at /, the running system).",
        refusal(DEVBOX, "/dev/md0", "/dev/md0"));
    assertEquals(
        "/dev/nvme0n1p4 is refused, even with --force-wipe: /dev/md2 is mounted at"
            + " /var/lib/sail/run; /dev/md2 is mounted at /dev/.incus-systemd-credentials; /dev/md2"
            + " is mounted at /, the running system; /dev/md2 (raid1) is built on it.",
        refusal(DEVBOX, "/dev/nvme0n1p4", null));
  }

  @Test
  void everyDeviceOfTheRunnerIsRefusedWithAndWithoutTheForceFlag() {
    for (var device : RUNNER_DEVICES) {
      for (var force : new String[] {null, device}) {
        assertTrue(
            refusal(RUNNER, device, force)
                .startsWith(device + " is refused, even with --force-wipe: "),
            device);
      }
    }
    assertEquals(
        "/dev/sda14 is refused, even with --force-wipe: it is a partition of /dev/sda, which"
            + " carries the running system (/dev/sda1 is mounted at /, the running system).",
        refusal(RUNNER, "/dev/sda14", "/dev/sda14"));
    assertEquals(
        "/dev/sda (/dev/disk/by-id/scsi-root) is refused, even with --force-wipe: /dev/sda1 is"
            + " mounted at /, the running system; /dev/sda15 is mounted at /boot/efi, the running"
            + " system; /dev/sda16 is mounted at /boot, the running system.",
        refusal(RUNNER, "/dev/disk/by-id/scsi-root", "/dev/sda", "/dev/sda"));
  }

  @Test
  void aFlatListFromLsblkIsUnreadableAndRefused() {
    for (var flat : List.of("devbox-flat.lsblk.json", "github-runner-flat.lsblk.json")) {
      var shell = new Box(flat).name("/dev/sdb", "/dev/sdb").shell();

      var cleared =
          assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb", "/dev/sdb"));
      var offered = assertThrows(IOException.class, () -> devices(shell).candidates());

      assertEquals(
          "lsblk printed a flat list, not a device tree, so what is built on a device cannot be"
              + " read; nothing was wiped.",
          cleared.getMessage());
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
  void anUnusedSpareDiskIsClearedAfterEveryProbe() throws Exception {
    var shell = spares().shell();

    assertEquals(
        new PoolSource.Device("/dev/sdb", false), devices(shell).cleared("/dev/sdb", null));
    assertEquals(
        List.of(
            "realpath -e -- /dev/sdb",
            LSBLK,
            "wipefs --no-act --json -- /dev/sdb",
            "which zpool",
            "wipefs --all --no-act --types sail_exclusive_probe -- /dev/sdb"),
        shell.invocations());
  }

  @Test
  void aDiskThatHoldsAFilesystemIsRefusedNamingItAndClearedWhenNamedAgain() throws Exception {
    var refused = refusal(spares().holds("/dev/sdc", EXT4), "/dev/sdc", null);
    var shell = spares().holds("/dev/sdc", EXT4).shell();

    assertEquals(
        "/dev/sdc holds ext4. Nothing on it is erased unless you name it again: add --force-wipe"
            + " /dev/sdc to erase it and make the pool there.",
        refused);
    assertEquals(
        new PoolSource.Device("/dev/sdc", true), devices(shell).cleared("/dev/sdc", "/dev/sdc"));
  }

  @Test
  void aPartitionedDiskNamesItsTableAndWhatItsPartitionsHold() throws Exception {
    var box = spares().holds("/dev/sdd", GPT).holds("/dev/sdd1", EXT4);

    assertEquals(
        "/dev/sdd holds gpt, PMBR, ext4 on /dev/sdd1. Nothing on it is erased unless you name it"
            + " again: add --force-wipe /dev/sdd to erase it and make the pool there.",
        refusal(box, "/dev/sdd", null));
    assertEquals(
        new PoolSource.Device("/dev/sdd", true),
        devices(box.shell()).cleared("/dev/sdd", "/dev/sdd"));
  }

  @Test
  void aPartitionBesideSwapInUseIsRefusedEvenWithTheForceFlag() {
    assertEquals(
        "/dev/sdl2 is refused, even with --force-wipe: it is a partition of /dev/sdl, which"
            + " carries the running system (/dev/sdl1 is in use as swap).",
        refusal(spares().name("/dev/sdl2", "/dev/sdl2"), "/dev/sdl2", "/dev/sdl2"));
  }

  @Test
  void aSparePartitionIsCleared() throws Exception {
    assertEquals(
        new PoolSource.Device("/dev/sdd1", false),
        devices(spares().shell()).cleared("/dev/sdd1", null));
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
  }

  @Test
  void aMemberLsblkDoesNotKnowIsRefusedOnWhatItsProbeFinds() {
    var members =
        Map.of(
            "linux_raid_member",
            "/dev/sdh is refused, even with --force-wipe: /dev/sdh is a RAID member: stop its array"
                + " and clear it with mdadm --zero-superblock /dev/sdh yourself.",
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
                    spares().holds("/dev/sdh", signatures("sdh", type)), "/dev/sdh", "/dev/sdh")));
  }

  @Test
  void aDeviceAnotherHolderHasOpenIsRefusedEvenWithTheForceFlag() {
    var box = spares().busy("/dev/sdk");

    assertEquals(
        "/dev/sdk is refused, even with --force-wipe: /dev/sdk is held open by something lsblk"
            + " does not show.",
        refusal(box, "/dev/sdk", "/dev/sdk"));
  }

  @Test
  void anExclusiveOpenThatFailsOtherwiseRefusesTheDevice() {
    var shell =
        new Box(SPARES)
            .name("/dev/sdb", "/dev/sdb")
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
    var box = imported("raw-file-pool.zfs-list.txt");

    assertEquals(new PoolSource.Zpool("sail-raw"), devices(box.shell()).cleared("/dev/sdi", null));
    assertEquals(
        "/dev/sdi is refused, even with --force-wipe: it is in the imported zpool sail-raw, which"
            + " is never wiped: drop --force-wipe to hand the empty pool to Incus, or destroy the"
            + " pool yourself.",
        refusal(imported("raw-file-pool.zfs-list.txt"), "/dev/sdi", "/dev/sdi"));
  }

  @Test
  void aDeviceInTwoImportedPoolsIsRefused() {
    var pools =
        fixture("raw-file-pool.zpool-list.txt").replace("/tmp/sail-raw.img", "/dev/sdi1")
            + fixture("raw-file-pool.zpool-list.txt")
                .replace("sail-raw", "other")
                .replace("/tmp/other.img", "/dev/sdi9");
    var box = spares().holds("/dev/sdi", GPT).zpool(pools, "/dev/sdi1\n/dev/sdi9");

    assertEquals(
        "/dev/sdi is refused, even with --force-wipe: it holds devices of the imported pools"
            + " sail-raw, other.",
        refusal(box, "/dev/sdi", null));
  }

  @Test
  void anImportedPoolThatHoldsDatasetsIsRefusedNamingThem() {
    var box =
        imported(
            "raw-file-pool.zfs-list.txt",
            fixture("incus-file-pool.zfs-list.txt").replace("sail-capture", "sail-raw"));

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
        imported("raw-file-pool.zfs-list.txt")
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
            .holds("/dev/sdj", GPT)
            .holds("/dev/sdj1", signatures("sdj1", "zfs_member"))
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
        spares()
            .shell(
                new ScriptedShellExecutor()
                    .onOk("which zpool")
                    .onFail("zpool list", "no permission"));

    var failed = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb", null));

    assertEquals("Cannot list the imported pools: no permission", failed.getMessage());
  }

  @Test
  void aForceFlagNamingAnotherDeviceIsRefused() {
    var box = spares().name("/dev/sdb", "/dev/sdb").holds("/dev/sdc", EXT4);

    assertEquals(
        "--force-wipe names /dev/sdb, not the pool's device /dev/sdc. Name the same device to"
            + " both.",
        refusal(box, "/dev/sdc", "/dev/sdb"));
  }

  @Test
  void twoSpellingsOfOneDeviceAreOneDevice() throws Exception {
    var byId = "/dev/disk/by-id/scsi-SPARE_123";
    var shell = spares().name(byId, "/dev/sdc").holds("/dev/sdc", EXT4).shell();

    assertEquals(new PoolSource.Device("/dev/sdc", true), devices(shell).cleared(byId, "/dev/sdc"));
  }

  @Test
  void namesThatResolveToNothingAreRefused() {
    var unnamed =
        new ScriptedShellExecutor().onFail("realpath", "realpath: /dev/sdz: No such file");
    var unforced =
        spares()
            .holds("/dev/sdc", EXT4)
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
                () -> devices(unforced).cleared("/dev/sdc", "/dev/sdq"))
            .getMessage());
  }

  @Test
  void aFileIsNotABlockDeviceLsblkLists() {
    assertEquals(
        "/srv/pool.img is not a block device lsblk lists.",
        refusal(spares().name("/srv/pool.img", "/srv/pool.img"), "/srv/pool.img", null));
  }

  @Test
  void signaturesThatCannotBeReadRefuseTheDevice() {
    var shell =
        spares()
            .shell(
                new ScriptedShellExecutor()
                    .onFail(
                        "wipefs --no-act --json",
                        "wipefs: error: /dev/sdb: probing initialization failed"));

    var failed = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb", null));

    assertEquals(
        "Cannot read the signatures on /dev/sdb: wipefs: error: /dev/sdb: probing initialization"
            + " failed",
        failed.getMessage());
  }

  @Test
  void onlySpareWholeDisksAreOffered() throws Exception {
    var shell = new ScriptedShellExecutor().onOk(LSBLK, fixture(SPARES));

    assertEquals(
        List.of("/dev/sdb", "/dev/sdh", "/dev/sdk"),
        devices(shell).candidates().stream().map(PoolDevices.Candidate::device).toList());
    assertEquals(
        new PoolDevices.Candidate("/dev/sdb", "100.0 GiB", "Virtual Disk"),
        devices(shell).candidates().getFirst());
    assertEquals(List.of(), devices(new Box(DEVBOX).shell()).candidates());
    assertEquals(List.of(), devices(new Box(RUNNER).shell()).candidates());
  }

  private static PoolDevices devices(ShellExec shell) {
    return new PoolDevices(shell);
  }

  private static Box spares() {
    var box = new Box(SPARES);
    for (var device :
        List.of(
            "/dev/sdb",
            "/dev/sdc",
            "/dev/sdd",
            "/dev/sdd1",
            "/dev/sde",
            "/dev/sdf",
            "/dev/sdg",
            "/dev/sdh",
            "/dev/sdi",
            "/dev/sdj",
            "/dev/sdk",
            "/dev/zram0",
            "/dev/nbd0")) {
      box.name(device, device);
    }
    return box;
  }

  /** The spares, /dev/sdi's first partition in the imported zpool sail-raw, holding {@code zfs}. */
  private static Box imported(String zfs) {
    return imported(zfs, fixture(zfs));
  }

  private static Box imported(String zfs, String datasets) {
    return spares()
        .holds("/dev/sdi", GPT)
        .holds("/dev/sdi1", signatures("sdi1", "zfs_member"))
        .zpool(
            fixture("raw-file-pool.zpool-list.txt").replace("/tmp/sail-raw.img", "/dev/sdi1"),
            "/dev/sdi1")
        .datasets("sail-raw", datasets);
  }

  private static String refusal(String layout, String named, String force) {
    return refusal(new Box(layout).name(named, named), named, force);
  }

  private static String refusal(String layout, String named, String path, String force) {
    return refusal(new Box(layout).name(named, path).name(force, force), named, force);
  }

  private static String refusal(Box box, String named, String force) {
    return assertThrows(
            IllegalArgumentException.class, () -> devices(box.shell()).cleared(named, force))
        .getMessage();
  }

  /** {@code wipefs --json} as the runner printed it, reporting {@code type} on {@code device}. */
  private static String signatures(String device, String type) {
    return EMPTY.replace(
        "[\n\n   ]",
        "[\n      {\n         \"device\": \""
            + device
            + "\",\n         \"offset\": \"0x1000\",\n         \"type\": \""
            + type
            + "\",\n         \"uuid\": null,\n         \"label\": null\n      }\n   ]");
  }

  private static String fixture(String name) {
    return DeviceFixtures.read(name);
  }

  /**
   * A box whose lsblk prints {@code layout}: each name resolves as given, each device's probes
   * answer as scripted (empty signatures, free to open, no ZFS tools unless a pool is listed), and
   * a test's own scripts, passed to {@link #shell(ScriptedShellExecutor)}, come first.
   */
  private static final class Box {
    private final String layout;
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, String> signatures = new LinkedHashMap<>();
    private final List<String> busy = new ArrayList<>();
    private final Map<String, String> datasets = new LinkedHashMap<>();
    private String zpools;
    private String vdev;
    private String incus;

    Box(String layout) {
      this.layout = layout;
    }

    Box name(String named, String path) {
      if (named != null) {
        names.put(named, path);
      }
      return this;
    }

    Box holds(String path, String wipefs) {
      signatures.put(path, wipefs);
      return this;
    }

    Box busy(String path) {
      busy.add(path);
      return this;
    }

    Box zpool(String listed, String resolvedVdev) {
      zpools = listed;
      vdev = resolvedVdev;
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
      var paths = new ArrayList<>(signatures.keySet());
      PoolDevices.BlockDevice.listed(YamlUtil.parseMap(fixture(layout)).get("blockdevices"))
          .stream()
          .flatMap(PoolDevices.BlockDevice::subtree)
          .forEach(device -> paths.add(device.path()));
      for (var path : longestFirst(paths)) {
        shell.onOk("wipefs --no-act --json -- " + path, signatures.getOrDefault(path, EMPTY));
        var probe = "wipefs --all --no-act --types " + PoolDevices.NOTHING + " -- " + path;
        if (busy.contains(path)) {
          shell.onFail(
              probe,
              "wipefs: error: "
                  + path
                  + ": probing initialization failed: Device or resource busy");
        } else {
          shell.onOk(probe);
        }
      }
      if (zpools != null) {
        shell.onOk("which zpool").onOk("zpool list -vHP", zpools).onOk("realpath -m", vdev + "\n");
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
