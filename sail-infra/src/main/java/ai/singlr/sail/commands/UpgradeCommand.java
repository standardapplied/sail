/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.SailVersion;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.PlatformDetector;
import ai.singlr.sail.engine.ReleaseFetcher;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.SemVer;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
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

  @Option(
      names = "--binary",
      paramLabel = "<file>",
      description =
          "Install this sail binary instead of downloading a release: an unreleased build, or a"
              + " box without internet access.")
  private Path localBinary;

  @Option(names = "--dry-run", description = "Print actions instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  private final Function<Path, String> versionOf;
  private final Supplier<Path> installedBinary;
  private final Callable<String> latestRelease;

  public UpgradeCommand() {
    this(UpgradeCommand::versionOf, SailPaths::installedBinary, ReleaseFetcher::fetchLatestVersion);
  }

  /**
   * @param versionOf the version a sail binary reports
   * @param installedBinary where the upgrade installs, and whose version it upgrades from
   * @param latestRelease the version of the latest release
   */
  UpgradeCommand(
      Function<Path, String> versionOf,
      Supplier<Path> installedBinary,
      Callable<String> latestRelease) {
    this.versionOf = versionOf;
    this.installedBinary = installedBinary;
    this.latestRelease = latestRelease;
  }

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    if ("dev".equals(SailVersion.version())) {
      throw new IllegalStateException(
          "Cannot upgrade a development build. Install a release version first.");
    }
    if (localBinary != null) {
      installLocal();
      return;
    }
    var binaryPath = installedBinary.get();
    var currentVersion = versionOf.apply(binaryPath);
    var current = SemVer.parse(currentVersion);

    String latestVersionStr;
    if (targetVersion != null) {
      latestVersionStr = targetVersion.startsWith("v") ? targetVersion.substring(1) : targetVersion;
    } else {
      latestVersionStr = latestRelease.call();
    }
    var latest = SemVer.parse(latestVersionStr);
    var versionTag = "v" + latest;

    if (checkOnly) {
      printCheckResult(currentVersion, latestVersionStr, current.compareTo(latest));
      return;
    }

    if (current.compareTo(latest) >= 0 && targetVersion == null) {
      printUpToDate(currentVersion);
      return;
    }

    if (needsSudo(binaryPath)) {
      return;
    }

    printBanner(currentVersion, latestVersionStr);

    if (!json) {
      System.out.println(
          Banner.stepLine(1, 4, "Downloading sail " + versionTag + "...", Ansi.AUTO));
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
                1, 4, "Downloaded (" + (binary.length / 1024 / 1024) + " MB)", Ansi.AUTO));
      }
    }

    if (!json) {
      System.out.println(Banner.stepLine(2, 4, "Verifying checksum...", Ansi.AUTO));
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
      if (!PlatformDetector.isValidBinary(binary)) {
        throw new IOException(
            "The download is not a " + PlatformDetector.platformSuffix() + " executable.");
      }
      if (!json) {
        System.out.println(Banner.stepDoneLine(2, 4, "Checksum verified", Ansi.AUTO));
      }
    }

    install(binary, binaryPath, currentVersion, latestVersionStr, new Steps(3, 4));
  }

  /**
   * {@code --binary}: the same install as a download, from a file already on this box. Refused
   * before anything changes when the file is missing, is no sail for this platform, or is older
   * than the installed sail — migrations never run backwards. The installed version's own bytes are
   * a no-op, so a repeated upgrade restarts nothing; a different build of the same version is
   * reinstalled.
   */
  private void installLocal() throws Exception {
    if (targetVersion != null || checkOnly) {
      throw new IllegalArgumentException(
          (checkOnly
                  ? "--check asks GitHub for the latest release, which --binary does not use."
                  : "--target picks a release to download, which --binary does not do.")
              + " Pass either --binary <file> or "
              + (checkOnly ? "--check" : "--target <version>")
              + ", not both.");
    }
    var file = localBinary.toAbsolutePath();
    var binary = readBinary(file);
    var offered = versionOf.apply(file);
    var binaryPath = installedBinary.get();
    var installed = versionOf.apply(binaryPath);
    if (SemVer.parse(offered).compareTo(SemVer.parse(installed)) < 0) {
      throw new IllegalArgumentException(
          file
              + " is sail "
              + offered
              + ", older than the installed sail "
              + installed
              + ". Migrations never run backwards: pass sail "
              + installed
              + " or newer.");
    }
    if (Arrays.equals(binary, Files.readAllBytes(binaryPath))) {
      printUpToDate(installed);
      return;
    }
    if (needsSudo(binaryPath)) {
      return;
    }
    printBanner(installed, offered);
    if (!json) {
      System.out.println(
          Banner.stepDoneLine(1, 3, "Verified " + file + " (sail " + offered + ")", Ansi.AUTO));
    }
    install(binary, binaryPath, installed, offered, new Steps(2, 3));
  }

  /**
   * The bytes of {@code file}, once they are known to be an executable for this platform — a
   * missing file or anything else is refused naming what to pass instead.
   */
  private static byte[] readBinary(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException(
          "No file at "
              + file
              + ". Pass --binary the path of a sail binary, e.g. --binary ./sail-"
              + PlatformDetector.platformSuffix()
              + ".");
    }
    var binary = Files.readAllBytes(file);
    if (!PlatformDetector.isValidBinary(binary)) {
      throw new IllegalArgumentException(
          file
              + " is not a "
              + PlatformDetector.platformSuffix()
              + " executable. Pass a sail binary built for this platform.");
    }
    return binary;
  }

  /**
   * The version {@code binary -V} reports. A file that cannot be run, or that answers as anything
   * but sail, is refused naming the remedy.
   */
  static String versionOf(Path binary) {
    ShellExec.Result result;
    try {
      result = new ShellExecutor(false).exec(List.of(binary.toString(), "-V"));
    } catch (IOException | TimeoutException e) {
      throw new IllegalArgumentException(
          "Could not run '"
              + binary
              + " -V' ("
              + e.getMessage()
              + "). Make it executable (chmod +x "
              + binary
              + ") and pass a sail binary.",
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted reading the version of " + binary, e);
    }
    return parseVersion(binary, result);
  }

  static String parseVersion(Path binary, ShellExec.Result result) {
    var answer = result.stdout().strip();
    var version = answer.startsWith("sail ") ? answer.substring("sail ".length()) : "";
    if (!result.ok() || SemVer.tryParse(version).isEmpty()) {
      throw new IllegalArgumentException(
          "'"
              + binary
              + " -V' answered '"
              + (answer.isEmpty() ? result.stderr().strip() : answer)
              + "', not 'sail <version>'. Pass a sail binary.");
    }
    return version;
  }

  /** Step numbering for the steps every upgrade shares: install, then reconcile + restart. */
  private record Steps(int install, int total) {
    int restart() {
      return install + 1;
    }
  }

  /**
   * Re-executes under sudo when the install directory is not writable and this is not root. Returns
   * whether it did, in which case the sudo run has done the upgrade.
   */
  private boolean needsSudo(Path binaryPath) throws IOException, InterruptedException {
    var installDir = binaryPath.getParent();
    if (dryRun || installDir == null || Files.isWritable(installDir) || ConsoleHelper.isRoot()) {
      return false;
    }
    if (!json) {
      System.out.println(
          Ansi.AUTO.string("  @|faint Installing to " + installDir + " (requires sudo)...|@"));
    }
    reExecWithSudo();
    return true;
  }

  private void printBanner(String from, String to) {
    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println(Ansi.AUTO.string("  @|bold Upgrading:|@ " + from + " \u2192 " + to));
      System.out.println();
    }
  }

  private void printUpToDate(String version) {
    if (json) {
      printJsonResult(version, version, "up_to_date", null);
    } else {
      System.out.println(
          Ansi.AUTO.string("  @|green \u2713|@ Already up to date (sail " + version + ")"));
    }
  }

  /**
   * Everything after the binary is in hand: install it over {@code binaryPath}, run its own {@code
   * migrate}, and reconcile and restart {@code sail-api} on it.
   */
  private void install(byte[] binary, Path binaryPath, String from, String to, Steps steps)
      throws IOException {
    if (!json) {
      System.out.println(
          Banner.stepLine(
              steps.install(), steps.total(), "Installing to " + binaryPath + "...", Ansi.AUTO));
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
        System.out.println(
            Banner.stepDoneLine(steps.install(), steps.total(), "Installed", Ansi.AUTO));
      }
    }

    RestartStatus restartStatus;
    if (HostYaml.exists()) {
      migrateDatabase(binaryPath);
      restartStatus = restartSailApi(steps);
    } else {
      restartStatus = RestartStatus.SKIPPED_CLIENT;
      if (!json) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|faint Client machine (no host.yaml) — database and service steps skipped.|@"));
      }
    }

    if (json) {
      printJsonResult(from, to, "upgraded", binaryPath.toString(), restartStatus);
    } else {
      System.out.println();
      System.out.println(Ansi.AUTO.string("  @|bold,green \u2713 Upgraded to sail " + to + "|@"));
    }
  }

  /**
   * Guidance when {@code sail upgrade} finds no sail-api on a box that should have one. A
   * provisioned box (it has {@code host.yaml}) is a working dev box that dispatches agents and
   * serves the board, so a missing service is a gap to fix, not a no-op — point at the one command
   * that installs it. An unprovisioned box (a thin client) legitimately has no service, so there is
   * nothing to say.
   */
  static Optional<String> missingApiRemediation(boolean provisioned) {
    if (!provisioned) {
      return Optional.empty();
    }
    return Optional.of(
        "  @|yellow Install it so you can dispatch agents and see the board here:|@"
            + " @|bold sudo sail host service install|@");
  }

  /**
   * Detects whether {@code sail-api.service} is installed (system- or user-level depending on who's
   * invoking this command) and restarts it if it was active so the new binary takes effect
   * immediately. Returns a short status string for the JSON payload. Failures are logged but never
   * fatal \u2014 the binary install already succeeded.
   */
  private RestartStatus restartSailApi(Steps steps) {
    var stepNum = steps.restart();
    var totalSteps = steps.total();
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
      var defaultEndpoint = new Endpoint("127.0.0.1", 7070);
      var bootstrap = HostServiceInstallers.existing(shell);
      if (!bootstrap.isInstalled()) {
        if (!json) {
          var provisioned = Files.exists(SailPaths.hostConfigPath());
          System.out.println(
              Banner.stepDoneLine(
                  stepNum,
                  totalSteps,
                  provisioned
                      ? "sail-api is not installed on this provisioned box"
                      : "sail-api not installed; nothing to restart",
                  Ansi.AUTO));
          missingApiRemediation(provisioned)
              .ifPresent(line -> System.out.println(Ansi.AUTO.string(line)));
        }
        return RestartStatus.NOT_INSTALLED;
      }
      var endpoint = readUnitEndpoint(bootstrap.serviceFilePath()).orElse(defaultEndpoint);
      var installer =
          HostServiceInstallers.create(
              shell, endpoint.host(), endpoint.port(), HostServiceInstallers.currentUsername());
      var driftReconciled = installer.reconcile();
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

  /** Bind address + port pair extracted from a sail-api unit file's {@code ExecStart}. */
  record Endpoint(String host, int port) {}

  /**
   * Reads the existing sail-api unit file and pulls the {@code --host}/{@code --port} values out of
   * the {@code ExecStart=} line so the reconcile step preserves the operator's configured endpoint.
   * Returns empty when the file can't be parsed; the caller falls back to the bootstrap defaults.
   */
  static Optional<Endpoint> readUnitEndpoint(Path unitFile) {
    try {
      var content = Files.readString(unitFile);
      var host = extractOption(content, "--host");
      var port = extractOption(content, "--port");
      if (host == null || port == null) {
        return Optional.empty();
      }
      return Optional.of(new Endpoint(host, Integer.parseInt(port)));
    } catch (IOException | NumberFormatException e) {
      return Optional.empty();
    }
  }

  private static String extractOption(String unitContent, String optionName) {
    for (var line : unitContent.split("\n", -1)) {
      if (!line.stripLeading().startsWith("ExecStart=")) {
        continue;
      }
      var tokens = line.trim().split("\\s+");
      for (var i = 0; i < tokens.length - 1; i++) {
        if (optionName.equals(tokens[i])) {
          return tokens[i + 1];
        }
      }
    }
    return null;
  }

  /**
   * Bootstraps the database and runs every pending migration using the NEW binary that was just
   * installed. The token bootstrap runs in-process (the OLD binary), but every schema and data
   * migration runs as a sub-process invocation of the freshly-installed binary's {@code sail
   * migrate --non-interactive}. That's the only way a release's new migrations can execute during
   * the upgrade itself — the alternative (running migrations from the OLD process's class files)
   * has bitten us every release since 0.13.4. (The host-level steps — relocating {@code host.yaml},
   * syncing {@code authorized_keys} — run only in the full {@code migrate}, as they need root.)
   */
  private void migrateDatabase(Path binaryPath) {
    var dbPath = SailPaths.controlPlaneDb();
    if (dryRun) {
      if (!json) {
        System.out.println("[dry-run] Would initialize database and create API token at " + dbPath);
        System.out.println(
            "[dry-run] Would exec '"
                + binaryPath
                + " migrate --non-interactive' to run new-binary migrations.");
      }
      return;
    }
    bootstrapAdminToken(dbPath);
    runNewBinaryMigrate(binaryPath);
  }

  /**
   * Creates the admin token if the DB is fresh. The token schema hasn't changed since the column
   * was introduced, so the OLD binary is safe to do this. Everything else (new schema migrations,
   * new data migrations) is handled by the sub-process that runs the NEW binary.
   */
  private void bootstrapAdminToken(Path dbPath) {
    try {
      SailPaths.ensureDataDir(dbPath.getParent());
      try (var operations = OperationsFactory.open(dbPath)) {
        if (operations.schema().version() == 0) {
          operations.schema().initialize();
        }
        if (operations.identity().tokens().isEmpty()) {
          var created =
              operations.identity().createToken("admin", "admin", null, TokenStore.DEFAULT_TTL);
          var configPath = SailPaths.clientConfigPath();
          ServerConnectionConfig.saveLocalToken(created.token(), configPath);
          if (!json) {
            System.out.println(
                Ansi.AUTO.string("    @|green ✓|@ API token created and saved to " + configPath));
          }
        }
      }
    } catch (Throwable e) {
      if (!json) {
        System.err.println("    Token bootstrap skipped: " + e.getMessage());
      }
    }
  }

  /**
   * Spawns the just-installed binary's {@code sail migrate --non-interactive}. The sub-process runs
   * NEW code — that's the whole point. Inherits stdio so the operator sees what migrated. Tolerates
   * non-zero exits; the {@code sail-api} restart afterwards is a second chance.
   */
  private void runNewBinaryMigrate(Path binaryPath) {
    try {
      var process =
          new ProcessBuilder(binaryPath.toString(), "migrate", "--non-interactive")
              .inheritIO()
              .start();
      var finished = process.waitFor(2, TimeUnit.MINUTES);
      if (!finished) {
        process.destroyForcibly();
        if (!json) {
          System.err.println(
              "    Migrate sub-process timed out after 2m. Run 'sail migrate' manually.");
        }
      } else if (process.exitValue() != 0 && !json) {
        System.err.println(
            "    Migrate sub-process exited with code "
                + process.exitValue()
                + ". Run 'sail migrate' manually.");
      }
    } catch (Throwable e) {
      if (!json) {
        System.err.println(
            "    Could not spawn migrate sub-process: "
                + e.getMessage()
                + ". The sail-api restart will retry; or run 'sail migrate' manually.");
      }
    }
  }

  /** Lifecycle outcome for the {@code sail-api} restart step. */
  private enum RestartStatus {
    NOT_INSTALLED("not_installed"),
    NOT_RUNNING("not_running"),
    RESTARTED("restarted"),
    FAILED("failed"),
    DRY_RUN("dry_run"),
    SKIPPED_CLIENT("skipped_client");

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
    var args = new ArrayList<String>();
    args.add("sudo");
    args.add(SailPaths.binaryPath().toString());
    args.add("upgrade");
    if (targetVersion != null) {
      args.add("--target");
      args.add(targetVersion);
    }
    if (localBinary != null) {
      args.add("--binary");
      args.add(localBinary.toAbsolutePath().toString());
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
