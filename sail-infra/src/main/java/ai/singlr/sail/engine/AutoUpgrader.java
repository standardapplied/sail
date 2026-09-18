/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.SailVersion;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import picocli.CommandLine.Help.Ansi;

/**
 * Silent auto-upgrader that runs before every CLI command. When a newer version is available,
 * downloads the binary, verifies its checksum and ELF header, replaces the current binary, and
 * re-execs the original command with the new version.
 *
 * <p>Rate-limited by {@link UpdateChecker}'s 24-hour cache — no network call when cache is fresh.
 * On any failure, silently falls back to the current binary. Never disrupts the actual command.
 *
 * <p>Skips for: dev builds, {@code SAIL_NO_UPDATE_CHECK=1}, {@code SAIL_AUTO_UPGRADED=1}, {@code
 * upgrade} subcommand (handles itself), {@code --version}/{@code --help}.
 */
public final class AutoUpgrader {

  private static final Set<String> SKIP_ARGS = Set.of("upgrade", "--version", "-V", "--help", "-h");

  private AutoUpgrader() {}

  /**
   * Checks for a newer version and auto-upgrades if available. Call early in {@code main()} before
   * command execution. If an upgrade succeeds, this method re-execs the original command with the
   * new binary and calls {@link System#exit} — it never returns in that case.
   *
   * @param args the original command-line arguments
   */
  public static void upgradeIfAvailable(String[] args) {
    if (shouldSkip(args)) {
      return;
    }
    try {
      doUpgrade(args);
    } catch (Exception ignored) {
    }
  }

  static boolean shouldSkip(String[] args) {
    return shouldSkip(SailVersion.version(), System::getenv, System.console() != null, args);
  }

  /**
   * Pure skip decision; version, environment and interactivity are injected so it is unit-tested.
   * Internal lanes never upgrade: {@code _sync}, {@code _gateway}, {@code _pty-host} and their kin
   * run unattended under a forced command or a service, where the download's sudo prompt lands in a
   * sync pipe or nowhere at all (the field incident of 2026-09-17: main's {@code _sync} asked a
   * node's terminal for main's sudo password). Nor does any invocation without a console to answer
   * that prompt. Upgrading unattended machinery is {@code sail upgrade}, run on purpose.
   */
  static boolean shouldSkip(
      String version, UnaryOperator<String> env, boolean interactive, String[] args) {
    if ("dev".equals(version) || !interactive) {
      return true;
    }
    if (args.length > 0 && args[0].startsWith("_")) {
      return true;
    }
    if ("1".equals(env.apply("SAIL_NO_UPDATE_CHECK"))) {
      return true;
    }
    if ("1".equals(env.apply("SAIL_AUTO_UPGRADED"))) {
      return true;
    }
    for (var arg : args) {
      if (SKIP_ARGS.contains(arg)) {
        return true;
      }
    }
    return false;
  }

  /** Whether {@code latestVersion} is strictly newer than {@code currentVersion}. */
  static boolean shouldUpgrade(String currentVersion, String latestVersion) {
    return SemVer.parse(currentVersion).compareTo(SemVer.parse(latestVersion)) < 0;
  }

  /**
   * Whether a downloaded artifact may be trusted: its SHA-256 matches the published checksum and it
   * carries the expected executable magic for the platform. The {@code osName} is injected so the
   * accept/reject decision is unit-tested.
   */
  public static boolean isAcceptable(byte[] binary, String expectedChecksum) {
    return isAcceptable(binary, expectedChecksum, System.getProperty("os.name", ""));
  }

  static boolean isAcceptable(byte[] binary, String expectedChecksum, String osName) {
    return checksumMatches(binary, expectedChecksum)
        && PlatformDetector.isValidBinary(binary, osName);
  }

  private static boolean checksumMatches(byte[] binary, String expectedChecksum) {
    try {
      var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(binary));
      return actual.equalsIgnoreCase(expectedChecksum);
    } catch (NoSuchAlgorithmException e) {
      return false;
    }
  }

  private static void doUpgrade(String[] args) throws Exception {
    var cacheFile = SailPaths.updateCheckFile();
    var latestVersionStr = UpdateChecker.doCheck(cacheFile);
    if (latestVersionStr == null) {
      return;
    }

    if (!shouldUpgrade(SailVersion.version(), latestVersionStr)) {
      return;
    }
    var latest = SemVer.parse(latestVersionStr);

    var versionTag = "v" + latest;
    var binaryPath = SailPaths.binaryPath();
    var installDir = binaryPath.getParent();

    System.err.println(
        Ansi.AUTO.string(
            "  @|faint Updating sail "
                + SailVersion.version()
                + " \u2192 "
                + latestVersionStr
                + "...|@"));

    var binary = ReleaseFetcher.downloadBinary(versionTag);
    var expectedChecksum = ReleaseFetcher.fetchChecksum(versionTag);
    if (!isAcceptable(binary, expectedChecksum)) {
      return;
    }

    if (installDir != null && Files.isWritable(installDir)) {
      installDirect(binary, binaryPath);
    } else {
      installWithSudo(binary, binaryPath);
    }

    System.err.println(
        Ansi.AUTO.string(
            "  @|bold,green \u2713|@ @|faint Updated to sail " + latestVersionStr + "|@\n"));

    reExec(binaryPath, args);
  }

  private static void installDirect(byte[] binary, Path binaryPath) throws IOException {
    var tmpPath = binaryPath.resolveSibling("sail.tmp");
    Files.write(tmpPath, binary);
    if (Files.exists(binaryPath)) {
      Files.setPosixFilePermissions(tmpPath, Files.getPosixFilePermissions(binaryPath));
    } else {
      Files.setPosixFilePermissions(
          tmpPath,
          Set.of(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE,
              PosixFilePermission.GROUP_READ,
              PosixFilePermission.GROUP_EXECUTE,
              PosixFilePermission.OTHERS_READ,
              PosixFilePermission.OTHERS_EXECUTE));
    }
    Files.move(
        tmpPath, binaryPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }

  private static void installWithSudo(byte[] binary, Path binaryPath) throws Exception {
    var tmpFile = Files.createTempFile("sail-upgrade-", ".tmp");
    try {
      Files.write(tmpFile, binary);
      var process =
          new ProcessBuilder(
                  "sudo", "install", "-m", "755", tmpFile.toString(), binaryPath.toString())
              .inheritIO()
              .start();
      var exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IOException("sudo install failed (exit " + exitCode + ")");
      }
    } finally {
      Files.deleteIfExists(tmpFile);
    }
  }

  private static void reExec(Path binaryPath, String[] args) throws Exception {
    var cmd = new ArrayList<String>();
    cmd.add(binaryPath.toString());
    cmd.addAll(List.of(args));
    var pb = new ProcessBuilder(cmd);
    pb.environment().put("SAIL_AUTO_UPGRADED", "1");
    pb.inheritIO();
    var exitCode = pb.start().waitFor();
    System.exit(exitCode);
  }
}
