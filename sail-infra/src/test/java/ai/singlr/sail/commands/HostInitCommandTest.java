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
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.TimingOutShell;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
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

  /**
   * The fifth GCP proof's local NVMe SSD: Incus holds pool znv under the by-id path Sail handed it,
   * and udev names the disk by three more links ({@code nvme-identity.txt}, {@code incus storage
   * show znv}).
   */
  private static final String NVME_ID =
      "/dev/disk/by-id/nvme-nvme.1ae0-6e766d655f63617264-6e766d655f63617264-00000001";

  private static final String NVME_ALIAS = "/dev/disk/by-id/google-local-nvme-ssd-0";

  private static ScriptedShellExecutor heldUnder(String source) {
    return new ScriptedShellExecutor()
        .onOk(
            "incus storage list",
            "[{\"name\": \"znv\", \"driver\": \"zfs\", \"config\": {\"source\": \"znv\","
                + " \"volatile.initial_source\": \""
                + source
                + "\", \"zfs.pool_name\": \"znv\"}}]");
  }

  @Test
  void aReRunOfAPoolHeldUnderItsByIdPathKeepsItByAnyNameOfThatDisk() throws Exception {
    var shell =
        heldUnder(NVME_ID)
            .onOk("realpath -e -- " + NVME_ID, "/dev/nvme0n1\n")
            .onOk("realpath -e -- " + NVME_ALIAS, "/dev/nvme0n1\n")
            .onOk("realpath -e -- /dev/nvme0n1", "/dev/nvme0n1\n")
            .onOk("realpath -e -- /dev/sdb", "/dev/sdb\n");

    for (var named : List.of("/dev/nvme0n1", NVME_ID, NVME_ALIAS)) {
      assertEquals(
          new PoolSource.Zpool("znv"),
          source(shell, "--storage", "zfs", "--pool", "znv", "--disk", named),
          named);
    }
    assertEquals(
        "Incus already has zfs pool 'znv' on "
            + NVME_ID
            + ", which a re-run keeps, so --disk /dev/sdb would be ignored. Drop it, or name a new"
            + " pool with --pool <name>.",
        assertThrows(
                IllegalArgumentException.class,
                () -> source(shell, "--storage", "zfs", "--pool", "znv", "--disk", "/dev/sdb"))
            .getMessage());
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.startsWith("lsblk") || c.startsWith("wipefs")),
        shell.invocations().toString());
  }

  @Test
  void aReRunOfAPoolHeldUnderAPartitionsByIdPathKeepsItByAnyNameOfThatPartition() throws Exception {
    var shell =
        heldUnder(NVME_ID + "-part1")
            .onOk("realpath -e -- " + NVME_ID + "-part1", "/dev/nvme0n1p1\n")
            .onOk("realpath -e -- " + NVME_ALIAS + "-part1", "/dev/nvme0n1p1\n")
            .onOk("realpath -e -- /dev/nvme0n1p1", "/dev/nvme0n1p1\n")
            .onOk("realpath -e -- /dev/nvme0n1", "/dev/nvme0n1\n");

    for (var named : List.of("/dev/nvme0n1p1", NVME_ID + "-part1", NVME_ALIAS + "-part1")) {
      assertEquals(
          new PoolSource.Zpool("znv"),
          source(shell, "--storage", "zfs", "--pool", "znv", "--disk", named),
          named);
    }
    assertEquals(
        "Incus already has zfs pool 'znv' on "
            + NVME_ID
            + "-part1, which a re-run keeps, so --disk /dev/nvme0n1 would be ignored. Drop it, or"
            + " name a new pool with --pool <name>.",
        assertThrows(
                IllegalArgumentException.class,
                () -> source(shell, "--storage", "zfs", "--pool", "znv", "--disk", "/dev/nvme0n1"))
            .getMessage());
  }

  @Test
  void aReRunNamingADiskWhenThePoolsDeviceIsGoneIsRefusedSayingSo() throws Exception {
    var shell =
        heldUnder(NVME_ID)
            .onFail(
                "realpath -e -- " + NVME_ID, "realpath: " + NVME_ID + ": No such file or directory")
            .onOk("realpath -e -- /dev/nvme0n1", "/dev/nvme0n1\n");

    assertEquals(
        "Incus already has zfs pool 'znv' on "
            + NVME_ID
            + ", which names no device on this box now: the pool's device is gone, so --disk"
            + " /dev/nvme0n1 cannot be it. See sudo zpool status znv, or name a new pool with"
            + " --pool <name>.",
        assertThrows(
                IllegalArgumentException.class,
                () -> source(shell, "--storage", "zfs", "--pool", "znv", "--disk", "/dev/nvme0n1"))
            .getMessage());
    assertEquals(new PoolSource.Zpool("znv"), source(shell, "--storage", "zfs", "--pool", "znv"));
  }

  @Test
  void aPoolMadeFromAZpoolsNameIsNeverTheDiskNamed() {
    var shell = heldUnder("znv").onOk("realpath -e -- /dev/nvme0n1", "/dev/nvme0n1\n");

    assertEquals(
        "Incus already has zfs pool 'znv' on znv, which a re-run keeps, so --disk /dev/nvme0n1"
            + " would be ignored. Drop it, or name a new pool with --pool <name>.",
        assertThrows(
                IllegalArgumentException.class,
                () -> source(shell, "--storage", "zfs", "--pool", "znv", "--disk", "/dev/nvme0n1"))
            .getMessage());
    assertFalse(shell.invocations().contains("realpath -e -- znv"), shell.invocations().toString());
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
            .onOk("realpath -e -- /dev/sdc", "/dev/sdc\n")
            .onOk("realpath -e -- /dev/sdb", "/dev/sdb\n");

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

  @Test
  void twoDisksAreOneMirrorOfBoth() throws Exception {
    var shell =
        spare(DeviceFixtures.spares(Set.of("/dev/sda", "/dev/sdb", "/dev/sdc")))
            .onOk("realpath -e -- /dev/sdc", "/dev/sdc\n");

    assertEquals(
        new PoolSource.Mirror(
            new PoolSource.Device("/dev/sdb", "spare-sdb 8:16 42949672960"),
            new PoolSource.Device("/dev/sdc", "spare-sdc 8:32 42949672960"),
            42949672960L,
            42949672960L),
        source(shell, "--storage", "zfs", "--disk", "/dev/sdb", "--disk", "/dev/sdc"));
  }

  @Test
  void threeDisksAreRefusedBeforeAnyIsRead() {
    var shell = new ScriptedShellExecutor();

    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                source(
                    shell,
                    "--storage",
                    "zfs",
                    "--disk",
                    "/dev/sdb",
                    "--disk",
                    "/dev/sdc",
                    "--disk",
                    "/dev/sdd"));

    assertEquals(
        "Pass --disk once, or twice for a mirror of two devices: Sail makes no pool of 3 devices.",
        refused.getMessage());
    assertTrue(
        shell.invocations().stream()
            .noneMatch(c -> c.startsWith("realpath") || c.startsWith("lsblk")),
        shell.invocations().toString());
  }

  private static final String MIRROR_A = "/dev/disk/by-id/wwn-0x5000c500a1b2c3d4-part5";
  private static final String MIRROR_B = "/dev/disk/by-id/wwn-0x5000c500e5f6a7b8-part5";

  /**
   * {@code shell}, answering after its own scripts as a box whose Incus holds tank, a mirror of the
   * by-id paths of /dev/sdb5 and /dev/sdc5; where the second resolves is the test's to say.
   */
  private static ScriptedShellExecutor mirrored(ScriptedShellExecutor shell) {
    return shell
        .onOk(
            "incus storage list",
            "[{\"name\": \"tank\", \"driver\": \"zfs\", \"config\": {\"source\": \"tank\","
                + " \"volatile.initial_source\": \"mirror="
                + MIRROR_A
                + ","
                + MIRROR_B
                + "\", \"zfs.pool_name\": \"tank\"}}]")
        .onOk("realpath -e -- " + MIRROR_A, "/dev/sdb5\n")
        .onOk("realpath -e -- /dev/sdb5", "/dev/sdb5\n")
        .onOk("realpath -e -- /dev/sdc5", "/dev/sdc5\n")
        .onOk("realpath -e -- /dev/sdd5", "/dev/sdd5\n");
  }

  @Test
  void aReRunOfAMirrorInEitherOrderOfItsDisksKeepsIt() throws Exception {
    var shell =
        mirrored(new ScriptedShellExecutor().onOk("realpath -e -- " + MIRROR_B, "/dev/sdc5\n"));

    for (var order :
        List.of(
            List.of("/dev/sdb5", "/dev/sdc5"),
            List.of("/dev/sdc5", "/dev/sdb5"),
            List.of(MIRROR_B, "/dev/sdb5"))) {
      assertEquals(
          new PoolSource.Zpool("tank"),
          source(
              shell,
              "--storage",
              "zfs",
              "--pool",
              "tank",
              "--disk",
              order.getFirst(),
              "--disk",
              order.getLast()),
          order.toString());
    }
    assertFalse(
        shell.invocations().stream().anyMatch(c -> c.startsWith("lsblk") || c.startsWith("wipefs")),
        shell.invocations().toString());
  }

  @Test
  void aReRunOfAMirrorNamingOneOfItsDisksOrAnotherPairIsRefusedByName() {
    var shell =
        mirrored(new ScriptedShellExecutor().onOk("realpath -e -- " + MIRROR_B, "/dev/sdc5\n"));
    var held = "Incus already has zfs pool 'tank' on a mirror of " + MIRROR_A + " and " + MIRROR_B;

    assertEquals(
        held
            + ", which a re-run keeps, so --disk /dev/sdb5 would be ignored. Drop it, or name a new"
            + " pool with --pool <name>.",
        assertThrows(
                IllegalArgumentException.class,
                () -> source(shell, "--storage", "zfs", "--pool", "tank", "--disk", "/dev/sdb5"))
            .getMessage());
    assertEquals(
        held
            + ", which a re-run keeps, so --disk /dev/sdb5 --disk /dev/sdd5 would be ignored. Drop"
            + " it, or name a new pool with --pool <name>.",
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
                        "/dev/sdb5",
                        "--disk",
                        "/dev/sdd5"))
            .getMessage());
    assertEquals(
        held
            + ", which a re-run keeps, so --disk /dev/sdb5 --disk /dev/sdb5 would be ignored. Drop"
            + " it, or name a new pool with --pool <name>.",
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
                        "/dev/sdb5",
                        "--disk",
                        "/dev/sdb5"))
            .getMessage());
  }

  @Test
  void aReRunOfAMirrorOneOfWhoseDevicesIsGoneSaysWhichAndKeepsItWithoutDisks() throws Exception {
    var shell =
        mirrored(
            new ScriptedShellExecutor()
                .onFail(
                    "realpath -e -- " + MIRROR_B,
                    "realpath: " + MIRROR_B + ": No such file or directory"));

    assertEquals(
        "Incus already has zfs pool 'tank' on a mirror of "
            + MIRROR_A
            + " and "
            + MIRROR_B
            + ", and "
            + MIRROR_B
            + " names no device on this box now: a device of the pool is gone, so --disk"
            + " /dev/sdb5 --disk /dev/sdc5 cannot be it. See sudo zpool status tank, or name a new"
            + " pool with --pool <name>.",
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
                        "/dev/sdb5",
                        "--disk",
                        "/dev/sdc5"))
            .getMessage());
    assertEquals(new PoolSource.Zpool("tank"), source(shell, "--storage", "zfs", "--pool", "tank"));
  }

  @Test
  void aReRunFindingAPoolIncusDidNotFinishSaysSoAndTakesItForNoPool() {
    for (var source : List.of("/dev/sdb", "mirror=" + MIRROR_A + "," + MIRROR_B)) {
      var shell =
          new ScriptedShellExecutor()
              .onOk(
                  "incus storage list",
                  "[{\"name\": \"tank\", \"driver\": \"zfs\", \"status\": \"Pending\","
                      + " \"config\": {\"source\": \""
                      + source
                      + "\", \"zfs.pool_name\": \"tank\"}}]");

      var refused =
          assertThrows(
              IllegalStateException.class,
              () -> source(shell, "--storage", "zfs", "--pool", "tank", "--disk", "/dev/sdb"));

      assertEquals(
          "Incus's pool 'tank' is Pending, not Created: Incus did not finish making it, so it is"
              + " not taken for a pool that exists and nothing is made over it. Remove Incus's"
              + " record of it with sudo incus storage delete tank, which leaves any zpool tank"
              + " Incus made; see what that holds with sudo zfs list -r tank and, if nothing on it"
              + " is wanted, destroy it yourself with sudo zpool destroy tank, then run this again.",
          refused.getMessage(),
          source);
      assertTrue(
          shell.invocations().stream()
              .noneMatch(c -> c.startsWith("realpath") || c.startsWith("lsblk")),
          shell.invocations().toString());
    }
  }

  /** A box whose Incus lists one pool in {@code status}, named and sourced as given. */
  private static ScriptedShellExecutor listing(
      String name, String driver, String status, String config) {
    return new ScriptedShellExecutor()
        .onOk(
            "incus storage list",
            "[{\"name\": \""
                + name
                + "\", \"driver\": \""
                + driver
                + "\", \"status\": \""
                + status
                + "\", \"config\": {"
                + config
                + "}}]")
        .onOk("realpath -e -- " + MIRROR_A, "/dev/sdb5\n")
        .onOk("realpath -e -- " + MIRROR_B, "/dev/sdc5\n")
        .onOk("realpath -e -- /dev/sdb5", "/dev/sdb5\n")
        .onOk("realpath -e -- /dev/sdc5", "/dev/sdc5\n")
        .onOk("realpath -e -- /dev/sdb", "/dev/sdb\n");
  }

  private static final String SINGLE =
      "\"source\": \"tank\", \"volatile.initial_source\": \"/dev/sdb\", \"zfs.pool_name\": \"tank\"";
  private static final String PAIR =
      "\"source\": \"tank\", \"volatile.initial_source\": \"mirror="
          + MIRROR_A
          + ","
          + MIRROR_B
          + "\", \"zfs.pool_name\": \"tank\"";
  private static final String HALF_MADE =
      "\"source\": \"mirror=" + MIRROR_A + "," + MIRROR_B + "\"";
  private static final String DIR = "\"source\": \"/var/lib/incus/storage-pools/devpool\"";

  @Test
  void aReRunFindingAPoolIncusReportsUnknownKeepsItAsBefore() throws Exception {
    assertEquals(
        new PoolSource.Zpool("tank"),
        source(
            listing("tank", "zfs", "Unknown", SINGLE),
            "--storage",
            "zfs",
            "--pool",
            "tank",
            "--disk",
            "/dev/sdb"));
    assertEquals(
        new PoolSource.Zpool("tank"),
        source(
            listing("tank", "zfs", "Unknown", PAIR),
            "--storage",
            "zfs",
            "--pool",
            "tank",
            "--disk",
            "/dev/sdc5",
            "--disk",
            "/dev/sdb5"));
    assertEquals(
        new PoolSource.Dir(),
        source(listing("devpool", "dir", "Unknown", DIR), "--storage", "dir"));
  }

  @Test
  void aReRunFindingAPoolIncusCannotStartIsRefusedBeforeAnythingAndSaysWhatToRun() {
    var made =
        "Incus's pool 'tank' is Unavailable: Incus has the pool but cannot start it, so it is not"
            + " taken for a pool that exists and nothing is changed. It was made: see why with sudo"
            + " incus storage show tank and sudo zpool status tank, mend that, then run this again.";
    for (var shape : List.of(SINGLE, PAIR)) {
      var shell = listing("tank", "zfs", "Unavailable", shape);
      assertEquals(
          made,
          assertThrows(
                  IllegalStateException.class,
                  () -> source(shell, "--storage", "zfs", "--pool", "tank"))
              .getMessage());
      assertTrue(
          shell.invocations().stream()
              .noneMatch(c -> c.startsWith("lsblk") || c.startsWith("wipefs")),
          shell.invocations().toString());
    }
    assertEquals(
        "Incus's pool 'tank' is Unavailable: Incus has the pool but cannot start it, so it is not"
            + " taken for a pool that exists and nothing is changed. Its record names no zpool, as"
            + " when incusd stopped while making it: remove the half-made pool with sudo incus"
            + " storage delete tank, then sudo zfs list -r tank, and if it lists only tank itself,"
            + " sudo zpool destroy tank; then run this again.",
        assertThrows(
                IllegalStateException.class,
                () ->
                    source(
                        listing("tank", "zfs", "Unavailable", HALF_MADE),
                        "--storage",
                        "zfs",
                        "--pool",
                        "tank",
                        "--disk",
                        "/dev/sdb5",
                        "--disk",
                        "/dev/sdc5"))
            .getMessage());
    assertEquals(
        "Incus's pool 'devpool' is Unavailable: Incus has the pool but cannot start it, so it is"
            + " not taken for a pool that exists and nothing is changed. It was made: see why with"
            + " sudo incus storage show devpool, mend that, then run this again.",
        assertThrows(
                IllegalStateException.class,
                () -> source(listing("devpool", "dir", "Unavailable", DIR), "--storage", "dir"))
            .getMessage());
  }

  /** The proof's case G: kernel names moved after the pool was made, and ZFS says where it is. */
  private static ScriptedShellExecutor movedNames(ScriptedShellExecutor shell) {
    var k1 = "/dev/disk/by-id/scsi-0Google_PersistentDisk_k1";
    var k2 = "/dev/disk/by-id/scsi-0Google_PersistentDisk_k2";
    return shell
        .onOk(
            "incus storage list",
            "[{\"name\": \"zg\", \"driver\": \"zfs\", \"status\": \"Created\","
                + " \"config\": {\"source\": \"zg\", \"volatile.initial_source\":"
                + " \"mirror=/dev/sdd,/dev/sde\", \"zfs.pool_name\": \"zg\"}}]")
        .onOk(
            "zpool list -vHP zg",
            "zg\t19.5G\t1M\t19.5G\t-\t-\t0%\t0%\t1.00x\tONLINE\t-\n"
                + "\tmirror-0\t19.5G\t1M\t19.5G\t-\t-\t0%\t0.00%\t-\tONLINE\n"
                + "\t"
                + k1
                + "-part1\t20.0G\t-\t-\t-\t-\t-\t-\t-\tONLINE\n"
                + "\t"
                + k2
                + "-part1\t20.0G\t-\t-\t-\t-\t-\t-\t-\tONLINE\n")
        .onOk(
            "zpool list -vH zg",
            "zg\t19.5G\t1M\t19.5G\t-\t-\t0%\t0%\t1.00x\tONLINE\t-\n"
                + "\tmirror-0\t19.5G\t1M\t19.5G\t-\t-\t0%\t0.00%\t-\tONLINE\n"
                + "\tscsi-0Google_PersistentDisk_k1\t20.0G\t-\t-\t-\t-\t-\t-\t-\tONLINE\n"
                + "\tscsi-0Google_PersistentDisk_k2\t20.0G\t-\t-\t-\t-\t-\t-\t-\tONLINE\n")
        .onOk("realpath -e -- " + k1, "/dev/sdh\n")
        .onOk("realpath -e -- " + k2, "/dev/sdj\n")
        .onOk("realpath -e -- /dev/sdh", "/dev/sdh\n")
        .onOk("realpath -e -- /dev/sdj", "/dev/sdj\n")
        .onOk("realpath -e -- /dev/sdd", "/dev/sdd\n")
        .onOk("realpath -e -- /dev/sde", "/dev/sde\n");
  }

  @Test
  void aReRunComparesThePoolsDevicesAsZfsListsThemNotAsIncusRecordedThem() throws Exception {
    var shell = movedNames(new ScriptedShellExecutor());

    assertEquals(
        new PoolSource.Zpool("zg"),
        source(
            shell, "--storage", "zfs", "--pool", "zg", "--disk", "/dev/sdj", "--disk", "/dev/sdh"));
    assertEquals(
        "Incus already has zfs pool 'zg' on a mirror of"
            + " /dev/disk/by-id/scsi-0Google_PersistentDisk_k1 and"
            + " /dev/disk/by-id/scsi-0Google_PersistentDisk_k2, which a re-run keeps, so --disk"
            + " /dev/sdd --disk /dev/sde would be ignored. Drop it, or name a new pool with --pool"
            + " <name>.",
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    source(
                        shell,
                        "--storage",
                        "zfs",
                        "--pool",
                        "zg",
                        "--disk",
                        "/dev/sdd",
                        "--disk",
                        "/dev/sde"))
            .getMessage());
  }

  @Test
  void aReRunOfTheTwoDiskCommandKeepsAMirrorTheOperatorGaveALogOrCacheDeviceByHand()
      throws Exception {
    for (var listed :
        List.of(
            List.of(
                "gcp6/g-701-mirror-with-log.zpool-list-P.txt",
                "gcp6/g-701-mirror-with-log.zpool-list.txt"),
            List.of(
                "gcp6/g-705-mirror-with-log-and-cache.zpool-list-P.txt",
                "gcp6/handwritten-g-705-mirror-with-log-and-cache.zpool-list.txt"))) {
      var shell =
          DeviceFixtures.zpoolLists(
                  new ScriptedShellExecutor(), "zg", listed.getFirst(), listed.getLast())
              .onOk(
                  "incus storage list",
                  "[{\"name\": \"zg\", \"driver\": \"zfs\", \"status\": \"Created\","
                      + " \"config\": {\"source\": \"zg\", \"volatile.initial_source\":"
                      + " \"mirror=/dev/sdd,/dev/sde\", \"zfs.pool_name\": \"zg\"}}]")
              .onOk("realpath -e -- " + DeviceFixtures.K1, "/dev/sdn\n")
              .onOk("realpath -e -- " + DeviceFixtures.K2, "/dev/sdm\n")
              .onOk("realpath -e -- /dev/sdn", "/dev/sdn\n")
              .onOk("realpath -e -- /dev/sdm", "/dev/sdm\n")
              .onOk("realpath -e -- /dev/sde", "/dev/sde\n");

      assertEquals(
          new PoolSource.Zpool("zg"),
          source(
              shell,
              "--storage",
              "zfs",
              "--pool",
              "zg",
              "--disk",
              "/dev/sdn",
              "--disk",
              "/dev/sdm"),
          listed.getFirst());
      assertEquals(
          "Incus already has zfs pool 'zg' on a mirror of "
              + DeviceFixtures.K1
              + " and "
              + DeviceFixtures.K2
              + ", which a re-run keeps, so --disk /dev/sdn --disk /dev/sde would be ignored."
              + " Drop it, or name a new pool with --pool <name>.",
          assertThrows(
                  IllegalArgumentException.class,
                  () ->
                      source(
                          shell,
                          "--storage",
                          "zfs",
                          "--pool",
                          "zg",
                          "--disk",
                          "/dev/sdn",
                          "--disk",
                          "/dev/sde"))
              .getMessage(),
          listed.getFirst());
    }
  }

  @Test
  void aReRunFindingAZpoolThatHangsRefusesAfterOneBoundAndChangesNothing() {
    var hanging = new TimingOutShell(movedNames(new ScriptedShellExecutor()), "zpool ");

    assertEquals(
        "zpool did not answer within 10 s for zpool zg, so Sail cannot tell what the pool is on or"
            + " whether a device is in use. Nothing was changed; run this again once zpool"
            + " answers.",
        assertThrows(
                IllegalStateException.class,
                () ->
                    source(
                        hanging,
                        "--storage",
                        "zfs",
                        "--pool",
                        "zg",
                        "--disk",
                        "/dev/sdj",
                        "--disk",
                        "/dev/sdh"))
            .getMessage());
    assertEquals(List.of(Duration.ofSeconds(10)), hanging.deadlines());
  }

  @Test
  void aPoolZpoolHasNotImportedIsSaidAsIncusRecordedIt() {
    var shell =
        listing("tank", "zfs", "Created", SINGLE)
            .onFail("zpool list -vHP tank", "cannot open 'tank': no such pool")
            .onOk("realpath -e -- /dev/sdc", "/dev/sdc\n");

    assertEquals(
        "Incus already has zfs pool 'tank' on /dev/sdb (as Incus recorded at creation: zpool tank"
            + " is not imported), which a re-run keeps, so --disk /dev/sdc would be ignored. Drop"
            + " it, or name a new pool with --pool <name>.",
        assertThrows(
                IllegalArgumentException.class,
                () -> source(shell, "--storage", "zfs", "--pool", "tank", "--disk", "/dev/sdc"))
            .getMessage());
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

  private static PoolSource source(ShellExec shell, String... args) throws Exception {
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
