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
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Provisions a bare-metal host: installs Incus, creates the storage pool (dir or ZFS), initializes
 * Incus, creates the base profile, caches the container image, and writes {@code
 * /etc/sail/host.yaml}.
 *
 * <p>Every step is idempotent — safe to run again on an already-provisioned host.
 */
public final class HostProvisioner {

  private static final int TOTAL_STEPS = 8;
  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration POOL_TIMEOUT = Duration.ofMinutes(3);
  private static final Duration IMAGE_TIMEOUT = Duration.ofMinutes(5);

  private final ShellExec shell;
  private final ProvisionListener listener;

  public HostProvisioner(ShellExec shell, ProvisionListener listener) {
    this.shell = shell;
    this.listener = Objects.requireNonNullElse(listener, ProvisionListener.NOOP);
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
   * @return the written HostYaml
   */
  public HostYaml provision(
      String storageBackend,
      String disk,
      String pool,
      String bridge,
      String serverIp,
      Path hostYamlPath)
      throws IOException, InterruptedException, TimeoutException {
    var isDir = HostYaml.BACKEND_DIR.equals(storageBackend);

    updatePackages();
    installIncus();
    installZfs(isDir);
    var incusVersion = initIncus(bridge);
    createStoragePool(isDir, pool, disk);
    createBaseProfile(pool, bridge);
    cacheImage();
    configureFirewall(bridge);

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
            DateTimeUtils.now().toString());

    writeHostYaml(hostYaml, hostYamlPath);
    return hostYaml;
  }

  private void updatePackages() throws IOException, InterruptedException, TimeoutException {
    step(1, "Updating system packages...");
    var update = shell.exec(List.of("apt-get", "update", "-qq"), null, INSTALL_TIMEOUT);
    if (!update.ok()) {
      throw new IOException("apt-get update failed: " + update.stderr());
    }
    var upgrade = shell.exec(List.of("apt-get", "upgrade", "-y", "-qq"), null, INSTALL_TIMEOUT);
    if (!upgrade.ok()) {
      throw new IOException("apt-get upgrade failed: " + upgrade.stderr());
    }
    stepDone(1, "up to date");
  }

  private void installIncus() throws IOException, InterruptedException, TimeoutException {
    step(2, "Installing Incus (Zabbly stable)...");

    var check = shell.exec(List.of("which", "incus"));
    if (check.ok()) {
      stepDone(2, "already installed");
      return;
    }

    var mkdirResult = shell.exec(List.of("mkdir", "-p", "/etc/apt/keyrings"));
    if (!mkdirResult.ok()) {
      throw new IOException("Failed to create /etc/apt/keyrings: " + mkdirResult.stderr());
    }
    var gpg =
        shell.exec(
            List.of(
                "bash",
                "-c",
                "curl -fsSL https://pkgs.zabbly.com/key.asc -o /etc/apt/keyrings/zabbly.asc"),
            null,
            INSTALL_TIMEOUT);
    if (!gpg.ok()) {
      throw new IOException("Failed to add Zabbly GPG key: " + gpg.stderr());
    }

    var codename = shell.exec(List.of("bash", "-c", ". /etc/os-release && echo $VERSION_CODENAME"));
    if (!codename.ok()) {
      throw new IOException("Failed to detect OS codename: " + codename.stderr());
    }
    var arch = shell.exec(List.of("dpkg", "--print-architecture"));
    var aptSource =
        """
        Enabled: yes
        Types: deb
        URIs: https://pkgs.zabbly.com/incus/stable
        Suites: %s
        Components: main
        Architectures: %s
        Signed-By: /etc/apt/keyrings/zabbly.asc
        """
            .formatted(codename.stdout().strip(), arch.ok() ? arch.stdout().strip() : "amd64");
    pushHostFile(Path.of("/etc/apt/sources.list.d/zabbly-incus-stable.sources"), aptSource);
    var aptUpdate = shell.exec(List.of("apt-get", "update", "-qq"), null, INSTALL_TIMEOUT);
    if (!aptUpdate.ok()) {
      throw new IOException(
          "apt-get update failed after adding Zabbly repo: " + aptUpdate.stderr());
    }
    var install =
        shell.exec(List.of("apt-get", "install", "-y", "-qq", "incus"), null, INSTALL_TIMEOUT);
    if (!install.ok()) {
      throw new IOException("Failed to install Incus: " + install.stderr());
    }
    stepDone(2, "installed");
  }

  private void installZfs(boolean isDir)
      throws IOException, InterruptedException, TimeoutException {
    if (isDir) {
      stepSkipped(3, "not needed (dir backend)");
      return;
    }

    step(3, "Installing ZFS utilities...");

    var check = shell.exec(List.of("which", "zpool"));
    if (check.ok()) {
      stepDone(3, "already installed");
      return;
    }

    var result =
        shell.exec(
            List.of("apt-get", "install", "-y", "-qq", "zfsutils-linux"), null, INSTALL_TIMEOUT);
    if (!result.ok()) {
      throw new IOException("Failed to install ZFS utilities: " + result.stderr());
    }
    stepDone(3, "installed");
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
              INSTALL_TIMEOUT);
    } finally {
      Files.deleteIfExists(preseedFile);
    }
    if (!result.ok() && !result.stderr().contains("already exists")) {
      throw new IOException("Incus init failed: " + result.stderr());
    }

    var version = "unknown";
    var vResult = shell.exec(List.of("incus", "version"));
    if (vResult.ok()) {
      var firstLine = vResult.stdout().lines().findFirst().orElse("");
      var colonIdx = firstLine.indexOf(':');
      version = colonIdx >= 0 ? firstLine.substring(colonIdx + 1).strip() : firstLine.strip();
    }

    stepDone(4, "bridge: " + bridge);
    return version;
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

    var status = shell.exec(List.of("ufw", "status"));
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

  /**
   * Writes content to a host file via temp file + {@code install -m 644}. Routes the final
   * placement through the shell executor so test doubles capture the write and dry-run mode prints
   * it rather than touching the real filesystem.
   */
  private void pushHostFile(Path path, String content)
      throws IOException, InterruptedException, TimeoutException {
    var tmpFile = Files.createTempFile("sail-hostfile-", ".tmp");
    try {
      Files.writeString(tmpFile, content);
      var result = shell.exec(List.of("install", "-m", "644", tmpFile.toString(), path.toString()));
      if (!result.ok()) {
        throw new IOException("Failed to write " + path + ": " + result.stderr());
      }
    } finally {
      Files.deleteIfExists(tmpFile);
    }
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
