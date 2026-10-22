/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.DemoSeeder;
import ai.singlr.sail.engine.DiskDetector;
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.HostProvisioner;
import ai.singlr.sail.engine.NetworkDetector;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PrerequisiteChecker;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.ssh.SshPublicKey;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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

  @Spec private CommandSpec spec;

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
      description = "Server's public IP address, for the SSH config that project connect prints.")
  private String serverIp;

  @Option(
      names = "--ssh-public-key",
      description =
          "Your workstation SSH public key, authorized into this box's project containers so you"
              + " can connect from your laptop. Set it later with host config set ssh-public-key.")
  private String sshPublicKey;

  @Option(names = "--yes", description = "Skip confirmation prompts (for non-interactive use).")
  private boolean yes;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  private final boolean standalone;

  public HostInitCommand() {
    this(true);
  }

  private HostInitCommand(boolean standalone) {
    this.standalone = standalone;
  }

  /** Host init as {@code sail init}'s first step, which prints no next step of its own. */
  static HostInitCommand initStep() {
    return new HostInitCommand(false);
  }

  /** Whether this host init runs on its own rather than as a step of {@code sail init}. */
  boolean standalone() {
    return standalone;
  }

  @Override
  public void run() {
    CliCommand.run(CliCommand.DRY_RUN_HINT, this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
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
      throw RootRequired.refusal(spec);
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
      throw new CliCommand.Refused("Unsupported operating system: " + hostInfo.osPrettyName());
    }

    if (Files.exists(hostYamlPath) && !dryRun) {
      if (!json) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|yellow Host already initialized|@ (" + hostYamlPath + " exists)."));
        System.out.println("  Re-running will update the installation (all steps are idempotent).");
      }
      if (!yes && !json) {
        ask("Continue?");
      }
    }

    var selectedDisk = isZfs && disk == null ? detectAndSelectDisk(new ShellExecutor(false)) : disk;

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
                    + " Pass --server-ip or run: "
                    + operator.sail("host config set server-ip <ip>")));
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

    if (!yes && !dryRun && !json) {
      ask("Proceed with provisioning?");
    }

    var listener = json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE;
    var provisioner = new HostProvisioner(shell, listener);
    var hostYaml =
        provisioner.provision(
            storage,
            selectedDisk,
            pool,
            HostYaml.DEFAULT_BRIDGE,
            resolvedServerIp,
            hostYamlPath,
            SailPaths.controlPlaneDb());

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
    nextStep(standalone, Files.exists(SailPaths.SYSTEM_UNIT), operator)
        .ifPresent(
            line -> {
              System.out.println();
              System.out.println(Ansi.AUTO.string(line));
            });
  }

  /**
   * Asks {@code prompt} before provisioning goes on, refused unless answered yes on a terminal
   * ({@link ConsoleHelper#proceed}).
   */
  static void ask(String prompt) {
    ConsoleHelper.proceed(prompt, "Not provisioned", "--yes");
  }

  /** The refusal of a command that needs this box provisioned, on a box that is not. */
  static IllegalStateException notInitialized(OperatorCommand operator) {
    return new IllegalStateException(
        "Server not initialized. Run '" + operator.sail("host init") + "' first.");
  }

  /**
   * What a standalone host init tells its operator to run next; nothing inside {@code sail init},
   * which goes on to install sail-api itself.
   */
  static Optional<String> nextStep(
      boolean standalone, boolean apiInstalled, OperatorCommand operator) {
    if (!standalone) {
      return Optional.empty();
    }
    return Optional.of(
        apiInstalled
            ? "  @|green ✓|@ sail API service already installed — nothing to do here; '"
                + operator.sail("upgrade")
                + "' keeps it current."
            : "  @|bold Next step:|@ "
                + operator.render(Remedy.setUp())
                + ", installs sail-api and connects this box.");
  }

  private String detectAndSelectDisk(ShellExec shell) throws Exception {
    var detector = new DiskDetector(shell);
    var candidates = detector.detect();

    if (candidates.isEmpty()) {
      throw new CliCommand.Refused(
          "Not provisioned: no candidate disks found. Add a second disk, or name one with --disk"
              + " /dev/sdX.");
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
      if (!yes && !dryRun) {
        ask("Use " + c.device() + " (" + c.size() + ") for ZFS pool \"" + pool + "\"?");
      }
      return c.device();
    }

    if (!ConsoleHelper.hasConsole()) {
      throw unchosen("no terminal to choose a disk on", candidates);
    }
    System.out.print("  Select disk [1-" + candidates.size() + "]: ");
    System.out.flush();
    return chosenDisk(ConsoleHelper.readLine(), candidates);
  }

  /** The disk {@code line} picks of {@code candidates}, numbered from 1; refused otherwise. */
  static String chosenDisk(String line, List<DiskDetector.Candidate> candidates) {
    var choice = Strings.isBlank(line) ? 0 : parseChoice(line.strip());
    if (choice < 1 || choice > candidates.size()) {
      throw unchosen("no disk chosen", candidates);
    }
    return candidates.get(choice - 1).device();
  }

  private static CliCommand.Refused unchosen(String why, List<DiskDetector.Candidate> candidates) {
    var devices = candidates.stream().map(DiskDetector.Candidate::device).toList();
    return new CliCommand.Refused(
        "Not provisioned: "
            + why
            + ". Re-run with --disk and one of: "
            + String.join(", ", devices)
            + ".");
  }

  private static int parseChoice(String typed) {
    try {
      return Integer.parseInt(typed);
    } catch (NumberFormatException e) {
      return 0;
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
        throw new CliCommand.Refused(
            "Missing commands that cannot be auto-installed: " + String.join(", ", names));
      }

      Banner.printMissingPrerequisites(installable, System.out, Ansi.AUTO);
      if (!yes && !dryRun) {
        ask("Install missing prerequisites?");
      }
      new PrerequisiteChecker(installShell).installMissing(installable);
    }
  }
}
