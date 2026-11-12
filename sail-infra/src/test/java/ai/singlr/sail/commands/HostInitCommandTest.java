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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class HostInitCommandTest {

  /** Runs {@code sail} with {@code args} through its command line, as an isolated box does. */
  public static void main(String[] args) {
    System.exit(new CommandLine(new Sail()).execute(args));
  }

  /**
   * A dry run through the command line, on a dir box whose Incus (a stand-in on the PATH) holds
   * devpool and puts the base profile's containers on it: adding a zfs pool in a file shows the
   * profile staying on devpool, as a real run leaves it, and the box's store is never opened.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void aDryRunShowsTheProfileWhereIncusHasItAndTouchesNoStore(@TempDir Path home) throws Exception {
    var said =
        dryRun(home, POOLS, "--storage", "zfs", "--pool-file-size", "20GiB", "--pool", "tank");

    assertTrue(said.contains("singlr-base (already exists): containers on devpool (dir)"), said);
    assertFalse(said.contains("demo project"), said);
    assertTrue(said.contains("Dry run done: nothing was changed."), said);
    assertFalse(said.contains("Server ready"), said);
    try (var held = Files.list(home.resolve("data"))) {
      assertEquals(List.of(), held.toList());
    }
  }

  /**
   * A dry run of the command that made the zfs pool tank, on the box whose Incus holds it: it reads
   * the pool Incus has, as a real run does, and says it is kept.
   */
  @Test
  @EnabledOnOs(OS.LINUX)
  void aDryRunOfTheCommandThatMadeAPoolSaysItIsKept(@TempDir Path home) throws Exception {
    var tank =
        "{\"name\":\"tank\",\"driver\":\"zfs\",\"config\":{\"source\":\"tank\","
            + "\"volatile.initial_source\":\"/dev/sdb\",\"zfs.pool_name\":\"tank\"}}";

    var said =
        dryRun(home, POOLS.replace("[{", "[" + tank + ",{"), "--storage", "zfs", "--pool", "tank");

    assertTrue(said.contains("tank (zfs on /dev/sdb, already exists)"), said);
  }

  private static final String POOLS =
      "[{\"name\":\"devpool\",\"driver\":\"dir\",\"config\":{\"source\":"
          + "\"/var/lib/incus/storage-pools/devpool\"}}]";

  /**
   * What {@code host init --dry-run --yes} with {@code flags} prints, run through the command line
   * in a JVM of its own, its store and home in {@code home}, on a box whose Incus is a stand-in
   * that lists {@code pools} and a base profile with its root disk on devpool.
   */
  private static String dryRun(Path home, String pools, String... flags) throws Exception {
    var data = Files.createDirectories(home.resolve("data"));
    var bin = Files.createDirectories(home.resolve("bin"));
    var incus = bin.resolve("incus");
    Files.writeString(
        incus,
        """
        #!/bin/sh
        case "$*" in
          "storage list --format json") printf '%s\\n' '$POOLS' ;;
          "profile show singlr-base") echo "name: singlr-base" ;;
          "profile device list singlr-base") printf 'root\\neth0\\n' ;;
          "profile device get singlr-base root pool") echo devpool ;;
          *) exit 1 ;;
        esac
        """
            .replace("$POOLS", pools));
    Files.setPosixFilePermissions(incus, PosixFilePermissions.fromString("rwxr-xr-x"));
    var output = home.resolve("output.txt");
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home,
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                HostInitCommandTest.class.getName(),
                "host",
                "init",
                "--dry-run",
                "--yes"));
    command.addAll(List.of(flags));
    var builder =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
    builder.environment().put("SAIL_DATA_DIR", data.toString());
    builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
    var process = builder.start();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the dry run timed out");
      var said = Files.readString(output);
      assertEquals(0, process.exitValue(), said);
      return said;
    } finally {
      process.destroyForcibly();
    }
  }

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
    for (var flags : List.of(List.of("--disk", "/dev/sdb"), List.of("--pool-file-size", "50GiB"))) {
      var refused =
          assertThrows(
              IllegalArgumentException.class,
              () -> source(new ScriptedShellExecutor(), flags.toArray(String[]::new)));
      assertEquals(
          "--disk and --pool-file-size choose a ZFS pool's source: pass them with --storage zfs.",
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
  void thereIsNoFlagThatErases() {
    assertThrows(
        CommandLine.UnmatchedArgumentException.class,
        () ->
            source(
                new ScriptedShellExecutor(),
                "--storage",
                "zfs",
                "--disk",
                "/dev/sdb",
                "--force-wipe",
                "/dev/sdb"));
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
        new PoolSource.Device("/dev/sdb", "spare-sdb 8:16 42949672960"),
        source(shell, "--storage", "zfs", "--yes"));
    assertTrue(shell.invocations().contains("wipefs -- /dev/sdb"));
  }

  @Test
  void aReRunKeepsTheZfsPoolIncusHasAndClearsNoDeviceAgain() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus storage list",
                "[{\"name\": \"tank\", \"driver\": \"zfs\", \"config\": {\"source\": \"tank\","
                    + " \"volatile.initial_source\": \"/dev/sdb\", \"zfs.pool_name\": \"tank\"}}]")
            .onOk("realpath -e -- /dev/disk/by-id/d1", "/dev/sdb\n")
            .onOk("realpath -e -- /dev/sdb", "/dev/sdb\n");

    assertEquals(
        new PoolSource.Zpool("tank"),
        source(shell, "--storage", "zfs", "--pool", "tank", "--disk", "/dev/sdb"));
    assertEquals(
        new PoolSource.Zpool("tank"),
        source(shell, "--storage", "zfs", "--pool", "tank", "--disk", "/dev/disk/by-id/d1"));
    assertEquals(new PoolSource.Zpool("tank"), source(shell, "--storage", "zfs", "--pool", "tank"));
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.startsWith("lsblk") || c.startsWith("wipefs")),
        shell.invocations().toString());
  }

  @Test
  void aReRunOfTheCommandThatMadeAFilePoolKeepsItAndADifferentSizeIsRefused() throws Exception {
    var shell =
        new ScriptedShellExecutor()
            .onOk(
                "incus storage list",
                "[{\"name\": \"tank\", \"driver\": \"zfs\", \"config\": {\"source\":"
                    + " \"/var/lib/incus/disks/tank.img\", \"size\": \"20480MiB\","
                    + " \"zfs.pool_name\": \"tank\"}}]");

    assertEquals(
        new PoolSource.Zpool("tank"),
        source(shell, "--storage", "zfs", "--pool", "tank", "--pool-file-size", "20GiB"));
    assertEquals(
        "Incus already has zfs pool 'tank' on /var/lib/incus/disks/tank.img, which a re-run keeps,"
            + " so --pool-file-size 30GiB would be ignored. Drop it, or name a new pool with --pool"
            + " <name>.",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    source(
                        shell, "--storage", "zfs", "--pool", "tank", "--pool-file-size", "30GiB"))
            .getMessage());
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
            () -> source(shell, "--storage", "zfs", "--pool", "tank", "--disk", "/dev/sdc"));
    var file =
        assertThrows(
            IllegalArgumentException.class,
            () -> source(shell, "--storage", "zfs", "--pool", "tank", "--pool-file-size", "9GiB"));

    assertEquals(
        "Incus already has zfs pool 'tank' on /dev/sdb, which a re-run keeps, so --disk /dev/sdc"
            + " would be ignored. Drop it, or name a new pool with --pool <name>.",
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
        .onOk("wipefs --all --no-act")
        .onOk("/proc/[0-9]*");
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
