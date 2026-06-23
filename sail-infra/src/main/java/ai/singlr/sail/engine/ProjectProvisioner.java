/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SailYaml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Provisions a project container: launches via Incus, sets resource limits, installs packages,
 * configures SSH, and writes project state. Uses {@link ProvisionTracker} for resumable progress
 * tracking — if a step fails, re-running picks up from where it left off.
 *
 * <p>Every step is idempotent — safe to run again on a partially-provisioned container.
 */
public final class ProjectProvisioner {

  private static final int TOTAL_STEPS = ProjectPhase.values().length;
  private static final Duration LAUNCH_TIMEOUT = Duration.ofMinutes(2);
  private static final Duration NETWORK_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);

  private static final Pattern VERSION_PATTERN = Pattern.compile("[0-9][0-9.]*");
  private static final String MAVEN_PRIMARY_BASE_URL = "https://dlcdn.apache.org/maven/maven-3/";
  private static final String MAVEN_ARCHIVE_BASE_URL =
      "https://archive.apache.org/dist/maven/maven-3/";

  private static final List<String> BASELINE_PACKAGES =
      List.of("curl", "wget", "git", "sudo", "openssh-server");

  private final ShellExec shell;
  private final ProvisionTracker<ProjectPhase> tracker;
  private final ProvisionListener listener;
  private ProjectPhase currentPhase;

  public ProjectProvisioner(
      ShellExec shell, ProvisionTracker<ProjectPhase> tracker, ProvisionListener listener) {
    this.shell = shell;
    this.tracker = tracker;
    this.listener = Objects.requireNonNullElse(listener, ProvisionListener.NOOP);
  }

  /**
   * Runs all provisioning steps with resume support. On failure, the current phase is recorded in
   * the tracker and the exception is re-thrown.
   */
  public void provision(
      SailYaml config, HostYaml host, Map<String, String> gitTokens, Path singYamlPath)
      throws Exception {
    tracker.load();
    try {
      launchContainer(config, host);
      setDiskQuota(config, host);
      setResourceLimits(config);
      waitForNetwork(config);
      installPackages(config);
      configureSsh(config);
      installPodman(config);
      configureTestcontainers(config);
      installJdk(config);
      installNode(config);
      configureGit(config, gitTokens);
      installMaven(config);
      cloneRepos(config, gitTokens);
      pushWorkspaceFiles(config, singYamlPath);
      provisionServices(config);
      configurePruneCron(config);
      installAgentTools(config);
      generateAgentContext(config);
      createSpecsScaffold(config);
      writeProjectState(config);
      tracker.cleanup();
    } catch (Exception e) {
      if (currentPhase != null) {
        tracker.recordFailure(currentPhase, e.getMessage());
      }
      throw e;
    }
  }

  private void launchContainer(SailYaml config, HostYaml host) throws Exception {
    currentPhase = ProjectPhase.CONTAINER_LAUNCHED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(1, "container already running");
      return;
    }
    step(1, "Launching container " + config.name() + "...");

    var check = shell.exec(List.of("incus", "info", config.name()));
    if (check.ok()) {
      tracker.advance(currentPhase);
      stepDone(1, "Container " + config.name() + " (already exists)");
      return;
    }

    var rawImage = Objects.requireNonNullElse(config.image(), host.image());
    var image = rawImage.replace('/', '-');
    var result =
        shell.exec(
            List.of("incus", "launch", image, config.name(), "--profile", host.baseProfile()),
            null,
            LAUNCH_TIMEOUT);
    if (!result.ok()) {
      throw new IOException("Failed to launch container: " + result.stderr());
    }
    attachEventSocket(config.name());
    tracker.advance(currentPhase);
    stepDone(1, "Container " + config.name());
  }

  /**
   * Idempotently bind-mounts the host's sail-api event socket into the container at {@code
   * /run/sail/api.sock}, installs the {@code sail-event.sh} hook helper at {@code
   * ~/.sail/bin/sail-event.sh}, and writes the sail-owned Claude Code settings file at {@code
   * ~/.sail/claude-settings.json}. Failure of any step is logged but non-fatal — without these,
   * agent hooks fall back to file-only audit and lose the live event-bus fan-out from inside the
   * container.
   */
  private void attachEventSocket(String container) {
    try {
      var manager = new IncusDeviceManager(shell);
      manager.ensureEventSocket(
          container, SailPaths.apiSocketHostDir(), SailPaths.apiSocketContainerDir());
    } catch (Exception e) {
      System.err.println(
          "  [provision] Warning: failed to attach event socket to "
              + container
              + ": "
              + e.getMessage()
              + ". Run 'sail project sync "
              + container
              + "' to retry.");
    }
    try {
      new SailEventHelper(shell).install(container);
    } catch (Exception e) {
      System.err.println(
          "  [provision] Warning: failed to install sail-event.sh in "
              + container
              + ": "
              + e.getMessage());
    }
    try {
      new SpecCliHelper(shell).install(container);
    } catch (Exception e) {
      System.err.println(
          "  [provision] Warning: failed to install the spec CLI in "
              + container
              + ": "
              + e.getMessage());
    }
    try {
      new ClaudeCodeHookConfig(shell).install(container);
    } catch (Exception e) {
      System.err.println(
          "  [provision] Warning: failed to install claude-settings.json in "
              + container
              + ": "
              + e.getMessage());
    }
    try {
      new CodexHookConfig(shell).install(container);
    } catch (Exception e) {
      System.err.println(
          "  [provision] Warning: failed to install codex hooks.json in "
              + container
              + ": "
              + e.getMessage());
    }
  }

  private void setDiskQuota(SailYaml config, HostYaml host) throws Exception {
    currentPhase = ProjectPhase.DISK_QUOTA_SET;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(2, "disk quota already set");
      return;
    }
    step(2, "Setting disk quota...");

    var disk = config.resources().disk();
    var result =
        shell.exec(
            List.of(
                "incus", "config", "device", "override", config.name(), "root", "size=" + disk));
    if (!result.ok()) {
      if (result.stderr().contains("already exists")) {
        var setResult =
            shell.exec(
                List.of("incus", "config", "device", "set", config.name(), "root", "size=" + disk));
        if (!setResult.ok()) {
          throw new IOException("Failed to set disk quota: " + setResult.stderr());
        }
      } else {
        throw new IOException("Failed to set disk quota: " + result.stderr());
      }
    }
    tracker.advance(currentPhase);
    if (host.isDir()) {
      stepDone(2, "Disk quota " + disk + " (advisory)");
    } else {
      stepDone(2, "Disk quota " + disk);
    }
  }

  private void setResourceLimits(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.RESOURCE_LIMITS_SET;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(3, "resource limits already set");
      return;
    }
    step(3, "Setting resource limits...");

    var cpu = config.resources().cpu();
    var memory = config.resources().memory();
    var result =
        shell.exec(
            List.of(
                "incus",
                "config",
                "set",
                config.name(),
                "limits.cpu=" + cpu,
                "limits.memory=" + memory,
                "security.nesting=true",
                "raw.lxc=lxc.apparmor.profile=unconfined"));
    if (!result.ok()) {
      throw new IOException("Failed to set resource limits: " + result.stderr());
    }
    var restart = shell.exec(List.of("incus", "restart", config.name()));
    if (!restart.ok()) {
      throw new IOException("Failed to restart container after config: " + restart.stderr());
    }
    tracker.advance(currentPhase);
    stepDone(3, "Resources " + cpu + " CPU, " + memory + " RAM");
  }

  private void waitForNetwork(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.NETWORK_READY;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(4, "network already ready");
      return;
    }
    step(4, "Waiting for container network...");

    var script =
        """
            for i in $(seq 1 30); do \
              ip -4 addr show eth0 | grep -q inet && exit 0; \
              sleep 1; \
            done; exit 1""";
    var result = execInContainer(config.name(), List.of("bash", "-c", script), NETWORK_TIMEOUT);
    if (!result.ok()) {
      throw new IOException("Container network did not come up within 30s");
    }
    var ipResult =
        execInContainer(
            config.name(),
            List.of("bash", "-c", "ip -4 addr show eth0 | grep -oP '(?<=inet )\\S+'"));
    var ipDetail = ipResult.ok() ? ipResult.stdout().strip() : "";
    tracker.advance(currentPhase);
    stepDone(4, "Network connected" + (ipDetail.isEmpty() ? "" : " (" + ipDetail + ")"));
  }

  private void installPackages(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.PACKAGES_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(5, "packages already installed");
      return;
    }
    step(5, "Installing packages...");

    var updateResult =
        execInContainer(config.name(), List.of("apt-get", "update", "-qq"), INSTALL_TIMEOUT);
    if (!updateResult.ok()) {
      throw new IOException("Failed to update package lists: " + updateResult.stderr());
    }

    var packages = new ArrayList<>(BASELINE_PACKAGES);
    if (config.packages() != null) {
      packages.addAll(config.packages());
    }

    var cmd = new ArrayList<>(List.of("apt-get", "install", "-y", "-qq"));
    cmd.addAll(packages);
    var result = execInContainer(config.name(), cmd, INSTALL_TIMEOUT);
    if (!result.ok()) {
      throw new IOException("Failed to install packages: " + result.stderr());
    }
    tracker.advance(currentPhase);
    stepDone(5, "Installed " + packages.size() + " packages");
  }

  private void configureSsh(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.SSH_CONFIGURED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(6, "SSH already configured");
      return;
    }
    step(6, "Configuring SSH access...");

    if (config.ssh() == null) {
      tracker.advance(currentPhase);
      stepDone(6, "skipped (no ssh config)");
      return;
    }

    var name = config.name();
    var user = config.ssh().user();

    var userCheck = execInContainer(name, List.of("id", user));
    if (!userCheck.ok()) {
      var ubuntuCheck = execInContainer(name, List.of("id", "ubuntu"));
      if (ubuntuCheck.ok()) {
        execInContainer(name, List.of("userdel", "-r", "ubuntu"));
      }
      var result = execInContainer(name, List.of("useradd", "-m", "-s", "/bin/bash", user));
      if (!result.ok()) {
        throw new IOException("Failed to create user '" + user + "': " + result.stderr());
      }
    }

    var sudoResult = execInContainer(name, List.of("usermod", "-aG", "sudo", user));
    if (!sudoResult.ok()) {
      throw new IOException(
          "Failed to add user '" + user + "' to sudo group: " + sudoResult.stderr());
    }
    pushFileToContainer(name, "/etc/sudoers.d/" + user, user + " ALL=(ALL) NOPASSWD:ALL\n", "0440");

    if (config.ssh().authorizedKeys() != null && !config.ssh().authorizedKeys().isEmpty()) {
      var sshDir = "/home/" + user + "/.ssh";
      execInContainer(name, List.of("mkdir", "-p", sshDir));
      pushFileToContainer(
          name,
          sshDir + "/authorized_keys",
          String.join("\n", config.ssh().authorizedKeys()) + "\n",
          "0600");
      execInContainer(name, List.of("chmod", "700", sshDir));
      execInContainer(name, List.of("chown", "-R", user + ":" + user, sshDir));
    }

    var sshResult = execInContainer(name, List.of("systemctl", "enable", "--now", "ssh"));
    if (!sshResult.ok()) {
      throw new IOException(
          "Failed to start SSH service: "
              + sshResult.stderr()
              + "\n  Remote access to the container will not work.");
    }

    tracker.advance(currentPhase);
    stepDone(6, "Created user " + user + " (uid 1000)");
  }

  private void installPodman(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.PODMAN_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(7, "Podman already installed");
      return;
    }
    step(7, "Installing Podman...");

    var name = config.name();

    var check = execInContainer(name, List.of("which", "podman"));
    if (!check.ok()) {
      var result =
          execInContainer(
              name, List.of("apt-get", "install", "-y", "-qq", "podman"), INSTALL_TIMEOUT);
      if (!result.ok()) {
        throw new IOException("Failed to install Podman: " + result.stderr());
      }
    }

    pushFileToContainer(
        name,
        "/etc/containers/registries.conf.d/01-unqualified.conf",
        "unqualified-search-registries = [\"docker.io\"]\n");

    var sshUser = sshUser(config);
    var lingerResult = execInContainer(name, List.of("loginctl", "enable-linger", sshUser));
    if (!lingerResult.ok()) {
      throw new IOException(
          "Failed to enable linger for user '"
              + sshUser
              + "': "
              + lingerResult.stderr()
              + "\n  Podman services will not survive container reboots.");
    }

    waitForUserBus(name);

    var socketResult =
        execAsDevUser(
            name,
            List.of("systemctl", "--user", "enable", "--now", "podman.socket"),
            INSTALL_TIMEOUT);
    if (!socketResult.ok()) {
      throw new IOException(
          "Failed to enable podman.socket: "
              + socketResult.stderr()
              + "\n  Testcontainers requires the rootless Podman socket at "
              + ContainerExec.DEV_XDG_RUNTIME_DIR
              + "/podman/podman.sock");
    }

    ensurePodmanRestartService(name, sshUser);

    tracker.advance(currentPhase);
    stepDone(7, "Podman (rootless) + socket + auto-restart");
  }

  private void configureTestcontainers(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.TESTCONTAINERS_CONFIGURED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(8, "Testcontainers already configured");
      return;
    }
    step(8, "Configuring Testcontainers environment...");

    var name = config.name();
    var profilePath = "/etc/profile.d/testcontainers.sh";

    var check = execInContainer(name, List.of("test", "-f", profilePath));
    if (check.ok()) {
      tracker.advance(currentPhase);
      stepDone(8, "already configured");
      return;
    }

    var envScript =
        """
            # Testcontainers via Podman (added by sail)
            export DOCKER_HOST=unix://%s/podman/podman.sock
            export TESTCONTAINERS_RYUK_DISABLED=true
            """
            .formatted(ContainerExec.DEV_XDG_RUNTIME_DIR);
    pushFileToContainer(name, profilePath, envScript);

    tracker.advance(currentPhase);
    stepDone(8, "Testcontainers configured");
  }

  private void installJdk(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.JDK_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(9, "JDK already installed");
      return;
    }

    if (config.runtimes() == null || config.runtimes().jdk() == 0) {
      tracker.advance(currentPhase);
      stepSkipped(9, "no JDK requested");
      return;
    }

    var version = config.runtimes().jdk();
    step(9, "Installing JDK " + version + "...");

    var name = config.name();
    var pkg = "temurin-" + version + "-jdk";

    var check = execInContainer(name, List.of("dpkg", "-s", pkg));
    if (check.ok()) {
      tracker.advance(currentPhase);
      stepDone(9, "JDK " + version + " (Temurin, already installed)");
      return;
    }

    var gpgPrereqs =
        execInContainer(
            name,
            List.of("apt-get", "install", "-y", "-qq", "gpg", "apt-transport-https"),
            INSTALL_TIMEOUT);
    if (!gpgPrereqs.ok()) {
      throw new IOException("Failed to install GPG prerequisites: " + gpgPrereqs.stderr());
    }
    var gpgKey =
        execInContainer(
            name,
            List.of(
                "bash",
                "-c",
                "curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public"
                    + " | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg"),
            INSTALL_TIMEOUT);
    if (!gpgKey.ok()) {
      throw new IOException("Failed to import Adoptium GPG key: " + gpgKey.stderr());
    }

    var distCodename =
        execInContainer(name, List.of("bash", "-c", ". /etc/os-release && echo $VERSION_CODENAME"));
    if (!distCodename.ok()) {
      throw new IOException("Failed to detect OS codename: " + distCodename.stderr());
    }
    var codename = distCodename.stdout().strip();
    var aptLine =
        "deb [signed-by=/usr/share/keyrings/adoptium.gpg]"
            + " https://packages.adoptium.net/artifactory/deb "
            + codename
            + " main\n";
    pushFileToContainer(name, "/etc/apt/sources.list.d/adoptium.list", aptLine);

    var jdkUpdate = execInContainer(name, List.of("apt-get", "update", "-qq"), INSTALL_TIMEOUT);
    if (!jdkUpdate.ok()) {
      throw new IOException(
          "Failed to update package lists after adding Adoptium repository: " + jdkUpdate.stderr());
    }
    var result =
        execInContainer(name, List.of("apt-get", "install", "-y", "-qq", pkg), INSTALL_TIMEOUT);
    if (!result.ok()) {
      throw new IOException(
          "Failed to install "
              + pkg
              + ": "
              + result.stderr()
              + "\n  Check that JDK "
              + version
              + " is available in the Adoptium repository.");
    }

    var archResult = execInContainer(name, List.of("dpkg", "--print-architecture"));
    var arch = archResult.ok() ? archResult.stdout().strip() : "amd64";
    var javaHome = "/usr/lib/jvm/" + pkg + "-" + arch;
    var profileScript =
        """
            export JAVA_HOME=%s
            export PATH=$JAVA_HOME/bin:$PATH
            """
            .formatted(javaHome);
    pushFileToContainer(name, "/etc/profile.d/jdk-env.sh", profileScript);

    tracker.advance(currentPhase);
    stepDone(9, "JDK " + version + " (Temurin)");
  }

  private void installNode(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.NODE_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(10, "Node already installed");
      return;
    }

    if (config.runtimes() == null || config.runtimes().node() == null) {
      tracker.advance(currentPhase);
      stepSkipped(10, "no Node requested");
      return;
    }

    var version = config.runtimes().node();
    if (!VERSION_PATTERN.matcher(version).matches()) {
      throw new IOException(
          "Invalid Node.js version '"
              + version
              + "': must contain only digits and dots."
              + "\n  Example: runtimes.node: 22.4.0");
    }
    var majorVersion = version.contains(".") ? version.substring(0, version.indexOf('.')) : version;
    step(10, "Installing Node.js " + version + "...");

    var name = config.name();

    var check = execInContainer(name, List.of("node", "--version"));
    if (check.ok()) {
      var installed = check.stdout().strip();
      if (installed.startsWith("v" + version)) {
        tracker.advance(currentPhase);
        stepDone(10, "Node.js " + installed.substring(1) + " (already installed)");
        return;
      }
    }

    var nodePrereqs =
        execInContainer(
            name,
            List.of("apt-get", "install", "-y", "-qq", "ca-certificates", "curl", "gnupg"),
            INSTALL_TIMEOUT);
    if (!nodePrereqs.ok()) {
      throw new IOException("Failed to install Node.js prerequisites: " + nodePrereqs.stderr());
    }
    var nodeSetup =
        execInContainer(
            name,
            List.of(
                "bash",
                "-c",
                "curl -fsSL https://deb.nodesource.com/setup_" + majorVersion + ".x | bash -"),
            INSTALL_TIMEOUT);
    if (!nodeSetup.ok()) {
      throw new IOException(
          "Failed to configure NodeSource repository for Node "
              + majorVersion
              + ": "
              + nodeSetup.stderr());
    }
    var result =
        execInContainer(
            name, List.of("apt-get", "install", "-y", "-qq", "nodejs"), INSTALL_TIMEOUT);
    if (!result.ok()) {
      throw new IOException(
          "Failed to install Node.js "
              + version
              + ": "
              + result.stderr()
              + "\n  Check that Node "
              + majorVersion
              + " is available from NodeSource.");
    }

    tracker.advance(currentPhase);
    stepDone(10, "Node.js " + version);
  }

  private void configureGit(SailYaml config, Map<String, String> gitTokens) throws Exception {
    currentPhase = ProjectPhase.GIT_CONFIGURED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(11, "git already configured");
      return;
    }

    if (config.git() == null) {
      tracker.advance(currentPhase);
      stepSkipped(11, "no git config");
      return;
    }

    step(11, "Configuring git identity...");

    var name = config.name();
    var user = sshUser(config);

    var nameResult =
        execAsDevUser(name, List.of("git", "config", "--global", "user.name", config.git().name()));
    if (!nameResult.ok()) {
      throw new IOException("Failed to set git user.name: " + nameResult.stderr());
    }
    var emailResult =
        execAsDevUser(
            name, List.of("git", "config", "--global", "user.email", config.git().email()));
    if (!emailResult.ok()) {
      throw new IOException("Failed to set git user.email: " + emailResult.stderr());
    }

    var knownHostsPath = "/etc/ssh/ssh_known_hosts";
    var sshHosts = GitCredentials.extractSshHosts(config.repos());
    if (!sshHosts.isEmpty()) {
      var command =
          new ArrayList<>(
              List.of(
                  "bash",
                  "-c",
                  "out=$1; shift; ssh-keyscan -H \"$@\" >> \"$out\" 2>/dev/null",
                  "ssh-keyscan",
                  knownHostsPath));
      command.addAll(sshHosts);
      execInContainer(name, command, INSTALL_TIMEOUT);
    }

    if ("token".equals(config.git().auth()) && gitTokens != null && !gitTokens.isEmpty()) {
      var credContent = GitCredentials.buildCredentialStore(gitTokens, config.repos());
      var credPath = "/home/" + user + "/.git-credentials";
      pushFileToContainer(name, credPath, credContent, "0600");
      var chownCred = execInContainer(name, List.of("chown", user + ":" + user, credPath));
      if (!chownCred.ok()) {
        throw new IOException("Failed to set ownership on " + credPath + ": " + chownCred.stderr());
      }
      var credHelper =
          execAsDevUser(name, List.of("git", "config", "--global", "credential.helper", "store"));
      if (!credHelper.ok()) {
        throw new IOException("Failed to configure git credential helper: " + credHelper.stderr());
      }
    }

    if ("ssh".equals(config.git().auth()) && config.git().sshKey() != null) {
      var sshKeyHostPath = GitCredentials.resolveHostPath(config.git().sshKey());
      if (!Files.exists(sshKeyHostPath)) {
        throw new IOException(
            "SSH key not found: "
                + sshKeyHostPath
                + "\n  Check the 'git.ssh_key' path in sail.yaml points to an existing private key.");
      }
      var sshDir = "/home/" + user + "/.ssh";
      execInContainer(name, List.of("mkdir", "-p", sshDir));
      execInContainer(name, List.of("chmod", "700", sshDir));

      var keyContent = Files.readString(sshKeyHostPath);
      pushFileToContainer(name, sshDir + "/id_ed25519", keyContent, "0600");

      var pubKeyPath = Path.of(sshKeyHostPath + ".pub");
      if (Files.exists(pubKeyPath)) {
        var pubContent = Files.readString(pubKeyPath);
        pushFileToContainer(name, sshDir + "/id_ed25519.pub", pubContent, "0644");
      }

      execInContainer(name, List.of("chown", "-R", user + ":" + user, sshDir));
    }

    tracker.advance(currentPhase);
    stepDone(11, "Git identity configured");
  }

  private void installMaven(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.MAVEN_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(12, "Maven already installed");
      return;
    }

    if (config.runtimes() == null || config.runtimes().maven() == null) {
      tracker.advance(currentPhase);
      stepSkipped(12, "no Maven requested");
      return;
    }

    var version = config.runtimes().maven();
    if (!VERSION_PATTERN.matcher(version).matches()) {
      throw new IOException(
          "Invalid Maven version '"
              + version
              + "': must contain only digits and dots."
              + "\n  Example: runtimes.maven: 3.9.9");
    }
    step(12, "Installing Maven " + version + "...");

    var name = config.name();

    var check = execInContainer(name, List.of("/opt/maven/bin/mvn", "--version"));
    if (check.ok() && check.stdout().contains(version)) {
      tracker.advance(currentPhase);
      stepDone(12, "Maven " + version + " (already installed)");
      return;
    }

    var urls =
        List.of(
            mavenBinaryUrl(version, MAVEN_PRIMARY_BASE_URL),
            mavenBinaryUrl(version, MAVEN_ARCHIVE_BASE_URL));
    ShellExec.Result result = null;
    for (var url : urls) {
      result =
          execInContainer(
              name,
              List.of("bash", "-c", "curl -fsSL '" + url + "' | tar xz -C /opt"),
              INSTALL_TIMEOUT);
      if (result.ok()) {
        break;
      }
    }
    if (!result.ok()) {
      throw new IOException(
          "Failed to install Maven "
              + version
              + ": "
              + result.stderr()
              + "\n  Checked:"
              + "\n  - "
              + urls.getFirst()
              + "\n  - "
              + urls.get(1)
              + "\n  If this is unexpected, re-run with --dry-run to see what would execute.");
    }

    var linkResult =
        execInContainer(name, List.of("ln", "-sfn", "/opt/apache-maven-" + version, "/opt/maven"));
    if (!linkResult.ok()) {
      throw new IOException("Failed to create Maven symlink: " + linkResult.stderr());
    }

    var envScript =
        """
            export M2_HOME=/opt/maven
            export PATH=$M2_HOME/bin:$PATH
            """;
    pushFileToContainer(name, "/etc/profile.d/maven-env.sh", envScript);

    tracker.advance(currentPhase);
    stepDone(12, "Maven " + version);
  }

  private static String mavenBinaryUrl(String version, String baseUrl) {
    return baseUrl + version + "/binaries/apache-maven-" + version + "-bin.tar.gz";
  }

  private void cloneRepos(SailYaml config, Map<String, String> gitTokens) throws Exception {
    currentPhase = ProjectPhase.REPOS_CLONED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(13, "repos already cloned");
      return;
    }

    if (config.repos() == null || config.repos().isEmpty()) {
      tracker.advance(currentPhase);
      stepSkipped(13, "no repos configured");
      return;
    }

    step(13, "Cloning " + config.repos().size() + " repositories...");

    var name = config.name();
    var user = sshUser(config);
    var cloned = 0;

    refreshCredentialStore(config, gitTokens, name, user);

    if ("ssh".equals(config.git() != null ? config.git().auth() : null)) {
      verifySshConnectivity(name, config.repos());
    }

    execAsDevUser(name, List.of("mkdir", "-p", "/home/" + user + "/workspace"));

    for (var repo : config.repos()) {
      var targetDir = "/home/" + user + "/workspace/" + repo.path();

      var check = execAsDevUser(name, List.of("test", "-d", targetDir));
      if (check.ok()) {
        continue;
      }

      var cmd = new ArrayList<>(List.of("git", "clone"));
      if (repo.branch() != null) {
        cmd.addAll(List.of("--branch", repo.branch()));
      }
      cmd.add("--");
      cmd.addAll(List.of(repo.url(), targetDir));

      var result = execAsDevUser(name, cmd, INSTALL_TIMEOUT);
      if (!result.ok()) {
        throw new IOException("Failed to clone " + repo.url() + ": " + result.stderr());
      }
      cloned++;
    }

    tracker.advance(currentPhase);
    stepDone(13, "Cloned " + cloned + "/" + config.repos().size() + " repositories");
  }

  private void refreshCredentialStore(
      SailYaml config, Map<String, String> gitTokens, String containerName, String user)
      throws Exception {
    if (config.git() == null || !"token".equals(config.git().auth())) {
      return;
    }
    var credContent = GitCredentials.buildCredentialStore(gitTokens, config.repos());
    if (credContent.isEmpty()) {
      return;
    }
    var credPath = "/home/" + user + "/.git-credentials";
    pushFileToContainer(containerName, credPath, credContent, "0600");
    var chownResult = execInContainer(containerName, List.of("chown", user + ":" + user, credPath));
    if (!chownResult.ok()) {
      throw new IOException("Failed to set ownership on " + credPath + ": " + chownResult.stderr());
    }
    var helperResult =
        execAsDevUser(
            containerName, List.of("git", "config", "--global", "credential.helper", "store"));
    if (!helperResult.ok()) {
      throw new IOException("Failed to configure git credential helper: " + helperResult.stderr());
    }
  }

  private void pushWorkspaceFiles(SailYaml config, Path singYamlPath) throws Exception {
    currentPhase = ProjectPhase.WORKSPACE_FILES_PUSHED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(14, "workspace files already pushed");
      return;
    }

    var filesDir = WorkspaceFiles.resolveFilesDir(singYamlPath);
    if (filesDir == null) {
      tracker.advance(currentPhase);
      stepSkipped(14, "no files/ directory");
      return;
    }

    var entries = WorkspaceFiles.listFiles(filesDir);
    if (entries.isEmpty()) {
      tracker.advance(currentPhase);
      stepSkipped(14, "files/ directory is empty");
      return;
    }

    step(14, "Pushing " + entries.size() + " workspace file(s)...");

    var user = sshUser(config);
    var workspace = "/home/" + user + "/workspace/";
    for (var entry : entries) {
      var cmd =
          new ArrayList<>(List.of("incus", "file", "push", "-p", "--uid", "1000", "--gid", "1000"));
      if (WorkspaceFiles.isExecutable(entry.relativePath())) {
        cmd.addAll(List.of("--mode", "0755"));
      }
      cmd.add(entry.hostPath().toString());
      cmd.add(config.name() + workspace + entry.relativePath());
      var pushResult = shell.exec(cmd);
      if (!pushResult.ok()) {
        throw new IOException(
            "Failed to push workspace file '" + entry.relativePath() + "': " + pushResult.stderr());
      }
    }

    tracker.advance(currentPhase);
    stepDone(14, "Pushed " + entries.size() + " workspace file(s)");
  }

  private void provisionServices(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.SERVICES_PROVISIONED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(15, "services already provisioned");
      return;
    }

    if (config.services() == null || config.services().isEmpty()) {
      tracker.advance(currentPhase);
      stepSkipped(15, "no services configured");
      return;
    }

    step(15, "Provisioning " + config.services().size() + " services...");

    var name = config.name();
    var started = 0;

    for (var entry : config.services().entrySet()) {
      var svcName = entry.getKey();
      var svc = entry.getValue();

      var check = execAsDevUser(name, List.of("podman", "container", "inspect", svcName));
      if (check.ok()) {
        continue;
      }

      var cmd = PodmanCommands.buildRunCommand(svcName, svc);
      var result = execAsDevUser(name, cmd, INSTALL_TIMEOUT);
      if (!result.ok()) {
        throw new IOException("Failed to start service '" + svcName + "': " + result.stderr());
      }
      started++;
    }

    tracker.advance(currentPhase);
    var serviceNames = String.join(", ", config.services().keySet());
    stepDone(
        15,
        "Started " + started + "/" + config.services().size() + " services (" + serviceNames + ")");
  }

  private void configurePruneCron(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.PRUNE_CRON_CONFIGURED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(16, "cleanup cron already configured");
      return;
    }
    step(16, "Configuring stale container cleanup...");

    var name = config.name();
    var user = sshUser(config);

    var check = execAsDevUser(name, List.of("crontab", "-l"));
    if (check.ok() && check.stdout().contains(CleanupScripts.CONTAINER_CLEANUP_PATH)) {
      tracker.advance(currentPhase);
      stepDone(16, "already configured");
      return;
    }

    execAsDevUser(name, List.of("mkdir", "-p", CleanupScripts.SAIL_DIR));
    pushFileToContainer(
        name,
        CleanupScripts.CONTAINER_CLEANUP_PATH,
        CleanupScripts.containerCleanupScript(),
        "0755");
    pushFileToContainer(
        name, CleanupScripts.AGENT_CLEANUP_PATH, CleanupScripts.agentCleanupScript(), "0755");
    execInContainer(name, List.of("chown", "-R", user + ":" + user, CleanupScripts.SAIL_DIR));

    var existingCron = check.ok() ? check.stdout() : "";
    var newCron = CleanupScripts.buildUpgradedCrontab(existingCron);
    var tmpPath = writeTempCrontab(name, newCron);
    var cronResult = execInContainer(name, List.of("crontab", "-u", user, tmpPath));
    execInContainer(name, List.of("rm", "-f", tmpPath));
    if (!cronResult.ok()) {
      throw new IOException(
          "Failed to install crontab for user '" + user + "': " + cronResult.stderr());
    }

    tracker.advance(currentPhase);
    stepDone(16, "Container cleanup scheduled (hourly) + agent cleanup helper installed");
  }

  private void installAgentTools(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.AGENT_TOOLS_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(17, "agent tools already installed");
      return;
    }

    var agentConfig = config.agent();
    if (agentConfig == null) {
      tracker.advance(currentPhase);
      stepSkipped(17, "no agent configured");
      return;
    }

    var installNames = agentConfig.install();
    if (installNames == null || installNames.isEmpty()) {
      installNames = List.of(agentConfig.type());
    }

    var tools = installNames.stream().map(AgentCli::fromYamlName).toList();

    step(17, "Installing " + tools.size() + " agent CLI(s)...");

    var name = config.name();
    var installed = 0;

    for (var tool : tools) {
      var check = execAsDevUser(name, List.of("which", tool.binaryName()));
      if (check.ok()) {
        continue;
      }

      if (tool.requiresNode()) {
        var nodeCheck = execInContainer(name, List.of("which", "node"));
        if (!nodeCheck.ok()) {
          throw new IllegalStateException(
              "Bug: agent '"
                  + tool.yamlName()
                  + "' requires Node.js, but Node is not installed."
                  + " The command layer should have resolved this before provisioning.");
        }
      }

      var result =
          execAsDevUser(name, List.of("bash", "-c", tool.installCommand()), INSTALL_TIMEOUT);
      if (!result.ok()) {
        throw new IOException(
            "Failed to install agent CLI '" + tool.yamlName() + "': " + result.stderr());
      }
      installed++;
    }

    tracker.advance(currentPhase);
    var toolNames = tools.stream().map(AgentCli::yamlName).toList();
    stepDone(
        17,
        "Installed "
            + installed
            + "/"
            + tools.size()
            + " agent tools ("
            + String.join(", ", toolNames)
            + ")");
  }

  private void generateAgentContext(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.CONTEXT_GENERATED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(18, "agent context already generated");
      return;
    }

    var user = sshUser(config);
    var workspace = "/home/" + user + "/workspace";
    execInContainer(config.name(), List.of("mkdir", "-p", workspace));
    execInContainer(config.name(), List.of("chown", user + ":" + user, workspace));

    var result = AgentContextInstaller.install(shell, config.name(), config);
    tracker.advance(currentPhase);

    if (result.isEmpty()) {
      stepSkipped(18, "no agent configured");
      return;
    }

    step(18, "Generating agent context files...");
    var summary = new StringBuilder("Agent context generated");
    if (!result.pushed().isEmpty()) {
      summary.append(" (").append(String.join(", ", baseNames(result.pushed()))).append(")");
    }
    stepDone(18, summary.toString());
  }

  private void createSpecsScaffold(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.SPECS_SCAFFOLD_CREATED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(19, "specs scaffold already created");
      return;
    }

    if (config.agent() == null || config.agent().specsDir() == null) {
      tracker.advance(currentPhase);
      stepSkipped(19, "no specs directory configured");
      return;
    }

    var user = sshUser(config);
    var specsPath = "/home/" + user + "/workspace/" + config.agent().specsDir();
    var check = execInContainer(config.name(), List.of("test", "-d", specsPath));
    if (check.ok()) {
      tracker.advance(currentPhase);
      stepSkipped(19, "specs directory already exists");
      return;
    }

    step(19, "Creating specs scaffold...");
    execInContainer(config.name(), List.of("mkdir", "-p", specsPath));
    execInContainer(config.name(), List.of("chown", "-R", user + ":" + user, specsPath));
    tracker.advance(currentPhase);
    stepDone(19, "specs scaffold created at " + config.agent().specsDir() + "/");
  }

  private void writeProjectState(SailYaml config) throws Exception {
    currentPhase = ProjectPhase.COMPLETE;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped(20, "project state already written");
      return;
    }
    step(20, "Writing project state...");

    var name = config.name();
    execInContainer(name, List.of("mkdir", "-p", "/etc/sail"));

    var yaml =
        """
            name: %s
            created_at: %s
            """
            .formatted(name, DateTimeUtils.now());
    pushFileToContainer(name, "/etc/sail/project.yaml", yaml);

    tracker.advance(currentPhase);
    stepDone(20, "Project config saved");
  }

  /**
   * Ensures the podman-restart systemd user service is enabled so that containers started with
   * {@code --restart=always} auto-start after the Incus container reboots. The service ships with
   * Podman on Ubuntu 24.04 — if missing, we create it manually as a fallback.
   */
  private void ensurePodmanRestartService(String name, String user)
      throws IOException, InterruptedException, TimeoutException {
    var result = execAsDevUser(name, List.of("systemctl", "--user", "enable", "podman-restart"));
    if (result.ok()) {
      return;
    }

    var serviceContent =
        """
            [Unit]
            Description=Podman Start All Containers With Restart Policy Set To Always
            StartLimitIntervalSec=0
            Wants=network-online.target
            After=network-online.target

            [Service]
            Type=oneshot
            RemainAfterExit=true
            ExecStart=/usr/bin/podman start --all --filter restart-policy=always
            ExecStop=/usr/bin/podman stop --all --filter restart-policy=always

            [Install]
            WantedBy=default.target
            """;
    var serviceDir = "/home/" + user + "/.config/systemd/user";
    execAsDevUser(name, List.of("mkdir", "-p", serviceDir));
    pushFileToContainer(name, serviceDir + "/podman-restart.service", serviceContent);
    execInContainer(
        name, List.of("chown", "-R", user + ":" + user, "/home/" + user + "/.config/systemd"));
    var reloadResult = execAsDevUser(name, List.of("systemctl", "--user", "daemon-reload"));
    if (!reloadResult.ok()) {
      throw new IOException("Failed to reload systemd user units: " + reloadResult.stderr());
    }
    var enableResult =
        execAsDevUser(name, List.of("systemctl", "--user", "enable", "podman-restart"));
    if (!enableResult.ok()) {
      throw new IOException(
          "Failed to enable podman-restart service: "
              + enableResult.stderr()
              + "\n  Podman containers with --restart=always will not auto-start after reboot.");
    }
  }

  /**
   * Tests SSH connectivity to each unique git host before attempting clones. Extracts hostnames
   * from SSH-style repo URLs ({@code git@host:path}) and runs {@code ssh -T git@host} inside the
   * container. Fails with a clear diagnostic message if any host rejects the key.
   */
  private void verifySshConnectivity(String name, List<SailYaml.Repo> repos)
      throws IOException, InterruptedException, TimeoutException {
    var hosts =
        repos.stream()
            .map(SailYaml.Repo::url)
            .filter(u -> u.startsWith("git@"))
            .map(u -> u.substring(4, u.indexOf(':')))
            .distinct()
            .toList();

    for (var host : hosts) {
      var result = execAsDevUser(name, List.of("ssh", "-T", "-o", "BatchMode=yes", "git@" + host));
      if (!result.ok() && !result.stderr().contains("successfully authenticated")) {
        throw new IOException(
            "SSH authentication to "
                + host
                + " failed."
                + "\n  Possible causes:"
                + "\n    - The SSH key has a passphrase (not supported in non-interactive mode)"
                + "\n    - The key is not registered in "
                + host
                + "\n    - Wrong key path in sail.yaml (git.ssh_key)"
                + "\n  Debug: ssh into the container and run 'ssh -vT git@"
                + host
                + "'");
      }
    }
  }

  /** Returns the SSH user from config, defaulting to {@code "dev"}. */
  private static String sshUser(SailYaml config) {
    return config.ssh() != null ? config.ssh().user() : "dev";
  }

  /**
   * Writes crontab content to a unique temp file inside the container using {@code mktemp}. Returns
   * the generated path. Avoids the TOCTOU race of a predictable {@code /tmp/sail-crontab.tmp}.
   */
  private String writeTempCrontab(String name, String content)
      throws IOException, InterruptedException, TimeoutException {
    var mktemp = execInContainer(name, List.of("mktemp", "/tmp/sail-crontab.XXXXXX"));
    if (!mktemp.ok()) {
      throw new IOException("Failed to create temp file for crontab: " + mktemp.stderr());
    }
    var tmpPath = mktemp.stdout().strip();
    pushFileToContainer(name, tmpPath, content);
    return tmpPath;
  }

  private ShellExec.Result execInContainer(String name, List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    var full = new ArrayList<>(List.of("incus", "exec", name, "--"));
    full.addAll(command);
    return shell.exec(full);
  }

  private ShellExec.Result execInContainer(String name, List<String> command, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    var full = new ArrayList<>(List.of("incus", "exec", name, "--"));
    full.addAll(command);
    return shell.exec(full, null, timeout);
  }

  /**
   * Executes a command inside the container as the dev user (UID 1000). Delegates to {@link
   * ContainerExec#asDevUser} for command construction.
   */
  private ShellExec.Result execAsDevUser(String name, List<String> command)
      throws IOException, InterruptedException, TimeoutException {
    return shell.exec(ContainerExec.asDevUser(name, command));
  }

  /** Executes a command inside the container as the dev user (UID 1000) with a custom timeout. */
  private ShellExec.Result execAsDevUser(String name, List<String> command, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    return shell.exec(ContainerExec.asDevUser(name, command), null, timeout);
  }

  private void waitForUserBus(String name)
      throws IOException, InterruptedException, TimeoutException {
    var busPath = ContainerExec.DEV_XDG_RUNTIME_DIR + "/bus";
    for (var attempt = 0; attempt < 30; attempt++) {
      var check = execInContainer(name, List.of("test", "-S", busPath));
      if (check.ok()) {
        return;
      }
      Thread.sleep(500);
    }
  }

  private static List<String> baseNames(List<String> paths) {
    return paths.stream().map(p -> p.substring(p.lastIndexOf('/') + 1)).toList();
  }

  /**
   * Writes content to a file inside the container via {@code incus file push}, avoiding shell
   * injection. Content is written to a host-side temp file, pushed, then deleted. Files are created
   * with mode 0644 (world-readable) by default.
   */
  private void pushFileToContainer(String containerName, String remotePath, String content)
      throws IOException, InterruptedException, TimeoutException {
    pushFileToContainer(containerName, remotePath, content, "0644");
  }

  /** Pushes content to a file inside the container with the specified POSIX permission mode. */
  private void pushFileToContainer(
      String containerName, String remotePath, String content, String mode)
      throws IOException, InterruptedException, TimeoutException {
    ContainerFilePush.push(shell, containerName, remotePath, content, List.of("--mode", mode));
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
