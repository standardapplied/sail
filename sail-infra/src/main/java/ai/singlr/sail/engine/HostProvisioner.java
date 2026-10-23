/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Provisions a bare-metal host: keeps a library update from restarting Incus ({@link
 * NeedrestartPolicy}), installs and holds what sail runs on ({@link HostPackages}), creates the
 * storage pool (dir or ZFS), initializes Incus, creates the base profile, caches the container
 * image, and writes {@code /etc/sail/host.yaml}.
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
  private static final Duration POOL_TIMEOUT = Duration.ofMinutes(3);
  private static final Duration IMAGE_TIMEOUT = Duration.ofMinutes(5);

  private final ShellExec shell;
  private final ProvisionListener listener;
  private final HostPackages packages;
  private final OperatorCommand operator;

  /**
   * A provisioner whose steps report to {@code listener}, their hints rendered for {@code
   * operator}.
   */
  public HostProvisioner(ShellExec shell, ProvisionListener listener, OperatorCommand operator) {
    this.shell = shell;
    this.listener = Objects.requireNonNullElse(listener, ProvisionListener.NOOP);
    this.packages = new HostPackages(shell, this.listener::onStepProgress);
    this.operator = operator;
  }

  /**
   * Runs all provisioning steps and writes host.yaml.
   *
   * @param storageBackend {@code "dir"} (host filesystem) or {@code "zfs"} (dedicated disk)
   * @param disk the disk device path (e.g. {@code /dev/sdb}), null for dir backend
   * @param pool the storage pool name (e.g. {@code devpool})
   * @param bridge the network bridge name (e.g. {@code incusbr0})
   * @param serverIp the server's public IP address (for SSH config generation), may be null
   * @param hostYamlPath where to write host.yaml (usually {@code /etc/sail/host.yaml})
   * @param dbPath the box's database, whose round lock the final write holds
   * @return the written HostYaml
   */
  public HostYaml provision(
      String storageBackend,
      String disk,
      String pool,
      String bridge,
      String serverIp,
      Path hostYamlPath,
      Path dbPath)
      throws IOException, InterruptedException, TimeoutException {
    configured(hostYamlPath);

    var incusVersion = runSteps(storageBackend, disk, pool, bridge);

    try (var rounds = holdRounds(dbPath)) {
      var kept = configured(hostYamlPath);
      var hostYaml =
          new HostYaml(
              storageBackend,
              pool,
              disk,
              bridge,
              HostYaml.DEFAULT_PROFILE,
              HostYaml.DEFAULT_IMAGE,
              incusVersion,
              serverIp,
              DateTimeUtils.now().toString(),
              kept.webauthn(),
              kept.sync(),
              kept.limits(),
              kept.retention());
      writeHostYaml(hostYaml, hostYamlPath);
      return hostYaml;
    }
  }

  /** Runs every step, returning the incus version installed; a step that throws is failed. */
  private String runSteps(String storageBackend, String disk, String pool, String bridge)
      throws IOException, InterruptedException, TimeoutException {
    var isDir = HostYaml.BACKEND_DIR.equals(storageBackend);
    try {
      writeNeedrestartPolicy();
      installIncus();
      installZfs(storageBackend);
      var incusVersion = initIncus(bridge);
      createStoragePool(isDir, pool, disk);
      createBaseProfile(pool, bridge);
      cacheImage();
      configureFirewall(bridge);
      return incusVersion;
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

  private void installZfs(String storageBackend)
      throws IOException, InterruptedException, TimeoutException {
    if (!HostPackages.forBackend(storageBackend).contains(HostPackages.ZFS)) {
      stepSkipped(3, "not needed (" + storageBackend + " backend)");
      return;
    }
    step(3, "Installing ZFS utilities...");
    stepDone(3, packages.ensure(HostPackages.ZFS) ? "installed" : "already installed");
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

  private void createStoragePool(boolean isDir, String pool, String disk)
      throws IOException, InterruptedException, TimeoutException {
    step(5, "Creating storage pool...");

    if (isDir) {
      var check = shell.exec(List.of("incus", "storage", "show", pool));
      if (check.ok()) {
        stepDone(5, pool + " (already exists)");
        return;
      }

      var result = shell.exec(List.of("incus", "storage", "create", pool, "dir"));
      if (!result.ok()) {
        throw new IOException("Failed to create dir storage pool: " + result.stderr());
      }
      stepDone(5, pool + " (dir)");
    } else {
      var check = shell.exec(List.of("zpool", "list", pool));
      if (check.ok()) {
        stepDone(5, pool + " (already exists)");
        return;
      }

      var result = shell.exec(List.of("zpool", "create", "-f", pool, disk), null, POOL_TIMEOUT);
      if (!result.ok()) {
        throw new IOException("Failed to create ZFS pool: " + result.stderr());
      }
      stepDone(5, pool + " (zfs on " + disk + ")");
    }
  }

  private void createBaseProfile(String pool, String bridge)
      throws IOException, InterruptedException, TimeoutException {
    step(6, "Creating base profile...");

    var profile = HostYaml.DEFAULT_PROFILE;

    var check = shell.exec(List.of("incus", "profile", "show", profile));
    if (check.ok()) {
      stepDone(6, profile + " (already exists)");
      return;
    }

    var create = shell.exec(List.of("incus", "profile", "create", profile));
    if (!create.ok()) {
      throw new IOException("Failed to create Incus profile: " + create.stderr());
    }
    var addDisk =
        shell.exec(
            List.of(
                "incus",
                "profile",
                "device",
                "add",
                profile,
                "root",
                "disk",
                "path=/",
                "pool=" + pool));
    if (!addDisk.ok()) {
      throw new IOException("Failed to add root disk to profile: " + addDisk.stderr());
    }
    var addNic =
        shell.exec(
            List.of(
                "incus",
                "profile",
                "device",
                "add",
                profile,
                "eth0",
                "nic",
                "name=eth0",
                "network=" + bridge,
                "type=nic"));
    if (!addNic.ok()) {
      throw new IOException("Failed to add NIC to profile: " + addNic.stderr());
    }

    stepDone(6, profile);
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
