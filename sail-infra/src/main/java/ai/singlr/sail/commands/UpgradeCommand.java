/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.SailVersion;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.PlatformDetector;
import ai.singlr.sail.engine.ReleaseFetcher;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.SemVer;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "upgrade",
    description = "Upgrade SAIL to the latest version.",
    mixinStandardHelpOptions = true)
public final class UpgradeCommand implements Runnable {

  @Option(names = "--check", description = "Check for updates without installing.")
  private boolean checkOnly;

  @Option(names = "--target", description = "Install a specific version (e.g. 1.7.0).")
  private String targetVersion;

  @Option(names = "--dry-run", description = "Print actions instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    var currentVersion = SailVersion.version();
    if ("dev".equals(currentVersion)) {
      throw new IllegalStateException(
          "Cannot upgrade a development build. Install a release version first.");
    }
    var current = SemVer.parse(currentVersion);

    String latestVersionStr;
    if (targetVersion != null) {
      latestVersionStr = targetVersion.startsWith("v") ? targetVersion.substring(1) : targetVersion;
    } else {
      latestVersionStr = ReleaseFetcher.fetchLatestVersion();
    }
    var latest = SemVer.parse(latestVersionStr);
    var versionTag = "v" + latest;

    if (checkOnly) {
      printCheckResult(currentVersion, latestVersionStr, current.compareTo(latest));
      return;
    }

    if (current.compareTo(latest) >= 0 && targetVersion == null) {
      if (json) {
        printJsonResult(currentVersion, latestVersionStr, "up_to_date", null);
      } else {
        System.out.println(
            Ansi.AUTO.string(
                "  @|green \u2713|@ Already up to date (sail " + currentVersion + ")"));
      }
      return;
    }

    var binaryPath = SailPaths.binaryPath();
    var installDir = binaryPath.getParent();
    if (!dryRun && installDir != null && !Files.isWritable(installDir)) {
      if (!ConsoleHelper.isRoot()) {
        if (!json) {
          System.out.println(
              Ansi.AUTO.string("  @|faint Installing to " + installDir + " (requires sudo)...|@"));
        }
        reExecWithSudo();
        return;
      }
    }

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println(
          Ansi.AUTO.string(
              "  @|bold Upgrading:|@ " + currentVersion + " \u2192 " + latestVersionStr));
      System.out.println();
    }

    if (!json) {
      System.out.println(
          Banner.stepLine(1, 3, "Downloading sail " + versionTag + "...", Ansi.AUTO));
    }
    byte[] binary;
    if (dryRun) {
      System.out.println(
          "[dry-run] Download " + ReleaseFetcher.buildDownloadUrl(versionTag, "sail"));
      binary = new byte[0];
    } else {
      binary =
          targetVersion != null
              ? ReleaseFetcher.downloadBinary(versionTag)
              : ReleaseFetcher.downloadLatestBinary();
      if (!json) {
        System.out.println(
            Banner.stepDoneLine(
                1, 3, "Downloaded (" + (binary.length / 1024 / 1024) + " MB)", Ansi.AUTO));
      }
    }

    if (!json) {
      System.out.println(Banner.stepLine(2, 3, "Verifying checksum...", Ansi.AUTO));
    }
    if (dryRun) {
      System.out.println("[dry-run] Verify SHA-256 checksum");
    } else {
      var expectedChecksum =
          targetVersion != null
              ? ReleaseFetcher.fetchChecksum(versionTag)
              : ReleaseFetcher.fetchLatestChecksum();
      var digest = MessageDigest.getInstance("SHA-256");
      var actualChecksum = HexFormat.of().formatHex(digest.digest(binary));
      if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
        throw new IOException(
            "Checksum mismatch.\n  Expected: "
                + expectedChecksum
                + "\n  Actual:   "
                + actualChecksum
                + "\n  The download may be corrupted. Try again.");
      }
      if (!json) {
        System.out.println(Banner.stepDoneLine(2, 3, "Checksum verified", Ansi.AUTO));
      }
    }

    if (!dryRun && !PlatformDetector.isValidBinary(binary)) {
      throw new IOException("Downloaded file is not a valid binary for this platform.");
    }

    if (!json) {
      System.out.println(Banner.stepLine(3, 4, "Installing to " + binaryPath + "...", Ansi.AUTO));
    }
    if (dryRun) {
      System.out.println("[dry-run] Write new binary to " + binaryPath);
      System.out.println("[dry-run] chmod +x " + binaryPath);
    } else {
      var tmpPath = binaryPath.resolveSibling("sail.tmp");
      Files.write(tmpPath, binary);
      Files.setPosixFilePermissions(tmpPath, Files.getPosixFilePermissions(binaryPath));
      Files.move(
          tmpPath, binaryPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      if (!json) {
        System.out.println(Banner.stepDoneLine(3, 4, "Installed", Ansi.AUTO));
      }
    }

    migrateDatabase(dryRun);

    var restartStatus = restartSailApi(dryRun);

    if (json) {
      printJsonResult(
          currentVersion, latestVersionStr, "upgraded", binaryPath.toString(), restartStatus);
    } else {
      System.out.println();
      System.out.println(
          Ansi.AUTO.string("  @|bold,green \u2713 Upgraded to sail " + latestVersionStr + "|@"));
    }
  }

  /**
   * Detects whether {@code sail-api.service} is installed (system- or user-level depending on who's
   * invoking this command) and restarts it if it was active so the new binary takes effect
   * immediately. Returns a short status string for the JSON payload. Failures are logged but never
   * fatal \u2014 the binary install already succeeded.
   */
  private RestartStatus restartSailApi(boolean dryRun) {
    var stepNum = 4;
    var totalSteps = 4;
    if (dryRun) {
      if (!json) {
        System.out.println(
            Banner.stepLine(stepNum, totalSteps, "Reconcile + restart sail-api...", Ansi.AUTO));
        System.out.println(
            "[dry-run] Would re-render the unit file from the new binary, daemon-reload, and"
                + " restart sail-api.service when installed.");
      }
      return RestartStatus.DRY_RUN;
    }
    if (!json) {
      System.out.println(
          Banner.stepLine(
              stepNum, totalSteps, "Reconciling sail-api unit + restart...", Ansi.AUTO));
    }
    try {
      var shell = new ShellExecutor(false);
      var installer =
          HostServiceInstallers.create(
              shell, "127.0.0.1", 7070, HostServiceInstallers.currentUsername());
      if (!installer.isInstalled()) {
        if (!json) {
          System.out.println(
              Banner.stepDoneLine(
                  stepNum, totalSteps, "sail-api not installed; nothing to restart", Ansi.AUTO));
        }
        return RestartStatus.NOT_INSTALLED;
      }
      // Re-render the unit file IF it drifted from the new binary's template. Without this,
      // template changes shipped with a new binary stay invisible to systemd — the bug that
      // silently held 0.12.5 / 0.12.6 in production stuck on the 0.12.0 RuntimeDirectory mode.
      var driftReconciled = reconcileUnitFile(installer);
      var status = installer.status();
      if (!status.running()) {
        if (!json) {
          System.out.println(
              Banner.stepDoneLine(
                  stepNum,
                  totalSteps,
                  "sail-api is installed but not running; left as-is"
                      + (driftReconciled ? " (unit file reconciled)" : ""),
                  Ansi.AUTO));
        }
        return RestartStatus.NOT_RUNNING;
      }
      installer.restart();
      if (!json) {
        var modeLabel =
            installer.mode() == SystemdServiceInstaller.Mode.SYSTEM ? "system-level" : "user-level";
        var reconciledTag = driftReconciled ? " (unit file reconciled)" : "";
        System.out.println(
            Banner.stepDoneLine(
                stepNum,
                totalSteps,
                "Restarted sail-api (" + modeLabel + ") on the new binary" + reconciledTag,
                Ansi.AUTO));
      }
      return RestartStatus.RESTARTED;
    } catch (Exception e) {
      if (!json) {
        System.err.println(
            Banner.errorLine(
                "sail-api restart failed: "
                    + e.getMessage()
                    + ". The new binary is on disk; restart manually with:"
                    + " 'systemctl restart sail-api' (or 'systemctl --user restart sail-api').",
                Ansi.AUTO));
      }
      return RestartStatus.FAILED;
    }
  }

  /**
   * Delegates to {@link SystemdServiceInstaller#reconcile} so a binary upgrade that ships a
   * template change rewrites the on-disk unit and {@code daemon-reload}s before the restart.
   */
  private boolean reconcileUnitFile(SystemdServiceInstaller installer) throws Exception {
    return installer.reconcile();
  }

  private void migrateDatabase(boolean dryRun) {
    var dbPath = SailPaths.sailDir().resolve("sail.db");
    if (dryRun) {
      if (!json) {
        System.out.println("[dry-run] Would run database schema migration on " + dbPath);
      }
      return;
    }
    try {
      Files.createDirectories(dbPath.getParent());
      try (var db = Sqlite.open(dbPath)) {
        var schema = new SchemaManager(db);
        var before = schema.currentVersion();
        schema.migrate();
        var after = schema.currentVersion();
        if (!json && after > before) {
          System.out.println(
              Ansi.AUTO.string(
                  "    @|faint Database schema migrated: " + before + " → " + after + "|@"));
        }
      }
    } catch (Exception e) {
      if (!json) {
        System.err.println(
            "    Database migration skipped: "
                + e.getMessage()
                + ". Run 'sail server init' manually.");
      }
    }
  }

  /** Lifecycle outcome for the {@code sail-api} restart step. */
  private enum RestartStatus {
    NOT_INSTALLED("not_installed"),
    NOT_RUNNING("not_running"),
    RESTARTED("restarted"),
    FAILED("failed"),
    DRY_RUN("dry_run");

    private final String wireValue;

    RestartStatus(String wireValue) {
      this.wireValue = wireValue;
    }

    String wireValue() {
      return wireValue;
    }
  }

  private void printCheckResult(String current, String latest, int comparison) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("current", current);
      map.put("latest", latest);
      map.put("update_available", comparison < 0);
      System.out.println(YamlUtil.dumpJson(map));
    } else {
      if (comparison < 0) {
        System.out.println(
            Ansi.AUTO.string("  @|bold Update available:|@ " + current + " \u2192 " + latest));
        System.out.println(Ansi.AUTO.string("  @|faint Run 'sail upgrade' to install.|@"));
      } else {
        System.out.println(
            Ansi.AUTO.string("  @|green \u2713|@ Already up to date (sail " + current + ")"));
      }
    }
  }

  private void printJsonResult(
      String from, String to, String status, String path, RestartStatus restart) {
    var map = new LinkedHashMap<String, Object>();
    map.put("status", status);
    map.put("from", from);
    map.put("to", to);
    if (path != null) {
      map.put("path", path);
    }
    if (restart != null) {
      map.put("service_restart", restart.wireValue());
    }
    System.out.println(YamlUtil.dumpJson(map));
  }

  private void printJsonResult(String from, String to, String status, String path) {
    printJsonResult(from, to, status, path, null);
  }

  /** Re-executes the current command with sudo, inheriting stdin/stdout/stderr. */
  private void reExecWithSudo() throws IOException, InterruptedException {
    var args = new java.util.ArrayList<String>();
    args.add("sudo");
    args.add(SailPaths.binaryPath().toString());
    args.add("upgrade");
    if (targetVersion != null) {
      args.add("--target");
      args.add(targetVersion);
    }
    if (json) {
      args.add("--json");
    }
    var process = new ProcessBuilder(args).inheritIO().start();
    var exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IllegalStateException("sudo sail upgrade failed (exit code " + exitCode + ")");
    }
  }
}
