/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Provisions a bare-metal host: keeps a library update from restarting Incus ({@link
 * NeedrestartPolicy}), installs and holds what sail runs on ({@link HostPackages}), caps the ZFS
 * cache before ZFS can take memory ({@link ZfsCacheCap}), initializes Incus, has Incus create the
 * storage pool ({@link IncusStorage}) from the source the operator chose, creates the base profile,
 * caches the container image, and writes {@code /etc/sail/host.yaml}.
 *
 * <p>The storage {@code host.yaml} states is the pool the base profile puts containers on, as Incus
 * has it. A box whose profile already exists keeps it: a ZFS pool added to a dir box sits beside
 * the dir pool, its containers and the profile untouched; placing projects on it waits for
 * workspaces.
 *
 * <p>Every step is idempotent — safe to run again on an already-provisioned host. A re-run
 * refreshes only the fields it provisions and keeps the rest of {@code host.yaml}: sync identity,
 * passkeys, limits and retention. A box that lost its sync identity would turn standalone and
 * orphan every checkpoint its peers hold for its box id. It reads what it keeps once up front, to
 * refuse an unreadable file before any step, and again just before writing, under the box's round
 * lock ({@link FileMutex#holdRounds}) every write of the identity holds: a {@code host sync},
 * {@code join} or handle change that finished while the packages installed is kept, not undone.
 */
public final class HostProvisioner {

  private static final int TOTAL_STEPS = 8;
  private static final Duration INIT_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration IMAGE_TIMEOUT = Duration.ofMinutes(5);

  private final ShellExec shell;
  private final ShellExec reads;
  private final ProvisionListener listener;
  private final HostPackages packages;
  private final OperatorCommand operator;
  private final IncusStorage storage;
  private final PoolDevices devices;

  /**
   * A provisioner whose steps report to {@code listener}, their hints rendered for {@code
   * operator}.
   */
  public HostProvisioner(ShellExec shell, ProvisionListener listener, OperatorCommand operator) {
    this(shell, shell, listener, operator);
  }

  /**
   * A provisioner that reads what Incus holds through {@code reads}, which a dry run's {@code
   * shell} cannot, so the plan it shows is what a real run does.
   */
  public HostProvisioner(
      ShellExec shell, ShellExec reads, ProvisionListener listener, OperatorCommand operator) {
    this.shell = shell;
    this.reads = reads;
    this.listener = Objects.requireNonNullElse(listener, ProvisionListener.NOOP);
    this.packages = new HostPackages(shell, this.listener::onStepProgress);
    this.operator = operator;
    this.storage = new IncusStorage(shell);
    this.devices = new PoolDevices(shell);
  }

  /**
   * Runs all provisioning steps and writes host.yaml.
   *
   * @param source what the storage pool is made from; a device as {@link PoolDevices#cleared}
   *     cleared it, or two as {@link PoolDevices#mirror} did
   * @param pool the storage pool name (e.g. {@code devpool})
   * @param bridge the network bridge name (e.g. {@code incusbr0})
   * @param serverIp the server's public IP address (for SSH config generation), may be null
   * @param memoryMb the box's memory, which the ZFS cache cap is a fraction of
   * @param hostYamlPath where to write host.yaml (usually {@code /etc/sail/host.yaml})
   * @param dbPath the box's database, whose round lock the final write holds
   * @return the written HostYaml
   */
  public HostYaml provision(
      PoolSource source,
      String pool,
      String bridge,
      String serverIp,
      long memoryMb,
      Path hostYamlPath,
      Path dbPath)
      throws IOException, InterruptedException, TimeoutException {
    configured(hostYamlPath);

    var outcome = runSteps(source, pool, bridge, memoryMb);

    try (var rounds = holdRounds(dbPath)) {
      var kept = configured(hostYamlPath);
      var hostYaml =
          new HostYaml(
              outcome.pool().driver(),
              outcome.pool().name(),
              outcome.pool().source(),
              bridge,
              HostYaml.DEFAULT_PROFILE,
              HostYaml.DEFAULT_IMAGE,
              outcome.incusVersion(),
              serverIp,
              DateTimeUtils.format(DateTimeUtils.now()),
              kept.webauthn(),
              kept.sync(),
              kept.limits(),
              kept.retention(),
              kept.listen());
      writeHostYaml(hostYaml, hostYamlPath);
      return hostYaml;
    }
  }

  /** What the steps leave: the Incus version installed, and the pool the base profile names. */
  private record Outcome(String incusVersion, IncusStorage.Pool pool) {}

  /** Runs every step; a step that throws is failed. */
  private Outcome runSteps(PoolSource source, String pool, String bridge, long memoryMb)
      throws IOException, InterruptedException, TimeoutException {
    try {
      writeNeedrestartPolicy();
      installIncus();
      installZfs(source, memoryMb);
      var incusVersion = initIncus(bridge);
      createStoragePool(pool, source);
      var profiled = ensureBaseProfile(pool, source, bridge);
      cacheImage();
      configureFirewall(bridge);
      return new Outcome(incusVersion, profiled);
    } catch (Exception e) {
      listener.onStepFailed();
      throw e;
    }
  }

  private FileMutex holdRounds(Path dbPath) throws IOException {
    return shell.isDryRun() ? null : FileMutex.holdRounds(dbPath);
  }

  private static HostYaml configured(Path hostYamlPath) {
    try {
      return HostYaml.fromMap(
          Files.exists(hostYamlPath) ? YamlUtil.parseFile(hostYamlPath) : Map.of());
    } catch (IOException | RuntimeException e) {
      throw new IllegalStateException(
          "Cannot read "
              + hostYamlPath
              + " to keep this box's sync identity and settings: "
              + e.getMessage()
              + ". Fix the file, or move it aside to provision this box as new, then rerun.",
          e);
    }
  }

  private void writeNeedrestartPolicy() throws IOException, InterruptedException, TimeoutException {
    step(1, "Writing needrestart policy...");
    NeedrestartPolicy.DROP_IN.install(shell);
    stepDone(1, "a library update never restarts Incus");
  }

  private void installIncus() throws IOException, InterruptedException, TimeoutException {
    step(2, "Installing Incus (Zabbly stable)...");
    var installed = installed(HostPackages.INCUS);
    stepDone(
        2, packages.hold() ? installed + "; " + operator.render(HostPackages.HELD) : installed);
  }

  /** Caps the ZFS cache before the package that loads the module is installed. */
  private void installZfs(PoolSource source, long memoryMb)
      throws IOException, InterruptedException, TimeoutException {
    if (!HostPackages.forBackend(source.driver()).contains(HostPackages.ZFS)) {
      stepSkipped(3, "not needed (" + source.driver() + " backend)");
      return;
    }
    step(3, "Installing ZFS utilities...");
    var capped = ZfsCacheCap.forMemory(memoryMb).install(shell);
    var installed = installed(HostPackages.ZFS);
    stepDone(3, installed + "; " + capped);
  }

  /**
   * Installs {@code pkg} unless it is there, saying which; a dry run installs nothing and says
   * whether a real run would, from what the box has.
   */
  private String installed(HostPackages.Package pkg)
      throws IOException, InterruptedException, TimeoutException {
    if (shell.isDryRun()) {
      return read(List.of("which", pkg.command())).ok()
          ? "already installed"
          : "would be installed";
    }
    return packages.ensure(pkg) ? "installed" : "already installed";
  }

  private String initIncus(String bridge)
      throws IOException, InterruptedException, TimeoutException {
    step(4, "Initializing Incus...");

    var preseed =
        """
            config: {}
            networks:
            - config:
                ipv4.address: auto
                ipv6.address: none
              description: ""
              name: %s
              type: bridge
              project: default
            storage_pools: []
            profiles: []
            projects: []
            cluster: null
            """
            .formatted(bridge);

    var preseedFile = Files.createTempFile("sail-preseed-", ".yaml");
    ShellExec.Result result;
    try {
      Files.writeString(preseedFile, preseed);
      result =
          shell.exec(
              List.of("bash", "-c", "cat " + preseedFile + " | incus admin init --preseed"),
              null,
              INIT_TIMEOUT);
    } finally {
      Files.deleteIfExists(preseedFile);
    }
    if (!result.ok() && !result.stderr().contains("already exists")) {
      throw new IOException("Incus init failed: " + result.stderr());
    }

    var version = incusVersion(shell);
    var made =
        shell.isDryRun() && !read(List.of("incus", "network", "show", bridge)).ok()
            ? "would create bridge "
            : "bridge: ";
    stepDone(4, made + bridge);
    return version;
  }

  /**
   * The installed Incus version, as {@code incus version} reports it: its first line (the client),
   * after the colon. {@code "unknown"} when incus does not answer.
   */
  public static String incusVersion(ShellExec shell) throws InterruptedException {
    try {
      var result = shell.exec(List.of("incus", "version"));
      if (!result.ok()) {
        return "unknown";
      }
      var firstLine = result.stdout().lines().findFirst().orElse("");
      var version = firstLine.substring(firstLine.indexOf(':') + 1).strip();
      return version.isEmpty() ? "unknown" : version;
    } catch (IOException | TimeoutException e) {
      return "unknown";
    }
  }

  /**
   * Has Incus create {@code pool} from {@code source}, unless Incus has it already with the same
   * driver. A device is cleared again first, since it may have become busy since the operator named
   * it.
   */
  private void createStoragePool(String pool, PoolSource source)
      throws IOException, InterruptedException, TimeoutException {
    step(5, "Creating storage pool...");
    var existing =
        shell.isDryRun()
            ? new IncusStorage(reads)
                .poolsOrNone().stream().filter(found -> found.name().equals(pool)).findFirst()
            : storage.find(pool);
    if (existing.isPresent()) {
      var held = existing.get();
      held.requireDriver(source.driver());
      held.requireFinished();
      stepDone(
          5,
          pool
              + " ("
              + held.driver()
              + " on "
              + IncusStorage.said(held.source())
              + ", already exists"
              + held.standing()
              + ")");
      return;
    }
    var restarted = make(pool, source);
    var trial =
        source instanceof PoolSource.File ? "; slower than a disk, meant for trying ZFS" : "";
    stepDone(5, wouldCreate() + pool + " (" + source.said() + trial + ")" + restarted);
  }

  /**
   * Creates the pool, a device cleared again just before each create, since it may have changed
   * since the operator named it, or while Incus restarted. An incusd that started before the ZFS
   * tools were installed and does not see them is restarted only when it runs no container, since a
   * restart ends every {@code incus exec} into one; otherwise the operator is told to restart it.
   * Before any of it, a mirror needs an Incus that makes one (a dry run asks the Incus the box has,
   * when it has one), and a device's pool a name no imported zpool has, since zpool labels the
   * devices before it finds the name taken. Returns what the step says it did.
   */
  private String make(String pool, PoolSource source)
      throws IOException, InterruptedException, TimeoutException {
    if (source instanceof PoolSource.Mirror
        && (!shell.isDryRun() || read(List.of("which", "incus")).ok())) {
      new IncusStorage(reads).requireMirrors();
    }
    if (source instanceof PoolSource.Device || source instanceof PoolSource.Mirror) {
      requireUnnamed(pool);
    }
    clearedAgain(source);
    var made = storage.create(pool, source);
    var unseen = !made.ok() && IncusStorage.zfsUnseen(made);
    if (unseen) {
      restartToSeeZfs(made);
      clearedAgain(source);
      made = storage.create(pool, source);
    }
    if (!made.ok()) {
      throw new IOException(
          "Failed to create storage pool " + pool + ": " + IncusStorage.refused(made));
    }
    return unseen ? "; restarted Incus, which ran no container, to see ZFS" : "";
  }

  /**
   * Refuses a device, or either device of a mirror, that is no longer the one first cleared, or no
   * longer carries nothing; a mirror's two are cleared again together, so the last look at each is
   * right before the create.
   */
  private void clearedAgain(PoolSource source)
      throws IOException, InterruptedException, TimeoutException {
    if (shell.isDryRun()) {
      return;
    }
    switch (source) {
      case PoolSource.Device device -> requireSame(device, devices.cleared(device.path()));
      case PoolSource.Mirror mirror -> {
        var now = devices.mirror(mirror.one().path(), mirror.other().path());
        requireSame(mirror.one(), now.one());
        requireSame(mirror.other(), now.other());
      }
      default -> {}
    }
  }

  private static void requireSame(PoolSource.Device first, PoolSource.Device now) {
    if (!now.equals(first)) {
      throw new IllegalStateException(
          first.path()
              + " is not the device first cleared: it was "
              + first.identity()
              + ", it is now "
              + now.identity()
              + ".");
    }
  }

  /**
   * Refuses pool {@code pool} when an imported zpool already has its name, which is no pool Incus
   * holds (step 5 found none): {@code zpool create} would label the devices before it found the
   * name taken.
   */
  private void requireUnnamed(String pool)
      throws IOException, InterruptedException, TimeoutException {
    if (new ImportedPools(reads).names().contains(pool)) {
      throw new IllegalStateException(
          "zpool "
              + pool
              + " is already imported on this box, and Incus holds no pool of that name, so none"
              + " is made under it. Nothing was written. Export or rename that zpool yourself, or"
              + " name another pool with --pool <name>.");
    }
  }

  private void restartToSeeZfs(ShellExec.Result unseen)
      throws IOException, InterruptedException, TimeoutException {
    if (!new BoxInstances(shell).all().isEmpty()) {
      throw Remedied.io(
          "Incus started before the ZFS tools were installed and does not see them ("
              + unseen.reason()
              + ").",
          Remedy.of(
              "Restart it when no terminal or agent run is open: ",
              Remedy.program("systemctl", "restart", "incus"),
              ", then run this command again."));
    }
    var restart = shell.exec(List.of("systemctl", "restart", "incus"), null, INIT_TIMEOUT);
    if (!restart.ok()) {
      throw new IOException("Failed to restart Incus to see ZFS: " + restart.reason());
    }
  }

  /**
   * Converges the base profile: its root disk on {@code pool} and its NIC on {@code bridge} when it
   * has none, an existing root disk kept wherever it is. Returns the pool its root disk names,
   * refused unless Incus has that pool, so what {@code host.yaml} states is what Incus has.
   */
  private IncusStorage.Pool ensureBaseProfile(String pool, PoolSource source, String bridge)
      throws IOException, InterruptedException, TimeoutException {
    step(6, "Creating base profile...");

    var profile = HostYaml.DEFAULT_PROFILE;
    var existed = read(List.of("incus", "profile", "show", profile)).ok();
    if (!existed) {
      var create = shell.exec(List.of("incus", "profile", "create", profile));
      if (!create.ok()) {
        throw new IOException("Failed to create Incus profile: " + create.stderr());
      }
    }
    var held =
        read(List.of("incus", "profile", "device", "list", profile))
            .stdout()
            .lines()
            .map(String::strip)
            .toList();
    if (!held.contains("root")) {
      addDevice(profile, List.of("root", "disk", "path=/", "pool=" + pool), "root disk");
    }
    if (!held.contains("eth0")) {
      addDevice(
          profile, List.of("eth0", "nic", "name=eth0", "network=" + bridge, "type=nic"), "NIC");
    }
    var named =
        held.contains("root")
            ? read(List.of("incus", "profile", "device", "get", profile, "root", "pool"))
                .stdout()
                .strip()
            : pool;
    var found =
        shell.isDryRun() && named.equals(pool)
            ? Optional.of(planned(pool, source))
            : new IncusStorage(reads).find(named);
    var root =
        found.orElseThrow(
            () ->
                new IllegalStateException(
                    profile
                        + " puts containers on pool '"
                        + named
                        + "', which Incus does not have. Point its root disk at a pool Incus"
                        + " lists (incus storage list), then run this command again."));
    stepDone(
        6,
        (existed ? "" : wouldCreate())
            + profile
            + (existed ? " (already exists)" : "")
            + ": containers on "
            + root.name()
            + " ("
            + root.driver()
            + ")");
    return root;
  }

  /** How a dry run's step says what a real run would make: before the name of what is made. */
  private String wouldCreate() {
    return shell.isDryRun() ? "would create " : "";
  }

  /**
   * {@code command}, a read of what Incus holds, run through {@link #reads}; failed where Incus
   * cannot be run, as before a dry run installs it.
   */
  private ShellExec.Result read(List<String> command)
      throws InterruptedException, TimeoutException {
    try {
      return reads.exec(command);
    } catch (IOException e) {
      return new ShellExec.Result(1, "", e.getMessage());
    }
  }

  private void addDevice(String profile, List<String> device, String what)
      throws IOException, InterruptedException, TimeoutException {
    var command = new ArrayList<>(List.of("incus", "profile", "device", "add", profile));
    command.addAll(device);
    var added = shell.exec(command);
    if (!added.ok()) {
      throw new IOException("Failed to add " + what + " to profile: " + added.stderr());
    }
  }

  /** The pool a dry run would have made, as {@code host.yaml} would state it. */
  private static IncusStorage.Pool planned(String pool, PoolSource source) {
    return new IncusStorage.Pool(
        pool, source.driver(), IncusStorage.sourceOf(source), null, null, Optional.empty());
  }

  private void cacheImage() throws IOException, InterruptedException, TimeoutException {
    step(7, "Caching Ubuntu 24.04 image...");

    var check =
        read(List.of("bash", "-c", "incus image list --format=json | grep -q ubuntu-24.04"));
    if (check.ok()) {
      stepDone(7, "already cached");
      return;
    }
    if (shell.isDryRun()) {
      stepDone(7, "would cache Ubuntu 24.04");
      return;
    }

    var result =
        shell.exec(
            List.of(
                "incus",
                "image",
                "copy",
                "images:" + HostYaml.DEFAULT_IMAGE,
                "local:",
                "--alias",
                "ubuntu-24.04"),
            null,
            IMAGE_TIMEOUT);
    if (!result.ok()) {
      throw new IOException("Failed to cache image: " + result.stderr());
    }
    stepDone(7, "cached");
  }

  private void configureFirewall(String bridge)
      throws IOException, InterruptedException, TimeoutException {
    step(8, "Configuring UFW firewall rules...");

    ShellExec.Result status;
    try {
      status = (shell.isDryRun() ? reads : shell).exec(List.of("ufw", "status"));
    } catch (IOException e) {
      stepSkipped(8, "UFW not installed");
      return;
    }
    if (!status.ok() || !status.stdout().contains("Status: active")) {
      stepSkipped(8, "UFW not active");
      return;
    }
    if (shell.isDryRun()) {
      stepDone(8, "would add bridge rules");
      return;
    }

    var failed = false;
    failed |= !shell.exec(List.of("ufw", "allow", "in", "on", bridge)).ok();
    failed |= !shell.exec(List.of("ufw", "allow", "out", "on", bridge)).ok();

    failed |= !shell.exec(List.of("ufw", "route", "allow", "in", "on", bridge)).ok();
    failed |= !shell.exec(List.of("ufw", "route", "allow", "out", "on", bridge)).ok();

    if (failed) {
      stepDone(8, "some rules failed (check ufw manually)");
    } else {
      stepDone(8, "bridge rules added");
    }
  }

  private void writeHostYaml(HostYaml hostYaml, Path path) throws IOException {
    if (shell.isDryRun()) {
      System.out.println("[dry-run] Would write " + path);
      return;
    }
    Files.createDirectories(path.getParent());
    YamlUtil.dumpToFile(hostYaml.toMap(), path);
  }

  private void step(int step, String description) {
    listener.onStep(step, TOTAL_STEPS, description);
  }

  private void stepDone(int step, String detail) {
    listener.onStepDone(step, TOTAL_STEPS, detail);
  }

  private void stepSkipped(int step, String detail) {
    listener.onStepSkipped(step, TOTAL_STEPS, detail);
  }
}
