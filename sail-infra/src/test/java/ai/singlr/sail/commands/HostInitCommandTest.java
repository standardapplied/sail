/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.Sail;
import ai.singlr.sail.engine.DeviceFixtures;
import ai.singlr.sail.engine.PoolDevices.Candidate;
import ai.singlr.sail.engine.PoolSource;
import ai.singlr.sail.engine.ScriptedShellExecutor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class HostInitCommandTest {

  private static final List<Candidate> DISKS =
      List.of(new Candidate("/dev/sdb", "100G", null), new Candidate("/dev/sdc", "200G", null));

  private final PrintStream originalOut = System.out;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();

  @AfterEach
  void restoreConsole() {
    ConsoleStub.restore();
    System.setOut(originalOut);
  }

  private void answering(String stdin, boolean terminal) {
    ConsoleStub.answering(stdin, terminal);
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
  }

  @Test
  void anInvalidWorkstationKeyAbortsBeforeAnyProvisioning() {
    var cmd = new CommandLine(new Sail());
    cmd.setOut(new PrintWriter(new StringWriter()));
    cmd.setErr(new PrintWriter(new StringWriter()));

    var exit = cmd.execute("host", "init", "--ssh-public-key", "not-a-real-key", "--dry-run");

    assertNotEquals(0, exit, "a malformed workstation key must fail host init fast");
  }

  @Test
  void provisioningNobodyCanConfirmIsRefusedNamingYes() {
    answering("", false);

    var refused =
        assertThrows(
            CliCommand.Refused.class, () -> HostInitCommand.ask("Proceed with provisioning?"));

    assertEquals(
        "Not provisioned: no terminal to confirm on. Re-run with --yes.", refused.getMessage());
    assertEquals("", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void provisioningDeclinedOnATerminalIsAborted() {
    answering("n\n", true);

    var refused =
        assertThrows(
            CliCommand.Refused.class, () -> HostInitCommand.ask("Proceed with provisioning?"));

    assertEquals("Aborted.", refused.getMessage());
    assertEquals("  Proceed with provisioning? [Y/n] ", out.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aDiskIsChosenByItsNumber() {
    assertEquals("/dev/sdc", HostInitCommand.chosenDisk(" 2 ", DISKS));
  }

  @Test
  void noDiskChosenIsRefusedNamingTheDisksToPassWithDisk() {
    for (var typed : Arrays.asList(null, "", "3", "0", "sdb")) {
      var refused =
          assertThrows(CliCommand.Refused.class, () -> HostInitCommand.chosenDisk(typed, DISKS));

      assertEquals(
          "Not provisioned: no disk chosen. Re-run with --disk and one of: /dev/sdb, /dev/sdc.",
          refused.getMessage(),
          String.valueOf(typed));
    }
  }

  @Test
  void aFailureHostInitDidNotDecideEndsWithTheDryRunHintOnlyWhenRunOnItsOwn() {
    assertEquals(
        "  ✗ Command timed out after 300s\n" + CliCommand.DRY_RUN_HINT + "\n",
        failing(new HostInitCommand()),
        "run on its own, host init has --dry-run to offer");
    assertEquals(
        "  ✗ Command timed out after 300s\n",
        failing(HostInitCommand.initStep()),
        "as a step of sail init, which has no --dry-run, it offers nothing");
  }

  private static String failing(HostInitCommand hostInit) {
    var err = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    try {
      assertThrows(
          CliCommand.Printed.class,
          () ->
              hostInit.run(
                  () -> {
                    throw new IOException("Command timed out after 300s");
                  }));
    } finally {
      System.setErr(originalErr);
    }
    return err.toString(StandardCharsets.UTF_8);
  }

  @Test
  void aDirBoxTakesNoZfsSource() throws Exception {
    assertEquals(new PoolSource.Dir(), source(new ScriptedShellExecutor()));
    for (var flags :
        List.of(
            List.of("--disk", "/dev/sdb"),
            List.of("--pool-file-size", "50GiB"),
            List.of("--force-wipe", "/dev/sdb"))) {
      var refused =
          assertThrows(
              IllegalArgumentException.class,
              () -> source(new ScriptedShellExecutor(), flags.toArray(String[]::new)));
      assertEquals(
          "--disk, --pool-file-size and --force-wipe choose a ZFS pool's source: pass them with"
              + " --storage zfs.",
          refused.getMessage());
    }
  }

  @Test
  void aZfsPoolIsMadeFromOneSource() {
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                source(
                    new ScriptedShellExecutor(),
                    "--storage",
                    "zfs",
                    "--disk",
                    "/dev/sdb",
                    "--pool-file-size",
                    "50GiB"));

    assertEquals(
        "Pass --disk or --pool-file-size, not both: a pool is made from one source.",
        refused.getMessage());
  }

  @Test
  void theForceFlagNamesTheDiskAgainSoItNeedsTheDisk() {
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                source(
                    new ScriptedShellExecutor(),
                    "--storage",
                    "zfs",
                    "--pool-file-size",
                    "50GiB",
                    "--force-wipe",
                    "/dev/sdb"));

    assertEquals(
        "--force-wipe names the device to erase again: name it with --disk too.",
        refused.getMessage());
  }

  @Test
  void aFileIsSizedAsGivenAndAtLeastAGibibyte() throws Exception {
    assertEquals(
        new PoolSource.File(51200),
        source(new ScriptedShellExecutor(), "--storage", "zfs", "--pool-file-size", "50GiB"));
    assertEquals(new PoolSource.File(204800), HostInitCommand.file("200GB"));
    assertEquals(new PoolSource.File(1024), HostInitCommand.file("1GiB"));
    for (var size : List.of("1023MiB", "lots", "-5GiB", "")) {
      var refused = assertThrows(IllegalArgumentException.class, () -> HostInitCommand.file(size));
      assertEquals(
          "--pool-file-size "
              + size
              + " is not a size of at least 1GiB. Give one such as 50GiB or"
              + " 200GB.",
          refused.getMessage());
    }
  }

  @Test
  void aNamedDiskIsClearedWithItsForceFlag() throws Exception {
    var shell = spare(DeviceFixtures.read(DeviceFixtures.SPARES));

    assertEquals(
        new PoolSource.Device("/dev/sdb", true, "40G"),
        source(shell, "--storage", "zfs", "--disk", "/dev/sdb", "--force-wipe", "/dev/sdb"));
  }

  @Test
  void aZfsBoxWithNoDiskNamedAndNoneUnusedIsToldOfPartitionsAndFiles() {
    var shell =
        new ScriptedShellExecutor()
            .onOk(DeviceFixtures.LSBLK, DeviceFixtures.spares(Set.of("/dev/sda")));

    var refused = assertThrows(CliCommand.Refused.class, () -> source(shell, "--storage", "zfs"));

    assertEquals(
        "Not provisioned: no unused disk found. Name a disk or a spare partition with --disk"
            + " <device>, or make the pool in a file with --pool-file-size <size>.",
        refused.getMessage());
  }

  @Test
  void theOneUnusedDiskIsTakenWithYesAndClearedBeforeUse() throws Exception {
    answering("", false);
    var shell = spare(DeviceFixtures.spares(Set.of("/dev/sda", "/dev/sdb")));

    assertEquals(
        new PoolSource.Device("/dev/sdb", false, "40G"),
        source(shell, "--storage", "zfs", "--yes"));
    assertTrue(shell.invocations().contains("wipefs -- /dev/sdb"));
  }

  @Test
  void aReRunKeepsTheZfsPoolIncusHasForcedOrNotAndClearsNoDeviceAgain() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus storage list",
                "[{\"name\": \"tank\", \"driver\": \"zfs\", \"config\": {\"source\": \"tank\","
                    + " \"volatile.initial_source\": \"/dev/sdb\", \"zfs.pool_name\": \"tank\"}}]")
            .onOk("realpath -e -- /dev/sdb", "/dev/sdb\n")
            .onOk("realpath -e -- /dev/disk/by-id/d1", "/dev/sdb\n");

    assertEquals(
        new PoolSource.Zpool("tank"),
        source(shell, "--storage", "zfs", "--pool", "tank", "--disk", "/dev/sdb"));
    assertEquals(
        new PoolSource.Zpool("tank"),
        source(
            shell,
            "--storage",
            "zfs",
            "--pool",
            "tank",
            "--disk",
            "/dev/sdb",
            "--force-wipe",
            "/dev/disk/by-id/d1"));
    assertEquals(new PoolSource.Zpool("tank"), source(shell, "--storage", "zfs", "--pool", "tank"));
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.startsWith("lsblk") || c.startsWith("wipefs")),
        shell.invocations().toString());
  }

  @Test
  void aReRunRefusesTheSourceFlagsItWouldIgnore() {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus storage list",
                "[{\"name\": \"tank\", \"driver\": \"zfs\", \"config\": {\"source\": \"tank\","
                    + " \"volatile.initial_source\": \"/dev/sdb\", \"zfs.pool_name\": \"tank\"}}]")
            .onOk("realpath -e -- /dev/sdc", "/dev/sdc\n");

    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                source(
                    shell,
                    "--storage",
                    "zfs",
                    "--pool",
                    "tank",
                    "--disk",
                    "/dev/sdc",
                    "--force-wipe",
                    "/dev/sdc"));
    var file =
        assertThrows(
            IllegalArgumentException.class,
            () -> source(shell, "--storage", "zfs", "--pool", "tank", "--pool-file-size", "9GiB"));

    assertEquals(
        "Incus already has zfs pool 'tank' on /dev/sdb, which a re-run keeps, so --disk /dev/sdc"
            + " and --force-wipe /dev/sdc would be ignored. Drop it, or name a new pool with --pool"
            + " <name>.",
        refused.getMessage());
    assertEquals(
        "Incus already has zfs pool 'tank' on /dev/sdb, which a re-run keeps, so --pool-file-size"
            + " 9GiB would be ignored. Drop it, or name a new pool with --pool <name>.",
        file.getMessage());
  }

  @Test
  void aDriverClashIsRefusedBeforeAnythingIsInstalled() {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus storage list",
                "[{\"name\": \"devpool\", \"driver\": \"dir\", \"config\": {\"source\": \"/d\"}}]");

    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () -> source(shell, "--storage", "zfs", "--pool-file-size", "9GiB"));

    assertEquals(
        "Incus already has a dir pool named 'devpool', so no zfs pool can take that name. Name the"
            + " new pool with --pool <name>.",
        refused.getMessage());
    assertEquals(
        List.of(
            "incus storage list --format json", "incus query /1.0/storage-pools/devpool/resources"),
        shell.invocations());
  }

  @Test
  void anIncusNotInstalledYetHasNoPool() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onThrow("incus storage list", new IOException("Cannot run program \"incus\""));

    assertEquals(
        new PoolSource.File(51200), source(shell, "--storage", "zfs", "--pool-file-size", "50GiB"));
  }

  /** A box whose lsblk prints {@code layout}, /dev/sdb resolving and every probe answering free. */
  private static ScriptedShellExecutor spare(String layout) {
    return new ScriptedShellExecutor()
        .onOk("realpath -e -- /dev/sdb", "/dev/sdb\n")
        .onOk(DeviceFixtures.LSBLK, layout)
        .onOk("wipefs -- ", DeviceFixtures.NOTHING_HELD)
        .onOk("wipefs --all --no-act");
  }

  private static PoolSource source(ScriptedShellExecutor shell, String... args) throws Exception {
    var command = new HostInitCommand();
    new CommandLine(command).parseArgs(args);
    return command.source(shell);
  }

  @Test
  void anInvalidStorageBackendIsRefusedWithNoDryRunHint() {
    var err = new ByteArrayOutputStream();
    var originalErr = System.err;
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    var cmd = new CommandLine(new Sail());
    cmd.setExecutionExceptionHandler((failure, line, parsed) -> 1);
    int exit;
    try {
      exit = cmd.execute("host", "init", "--storage", "btrfs", "--dry-run");
    } finally {
      System.setErr(originalErr);
    }

    assertEquals(1, exit);
    assertEquals(
        "  ✗ Invalid storage backend: 'btrfs'. Must be 'dir' or 'zfs'.\n",
        err.toString(StandardCharsets.UTF_8));
  }
}
