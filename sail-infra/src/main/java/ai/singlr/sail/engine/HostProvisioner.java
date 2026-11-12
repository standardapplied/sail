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
 * the dir pool, its containers untouched, and new containers are placed on it by naming it.
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
    this.shell = shell;
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
   *     cleared it
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
              kept.retention());
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
    var installed = packages.ensure(HostPackages.INCUS) ? "installed" : "already installed";
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
    var installed = packages.ensure(HostPackages.ZFS) ? "installed" : "already installed";
    stepDone(3, installed + "; " + capped);
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
    stepDone(4, "bridge: " + bridge);
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
    var existing = storage.find(pool);
    if (existing.isPresent()) {
      var held = existing.get();
      held.requireDriver(source.driver());
      stepDone(5, pool + " (" + held.driver() + " on " + held.source() + ", already exists)");
      return;
    }
    if (source instanceof PoolSource.Device device && !shell.isDryRun()) {
      var now = devices.cleared(device.path(), device.wipe() ? device.path() : null);
      if (!now.equals(source)) {
        throw new IllegalStateException(
            device.path() + " changed since it was named: it now reads as " + now.said() + ".");
      }
    }
    var restarted = make(pool, source);
    var trial =
        source instanceof PoolSource.File ? "; slower than a disk, meant for trying ZFS" : "";
    stepDone(5, pool + " (" + source.said() + trial + ")" + restarted);
  }

  /**
   * Creates the pool. An incusd that started before the ZFS tools were installed and does not see
   * them is restarted only when it runs no container, since a restart ends every {@code incus exec}
   * into one; otherwise the operator is told to restart it. Returns what the step says it did.
   */
  private String make(String pool, PoolSource source)
      throws IOException, InterruptedException, TimeoutException {
    var made = storage.create(pool, source);
    var unseen = !made.ok() && IncusStorage.zfsUnseen(made);
    if (unseen) {
      restartToSeeZfs(made);
      made = storage.create(pool, source);
    }
    if (!made.ok()) {
      throw new IOException("Failed to create storage pool " + pool + ": " + made.reason());
    }
    return unseen ? "; restarted Incus, which ran no container, to see ZFS" : "";
  }

  private void restartToSeeZfs(ShellExec.Result unseen)
      throws IOException, InterruptedException, TimeoutException {
    if (!new ContainerManager(shell).listAll().isEmpty()) {
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
    var existed = shell.exec(List.of("incus", "profile", "show", profile)).ok();
    if (!existed) {
      var create = shell.exec(List.of("incus", "profile", "create", profile));
      if (!create.ok()) {
        throw new IOException("Failed to create Incus profile: " + create.stderr());
      }
    }
    var held =
        shell
            .exec(List.of("incus", "profile", "device", "list", profile))
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
            ? shell
                .exec(List.of("incus", "profile", "device", "get", profile, "root", "pool"))
                .stdout()
                .strip()
            : pool;
    var found = shell.isDryRun() ? Optional.of(planned(pool, source)) : storage.find(named);
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
        profile
            + (existed ? " (already exists)" : "")
            + ": containers on "
            + root.name()
            + " ("
            + root.driver()
            + ")");
    return root;
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
    var from = source instanceof PoolSource.Device device ? device.path() : null;
    return new IncusStorage.Pool(pool, source.driver(), from, null, Optional.empty());
  }

  private void cacheImage() throws IOException, InterruptedException, TimeoutException {
    step(7, "Caching Ubuntu 24.04 image...");

    var check =
        shell.exec(List.of("bash", "-c", "incus image list --format=json | grep -q ubuntu-24.04"));
    if (check.ok()) {
      stepDone(7, "already cached");
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
      status = shell.exec(List.of("ufw", "status"));
    } catch (IOException e) {
      stepSkipped(8, "UFW not installed");
      return;
    }
    if (!status.ok() || !status.stdout().contains("Status: active")) {
      stepSkipped(8, "UFW not active");
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
