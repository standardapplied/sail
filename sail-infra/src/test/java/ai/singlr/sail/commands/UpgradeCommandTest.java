/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.SailVersion;
import ai.singlr.sail.engine.PlatformDetector;
import ai.singlr.sail.engine.SailBinary;
import ai.singlr.sail.engine.SemVer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class UpgradeCommandTest {

  private static final byte[] HEADER = executableHeader(System.getProperty("os.name", ""));
  private static final SemVer INSTALLED_VERSION = SemVer.parse("0.46.0");

  @TempDir Path tempDir;
  private Path installed;
  private Path candidate;

  @BeforeEach
  void installSail() throws Exception {
    installed = executable(tempDir.resolve("bin/sail"), "installed");
    candidate = executable(tempDir.resolve("staged/sail"), "candidate");
  }

  @Test
  void readUnitEndpointParsesHostAndPort() throws Exception {
    var unit = tempDir.resolve("sail-api.service");
    Files.writeString(
        unit,
        """
        [Service]
        ExecStart=/usr/local/bin/sail server start --host 10.0.0.5 --port 9999
        """);
    var endpoint = UpgradeCommand.readUnitEndpoint(unit).orElseThrow();
    assertEquals("10.0.0.5", endpoint.host());
    assertEquals(9999, endpoint.port());
  }

  @Test
  void readUnitEndpointReturnsEmptyWhenFileMissing() {
    assertTrue(UpgradeCommand.readUnitEndpoint(tempDir.resolve("nope.service")).isEmpty());
  }

  @Test
  void readUnitEndpointReturnsEmptyWhenExecStartMissing() throws Exception {
    var unit = tempDir.resolve("no-exec.service");
    Files.writeString(unit, "[Service]\nType=simple\n");
    assertTrue(UpgradeCommand.readUnitEndpoint(unit).isEmpty());
  }

  @Test
  void readUnitEndpointReturnsEmptyOnNonNumericPort() throws Exception {
    var unit = tempDir.resolve("bad-port.service");
    Files.writeString(
        unit,
        """
        [Service]
        ExecStart=/usr/local/bin/sail server start --host 127.0.0.1 --port not-a-number
        """);
    assertTrue(UpgradeCommand.readUnitEndpoint(unit).isEmpty());
  }

  @Test
  void helpTextIncludesOptions() {
    var cmd = new CommandLine(new UpgradeCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("upgrade"));
    assertTrue(usage.contains("--check"));
    assertTrue(usage.contains("--target"));
    assertTrue(usage.contains("--dry-run"));
    assertTrue(usage.contains("--json"));
  }

  @Test
  void versionFlagOutputStartsWithSail() {
    var version = SailVersion.version();
    assertNotNull(version);
    assertFalse(version.isBlank());

    var provider = new SailVersion();
    var lines = provider.getVersion();
    assertTrue(lines[0].startsWith("sail "));
  }

  @Test
  void aProvisionedBoxWithoutSailApiIsToldHowToInstallIt() {
    var remediation = UpgradeCommand.missingApiRemediation(true).orElseThrow();
    assertTrue(remediation.contains("sudo sail host service install"));
  }

  @Test
  void anUnprovisionedBoxGetsNoSailApiNag() {
    assertTrue(UpgradeCommand.missingApiRemediation(false).isEmpty());
  }

  @Test
  void theFixtureIsAnExecutableForThePlatformRunningTheSuite() {
    assertTrue(PlatformDetector.isValidBinary(HEADER));
  }

  @Test
  void helpListsBinary() {
    assertTrue(new CommandLine(new UpgradeCommand()).getUsageMessage().contains("--binary"));
  }

  @Test
  void aMissingBinaryIsRefusedNamingWhatToPass() throws Exception {
    var absent = tempDir.resolve("absent/sail");

    var run = upgrade(versions("0.46.0"), "--binary", absent.toString());

    assertRefused(run, "No file at " + absent, "Pass --binary the path of a sail binary");
  }

  @Test
  void aFileThatIsNoExecutableForThisPlatformIsRefused() throws Exception {
    var script = Files.writeString(tempDir.resolve("sail.sh"), "#!/bin/sh\necho sail 9.9.9\n");

    var run = upgrade(versions("9.9.9"), "--binary", script.toString());

    assertRefused(
        run,
        script + " is not a " + PlatformDetector.platformSuffix() + " executable",
        "Pass a sail binary built for this platform");
  }

  @Test
  void aFileWithoutItsExecuteBitIsRefusedNamingChmodInADryRunAsInARealOne() throws Exception {
    Files.setPosixFilePermissions(candidate, PosixFilePermissions.fromString("rw-r--r--"));

    for (var dryRun : List.of(true, false)) {
      var run =
          dryRun
              ? upgrade(versions("0.47.0"), "--binary", candidate.toString(), "--dry-run")
              : upgrade(versions("0.47.0"), "--binary", candidate.toString());

      assertRefused(run, candidate + " is not executable", "chmod +x " + candidate);
    }
  }

  @Test
  void aFileThatDoesNotRunAsSailIsRefusedAndNothingIsLeftBeside() throws Exception {
    var run =
        upgrade(
            path -> path.equals(installed) ? INSTALLED_VERSION : SailBinary.versionOf(path),
            "--binary",
            candidate.toString());

    assertRefused(
        run, candidate + " is not a runnable sail: ", "Pass a sail binary built for this platform");
    assertNothingStaged();
  }

  @Test
  void anOlderVersionIsRefusedBecauseMigrationsNeverRunBackwards() throws Exception {
    var run = upgrade(versions("0.45.9"), "--binary", candidate.toString());

    assertRefused(
        run,
        candidate + " is sail 0.45.9, older than the installed sail 0.46.0",
        "pass sail 0.46.0 or newer");
    assertNothingStaged();
  }

  @Test
  void anOlderReleaseIsRefusedAsAnOlderFileIs() throws Exception {
    var run = upgrade(versions("0.47.0"), "--target", "0.45.0", "--dry-run");

    assertRefused(
        run,
        "The release is sail 0.45.0, older than the installed sail 0.46.0",
        "pass --target 0.46.0 or newer");
  }

  @Test
  void aBinaryIsInstalledOverTheInstalledSailFromTheBytesItVerified() throws Exception {
    var asked = new ArrayList<Path>();

    var run =
        upgrade(
            path -> {
              asked.add(path);
              return path.equals(installed) ? INSTALLED_VERSION : SemVer.parse("0.47.0");
            },
            "--binary",
            candidate.toString(),
            "--json");

    assertEquals(0, run.exit(), run::err);
    assertTrue(run.out().contains("\"status\": \"upgraded\""), run.out());
    assertTrue(run.out().contains("\"from\": \"0.46.0\""), run.out());
    assertTrue(run.out().contains("\"to\": \"0.47.0\""), run.out());
    assertTrue(run.out().contains("\"service_restart\": \"skipped_client\""), run.out());
    assertInstalled("candidate");
    assertEquals(
        "rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(installed)));
    assertTrue(asked.contains(installed.resolveSibling("sail.tmp")), asked::toString);
    assertFalse(asked.contains(candidate), asked::toString);
    assertNothingStaged();
  }

  @Test
  void anInstalledSailThatCannotSayItsVersionIsRepairedNotRefused() throws Exception {
    var run =
        upgrade(
            path -> path.equals(installed) ? SailBinary.versionOf(path) : SemVer.parse("0.47.0"),
            "--binary",
            candidate.toString());

    assertEquals(0, run.exit(), run::err);
    assertTrue(run.err().contains(installed + " did not report its version"), run.err());
    assertTrue(run.out().contains("unknown \u2192 0.47.0"), run.out());
    assertInstalled("candidate");
  }

  @Test
  void anUpgradeNeverRunsAgainstACopyOfTheData() {
    assertDoesNotThrow(() -> UpgradeCommand.requireProvisionedData(null));
    assertDoesNotThrow(() -> UpgradeCommand.requireProvisionedData("/var/lib/sail"));

    var refused =
        assertThrows(
            IllegalStateException.class,
            () -> UpgradeCommand.requireProvisionedData("/root/rehearsal"));

    assertTrue(refused.getMessage().contains("SAIL_DATA_DIR is /root/rehearsal"));
    assertTrue(refused.getMessage().contains("'SAIL_DATA_DIR=/root/rehearsal <new sail> migrate'"));
    assertTrue(refused.getMessage().contains("unset SAIL_DATA_DIR"));
  }

  @Test
  void binaryExcludesTargetAndCheck() throws Exception {
    assertRefused(
        upgrade(versions("0.47.0"), "--binary", candidate.toString(), "--target", "0.47.0"),
        "--target picks a release to download",
        "Pass either --binary <file> or --target <version>, not both");
    assertRefused(
        upgrade(versions("0.47.0"), "--binary", candidate.toString(), "--check"),
        "--check asks GitHub for the latest release",
        "Pass either --binary <file> or --check, not both");
  }

  @Test
  void theInstalledBuildAgainIsANoOp() throws Exception {
    var copy = Files.copy(installed, tempDir.resolve("sail-again"));

    var run = upgrade(versions("0.46.0"), "--binary", copy.toString(), "--json");

    assertEquals(0, run.exit(), run::err);
    assertTrue(run.out().contains("\"status\": \"up_to_date\""), run.out());
    assertTrue(run.out().contains("\"to\": \"0.46.0\""), run.out());
    assertInstalled("installed");
  }

  @Test
  void aDryRunPlansTheSameInstallAsADownloadMinusTheDownload() throws Exception {
    var local = upgrade(versions("0.46.0"), "--binary", candidate.toString(), "--dry-run");
    var download = upgrade(versions("0.46.0"), "--target", "0.46.0", "--dry-run");

    assertEquals(0, local.exit(), local::err);
    assertEquals(0, download.exit(), download::err);
    assertTrue(local.out().contains("Verified " + candidate + " (sail 0.46.0)"), local.out());
    assertTrue(local.out().contains("[dry-run] Write new binary to " + installed), local.out());
    assertEquals(
        plan(download.out()).replaceAll("(?m)^\\[dry-run] (Download|Verify SHA-256).*\\R", ""),
        plan(local.out()));
    assertInstalled("installed");
  }

  @Test
  void aCheckReportsTheInstalledSailNotTheRunningOne() {
    var run = upgrade(() -> "98.0.0", path -> SemVer.parse("0.1.0"), "--check", "--json");

    assertEquals(0, run.exit(), run::err);
    assertTrue(run.out().contains("\"current\": \"0.1.0\""), run.out());
    assertTrue(run.out().contains("\"update_available\": true"), run.out());
  }

  @Test
  void aCheckOfAnInstalledSailThatCannotSayItsVersionOffersTheRelease() {
    var run = upgrade(() -> "98.0.0", SailBinary::versionOf, "--check", "--json");

    assertEquals(0, run.exit(), run::err);
    assertTrue(run.out().contains("\"current\": \"unknown\""), run.out());
    assertTrue(run.out().contains("\"update_available\": true"), run.out());
  }

  @Test
  void aReleaseUpgradesTheInstalledSailFromItsOwnVersion() throws Exception {
    var run = upgrade(() -> "98.0.0", path -> SemVer.parse("0.1.0"), "--dry-run");

    assertEquals(0, run.exit(), run::err);
    assertTrue(run.out().contains("0.1.0 \u2192 98.0.0"), run.out());
    assertTrue(run.out().contains("[dry-run] Write new binary to " + installed), run.out());
    assertInstalled("installed");
  }

  @Test
  void anInstalledBuildNewerThanTheLatestReleaseIsNotDowngraded() throws Exception {
    var run = upgrade(() -> "98.0.0", path -> SemVer.parse("99.0.0"), "--dry-run", "--json");

    assertEquals(0, run.exit(), run::err);
    assertTrue(run.out().contains("\"status\": \"up_to_date\""), run.out());
    assertTrue(run.out().contains("\"from\": \"99.0.0\""), run.out());
    assertFalse(run.out().contains("[dry-run]"), run.out());
    assertInstalled("installed");
  }

  private record Run(int exit, String out, String err) {}

  private Function<Path, SemVer> versions(String offered) {
    return path -> path.equals(installed) ? INSTALLED_VERSION : SemVer.parse(offered);
  }

  private Run upgrade(Function<Path, SemVer> versionOf, String... args) {
    return upgrade(
        () -> {
          throw new AssertionError("Only a release upgrade looks up the latest release");
        },
        versionOf,
        args);
  }

  private Run upgrade(
      Callable<String> latestRelease, Function<Path, SemVer> versionOf, String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    var originalOut = System.out;
    var originalErr = System.err;
    try (var capturedOut = new PrintStream(out, true, StandardCharsets.UTF_8);
        var capturedErr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      System.setOut(capturedOut);
      System.setErr(capturedErr);
      var command =
          new CommandLine(
              new UpgradeCommand(versionOf, () -> installed, latestRelease, () -> false));
      var exit = command.execute(args);
      return new Run(
          exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }

  private void assertRefused(Run run, String... phrases) throws Exception {
    assertTrue(run.exit() != 0, run::out);
    for (var phrase : phrases) {
      assertTrue(run.err().contains(phrase), run.err());
    }
    assertInstalled("installed");
  }

  private void assertNothingStaged() {
    assertFalse(Files.exists(installed.resolveSibling("sail.tmp")));
  }

  private void assertInstalled(String build) throws Exception {
    assertEquals(
        build, new String(Files.readAllBytes(installed), StandardCharsets.UTF_8).substring(8));
  }

  private static String plan(String output) {
    return output
        .lines()
        .filter(line -> line.startsWith("[dry-run]"))
        .map(line -> line + "\n")
        .reduce("", String::concat);
  }

  /** Eight bytes that open an executable for {@code osName}: Mach-O on macOS, ELF elsewhere. */
  private static byte[] executableHeader(String osName) {
    var os = osName.toLowerCase();
    return os.contains("mac") || os.contains("darwin")
        ? new byte[] {(byte) 0xcf, (byte) 0xfa, (byte) 0xed, (byte) 0xfe, 0x0c, 0, 0, 0x01}
        : new byte[] {0x7f, 'E', 'L', 'F', 2, 1, 1, 0};
  }

  private static Path executable(Path file, String build) throws Exception {
    Files.createDirectories(file.getParent());
    var bytes = new ByteArrayOutputStream();
    bytes.write(HEADER);
    bytes.write(build.getBytes(StandardCharsets.UTF_8));
    Files.write(file, bytes.toByteArray());
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    return file;
  }
}
