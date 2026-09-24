/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.SailVersion;
import ai.singlr.sail.engine.PlatformDetector;
import ai.singlr.sail.engine.ShellExec;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class UpgradeCommandTest {

  private static final byte[] ELF = {0x7f, 'E', 'L', 'F', 2, 1, 1, 0};

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
  void aBinaryThatCannotBeRunIsRefusedNamingChmod() throws Exception {
    Files.setPosixFilePermissions(candidate, PosixFilePermissions.fromString("rw-r--r--"));

    var run =
        upgrade(
            path -> path.equals(installed) ? "0.46.0" : UpgradeCommand.versionOf(path),
            "--binary",
            candidate.toString());

    assertRefused(run, "Could not run '" + candidate + " -V'", "chmod +x " + candidate);
  }

  @Test
  void anOlderVersionIsRefusedBecauseMigrationsNeverRunBackwards() throws Exception {
    var run = upgrade(versions("0.45.9"), "--binary", candidate.toString());

    assertRefused(
        run,
        candidate + " is sail 0.45.9, older than the installed sail 0.46.0",
        "pass sail 0.46.0 or newer");
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
    var run = upgrade(() -> "98.0.0", path -> "0.1.0", "--check", "--json");

    assertEquals(0, run.exit(), run::err);
    assertTrue(run.out().contains("\"current\": \"0.1.0\""), run.out());
    assertTrue(run.out().contains("\"update_available\": true"), run.out());
  }

  @Test
  void aReleaseUpgradesTheInstalledSailFromItsOwnVersion() throws Exception {
    var run = upgrade(() -> "98.0.0", path -> "0.1.0", "--dry-run");

    assertEquals(0, run.exit(), run::err);
    assertTrue(run.out().contains("0.1.0 \u2192 98.0.0"), run.out());
    assertTrue(run.out().contains("[dry-run] Write new binary to " + installed), run.out());
    assertInstalled("installed");
  }

  @Test
  void anInstalledBuildNewerThanTheLatestReleaseIsNotDowngraded() throws Exception {
    var run = upgrade(() -> "98.0.0", path -> "99.0.0", "--dry-run", "--json");

    assertEquals(0, run.exit(), run::err);
    assertTrue(run.out().contains("\"status\": \"up_to_date\""), run.out());
    assertTrue(run.out().contains("\"from\": \"99.0.0\""), run.out());
    assertFalse(run.out().contains("[dry-run]"), run.out());
    assertInstalled("installed");
  }

  @Test
  void theVersionIsWhatTheBinaryAnswersToDashV() throws Exception {
    var script = executable(tempDir.resolve("fake-sail"), "");
    Files.writeString(script, "#!/bin/sh\necho \"sail 0.47.1\"\n");

    assertEquals("0.47.1", UpgradeCommand.versionOf(script));
  }

  @Test
  void aBinaryThatDoesNotAnswerAsSailIsRefused() {
    var file = Path.of("/opt/other");

    for (var answer :
        List.of(
            new ShellExec.Result(0, "other 1.0.0\n", ""),
            new ShellExec.Result(0, "sail dev\n", ""),
            new ShellExec.Result(1, "", "boom"))) {
      var refused =
          assertThrows(
              IllegalArgumentException.class, () -> UpgradeCommand.parseVersion(file, answer));
      assertTrue(refused.getMessage().contains("Pass a sail binary"), refused.getMessage());
    }
    assertEquals(
        "0.46.0", UpgradeCommand.parseVersion(file, new ShellExec.Result(0, "sail 0.46.0\n", "")));
  }

  private record Run(int exit, String out, String err) {}

  private Function<Path, String> versions(String offered) {
    return path -> path.equals(installed) ? "0.46.0" : offered;
  }

  private Run upgrade(Function<Path, String> versionOf, String... args) {
    return upgrade(
        () -> {
          throw new AssertionError("Only a release upgrade looks up the latest release");
        },
        versionOf,
        args);
  }

  private Run upgrade(
      Callable<String> latestRelease, Function<Path, String> versionOf, String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    var originalOut = System.out;
    var originalErr = System.err;
    try (var capturedOut = new PrintStream(out, true, StandardCharsets.UTF_8);
        var capturedErr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      System.setOut(capturedOut);
      System.setErr(capturedErr);
      var command = new CommandLine(new UpgradeCommand(versionOf, () -> installed, latestRelease));
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

  private static Path executable(Path file, String build) throws Exception {
    Files.createDirectories(file.getParent());
    var bytes = new ByteArrayOutputStream();
    bytes.write(ELF);
    bytes.write(build.getBytes(StandardCharsets.UTF_8));
    Files.write(file, bytes.toByteArray());
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    return file;
  }
}
