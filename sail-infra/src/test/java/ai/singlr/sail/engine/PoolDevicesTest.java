/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PoolDevicesTest {

  private static final String LSBLK =
      "lsblk --json --output PATH,TYPE,FSTYPE,PTTYPE,MOUNTPOINTS,SIZE,MODEL";

  private static final Map<String, Object> OS_DISK =
      dev(
          "/dev/nvme0n1",
          "disk",
          null,
          "gpt",
          List.of(),
          dev("/dev/nvme0n1p1", "part", "vfat", null, List.of("/boot/efi")),
          dev("/dev/nvme0n1p2", "part", "ext4", null, List.of("/")),
          dev("/dev/nvme0n1p3", "part", "swap", null, List.of("[SWAP]")),
          dev("/dev/nvme0n1p5", "part", null, null, List.of()));

  private static final Map<String, Object> SPARE_DISK =
      dev("/dev/sdb", "disk", null, null, List.of());

  private static final Map<String, Object> EXT4_DISK =
      dev("/dev/sdc", "disk", "ext4", null, List.of());

  private static final Map<String, Object> RAID_DISK =
      dev(
          "/dev/sdd",
          "disk",
          null,
          "gpt",
          List.of(),
          dev(
              "/dev/sdd1",
              "part",
              "linux_raid_member",
              null,
              List.of(),
              dev("/dev/md0", "raid1", "ext4", null, List.of("/srv"))));

  private static final Map<String, Object> PV_DISK =
      dev(
          "/dev/sde",
          "disk",
          "LVM2_member",
          null,
          List.of(),
          dev("/dev/mapper/vg-data", "lvm", "xfs", null, List.of()));

  private static final Map<String, Object> MOUNTED_DISK =
      dev("/dev/sdf", "disk", "xfs", null, List.of("/mnt/data", "/srv/data"));

  private static final Map<String, Object> LUKS_DISK =
      dev(
          "/dev/sdg",
          "disk",
          "crypto_LUKS",
          null,
          List.of(),
          dev("/dev/mapper/secret", "crypt", null, null, List.of()));

  private static final Map<String, Object> PARTITIONED_DISK =
      dev(
          "/dev/sdh",
          "disk",
          null,
          "gpt",
          List.of(),
          dev("/dev/sdh1", "part", "ext4", null, List.of()),
          dev("/dev/sdh2", "part", null, null, List.of()));

  private static final Map<String, Object> LOOP = dev("/dev/loop3", "loop", null, null, List.of());

  @Test
  void anUnusedDiskIsClearedAsItsResolvedPathWithNothingToWipe() throws Exception {
    var shell = box(Map.of("/dev/sdb", "/dev/sdb"), Map.of());

    assertEquals(
        new PoolSource.Device("/dev/sdb", false), devices(shell).cleared("/dev/sdb", null));
    assertEquals(
        List.of(
            "realpath -e -- /dev/sdb",
            LSBLK + " -- /dev/sdb",
            "wipefs --no-act --json -- /dev/sdb"),
        shell.invocations());
  }

  @Test
  void aSparePartitionOfTheOsDiskIsCleared() throws Exception {
    var shell = box(Map.of("/dev/nvme0n1p5", "/dev/nvme0n1p5"), Map.of());

    assertEquals(
        new PoolSource.Device("/dev/nvme0n1p5", false),
        devices(shell).cleared("/dev/nvme0n1p5", null));
  }

  @Test
  void aDiskThatHoldsAFilesystemIsRefusedNamingWhatIsOnIt() {
    var shell = box(Map.of("/dev/sdc", "/dev/sdc"), Map.of("/dev/sdc", List.of("ext4")));

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdc", null));

    assertEquals(
        "/dev/sdc holds ext4. Nothing on it is erased unless you name it again: add --force-wipe"
            + " /dev/sdc to erase it and make the pool there.",
        refused.getMessage());
  }

  @Test
  void theSameDiskNamedAgainWithTheForceFlagIsClearedToBeWiped() throws Exception {
    var shell = box(Map.of("/dev/sdc", "/dev/sdc"), Map.of("/dev/sdc", List.of("ext4")));

    assertEquals(
        new PoolSource.Device("/dev/sdc", true), devices(shell).cleared("/dev/sdc", "/dev/sdc"));
  }

  @Test
  void aForceFlagNamingAnotherDeviceIsRefused() {
    var shell =
        box(
            Map.of("/dev/sdc", "/dev/sdc", "/dev/sdb", "/dev/sdb"),
            Map.of("/dev/sdc", List.of("ext4")));

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdc", "/dev/sdb"));

    assertEquals(
        "--force-wipe names /dev/sdb, not the pool's device /dev/sdc. Name the same device to"
            + " both.",
        refused.getMessage());
  }

  @Test
  void twoSpellingsOfOneDeviceAreOneDevice() throws Exception {
    var byId = "/dev/disk/by-id/ata-SPARE_123";
    var shell =
        box(Map.of(byId, "/dev/sdc", "/dev/sdc", "/dev/sdc"), Map.of("/dev/sdc", List.of("ext4")));

    assertEquals(new PoolSource.Device("/dev/sdc", true), devices(shell).cleared(byId, "/dev/sdc"));
  }

  @Test
  void aSymlinkToTheOsDiskIsJudgedAsTheOsDiskAndRefusedEvenWithTheForceFlag() {
    var link = "/dev/disk/by-id/nvme-SYSTEM_1";
    var shell = box(Map.of(link, "/dev/nvme0n1"), Map.of());

    var refused =
        assertThrows(IllegalArgumentException.class, () -> devices(shell).cleared(link, link));

    assertEquals(
        "/dev/nvme0n1 (/dev/disk/by-id/nvme-SYSTEM_1) is in use, so no pool is made on it, even"
            + " with --force-wipe: /dev/nvme0n1p1 is mounted at /boot/efi, the running system;"
            + " /dev/nvme0n1p2 is mounted at /, the running system; /dev/nvme0n1p3 is in use as"
            + " swap.",
        refused.getMessage());
  }

  @Test
  void aPartitionOfTheOsDiskThatTheSystemRunsFromIsRefused() {
    var shell = box(Map.of("/dev/nvme0n1p2", "/dev/nvme0n1p2"), Map.of());

    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> devices(shell).cleared("/dev/nvme0n1p2", "/dev/nvme0n1p2"));

    assertEquals(
        "/dev/nvme0n1p2 is in use, so no pool is made on it, even with --force-wipe:"
            + " /dev/nvme0n1p2 is mounted at /, the running system.",
        refused.getMessage());
  }

  @Test
  void aRaidMemberIsRefusedEvenWithTheForceFlag() {
    var shell = box(Map.of("/dev/sdd", "/dev/sdd", "/dev/sdd1", "/dev/sdd1"), Map.of());

    var disk =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdd", "/dev/sdd"));
    var member =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdd1", "/dev/sdd1"));

    assertEquals(
        "/dev/sdd is in use, so no pool is made on it, even with --force-wipe: /dev/sdd1 is a RAID"
            + " member; /dev/md0 is mounted at /srv; /dev/md0 (raid1) is built on it.",
        disk.getMessage());
    assertEquals(
        "/dev/sdd1 is in use, so no pool is made on it, even with --force-wipe: /dev/sdd1 is a"
            + " RAID member; /dev/md0 is mounted at /srv; /dev/md0 (raid1) is built on it.",
        member.getMessage());
  }

  @Test
  void aRaidMemberOfNoRunningArrayAndAnIdleLvmVolumeAreRefusedEvenWithTheForceFlag()
      throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk("realpath -e -- /dev/sdj", "/dev/sdj\n")
            .onOk("realpath -e -- /dev/sdk", "/dev/sdk\n")
            .onOk(
                LSBLK + " -- /dev/sdj",
                lsblk(dev("/dev/sdj", "disk", "linux_raid_member", null, List.of())))
            .onOk(
                LSBLK + " -- /dev/sdk",
                lsblk(dev("/dev/sdk", "disk", "LVM2_member", null, List.of())));

    var member =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdj", "/dev/sdj"));
    var volume =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdk", "/dev/sdk"));

    assertEquals(
        "/dev/sdj is in use, so no pool is made on it, even with --force-wipe: /dev/sdj is a RAID"
            + " member.",
        member.getMessage());
    assertEquals(
        "/dev/sdk is in use, so no pool is made on it, even with --force-wipe: /dev/sdk is an LVM"
            + " physical volume.",
        volume.getMessage());
  }

  @Test
  void anLvmPhysicalVolumeIsRefusedEvenWithTheForceFlag() {
    var shell = box(Map.of("/dev/sde", "/dev/sde"), Map.of());

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sde", "/dev/sde"));

    assertEquals(
        "/dev/sde is in use, so no pool is made on it, even with --force-wipe: /dev/sde is an LVM"
            + " physical volume; /dev/mapper/vg-data (lvm) is built on it.",
        refused.getMessage());
  }

  @Test
  void aMountedFilesystemIsRefusedEvenWithTheForceFlagNamingEachMount() {
    var shell = box(Map.of("/dev/sdf", "/dev/sdf"), Map.of());

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdf", "/dev/sdf"));

    assertEquals(
        "/dev/sdf is in use, so no pool is made on it, even with --force-wipe: /dev/sdf is mounted"
            + " at /mnt/data; /dev/sdf is mounted at /srv/data.",
        refused.getMessage());
  }

  @Test
  void anOpenEncryptedVolumeIsRefusedEvenWithTheForceFlag() {
    var shell = box(Map.of("/dev/sdg", "/dev/sdg"), Map.of());

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdg", "/dev/sdg"));

    assertEquals(
        "/dev/sdg is in use, so no pool is made on it, even with --force-wipe: /dev/mapper/secret"
            + " (crypt) is built on it.",
        refused.getMessage());
  }

  @Test
  void aDeviceThatIsNeitherADiskNorAPartitionIsRefused() {
    var shell = box(Map.of("/dev/loop3", "/dev/loop3"), Map.of());

    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> devices(shell).cleared("/dev/loop3", "/dev/loop3"));

    assertEquals(
        "/dev/loop3 is in use, so no pool is made on it, even with --force-wipe: it is a loop"
            + " device, not a disk or a partition.",
        refused.getMessage());
  }

  @Test
  void aPartitionedDiskNamesItsTableAndEachFilesystemOnItsPartitions() throws Exception {
    var shell =
        box(Map.of("/dev/sdh", "/dev/sdh"), Map.of("/dev/sdh", List.of("gpt", "PMBR", "gpt")));

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdh", null));

    assertEquals(
        "/dev/sdh holds gpt, PMBR, ext4 on /dev/sdh1. Nothing on it is erased unless you name it"
            + " again: add --force-wipe /dev/sdh to erase it and make the pool there.",
        refused.getMessage());
    assertEquals(
        new PoolSource.Device("/dev/sdh", true), devices(shell).cleared("/dev/sdh", "/dev/sdh"));
  }

  @Test
  void aNameThatResolvesToNothingIsRefused() {
    var shell = new ScriptedShellExecutor().onFail("realpath", "realpath: /dev/sdz: No such file");

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdz", null));

    assertEquals(
        "--disk /dev/sdz names no device on this box: realpath: /dev/sdz: No such file.",
        refused.getMessage());
  }

  @Test
  void aForceFlagThatResolvesToNothingIsRefused() {
    var shell =
        box(Map.of("/dev/sdc", "/dev/sdc"), Map.of("/dev/sdc", List.of("ext4")))
            .onFail("realpath -e -- /dev/sdq", "realpath: /dev/sdq: No such file");

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/dev/sdc", "/dev/sdq"));

    assertEquals(
        "--force-wipe /dev/sdq names no device on this box: realpath: /dev/sdq: No such file.",
        refused.getMessage());
  }

  @Test
  void aFileIsNotABlockDevice() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("realpath", "/srv/pool.img\n")
            .on(
                LSBLK,
                new ShellExec.Result(
                    32, "{\"blockdevices\": []}", "lsblk: /srv/pool.img: not a block device"));

    var refused =
        assertThrows(
            IllegalArgumentException.class, () -> devices(shell).cleared("/srv/pool.img", null));

    assertEquals(
        "/srv/pool.img is not a block device: lsblk: /srv/pool.img: not a block device.",
        refused.getMessage());
  }

  @Test
  void signaturesThatCannotBeReadRefuseTheDevice() {
    var shell =
        new ScriptedShellExecutor()
            .onOk("realpath", "/dev/sdb\n")
            .onOk(LSBLK, lsblk(SPARE_DISK))
            .onFail("wipefs", "wipefs: error: /dev/sdb: probing initialization failed");

    var failed = assertThrows(IOException.class, () -> devices(shell).cleared("/dev/sdb", null));

    assertEquals(
        "Cannot read the signatures on /dev/sdb: wipefs: error: /dev/sdb: probing initialization"
            + " failed",
        failed.getMessage());
  }

  @Test
  void onlyUnusedWholeDisksAreOffered() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                LSBLK,
                lsblk(
                    OS_DISK,
                    SPARE_DISK,
                    EXT4_DISK,
                    RAID_DISK,
                    PV_DISK,
                    MOUNTED_DISK,
                    LUKS_DISK,
                    PARTITIONED_DISK,
                    LOOP,
                    dev(
                        "/dev/sdi",
                        "disk",
                        null,
                        "gpt",
                        List.of(),
                        dev("/dev/sdi1", "part", null, null, List.of())),
                    dev("/dev/nvme1n1", "disk", null, null, List.of(), "3.5T", "KIOXIA  ")));

    assertEquals(
        List.of(
            new PoolDevices.Candidate("/dev/sdb", "100G", null),
            new PoolDevices.Candidate("/dev/nvme1n1", "3.5T", "KIOXIA")),
        devices(shell).candidates());
    assertEquals(List.of(LSBLK), shell.invocations());
  }

  @Test
  void aBoxLsblkCannotReadOffersNothingAndSaysWhy() {
    var shell = new ScriptedShellExecutor().onFail(LSBLK, "lsblk: permission denied");

    var failed = assertThrows(IOException.class, () -> devices(shell).candidates());

    assertEquals("lsblk failed: lsblk: permission denied", failed.getMessage());
  }

  @Test
  void aBoxWithNoDisksOffersNone() throws Exception {
    var shell = new ScriptedShellExecutor().onOk(LSBLK, "");

    assertEquals(List.of(), devices(shell).candidates());
  }

  private static PoolDevices devices(ShellExec shell) {
    return new PoolDevices(shell);
  }

  /**
   * A box holding every fixture device, where each name in {@code names} resolves to its path and
   * each path's wipefs reports {@code signatures}, none when it has no entry. Longer spellings are
   * scripted first, since the shell matches a command by what it contains.
   */
  private static ScriptedShellExecutor box(
      Map<String, String> names, Map<String, List<String>> signatures) {
    var shell = new ScriptedShellExecutor();
    var fixtures =
        List.of(
            OS_DISK,
            SPARE_DISK,
            EXT4_DISK,
            RAID_DISK,
            PV_DISK,
            MOUNTED_DISK,
            LUKS_DISK,
            PARTITIONED_DISK,
            LOOP);
    longestFirst(names.keySet())
        .forEach(name -> shell.onOk("realpath -e -- " + name, names.get(name) + "\n"));
    var nodes = new ArrayList<Map<String, Object>>();
    fixtures.forEach(fixture -> collect(fixture, nodes));
    nodes.sort(Comparator.comparing((Map<String, Object> node) -> path(node).length()).reversed());
    for (var node : nodes) {
      shell.onOk(LSBLK + " -- " + path(node), lsblk(node));
      var found = signatures.getOrDefault(path(node), List.of());
      shell.onOk("wipefs --no-act --json -- " + path(node), wipefs(path(node), found));
    }
    return shell;
  }

  private static List<String> longestFirst(Set<String> names) {
    return names.stream().sorted(Comparator.comparing(String::length).reversed()).toList();
  }

  @SuppressWarnings("unchecked")
  private static void collect(Map<String, Object> node, List<Map<String, Object>> into) {
    into.add(node);
    ((List<Map<String, Object>>) node.getOrDefault("children", List.of()))
        .forEach(child -> collect(child, into));
  }

  private static String path(Map<String, Object> node) {
    return (String) node.get("path");
  }

  @SafeVarargs
  private static Map<String, Object> dev(
      String path,
      String type,
      String fstype,
      String pttype,
      List<String> mounts,
      Map<String, Object>... children) {
    return dev(path, type, fstype, pttype, mounts, "100G", null, children);
  }

  @SafeVarargs
  private static Map<String, Object> dev(
      String path,
      String type,
      String fstype,
      String pttype,
      List<String> mounts,
      String size,
      String model,
      Map<String, Object>... children) {
    var node = new LinkedHashMap<String, Object>();
    node.put("path", path);
    node.put("type", type);
    node.put("fstype", fstype);
    node.put("pttype", pttype);
    var mountpoints = new ArrayList<Object>(mounts);
    if (mountpoints.isEmpty()) {
      mountpoints.add(null);
    }
    node.put("mountpoints", mountpoints);
    node.put("size", size);
    node.put("model", model);
    if (children.length > 0) {
      node.put("children", List.of(children));
    }
    return node;
  }

  @SafeVarargs
  private static String lsblk(Map<String, Object>... devices) {
    return YamlUtil.dumpJson(Map.of("blockdevices", List.of(devices)));
  }

  private static String wipefs(String path, List<String> types) {
    if (types.isEmpty()) {
      return "";
    }
    var signatures =
        types.stream()
            .map(type -> Map.<String, Object>of("device", path.substring(5), "type", type))
            .toList();
    return YamlUtil.dumpJson(Map.of("signatures", signatures));
  }
}
