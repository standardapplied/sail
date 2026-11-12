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
import ai.singlr.sail.engine.HostDetector;
import ai.singlr.sail.engine.HostProvisioner;
import ai.singlr.sail.engine.IncusStorage;
import ai.singlr.sail.engine.NetworkDetector;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PoolDevices;
import ai.singlr.sail.engine.PoolSource;
import ai.singlr.sail.engine.PrerequisiteChecker;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.ResourceChecker;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.ssh.SshPublicKey;
import java.nio.file.Files;
import java.util.ArrayList;
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
  private static final long MIN_FILE_MB = 1024;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(
      names = "--storage",
      description =
          "Storage backend: 'dir' (default, host filesystem) or 'zfs' (a disk, a partition or a"
              + " file).",
      defaultValue = "dir")
  private String storage;

  @Option(
      names = "--disk",
      description =
          "Whole disk or partition for the ZFS pool (e.g. /dev/sdb, /dev/nvme0n1p5), carrying"
              + " nothing: Sail erases nothing. With --storage zfs. Do not change disks while host"
              + " init runs: on disks with no hardware identity (no WWN or NVMe id), a disk replaced"
              + " under the same name after the last check is judged by zpool create alone, which"
              + " does not refuse an empty partition table or filesystems inside MBR partitions.")
  private String disk;

  @Option(
      names = "--pool-file-size",
      description =
          "Make the ZFS pool in a file of this size (e.g. 50GiB): slower than a disk, for trying"
              + " ZFS on a box with no spare one. With --storage zfs.")
  private String poolFileSize;

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

  /**
   * What a failure sail did not decide ends with: the dry-run hint for host init run on its own,
   * nothing as a step of {@code sail init}, which has no {@code --dry-run}.
   */
  String failureHint() {
    return standalone ? CliCommand.DRY_RUN_HINT : null;
  }

  @Override
  public void run() {
    run(this::execute);
  }

  /** Runs {@code body} as this host init runs: a failure it did not decide ends with its hint. */
  void run(CliCommand.CheckedRunnable body) {
    CliCommand.run(failureHint(), body);
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

    var reads = new ShellExecutor(false);
    var source = source(reads);

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
        System.out.println(Ansi.AUTO.string("  @|bold Source:|@  " + source.said()));
        System.out.println(Ansi.AUTO.string("  @|bold Bridge:|@  " + HostYaml.DEFAULT_BRIDGE));
      } else {
        System.out.println(Ansi.AUTO.string("  @|bold Storage:|@ " + pool + " (dir)"));
        System.out.println(Ansi.AUTO.string("  @|bold Bridge:|@  " + HostYaml.DEFAULT_BRIDGE));
        System.out.println();
        System.out.println(
            Ansi.AUTO.string(
                "  @|yellow Note:|@ Disk quotas are advisory with 'dir' backend (not enforced)."));
        System.out.println(
            "        Use --storage zfs with --disk <device> or --pool-file-size <size> for"
                + " enforced ones.");
      }
      System.out.println();
    }

    if (!yes && !dryRun && !json) {
      ask("Proceed with provisioning?");
    }

    var listener = json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE;
    var provisioner = new HostProvisioner(shell, reads, listener, operator);
    var hostYaml =
        provisioner.provision(
            source,
            pool,
            HostYaml.DEFAULT_BRIDGE,
            resolvedServerIp,
            hostInfo.memoryMb(),
            hostYamlPath,
            SailPaths.controlPlaneDb());

    if (json) {
      System.out.println(YamlUtil.dumpJson(hostYaml.toMap()));
      return;
    }

    System.out.println();
    System.out.println(
        Ansi.AUTO.string(
            dryRun
                ? "  @|bold \u2713 Dry run done: nothing was changed.|@"
                : "  @|bold,green \u2713 Server ready.|@"));
    System.out.println(
        Ansi.AUTO.string(
            "    @|bold Storage:|@  "
                + hostYaml.pool()
                + " ("
                + hostYaml.storageBackend()
                + (hostYaml.poolSource() == null ? "" : " on " + hostYaml.poolSource())
                + ")"));
    System.out.println(Ansi.AUTO.string("    @|bold Bridge:|@   " + hostYaml.bridge()));
    System.out.println(Ansi.AUTO.string("    @|bold Image:|@    " + hostYaml.image()));
    System.out.println(Ansi.AUTO.string("    @|bold Incus:|@    " + hostYaml.incusVersion()));
    if (hostYaml.serverIp() != null) {
      System.out.println(Ansi.AUTO.string("    @|bold Server:|@   " + hostYaml.serverIp()));
    }

    if (!dryRun) {
      DemoSeeder.seedIfAbsent();
    }
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

  /**
   * The pool these flags ask for, read on the machine {@code probe} runs on: dir; the zfs pool
   * Incus already holds under this name, which a re-run keeps; a file; a named device {@link
   * PoolDevices#cleared} clears; or for zfs with neither, an unused disk the operator picks.
   * Refused when the flags disagree, with each other or with the pool Incus has, before anything is
   * installed.
   */
  PoolSource source(ShellExec probe) throws Exception {
    var zfs = HostYaml.BACKEND_ZFS.equals(storage);
    if (!zfs && (disk != null || poolFileSize != null)) {
      throw new IllegalArgumentException(
          "--disk and --pool-file-size choose a ZFS pool's source: pass them with"
              + " --storage zfs.");
    }
    var held =
        new IncusStorage(probe)
            .poolsOrNone().stream().filter(found -> found.name().equals(pool)).findFirst();
    held.ifPresent(found -> found.requireDriver(storage));
    if (!zfs) {
      return new PoolSource.Dir();
    }
    if (disk != null && poolFileSize != null) {
      throw new IllegalArgumentException(
          "Pass --disk or --pool-file-size, not both: a pool is made from one source.");
    }
    var devices = new PoolDevices(probe);
    if (held.isPresent()) {
      return kept(held.get(), devices);
    }
    if (poolFileSize != null) {
      return file(poolFileSize);
    }
    return devices.cleared(disk != null ? disk : detectAndSelectDisk(devices));
  }

  /**
   * The zfs pool Incus holds under this pool's name, which a re-run keeps; a source flag the re-run
   * would ignore is refused, naming it. {@code --disk} and the pool's source are compared as the
   * devices they resolve to, since Incus holds the path Sail handed it, a by-id name where the disk
   * has one; a source that resolves to nothing is a device gone, which no {@code --disk} can be.
   */
  private PoolSource kept(IncusStorage.Pool held, PoolDevices devices) throws Exception {
    var ignored = new ArrayList<String>();
    if (disk != null && !sameDevice(held, devices.resolved("--disk", disk), devices)) {
      ignored.add("--disk " + disk);
    }
    if (poolFileSize != null && !(file(poolFileSize).sizeMb() + "MiB").equals(held.size())) {
      ignored.add("--pool-file-size " + poolFileSize);
    }
    if (!ignored.isEmpty()) {
      throw new IllegalArgumentException(
          "Incus already has zfs pool '"
              + pool
              + "' on "
              + held.source()
              + ", which a re-run keeps, so "
              + String.join(" and ", ignored)
              + " would be ignored. Drop it, or name a new pool with --pool <name>.");
    }
    return new PoolSource.Zpool(Objects.requireNonNullElse(held.zpool(), held.name()));
  }

  private boolean sameDevice(IncusStorage.Pool held, String named, PoolDevices devices)
      throws Exception {
    if (!held.source().startsWith("/")) {
      return false;
    }
    var source =
        devices
            .device(held.source())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Incus already has zfs pool '"
                            + pool
                            + "' on "
                            + held.source()
                            + ", which names no device on this box now: the pool's device is gone,"
                            + " so --disk "
                            + disk
                            + " cannot be it. See sudo zpool status "
                            + Objects.requireNonNullElse(held.zpool(), held.name())
                            + ", or name a new pool with --pool <name>."));
    return source.equals(named);
  }

  /** A file pool of {@code size}, refused unless it reads as at least {@link #MIN_FILE_MB}. */
  static PoolSource.File file(String size) {
    var sizeMb = ResourceChecker.parseMemoryMb(size);
    if (sizeMb < MIN_FILE_MB) {
      throw new IllegalArgumentException(
          "--pool-file-size "
              + size
              + " is not a size of at least 1GiB. Give one such as 50GiB or 200GB.");
    }
    return new PoolSource.File(sizeMb);
  }

  private String detectAndSelectDisk(PoolDevices devices) throws Exception {
    var candidates = devices.candidates();

    if (candidates.isEmpty()) {
      throw new CliCommand.Refused(
          "Not provisioned: no unused disk found. Name a disk or a spare partition with --disk"
              + " <device>, or make the pool in a file with --pool-file-size <size>.");
    }

    System.out.println(Ansi.AUTO.string("  @|bold Disks found:|@"));
    System.out.println();
    for (var i = 0; i < candidates.size(); i++) {
      var c = candidates.get(i);
      System.out.println(
          Ansi.AUTO.string(
              String.format(
                  "    @|bold %d.|@ %-14s %8s   %s",
                  i + 1, c.device(), c.size(), Objects.requireNonNullElse(c.model(), ""))));
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
  static String chosenDisk(String line, List<PoolDevices.Candidate> candidates) {
    var choice = Strings.isBlank(line) ? 0 : parseChoice(line.strip());
    if (choice < 1 || choice > candidates.size()) {
      throw unchosen("no disk chosen", candidates);
    }
    return candidates.get(choice - 1).device();
  }

  private static CliCommand.Refused unchosen(String why, List<PoolDevices.Candidate> candidates) {
    var devices = candidates.stream().map(PoolDevices.Candidate::device).toList();
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
