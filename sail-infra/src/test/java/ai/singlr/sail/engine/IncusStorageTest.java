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
        IncusStorage.createCommand("tank", new PoolSource.Device("/dev/sdb", false)));
    assertEquals(
        List.of("incus", "storage", "create", "tank", "zfs", "source=/dev/sdb", "source.wipe=true"),
        IncusStorage.createCommand("tank", new PoolSource.Device("/dev/sdb", true)));
    assertEquals(
        List.of("incus", "storage", "create", "tank", "zfs", "size=51200MiB"),
        IncusStorage.createCommand("tank", new PoolSource.File(51200)));
  }

  @Test
  void eachSourceIsSaidAsTheOperatorReadsIt() {
    assertEquals("dir", new PoolSource.Dir().said());
    assertEquals("zfs on /dev/sdb", new PoolSource.Device("/dev/sdb", false).said());
    assertEquals("zfs on /dev/sdb, erased first", new PoolSource.Device("/dev/sdb", true).said());
    assertEquals("zfs in a 50.0 GiB file", new PoolSource.File(51200).said());
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
                  "config": {"source": "tank", "volatile.initial_source": "/dev/sdb"}},
                 {"name": "trial", "driver": "zfs",
                  "config": {"source": "/var/lib/incus/disks/trial.img",
                             "volatile.initial_source": ""}},
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
                Optional.of(new IncusStorage.Space(1073741824L, 10737418240L))),
            new IncusStorage.Pool("tank", "zfs", "/dev/sdb", Optional.empty()),
            new IncusStorage.Pool(
                "trial", "zfs", "/var/lib/incus/disks/trial.img", Optional.empty()),
            new IncusStorage.Pool("bare", "dir", "null", Optional.empty())),
        pools);
    assertEquals(
        List.of(
            "devpool (dir, /var/lib/incus/storage-pools/devpool): 9.0 GiB free of 10.0 GiB",
            "tank (zfs, /dev/sdb): free space unknown"),
        pools.subList(0, 2).stream().map(IncusStorage.Pool::summary).toList());
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
