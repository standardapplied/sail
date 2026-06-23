/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.ContainerManager.ResourceLimits;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Applies incremental changes to an already-provisioned project container. Detects what is already
 * present in the container and only applies the delta — adding missing services, repos, agent
 * tools, etc. Also supports declarative reconciliation: removing services that are running in the
 * container but no longer declared in sail.yaml.
 *
 * <p>Unlike {@link ProjectProvisioner} (which runs the full provisioning pipeline from scratch),
 * this engine is safe to run on a live container at any time.
 */
public final class ProjectApplier {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);

  /** Result of an apply operation for a single section. */
  public record ApplyResult(int added, int removed, int skipped, List<String> warnings) {
    public static ApplyResult empty() {
      return new ApplyResult(0, 0, 0, List.of());
    }
  }

  private final ShellExec shell;
  private final PrintStream out;

  public ProjectApplier(ShellExec shell, PrintStream out) {
    this.shell = shell;
    this.out = out;
  }

  /**
   * Applies new services from the config. Services already running in the container are skipped.
   */
  public ApplyResult applyServices(String name, Map<String, SailYaml.Service> services)
      throws IOException, InterruptedException, TimeoutException {
    if (services == null || services.isEmpty()) {
      return ApplyResult.empty();
    }
    var added = 0;
    var skipped = 0;
    for (var entry : services.entrySet()) {
      var svcName = entry.getKey();
      var svc = entry.getValue();
      var check =
          shell.exec(
              ContainerExec.asDevUser(name, List.of("podman", "container", "inspect", svcName)));
      if (check.ok()) {
        out.println("  [skip] Service '" + svcName + "' already running");
        skipped++;
        continue;
      }
      out.println("  [add] Starting service '" + svcName + "' (" + svc.image() + ")...");
      var cmd = PodmanCommands.buildRunCommand(svcName, svc);
      var result = shell.exec(ContainerExec.asDevUser(name, cmd), null, INSTALL_TIMEOUT);
      if (!result.ok()) {
        throw new IOException("Failed to start service '" + svcName + "': " + result.stderr());
      }
      added++;
    }
    return new ApplyResult(added, 0, skipped, List.of());
  }

  /** Clones new repos. Repos whose target directory already exists are skipped. */
  public ApplyResult applyRepos(
      String name,
      List<SailYaml.Repo> repos,
      String sshUser,
      Map<String, String> gitTokens,
      SailYaml.Git git)
      throws IOException, InterruptedException, TimeoutException {
    if (repos == null || repos.isEmpty()) {
      return ApplyResult.empty();
    }

    ensureCredentialStore(name, sshUser, gitTokens, repos);
    ensureSshKey(name, sshUser, git);

    var added = 0;
    var skipped = 0;
    for (var repo : repos) {
      var targetDir = "/home/" + sshUser + "/workspace/" + repo.path();
      var check = shell.exec(ContainerExec.asDevUser(name, List.of("test", "-d", targetDir)));
      if (check.ok()) {
        out.println("  [skip] Repo '" + repo.path() + "' already cloned");
        skipped++;
        continue;
      }
      out.println("  [add] Cloning '" + repo.url() + "' into " + repo.path() + "...");
      var cloneCmd = new ArrayList<>(List.of("git", "clone"));
      if (repo.branch() != null && !repo.branch().isBlank()) {
        cloneCmd.addAll(List.of("--branch", repo.branch()));
      }
      cloneCmd.add("--");
      cloneCmd.add(repo.url());
      cloneCmd.add(targetDir);
      var result = shell.exec(ContainerExec.asDevUser(name, cloneCmd), null, INSTALL_TIMEOUT);
      if (!result.ok()) {
        throw new IOException("Failed to clone " + repo.url() + ": " + result.stderr());
      }
      added++;
    }
    return new ApplyResult(added, 0, skipped, List.of());
  }

  /**
   * Installs Node.js in the container if not already present. Used when a Node-dependent agent is
   * added to an already-provisioned project that did not originally include Node.
   */
  public ApplyResult applyNodeRuntime(String name, String version)
      throws IOException, InterruptedException, TimeoutException {
    var check = shell.exec(rootExec(name, List.of("node", "--version")));
    if (check.ok() && check.stdout().strip().startsWith("v" + version)) {
      out.println("  [skip] Node.js " + version + " already installed");
      return new ApplyResult(0, 0, 1, List.of());
    }
    out.println("  [add] Installing Node.js " + version + "...");
    var majorVersion = version.contains(".") ? version.substring(0, version.indexOf('.')) : version;
    var prereqs =
        shell.exec(
            rootExec(
                name,
                List.of("apt-get", "install", "-y", "-qq", "ca-certificates", "curl", "gnupg")),
            null,
            INSTALL_TIMEOUT);
    if (!prereqs.ok()) {
      throw new IOException("Failed to install Node.js prerequisites: " + prereqs.stderr());
    }
    var setup =
        shell.exec(
            rootExec(
                name,
                List.of(
                    "bash",
                    "-c",
                    "curl -fsSL https://deb.nodesource.com/setup_" + majorVersion + ".x | bash -")),
            null,
            INSTALL_TIMEOUT);
    if (!setup.ok()) {
      throw new IOException(
          "Failed to configure NodeSource repository for Node "
              + majorVersion
              + ": "
              + setup.stderr());
    }
    var result =
        shell.exec(
            rootExec(name, List.of("apt-get", "install", "-y", "-qq", "nodejs")),
            null,
            INSTALL_TIMEOUT);
    if (!result.ok()) {
      throw new IOException("Failed to install Node.js " + version + ": " + result.stderr());
    }
    out.println("  [done] Node.js " + version + " installed");
    return new ApplyResult(1, 0, 0, List.of());
  }

  private static List<String> rootExec(String containerName, List<String> args) {
    var full = new ArrayList<>(List.of("incus", "exec", containerName, "--"));
    full.addAll(args);
    return List.copyOf(full);
  }

  /** Installs missing agent CLI tools. Tools already on PATH are skipped. */
  public ApplyResult applyAgentTools(String name, List<String> install, SailYaml.Runtimes runtimes)
      throws IOException, InterruptedException, TimeoutException {
    if (install == null || install.isEmpty()) {
      return ApplyResult.empty();
    }
    var added = 0;
    var skipped = 0;
    for (var agentName : install) {
      var tool = AgentCli.fromYamlName(agentName);
      var check =
          shell.exec(
              ContainerExec.asDevUser(name, List.of("bash", "-lc", "which " + tool.binaryName())));
      if (check.ok()) {
        out.println("  [skip] Agent '" + agentName + "' already installed");
        skipped++;
        continue;
      }
      if (tool.requiresNode()) {
        var nodeCheck = shell.exec(ContainerExec.asDevUser(name, List.of("which", "node")));
        if (!nodeCheck.ok()) {
          throw new IllegalStateException(
              "Bug: agent '"
                  + agentName
                  + "' requires Node.js but it is not installed."
                  + " The command layer should have resolved this before applying.");
        }
      }
      out.println("  [add] Installing agent '" + agentName + "'...");
      var result =
          shell.exec(
              ContainerExec.asDevUser(name, List.of("bash", "-c", tool.installCommand())),
              null,
              INSTALL_TIMEOUT);
      if (!result.ok()) {
        throw new IOException("Failed to install agent '" + agentName + "': " + result.stderr());
      }
      added++;
    }
    return new ApplyResult(added, 0, skipped, List.of());
  }

  /** Re-applies git config (always idempotent). */
  public ApplyResult applyGitConfig(String name, SailYaml.Git git, String sshUser)
      throws IOException, InterruptedException, TimeoutException {
    if (git == null) {
      return ApplyResult.empty();
    }
    out.println("  [apply] Git config (name=" + git.name() + ", email=" + git.email() + ")");
    var nameResult =
        shell.exec(
            ContainerExec.asDevUser(
                name, List.of("git", "config", "--global", "user.name", git.name())));
    if (!nameResult.ok()) {
      throw new IOException("Failed to set git user.name: " + nameResult.stderr());
    }
    var emailResult =
        shell.exec(
            ContainerExec.asDevUser(
                name, List.of("git", "config", "--global", "user.email", git.email())));
    if (!emailResult.ok()) {
      throw new IOException("Failed to set git user.email: " + emailResult.stderr());
    }
    return new ApplyResult(1, 0, 0, List.of());
  }

  /**
   * Regenerates agent context files (per-agent context + SECURITY.md + methodology/skill + audit
   * hooks) via {@link AgentContextInstaller}, so a delta apply keeps engineer-owned files exactly
   * as {@code project reconfigure} and {@code agent context regen} do.
   */
  public ApplyResult applyAgentContext(String name, SailYaml config) throws Exception {
    var result = AgentContextInstaller.install(shell, name, config);
    if (result.isEmpty()) {
      return ApplyResult.empty();
    }
    for (var path : result.pushed()) {
      out.println("  [apply] Agent context \u2192 " + path);
    }
    return new ApplyResult(result.pushed().size(), 0, 0, List.of());
  }

  /**
   * Stops and removes the named Podman services from the container.
   *
   * @param name the Incus container name
   * @param serviceNames the service names to remove
   * @return result with removed and skipped counts
   */
  public ApplyResult removeServices(String name, List<String> serviceNames)
      throws IOException, InterruptedException, TimeoutException {
    if (serviceNames == null || serviceNames.isEmpty()) {
      return ApplyResult.empty();
    }
    var removed = 0;
    var skipped = 0;
    for (var svcName : serviceNames) {
      var check =
          shell.exec(
              ContainerExec.asDevUser(name, List.of("podman", "container", "inspect", svcName)));
      if (!check.ok()) {
        out.println("  [skip] Service '" + svcName + "' not found in container");
        skipped++;
        continue;
      }
      out.println("  [remove] Stopping service '" + svcName + "'...");
      shell.exec(ContainerExec.asDevUser(name, List.of("podman", "stop", svcName)));
      shell.exec(ContainerExec.asDevUser(name, List.of("podman", "rm", "-f", svcName)));
      removed++;
    }
    return new ApplyResult(0, removed, skipped, List.of());
  }

  /**
   * Reconciles running services against the config: stops and removes any Podman containers that
   * are running in the Incus container but are not declared in the services map.
   *
   * @param name the Incus container name
   * @param services the desired services from sail.yaml (may be null)
   * @return result with removed count for orphaned services
   */
  public ApplyResult reconcileServices(String name, Map<String, SailYaml.Service> services)
      throws IOException, InterruptedException, TimeoutException {
    var running = queryRunningServiceNames(name);
    if (running.isEmpty()) {
      return ApplyResult.empty();
    }
    var desired = services != null ? services.keySet() : Set.<String>of();
    var orphans = running.stream().filter(n -> !desired.contains(n)).toList();
    if (orphans.isEmpty()) {
      return ApplyResult.empty();
    }
    var removed = 0;
    for (var orphan : orphans) {
      out.println("  [remove] Orphaned service '" + orphan + "' not in sail.yaml");
      shell.exec(ContainerExec.asDevUser(name, List.of("podman", "stop", orphan)));
      shell.exec(ContainerExec.asDevUser(name, List.of("podman", "rm", "-f", orphan)));
      removed++;
    }
    return new ApplyResult(0, removed, 0, List.of());
  }

  /**
   * Queries running Podman container names inside the Incus container. Returns an empty list if no
   * containers are running or the query fails.
   */
  @SuppressWarnings("unchecked")
  List<String> queryRunningServiceNames(String containerName)
      throws IOException, InterruptedException, TimeoutException {
    var cmd = ContainerExec.asDevUser(containerName, List.of("podman", "ps", "--format", "json"));
    var result = shell.exec(cmd);
    if (!result.ok() || result.stdout().isBlank()) {
      return List.of();
    }
    var containers = YamlUtil.parseList(result.stdout());
    return containers.stream()
        .map(c -> c.get("Names"))
        .filter(n -> n instanceof List<?>)
        .map(n -> (List<String>) n)
        .filter(n -> !n.isEmpty())
        .map(List::getFirst)
        .toList();
  }

  /**
   * Pushes workspace files from the local {@code files/} directory into the container at {@code
   * ~/workspace/}. Pushes each file individually to preserve correct path structure.
   */
  public ApplyResult applyWorkspaceFiles(String name, Path singYamlPath, String sshUser)
      throws IOException, InterruptedException, TimeoutException {
    var filesDir = WorkspaceFiles.resolveFilesDir(singYamlPath);
    if (filesDir == null) {
      return ApplyResult.empty();
    }
    var entries = WorkspaceFiles.listFiles(filesDir);
    if (entries.isEmpty()) {
      return ApplyResult.empty();
    }
    out.println("  [apply] Pushing " + entries.size() + " workspace file(s)...");
    var workspace = "/home/" + sshUser + "/workspace/";
    for (var entry : entries) {
      var cmd =
          new ArrayList<>(List.of("incus", "file", "push", "-p", "--uid", "1000", "--gid", "1000"));
      if (WorkspaceFiles.isExecutable(entry.relativePath())) {
        cmd.addAll(List.of("--mode", "0755"));
      }
      cmd.add(entry.hostPath().toString());
      cmd.add(name + workspace + entry.relativePath());
      var pushResult = shell.exec(cmd);
      if (!pushResult.ok()) {
        throw new IOException(
            "Failed to push workspace file '" + entry.relativePath() + "': " + pushResult.stderr());
      }
    }
    return new ApplyResult(entries.size(), 0, 0, List.of());
  }

  /**
   * Creates the specs scaffold directory inside the container when {@code agent.specs_dir} is
   * configured and the directory does not already exist.
   */
  public ApplyResult applySpecsScaffold(String name, SailYaml config)
      throws IOException, InterruptedException, TimeoutException {
    if (config.agent() == null || config.agent().specsDir() == null) {
      return ApplyResult.empty();
    }
    var sshUser = config.sshUser();
    var specsPath = "/home/" + sshUser + "/workspace/" + config.agent().specsDir();
    var check = shell.exec(ContainerExec.asDevUser(name, List.of("test", "-d", specsPath)));
    if (check.ok()) {
      out.println("  [skip] Specs directory '" + config.agent().specsDir() + "' already exists");
      return new ApplyResult(0, 0, 1, List.of());
    }
    out.println("  [add] Creating specs scaffold at " + config.agent().specsDir() + "/...");
    shell.exec(ContainerExec.asDevUser(name, List.of("mkdir", "-p", specsPath)));
    return new ApplyResult(1, 0, 0, List.of());
  }

  /**
   * Upgrades the container cleanup cron from the legacy {@code podman system prune} to the robust
   * {@code cleanup-containers.sh} script that identifies stray containers by restart policy. Also
   * installs the manual agent cleanup helper. Idempotent — skips if already upgraded.
   */
  public ApplyResult applyCleanupCron(String name, String sshUser)
      throws IOException, InterruptedException, TimeoutException {
    var check = shell.exec(ContainerExec.asDevUser(name, List.of("crontab", "-l")));
    var existingCron = check.ok() ? check.stdout() : "";

    var scriptsExist =
        shell
            .exec(
                ContainerExec.asDevUser(
                    name, List.of("test", "-f", CleanupScripts.AGENT_CLEANUP_PATH)))
            .ok();

    if (existingCron.contains(CleanupScripts.CONTAINER_CLEANUP_PATH) && scriptsExist) {
      out.println("  [skip] Container cleanup cron already upgraded");
      return new ApplyResult(0, 0, 1, List.of());
    }

    out.println("  [apply] Upgrading container cleanup cron...");

    shell.exec(ContainerExec.asDevUser(name, List.of("mkdir", "-p", CleanupScripts.SAIL_DIR)));
    pushFile(
        name,
        CleanupScripts.CONTAINER_CLEANUP_PATH,
        CleanupScripts.containerCleanupScript(),
        "0755");
    pushFile(name, CleanupScripts.AGENT_CLEANUP_PATH, CleanupScripts.agentCleanupScript(), "0755");

    var newCron = CleanupScripts.buildUpgradedCrontab(existingCron);
    var mktemp =
        shell.exec(List.of("incus", "exec", name, "--", "mktemp", "/tmp/sail-crontab.XXXXXX"));
    if (!mktemp.ok()) {
      throw new IOException("Failed to create temp file for crontab: " + mktemp.stderr());
    }
    var tmpPath = mktemp.stdout().strip();
    pushFile(name, tmpPath, newCron);
    var cronResult =
        shell.exec(List.of("incus", "exec", name, "--", "crontab", "-u", sshUser, tmpPath));
    shell.exec(List.of("incus", "exec", name, "--", "rm", "-f", tmpPath));
    if (!cronResult.ok()) {
      throw new IOException(
          "Failed to install crontab for user '" + sshUser + "': " + cronResult.stderr());
    }

    out.println("  [apply] Agent cleanup helper \u2192 " + CleanupScripts.AGENT_CLEANUP_PATH);
    return new ApplyResult(1, 0, 0, List.of());
  }

  /** Checks for config sections that cannot be changed post-creation and returns warnings. */
  public List<String> checkUnsupportedChanges(SailYaml config, ResourceLimits liveLimits) {
    var warnings = new ArrayList<String>();
    if (config.resources() != null
        && liveLimits != null
        && (!String.valueOf(config.resources().cpu()).equals(liveLimits.cpu())
            || !normalizedSize(config.resources().memory())
                .equals(normalizedSize(liveLimits.memory())))) {
      warnings.add(
          "CPU and memory changes are not applied by 'sail project apply'."
              + " Use 'sail project resources set "
              + config.name()
              + " ...' instead.");
    }
    return warnings;
  }

  private static String normalizedSize(String value) {
    return value == null ? "" : value.strip().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
  }

  /**
   * Refreshes the git credential store inside the container so that {@code git clone} can
   * authenticate without embedding the token in the URL (which would leak to /proc/cmdline). Writes
   * one credential entry per unique HTTPS host found in the repo URLs.
   */
  private void ensureCredentialStore(
      String name, String sshUser, Map<String, String> gitTokens, List<SailYaml.Repo> repos)
      throws IOException, InterruptedException, TimeoutException {
    var credContent = GitCredentials.buildCredentialStore(gitTokens, repos);
    if (credContent.isEmpty()) {
      return;
    }
    var credPath = "/home/" + sshUser + "/.git-credentials";
    pushFile(name, credPath, credContent, "0600");
    var helperResult =
        shell.exec(
            ContainerExec.asDevUser(
                name, List.of("git", "config", "--global", "credential.helper", "store")));
    if (!helperResult.ok()) {
      throw new IOException("Failed to configure git credential helper: " + helperResult.stderr());
    }
  }

  /**
   * Pushes the SSH private key (and optional public key) into the container so that {@code git
   * clone} can authenticate via SSH. Only acts when {@code git.auth} is {@code "ssh"} and a key
   * path is configured.
   */
  private void ensureSshKey(String name, String sshUser, SailYaml.Git git)
      throws IOException, InterruptedException, TimeoutException {
    if (git == null || !"ssh".equals(git.auth()) || git.sshKey() == null) {
      return;
    }
    var sshKeyHostPath = GitCredentials.resolveHostPath(git.sshKey());
    if (!Files.exists(sshKeyHostPath)) {
      throw new IOException(
          "SSH key not found: "
              + sshKeyHostPath
              + "\n  Check the 'git.ssh_key' path in sail.yaml points to an existing private key.");
    }
    var sshDir = "/home/" + sshUser + "/.ssh";
    shell.exec(ContainerExec.asDevUser(name, List.of("mkdir", "-p", sshDir)));
    shell.exec(ContainerExec.asDevUser(name, List.of("chmod", "700", sshDir)));

    var keyContent = Files.readString(sshKeyHostPath);
    pushFile(name, sshDir + "/id_ed25519", keyContent, "0600");

    var pubKeyPath = Path.of(sshKeyHostPath + ".pub");
    if (Files.exists(pubKeyPath)) {
      var pubContent = Files.readString(pubKeyPath);
      pushFile(name, sshDir + "/id_ed25519.pub", pubContent);
    }
  }

  /** Pushes content to a file inside the container via temp file + incus file push + chown. */
  private void pushFile(String containerName, String remotePath, String content)
      throws IOException, InterruptedException, TimeoutException {
    pushFile(containerName, remotePath, content, null);
  }

  /** Pushes content with optional file mode (e.g. "0600" for credentials). */
  private void pushFile(String containerName, String remotePath, String content, String mode)
      throws IOException, InterruptedException, TimeoutException {
    var parentDir = remotePath.substring(0, remotePath.lastIndexOf('/'));
    shell.exec(ContainerExec.asDevUser(containerName, List.of("mkdir", "-p", parentDir)));
    var flags =
        new ArrayList<>(List.of("--uid", ContainerExec.DEV_UID, "--gid", ContainerExec.DEV_GID));
    if (mode != null) {
      flags.addAll(List.of("--mode", mode));
    }
    ContainerFilePush.push(shell, containerName, remotePath, content, flags);
  }
}
