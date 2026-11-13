/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IncusStorageTest {

  @Test
  void eachSourceIsOneIncusStorageCreate() {
    assertEquals(
        List.of("incus", "storage", "create", "devpool", "dir"),
        IncusStorage.createCommand("devpool", new PoolSource.Dir()));
    assertEquals(
        List.of("incus", "storage", "create", "tank", "zfs", "source=/dev/sdb"),
        IncusStorage.createCommand("tank", new PoolSource.Device("/dev/sdb", "id")));
    assertEquals(
        List.of("incus", "storage", "create", "tank", "zfs", "size=51200MiB"),
        IncusStorage.createCommand("tank", new PoolSource.File(51200)));
  }

  @Test
  void aMirrorIsOneCreateOfIncussMirrorSourceAndNeverForced() {
    var mirror =
        new PoolSource.Mirror(
            new PoolSource.Device("/dev/disk/by-id/wwn-0x5000c500a1b2c3d4-part5", "a"),
            new PoolSource.Device("/dev/disk/by-id/wwn-0x5000c500e5f6a7b8-part5", "b"),
            1L << 40,
            1L << 40);

    var create = IncusStorage.createCommand("devpool", mirror);

    assertEquals(
        List.of(
            "incus",
            "storage",
            "create",
            "devpool",
            "zfs",
            "source=mirror=/dev/disk/by-id/wwn-0x5000c500a1b2c3d4-part5,"
                + "/dev/disk/by-id/wwn-0x5000c500e5f6a7b8-part5"),
        create);
    assertTrue(create.stream().noneMatch(word -> word.contains("wipe")), create.toString());
    assertEquals(
        "zfs on a mirror of /dev/disk/by-id/wwn-0x5000c500a1b2c3d4-part5 and"
            + " /dev/disk/by-id/wwn-0x5000c500e5f6a7b8-part5",
        mirror.said());
  }

  @Test
  void aSourceIsReadAsTheDevicesItNamesAndSaidAsTheOperatorReadsIt() {
    assertEquals(
        List.of("/dev/sdb1", "/dev/sdc1"), IncusStorage.devices("mirror=/dev/sdb1,/dev/sdc1"));
    assertEquals(List.of("/dev/sdb"), IncusStorage.devices("/dev/sdb"));
    assertEquals(List.of(), IncusStorage.devices("tank"));
    assertEquals(
        "a mirror of /dev/sdb1 and /dev/sdc1", IncusStorage.said("mirror=/dev/sdb1,/dev/sdc1"));
    assertEquals("/dev/sdb", IncusStorage.said("/dev/sdb"));
  }

  @Test
  void aDevicePathIncusWouldSplitIsNoMirror() {
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new PoolSource.Mirror(
                    new PoolSource.Device("/dev/disk/by-id/odd,name", "a"),
                    new PoolSource.Device("/dev/sdc", "b"),
                    1,
                    1));

    assertEquals(
        "/dev/disk/by-id/odd,name holds ',' or '=', which Incus reads as separators in a mirror's"
            + " source, so Sail makes no pool from it.",
        refused.getMessage());
  }

  @Test
  void anIncusWithoutZfsVdevsIsRefusedBeforeAMirrorIsAsked() throws Exception {
    new IncusStorage(
            new ScriptedShellExecutor()
                .onOk("incus query /1.0", "{\"api_extensions\": [\"storage_zfs_vdev\"]}"))
        .requireMirrors();
    var old =
        new ScriptedShellExecutor()
            .onOk("incus query /1.0", "{\"api_extensions\": [\"storage_zfs_clone_copy\"]}");

    var refused =
        assertThrows(IllegalStateException.class, () -> new IncusStorage(old).requireMirrors());

    assertEquals(
        "This box's Incus cannot make a mirrored pool: it lacks the storage_zfs_vdev API"
            + " extension, which Incus has from 6.0.1 on. Nothing was written. Install Incus from"
            + " Zabbly's stable repository, as host init does on a box without it, then run this"
            + " again.",
        refused.getMessage());
    assertTrue(
        assertThrows(
                IllegalStateException.class,
                () -> new IncusStorage(new ScriptedShellExecutor()).requireMirrors())
            .getMessage()
            .contains("(incus query /1.0: command not found)"));
  }

  @Test
  void aPoolsStatusIsIncussOwnAndOnlyACreatedPoolIsTakenAsMade() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus storage list --format json",
                """
                [{"name": "tank", "driver": "zfs", "status": "Pending",
                  "config": {"source": "/dev/sdb", "zfs.pool_name": "tank"}},
                 {"name": "devpool", "driver": "dir", "status": "Created", "config": {}}]
                """);

    var pools = new IncusStorage(shell).pools();

    assertEquals(
        List.of("Pending", "Created"), pools.stream().map(IncusStorage.Pool::status).toList());
    pools.getLast().requireFinished();
    assertTrue(
        assertThrows(IllegalStateException.class, () -> pools.getFirst().requireFinished())
            .getMessage()
            .startsWith("Incus's pool 'tank' is Pending, not Created: "));
  }

  @Test
  void aZpoolIsNeverMadeIntoAPool() {
    var refused =
        assertThrows(
            IllegalStateException.class,
            () -> IncusStorage.createCommand("devpool", new PoolSource.Zpool("tank")));

    assertEquals(
        "Incus's pool 'devpool' on zpool tank is gone since this run began, and Sail makes no pool"
            + " from a zpool it did not make: run this command again.",
        refused.getMessage());
  }

  @Test
  void eachSourceIsSaidAsTheOperatorReadsIt() {
    assertEquals("dir", new PoolSource.Dir().said());
    assertEquals(
        "zfs on /dev/sdb, one device: no redundancy",
        new PoolSource.Device("/dev/sdb", "id").said());
    assertEquals("zfs in a 50.0 GiB file", new PoolSource.File(51200).said());
    assertEquals("zpool devpool as it is", new PoolSource.Zpool("devpool").said());
  }

  @Test
  void createRunsTheCommandAndAnswersAsIncusDid() throws Exception {
    var shell = new ScriptedShellExecutor().onFail("incus storage create", "Error: pool exists");

    var made = new IncusStorage(shell).create("tank", new PoolSource.File(51200));

    assertEquals(new ShellExec.Result(1, "", "Error: pool exists"), made);
    assertEquals(
        List.of(List.of("incus", "storage", "create", "tank", "zfs", "size=51200MiB")),
        shell.arguments());
  }

  @Test
  void poolsAreReadWithTheSourceTheyWereMadeFromAndTheirSpace() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus storage list --format json",
                """
                [{"name": "devpool", "driver": "dir",
                  "config": {"source": "/var/lib/incus/storage-pools/devpool"}},
                 {"name": "tank", "driver": "zfs",
                  "config": {"source": "tank", "volatile.initial_source": "/dev/sdb",
                             "zfs.pool_name": "tank"}},
                 {"name": "trial", "driver": "zfs",
                  "config": {"source": "/var/lib/incus/disks/trial.img",
                             "volatile.initial_source": "", "size": "20480MiB"}},
                 {"name": "bare", "driver": "dir"}]
                """)
            .onOk(
                "incus query /1.0/storage-pools/devpool/resources",
                "{\"space\": {\"used\": 1073741824, \"total\": 10737418240}}")
            .onOk("incus query /1.0/storage-pools/tank/resources", "{\"inodes\": {}}")
            .onOk(
                "incus query /1.0/storage-pools/trial/resources",
                "{\"space\": {\"used\": \"lots\", \"total\": 1}}")
            .onFail("incus query /1.0/storage-pools/bare/resources", "unavailable");

    var pools = new IncusStorage(shell).pools();

    assertEquals(
        List.of(
            new IncusStorage.Pool(
                "devpool",
                "dir",
                "/var/lib/incus/storage-pools/devpool",
                null,
                null,
                Optional.of(new IncusStorage.Space(1073741824L, 10737418240L))),
            new IncusStorage.Pool("tank", "zfs", "/dev/sdb", "tank", null, Optional.empty()),
            new IncusStorage.Pool(
                "trial",
                "zfs",
                "/var/lib/incus/disks/trial.img",
                null,
                "20480MiB",
                Optional.empty()),
            new IncusStorage.Pool("bare", "dir", "unknown", null, null, Optional.empty())),
        pools);
    assertEquals(
        List.of(
            "devpool (dir, /var/lib/incus/storage-pools/devpool): 9.0 GiB free of 10.0 GiB",
            "tank (zfs, /dev/sdb): free space unknown"),
        pools.subList(0, 2).stream().map(IncusStorage.Pool::summary).toList());
  }

  @Test
  void anIncusThatDoesNotAnswerHasNoPools() throws Exception {
    var missing =
        new ScriptedShellExecutor()
            .onThrow("incus storage list", new IOException("Cannot run program \"incus\""));

    assertEquals(List.of(), new IncusStorage(missing).poolsOrNone());
    assertEquals(List.of(), new IncusStorage(new ScriptedShellExecutor()).poolsOrNone());
  }

  @Test
  void aPoolRefusesANewPoolOfAnotherDriverUnderItsName() {
    var devpool = new IncusStorage.Pool("devpool", "dir", "/d", null, null, Optional.empty());

    devpool.requireDriver("dir");
    assertEquals(
        "Incus already has a dir pool named 'devpool', so no zfs pool can take that name. Name the"
            + " new pool with --pool <name>.",
        assertThrows(IllegalArgumentException.class, () -> devpool.requireDriver("zfs"))
            .getMessage());
  }

  @Test
  void aPoolIsFoundByItsName() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus storage list",
                "[{\"name\": \"devpool\", \"driver\": \"dir\", \"config\": {\"source\": \"/d\"}}]");

    assertEquals("dir", new IncusStorage(shell).find("devpool").orElseThrow().driver());
    assertEquals(Optional.empty(), new IncusStorage(shell).find("tank"));
  }

  @Test
  void anIncusThatCannotListItsPoolsSaysWhy() {
    var shell = new ScriptedShellExecutor().onFail("incus storage list", "not authorized");

    var failed = assertThrows(IOException.class, () -> new IncusStorage(shell).pools());

    assertEquals("incus storage list failed: not authorized", failed.getMessage());
  }

  @Test
  void aCreateThatMissesTheZfsToolsIsToldFromAnyOtherFailure() {
    assertTrue(
        IncusStorage.zfsUnseen(
            new ShellExec.Result(1, "", "Error: Required tool 'zpool' is missing")));
    assertFalse(
        IncusStorage.zfsUnseen(
            new ShellExec.Result(1, "", "Error: Error loading \"zfs\" module: exit status 1")));
  }
}
