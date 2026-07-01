/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.DemoSeeder;
import ai.singlr.sail.engine.DiskDetector;
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.HostProvisioner;
import ai.singlr.sail.engine.NetworkDetector;
import ai.singlr.sail.engine.PrerequisiteChecker;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import ai.singlr.sail.ssh.SshPublicKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "init",
    description =
        "Provision the bare-metal host: install Incus, create storage pool, cache base image.",
    mixinStandardHelpOptions = true)
public final class HostInitCommand implements Runnable {

  private static final Set<String> VALID_BACKENDS =
      Set.of(HostYaml.BACKEND_DIR, HostYaml.BACKEND_ZFS);

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(
      names = "--storage",
      description = "Storage backend: 'dir' (default, host filesystem) or 'zfs' (dedicated disk).",
      defaultValue = "dir")
  private String storage;

  @Option(
      names = "--disk",
      description = "Disk device for ZFS pool (e.g. /dev/sdb). Only used with --storage zfs.")
  private String disk;

  @Option(names = "--pool", description = "Storage pool name.", defaultValue = "devpool")
  private String pool;

  @Option(
      names = "--server-ip",
      description =
          "Server's public IP address (for SSH config generation in 'sail project connect').")
  private String serverIp;

  @Option(
      names = "--ssh-public-key",
      description =
          "Your workstation SSH public key, authorized into this box's project containers so you"
              + " can connect from your laptop. Set it later with 'sail host config set"
              + " ssh-public-key'.")
  private String sshPublicKey;

  @Option(names = "--yes", description = "Skip confirmation prompts (for non-interactive use).")
  private boolean yes;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(
        spec,
        "  If this is unexpected, re-run with --dry-run to see what would execute.",
        this::execute);
  }

  private void execute() throws Exception {
    if (!VALID_BACKENDS.contains(storage)) {
      throw new IllegalArgumentException(
          "Invalid storage backend: '" + storage + "'. Must be 'dir' or 'zfs'.");
    }

    var isZfs = HostYaml.BACKEND_ZFS.equals(storage);
    if (Strings.isNotBlank(sshPublicKey)) {
      SshPublicKey.parse(sshPublicKey);
    }

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
    }

    if (!dryRun && !ConsoleHelper.isRoot()) {
      throw new IllegalStateException("Root privileges required. Run with: sudo sail host init");
    }

    if (Strings.isNotBlank(sshPublicKey) && !dryRun) {
      HostConfigSetCommand.writeWorkstationKey(SailPaths.workstationPublicKeyPath(), sshPublicKey);
    }

    var hostYamlPath = SailPaths.hostConfigPath();

    var shell = new ShellExecutor(dryRun);

    if (!json) {
      checkPrerequisites(new ShellExecutor(false), shell);
    }

    var hostDetector = new HostDetector();
    var hostInfo = hostDetector.detect();
    if (!json) {
      Banner.printHostDetection(hostInfo);
    }

    if (!hostInfo.supported()) {
      if (!json) {
        Banner.printUnsupported(hostInfo, System.err, Ansi.AUTO);
      }
      throw new IllegalStateException("Unsupported operating system: " + hostInfo.osPrettyName());
    }

    if (Files.exists(hostYamlPath) && !dryRun) {
      if (!json) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|yellow Host already initialized|@ (" + hostYamlPath + " exists)."));
        System.out.println("  Re-running will update the installation (all steps are idempotent).");
      }
      if (!yes && !json && !ConsoleHelper.confirm("Continue?")) {
        System.out.println("  Aborted.");
        return;
      }
    }

    String selectedDisk = null;
    if (isZfs) {
      selectedDisk = disk;
      if (selectedDisk == null) {
        selectedDisk = detectAndSelectDisk(shell);
        if (selectedDisk == null) {
          return;
        }
      }
    }

    var resolvedServerIp = serverIp;
    if (resolvedServerIp == null) {
      var detected = NetworkDetector.detectPrimaryIpv4();
      if (detected != null) {
        if (!json) {
          System.out.println(
              Ansi.AUTO.string("  @|bold Server IP:|@ " + detected + " (auto-detected)"));
        }
        resolvedServerIp = detected;
      } else if (!json) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|yellow Server IP:|@ could not auto-detect."
                    + " Pass --server-ip or run: sail host config set server-ip <ip>"));
      }
    } else if (!json) {
      System.out.println(Ansi.AUTO.string("  @|bold Server IP:|@ " + resolvedServerIp));
    }

    if (!json) {
      System.out.println();
      if (isZfs) {
        System.out.println(Ansi.AUTO.string("  @|bold Pool:|@    " + pool));
        System.out.println(Ansi.AUTO.string("  @|bold Disk:|@    " + selectedDisk));
        System.out.println(Ansi.AUTO.string("  @|bold Bridge:|@  " + HostYaml.DEFAULT_BRIDGE));
      } else {
        System.out.println(Ansi.AUTO.string("  @|bold Storage:|@ " + pool + " (dir)"));
        System.out.println(Ansi.AUTO.string("  @|bold Bridge:|@  " + HostYaml.DEFAULT_BRIDGE));
        System.out.println();
        System.out.println(
            Ansi.AUTO.string(
                "  @|yellow Note:|@ Disk quotas are advisory with 'dir' backend (not enforced)."));
        System.out.println("        Use --storage zfs --disk /dev/sdX for hard disk limits.");
      }
      System.out.println();
    }

    if (!yes && !dryRun && !json && !ConsoleHelper.confirm("Proceed with provisioning?")) {
      System.out.println("  Aborted.");
      return;
    }

    var listener = json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE;
    var provisioner = new HostProvisioner(shell, listener);
    var hostYaml =
        provisioner.provision(
            storage, selectedDisk, pool, HostYaml.DEFAULT_BRIDGE, resolvedServerIp, hostYamlPath);

    if (json) {
      System.out.println(YamlUtil.dumpJson(hostYaml.toMap()));
      return;
    }

    System.out.println();
    System.out.println(Ansi.AUTO.string("  @|bold,green \u2713 Server ready.|@"));
    if (isZfs) {
      System.out.println(
          Ansi.AUTO.string(
              "    @|bold Pool:|@     " + hostYaml.pool() + " on " + hostYaml.poolDisk()));
    } else {
      System.out.println(Ansi.AUTO.string("    @|bold Storage:|@  " + hostYaml.pool() + " (dir)"));
    }
    System.out.println(Ansi.AUTO.string("    @|bold Bridge:|@   " + hostYaml.bridge()));
    System.out.println(Ansi.AUTO.string("    @|bold Image:|@    " + hostYaml.image()));
    System.out.println(Ansi.AUTO.string("    @|bold Incus:|@    " + hostYaml.incusVersion()));
    if (hostYaml.serverIp() != null) {
      System.out.println(Ansi.AUTO.string("    @|bold Server:|@   " + hostYaml.serverIp()));
    }

    DemoSeeder.seedIfAbsent();
    printApiServiceNextSteps(shell);
  }

  private void printApiServiceNextSteps(ShellExec shell) {
    var sudoUser = System.getenv("SUDO_USER");
    System.out.println();
    if (apiServiceInstalled(targetHome(sudoUser))) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ sail API service already installed — nothing to do here;"
                  + " 'sail upgrade' keeps it current."));
      return;
    }
    System.out.println(Ansi.AUTO.string("  @|bold Next step:|@ enable the sail API service."));
    if (Strings.isNotBlank(sudoUser) && !"root".equals(sudoUser)) {
      enableLingerForUser(shell, sudoUser);
      System.out.println(Ansi.AUTO.string("    Run as @|bold " + sudoUser + "|@ (without sudo):"));
    } else {
      System.out.println("    Run as your dev user (without sudo):");
    }
    System.out.println(Ansi.AUTO.string("      @|bold sail host service install|@"));
  }

  /**
   * Resolves the home of the engineer who will own the user-level API service: the {@code
   * SUDO_USER} when {@code init} runs under sudo, otherwise the current user's home.
   */
  private static Path targetHome(String sudoUser) {
    if (Strings.isNotBlank(sudoUser) && !"root".equals(sudoUser)) {
      return Path.of("/home/" + sudoUser);
    }
    return Path.of(System.getProperty("user.home"));
  }

  /**
   * True when the per-user {@code sail-api} unit already exists under {@code userHome}. Lets {@code
   * init} skip the install prompt on a box that was set up before (an idempotent re-init), since
   * the service is purely file-state that root can read for any user.
   */
  static boolean apiServiceInstalled(Path userHome) {
    if (userHome == null) {
      return false;
    }
    return new SystemdServiceInstaller(
            new ShellExecutor(false),
            SystemdServiceInstaller.Mode.USER,
            userHome,
            SailPaths.binaryPath(),
            "127.0.0.1",
            7070,
            "sail")
        .isInstalled();
  }

  private void enableLingerForUser(ShellExec shell, String user) {
    try {
      var result = shell.exec(List.of("loginctl", "enable-linger", user));
      if (result.ok()) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|green \u2713|@ Enabled systemd linger for user '"
                    + user
                    + "' (service survives logout)."));
      } else {
        System.err.println(
            Banner.errorLine(
                "Could not enable linger for '"
                    + user
                    + "': "
                    + result.stderr().strip()
                    + ". Run manually: sudo loginctl enable-linger "
                    + user,
                Ansi.AUTO));
      }
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine(
              "Could not enable linger for '"
                  + user
                  + "': "
                  + e.getMessage()
                  + ". Run manually: sudo loginctl enable-linger "
                  + user,
              Ansi.AUTO));
    }
  }

  private String detectAndSelectDisk(ShellExec shell) throws Exception {
    var detector = new DiskDetector(shell);
    var candidates = detector.detect();

    if (candidates.isEmpty()) {
      System.err.println(Banner.errorLine("No candidate disks found.", Ansi.AUTO));
      System.err.println(
          "    Options: add a second disk, or specify one manually with --disk /dev/sdX");
      return null;
    }

    System.out.println(Ansi.AUTO.string("  @|bold Disks found:|@"));
    System.out.println();
    for (var i = 0; i < candidates.size(); i++) {
      var c = candidates.get(i);
      System.out.println(
          Ansi.AUTO.string(
              String.format(
                  "    @|bold %d.|@ %-14s %8s   %s   @|faint (%s)|@",
                  i + 1,
                  c.device(),
                  c.size(),
                  Objects.requireNonNullElse(c.model(), ""),
                  c.reason())));
    }
    System.out.println();

    if (candidates.size() == 1) {
      var c = candidates.getFirst();
      if (yes) {
        return c.device();
      }
      if (ConsoleHelper.confirm(
          "Use " + c.device() + " (" + c.size() + ") for ZFS pool \"" + pool + "\"?")) {
        return c.device();
      }
      return null;
    }

    System.out.print("  Select disk [1-" + candidates.size() + "]: ");
    System.out.flush();
    var line = ConsoleHelper.readLine();
    if (Strings.isBlank(line)) {
      System.out.println("  Aborted.");
      return null;
    }
    try {
      var choice = Integer.parseInt(line.strip());
      if (choice < 1 || choice > candidates.size()) {
        System.out.println("  Invalid selection.");
        return null;
      }
      return candidates.get(choice - 1).device();
    } catch (NumberFormatException e) {
      System.out.println("  Invalid selection.");
      return null;
    }
  }

  private void checkPrerequisites(ShellExec checkShell, ShellExec installShell) throws Exception {
    var checker = new PrerequisiteChecker(checkShell);
    var prereqs = checker.check();
    Banner.printPrerequisites(prereqs, System.out, Ansi.AUTO);

    if (!prereqs.missing().isEmpty()) {
      var installable = prereqs.missing().stream().filter(p -> p.pkg() != null).toList();
      var fatal = prereqs.missing().stream().filter(p -> p.pkg() == null).toList();

      if (!fatal.isEmpty()) {
        Banner.printMissingPrerequisites(fatal, System.err, Ansi.AUTO);
        var names = fatal.stream().map(PrerequisiteChecker.Prerequisite::command).toList();
        throw new IllegalStateException(
            "Missing commands that cannot be auto-installed: " + String.join(", ", names));
      }

      Banner.printMissingPrerequisites(installable, System.out, Ansi.AUTO);
      if (!yes && !ConsoleHelper.confirm("Install missing prerequisites?")) {
        throw new IllegalStateException("Missing prerequisites. Aborting.");
      }
      new PrerequisiteChecker(installShell).installMissing(installable);
    }
  }
}
