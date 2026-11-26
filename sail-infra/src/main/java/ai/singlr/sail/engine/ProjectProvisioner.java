/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.api.ProjectSkills;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.webauthn.Hashes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
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

  /** How long an apt or dpkg call waits for dpkg's lock another holds. */
  static final Duration APT_LOCK_WAIT = Duration.ofMinutes(5);

  /** Beside a repo's directory, where its clone is made before it is renamed into place. */
  static final String PARTIAL = ".sail-partial";

  private static final Pattern VERSION_PATTERN = Pattern.compile("[0-9][0-9.]*");
  private static final String MAVEN_PRIMARY_BASE_URL = "https://dlcdn.apache.org/maven/maven-3/";
  private static final String MAVEN_ARCHIVE_BASE_URL =
      "https://archive.apache.org/dist/maven/maven-3/";

  static final String INPUTS_KEY = "user.sail.inputs";

  /**
   * An {@code authorized_keys} options field before a key, as {@link ContainerAuthorizedKeys} reads
   * it.
   */
  private static final String RESTART_PENDING_KEY = "user.sail.restart_pending";

  static final List<String> BASELINE_PACKAGES =
      List.of("curl", "wget", "git", "sudo", "openssh-server", "gh", "ripgrep");

  /**
   * The instance a run provisions, and how it is launched when it does not exist yet: on {@code
   * pool} when one is named, carrying {@code keys} from birth. The project's own container is
   * launched on the base profile's pool carrying its project's key ({@link #of}); its template on
   * the box's ZFS pool with the key that says whose template it is.
   */
  public record Instance(String name, Optional<String> pool, Map<String, String> keys) {
    public Instance {
      NameValidator.requireValidProjectName(name);
      Objects.requireNonNull(pool, "pool");
      keys = Map.copyOf(keys);
    }

    /**
     * The project's own container, named after it, on the base profile's pool, carrying {@link
     * BoxInstances#PROJECT_KEY} of its name: what tells it positively from any other instance.
     */
    public static Instance of(ProjectDefinition config) {
      return new Instance(
          config.name(), Optional.empty(), Map.of(BoxInstances.PROJECT_KEY, config.name()));
    }
  }

  private final ShellExec shell;
  private final ContainerConfig containerConfig;
  private final ProvisionTracker<ProjectPhase> tracker;
  private final ProvisionListener listener;
  private final ProjectSkills skills;
  private final Optional<SshPublicKey> workstationKey;
  private final OperatorCommand operator;

  private ProjectPhase currentPhase;

  public ProjectProvisioner(
      ShellExec shell,
      ProvisionTracker<ProjectPhase> tracker,
      ProvisionListener listener,
      Optional<SshPublicKey> workstationKey,
      OperatorCommand operator) {
    this(shell, tracker, listener, ProjectSkills.none(), workstationKey, operator);
  }

  /**
   * @param skills where the project's skills are read from, installed for every harness the project
   *     installs; {@link ProjectSkills#none()} installs sail's own alone
   * @param workstationKey the box's registered workstation key, which every container trusts
   * @param operator how the person reading a warning's retry command types sail
   */
  public ProjectProvisioner(
      ShellExec shell,
      ProvisionTracker<ProjectPhase> tracker,
      ProvisionListener listener,
      ProjectSkills skills,
      Optional<SshPublicKey> workstationKey,
      OperatorCommand operator) {
    this.shell = shell;
    this.containerConfig = new ContainerConfig(shell, SailPaths.locksDir());
    this.tracker = tracker;
    this.listener = Objects.requireNonNullElse(listener, ProvisionListener.NOOP);
    this.skills = Objects.requireNonNull(skills, "skills");
    this.workstationKey = Objects.requireNonNull(workstationKey, "workstationKey");
    this.operator = Objects.requireNonNull(operator, "operator");
  }

  /**
   * Runs all provisioning steps with resume support. On failure, the current phase is recorded in
   * the tracker and the exception is re-thrown. A tracker its owner already loaded for this
   * definition is used as it stands, so a reset the owner made in a dry run is not read back from
   * the file it left in place. Once the container exists its record of the last finished run is
   * cleared, and on success it records the inputs it was provisioned from and the progress file is
   * removed.
   */
  public void provision(
      ProjectDefinition config,
      HostYaml host,
      CheckedTokens gitTokens,
      LocalIdentity.GitIdentity git,
      Path filesDir)
      throws Exception {
    provision(Instance.of(config), config, host, gitTokens, git, filesDir);
  }

  /**
   * Provisions {@code config} into the instance {@code into} names, launched on its pool when it
   * names one: what {@link #provision(ProjectDefinition, HostYaml, CheckedTokens,
   * LocalIdentity.GitIdentity, Path)} does for the project's own container, for another instance,
   * such as its template. The definition, its digest and the project's skills are the project's
   * whatever instance takes them. With no {@code git} identity none is set, as a template, which is
   * no one's, is built.
   */
  public void provision(
      Instance into,
      ProjectDefinition config,
      HostYaml host,
      CheckedTokens gitTokens,
      LocalIdentity.GitIdentity git,
      Path filesDir)
      throws Exception {
    var inputs = inputs(config, workstationKey);
    if (!inputs.equals(tracker.currentState().inputs())) {
      tracker.load(inputs);
    }
    var name = into.name();
    var resumed = tracker.currentState().completedPhase() != null && !tracker.isComplete();
    try {
      launchContainer(config, into, host);
      if (!tracker.isComplete()) {
        clearProvisionedInputs(name);
      }
      setDiskQuota(config, name, host);
      setResourceLimits(config, name);
      waitForNetwork(name);
      readyPackages(name, resumed);
      installPackages(config, name);
      configureSsh(config, name, host);
      installSailMachinery(config, name);
      installPodman(name);
      configureTestcontainers(config, name);
      installJdk(config, name);
      installNode(config, name);
      configureGit(name, git);
      installMaven(config, name);
      cloneRepos(config, name, gitTokens);
      pushWorkspaceFiles(name, filesDir);
      provisionServices(config, name);
      configurePruneCron(config, name);
      installAgentTools(config, name);
      installSkills(config, name);
      writeProjectState(config, name);
      tracker.cleanup();
    } catch (Exception e) {
      listener.onStepFailed();
      if (currentPhase != null) {
        tracker.recordFailure(currentPhase, e.getMessage());
      }
      throw e;
    }
  }

  /**
   * Clones {@code repo} to {@code target} whole or not at all: into a directory beside it, a
   * leftover of a clone cut short removed first, then renamed into place, so a run cut mid-clone
   * resumes to a whole repo, never to the half a killed clone left at {@code target}.
   */
  private void cloneWhole(
      ProjectDefinition config,
      String name,
      ProjectDefinition.Repo repo,
      String target,
      Map<String, String> tokens)
      throws Exception {
    var partial = target + PARTIAL;
    var cleared = execAsDevUser(name, List.of("rm", "-rf", "--", partial));
    if (!cleared.ok()) {
      throw new IOException(
          "Could not remove a clone cut short at " + partial + ": " + cleared.stderr());
    }
    var cmd = new ArrayList<>(List.of("git", "clone"));
    if (repo.branch() != null) {
      cmd.addAll(List.of("--branch", repo.branch()));
    }
    cmd.addAll(List.of("--", repo.url(), partial));
    var result = execAsDevUser(name, cmd, INSTALL_TIMEOUT);
    if (!result.ok()) {
      throw cloneFailed(config, repo, tokens, readable(name, repo), result.stderr());
    }
    var placed = execAsDevUser(name, List.of("mv", "-T", "--", partial, target));
    if (!placed.ok()) {
      throw new IOException(
          "Could not move the clone of " + repo.url() + " into place: " + placed.stderr());
    }
  }

  /**
   * What a failed clone of {@code repo} says of the repo, asked again by exit status alone, never
   * by git's words: {@code git ls-remote --exit-code} through the same credentials answers 0 when
   * the repo (and its branch) can be read, so the clone failed for a cause its status does not say
   * (a dropped connection, a timeout, the disk), 2 when the branch is not there, anything else when
   * it cannot be read; then the host's own answer to a plain request without credentials (401, see
   * {@link ShellExec#asksForCredentials}) tells a refusal from a host that cannot be reached.
   */
  private CloneReach readable(String name, ProjectDefinition.Repo repo) throws Exception {
    var ask = new ArrayList<>(List.of("git", "ls-remote", "--exit-code", "--", repo.url()));
    if (repo.branch() != null) {
      ask.add("refs/heads/" + repo.branch());
    }
    var asked = execAsDevUser(name, ask, NETWORK_TIMEOUT);
    if (asked.ok()) {
      return CloneReach.READ;
    }
    if (asked.exitCode() == 2) {
      return CloneReach.NO_BRANCH;
    }
    return shell.asksForCredentials(repo.url()) ? CloneReach.REFUSED : CloneReach.UNREACHED;
  }

  /** How far a failed clone's repo could be read, by {@link #readable}. */
  enum CloneReach {
    READ,
    NO_BRANCH,
    REFUSED,
    UNREACHED
  }

  /**
   * A failed clone of {@code repo}, in a person's words by how far its repo could be read ({@code
   * reach}), the token sentence for a refusal alone; whether a token was given for its host is
   * {@code tokens}, keyed as repo URLs spell hosts. What git said stays the cause, for the log: a
   * person reads a build's record and the API, never a tool's output.
   */
  static IllegalStateException cloneFailed(
      ProjectDefinition config,
      ProjectDefinition.Repo repo,
      Map<String, String> tokens,
      CloneReach reach,
      String said) {
    var cause = new IOException("Failed to clone " + repo.url() + ": " + said);
    var named = config.name() + "'s repo " + repo.path();
    var host =
        GitCredentials.extractHttpsHosts(List.of(repo)).stream().findFirst().orElse(repo.url());
    return switch (reach) {
      case READ ->
          Remedied.state(
              named + " could be read, but its clone did not finish.",
              Remedy.of("Try again; if it does not finish again, an admin looks at the runner."),
              cause);
      case NO_BRANCH ->
          Remedied.state(
              host + " has no branch " + repo.branch() + " in " + named + ".",
              Remedy.of("Name a branch the repo has in the project's definition."),
              cause);
      case UNREACHED ->
          Remedied.state(
              host + " could not be reached to clone " + named + ".",
              Remedy.of("Try again once the runner reaches " + host + "."),
              cause);
      case REFUSED ->
          Remedied.state(
              host
                  + " did not give "
                  + named
                  + (tokens.containsKey(host)
                      ? " to the token stored for it: the token may not read the repo, or the"
                          + " repo may not exist."
                      : ": the repo may ask for a token, or may not exist."),
              Remedy.of(
                  "Store a token for " + host + " that reads it, then try again: ",
                  Remedy.sail("git login", host),
                  "."),
              cause);
    };
  }

  /**
   * The digest of what a run provisions from, the definition and the box's workstation key: the
   * {@link ProvisionTracker} keeps progress only for the inputs it was recorded for, so an edited
   * definition, or a workstation key registered, replaced or revoked, is provisioned again. With no
   * key the digest is the definition's alone, as it was before the key was an input.
   */
  public static String inputs(ProjectDefinition config, Optional<SshPublicKey> workstationKey) {
    var provisioned =
        ProjectDefinitionJson.write(config)
            + workstationKey.map(key -> "workstation_key: " + key.fingerprint() + "\n").orElse("");
    return HexFormat.of().formatHex(Hashes.sha256(provisioned.getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * Whether {@code recorded}, what a container records it was provisioned from ({@link
   * #provisionedInputs}), is what a run from {@code config} on a box holding {@code workstationKey}
   * provisions from: when it is, the container needs no run.
   */
  public static boolean provisionedFrom(
      Optional<String> recorded, ProjectDefinition config, Optional<SshPublicKey> workstationKey) {
    return recorded.equals(Optional.of(inputs(config, workstationKey)));
  }

  /**
   * The inputs the container {@code name} was last provisioned from, read from the Incus instance
   * itself so that every identity on the host, root or operator, reads the same record and a
   * deleted container takes its record with it. Empty when the container has none: it was never
   * provisioned to the end by this release, or it does not exist.
   */
  public static Optional<String> provisionedInputs(ShellExec shell, String name)
      throws IOException, InterruptedException, TimeoutException {
    var result = shell.exec(List.of("incus", "config", "get", name, INPUTS_KEY));
    if (!result.ok() || result.stdout().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(result.stdout().strip());
  }

  /**
   * Voids the container's record of a finished run before this run changes it, so a run that fails
   * part way leaves no record and the next apply provisions again, whatever definition it brings.
   */
  private void clearProvisionedInputs(String name) throws Exception {
    var cleared = containerConfig.set(name, List.of(INPUTS_KEY + "="));
    if (!cleared.ok()) {
      throw new IOException(
          "Failed to clear the record of the last provisioned definition: " + cleared.stderr());
    }
  }

  private void launchContainer(ProjectDefinition config, Instance into, HostYaml host)
      throws Exception {
    currentPhase = ProjectPhase.CONTAINER_LAUNCHED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("container already running");
      return;
    }
    var name = into.name();
    step("Launching container " + name + "...");

    var check = shell.exec(List.of("incus", "info", name));
    if (check.ok()) {
      tracker.advance(currentPhase);
      stepDone("Container " + name + " (already exists)");
      return;
    }

    var named = image(config, host);
    var image = new BaseImage(shell, named);
    image.requireContainer(healing(named, host));
    var launch =
        new ArrayList<>(
            List.of("incus", "launch", image.alias(), name, "--profile", host.baseProfile()));
    into.pool().ifPresent(pool -> launch.addAll(List.of("--storage", pool)));
    new TreeMap<>(into.keys())
        .forEach((key, value) -> launch.addAll(List.of("-c", key + "=" + value)));
    var result = shell.exec(launch, null, LAUNCH_TIMEOUT);
    if (!result.ok()) {
      throw new IOException("Failed to launch container: " + result.stderr());
    }
    tracker.advance(currentPhase);
    stepDone("Container " + name);
  }

  /**
   * Installs the in-container sail machinery — the event-socket bind mount, the {@code
   * sail-event.sh} hook helper, the {@code spec} CLI, and the Claude Code / Codex hook settings —
   * once the {@code dev} user and its home exist (step 6 creates {@code /home/dev}, which these
   * files live under). Reuses {@link ContainerSailSetup}, the same seam {@code project apply} uses,
   * so provisioning and recovery install identically. Failure is non-fatal: it is logged with the
   * one command that fixes it, and the agent hooks fall back to file-only audit until then.
   */
  private void installSailMachinery(ProjectDefinition config, String name) {
    try {
      ContainerSailSetup.ensureInstalled(shell, name);
    } catch (Exception e) {
      System.err.println(
          "  [provision] Warning: failed to install the sail machinery in "
              + name
              + ": "
              + e.getMessage()
              + ". Run '"
              + operator.sail("project apply " + config.name())
              + "' to retry.");
    }
  }

  private void setDiskQuota(ProjectDefinition config, String name, HostYaml host) throws Exception {
    currentPhase = ProjectPhase.DISK_QUOTA_SET;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("disk quota already set");
      return;
    }
    step("Setting disk quota...");

    var disk = config.resources().disk();
    new ContainerManager(shell).setDiskQuota(name, disk);
    tracker.advance(currentPhase);
    if (host.isDir()) {
      stepDone("Disk quota " + disk + " (advisory)");
    } else {
      stepDone("Disk quota " + disk);
    }
  }

  private void setResourceLimits(ProjectDefinition config, String name) throws Exception {
    currentPhase = ProjectPhase.RESOURCE_LIMITS_SET;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("resource limits already set");
      return;
    }
    step("Setting resource limits...");

    var cpu = config.resources().cpu();
    var memory = config.resources().memory();
    var limits = new LinkedHashMap<String, String>();
    limits.put("limits.cpu", String.valueOf(cpu));
    limits.put("limits.memory", memory);
    limits.put("security.nesting", "true");
    limits.put("raw.lxc", "lxc.apparmor.profile=unconfined");
    var detail = "Resources " + cpu + " CPU, " + memory + " RAM";
    if (!isRestartPending(name) && isConfigured(name, limits)) {
      tracker.advance(currentPhase);
      stepDone(detail + " (already set)");
      return;
    }
    var assignments = new ArrayList<String>();
    limits.forEach((key, value) -> assignments.add(key + "=" + value));
    assignments.add(RESTART_PENDING_KEY + "=true");
    var result = containerConfig.set(name, assignments);
    if (!result.ok()) {
      throw new IOException("Failed to set resource limits: " + result.stderr());
    }
    var restart = shell.exec(List.of("incus", "restart", name));
    if (!restart.ok()) {
      throw new IOException("Failed to restart container after config: " + restart.stderr());
    }
    var cleared = containerConfig.set(name, List.of(RESTART_PENDING_KEY + "="));
    if (!cleared.ok()) {
      throw new IOException("Failed to clear the pending restart: " + cleared.stderr());
    }
    tracker.advance(currentPhase);
    stepDone(detail);
  }

  /**
   * Whether settings were stored by a run that never completed the restart they need: {@code
   * raw.lxc} takes effect only at start, so stored settings alone do not prove the step's effect.
   */
  private boolean isRestartPending(String name)
      throws IOException, InterruptedException, TimeoutException {
    var pending = shell.exec(List.of("incus", "config", "get", name, RESTART_PENDING_KEY));
    return !pending.ok() || !pending.stdout().isBlank();
  }

  /**
   * Whether the container already holds every one of {@code settings}, so that a container
   * provisioned again for an edited definition is not restarted under the agents running in it.
   */
  private boolean isConfigured(String name, Map<String, String> settings)
      throws IOException, InterruptedException, TimeoutException {
    for (var setting : settings.entrySet()) {
      var current = shell.exec(List.of("incus", "config", "get", name, setting.getKey()));
      if (!current.ok() || !current.stdout().strip().equals(setting.getValue())) {
        return false;
      }
    }
    return true;
  }

  /**
   * Readies apt for the steps after the network wait, whenever one runs: every apt call waits up to
   * {@link #APT_LOCK_WAIT} for dpkg's lock (a container just started may have its own apt timers
   * holding it), and a run that resumes ({@code resumed}) first finishes what dpkg was left doing,
   * since a run cut while apt installed leaves dpkg interrupted and every later apt call refused.
   * {@code dpkg --configure -a} has no lock wait of its own: exit status 2, the lock held among
   * them, is asked again each second for as long.
   */
  private void readyPackages(String name, boolean resumed) throws Exception {
    if (tracker.isComplete()) {
      return;
    }
    pushFileToContainer(
        name,
        "/etc/apt/apt.conf.d/90sail-lock-wait",
        "DPkg::Lock::Timeout \"" + APT_LOCK_WAIT.toSeconds() + "\";\n");
    if (!resumed) {
      return;
    }
    var finished =
        execInContainer(
            name,
            List.of(
                "bash",
                "-c",
                ("for _ in $(seq 1 %d); do DEBIAN_FRONTEND=noninteractive dpkg --configure -a"
                        + " && exit 0; rc=$?; [ \"$rc\" -eq 2 ] || exit \"$rc\"; sleep 1; done;"
                        + " exit 2")
                    .formatted(APT_LOCK_WAIT.toSeconds())),
            INSTALL_TIMEOUT);
    if (!finished.ok()) {
      throw new IOException(
          "Could not finish the packages an earlier run left half installed: " + finished.stderr());
    }
  }

  /**
   * Waits for the container's address before any later step, whenever one runs: a run that resumes
   * may meet the container started again since the run before (a restart, a build's failure that
   * stopped it), whose network is not up yet, so the wait is never taken as done.
   */
  private void waitForNetwork(String name) throws Exception {
    currentPhase = ProjectPhase.NETWORK_READY;
    if (tracker.isComplete()) {
      stepSkipped("network already ready");
      return;
    }
    step("Waiting for container network...");

    var script =
        """
            for i in $(seq 1 30); do \
              ip -4 addr show eth0 | grep -q inet && exit 0; \
              sleep 1; \
            done; exit 1""";
    var result = execInContainer(name, List.of("bash", "-c", script), NETWORK_TIMEOUT);
    if (!result.ok()) {
      throw new IOException("Container network did not come up within 30s");
    }
    var ipResult =
        execInContainer(
            name, List.of("bash", "-c", "ip -4 addr show eth0 | grep -oP '(?<=inet )\\S+'"));
    var ipDetail = ipResult.ok() ? ipResult.stdout().strip() : "";
    if (!tracker.isCompleted(currentPhase)) {
      tracker.advance(currentPhase);
    }
    stepDone("Network connected" + (ipDetail.isEmpty() ? "" : " (" + ipDetail + ")"));
  }

  private void installPackages(ProjectDefinition config, String name) throws Exception {
    currentPhase = ProjectPhase.PACKAGES_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("packages already installed");
      return;
    }
    step("Installing packages...");

    var updateResult = execInContainer(name, List.of("apt-get", "update", "-qq"), INSTALL_TIMEOUT);
    if (!updateResult.ok()) {
      throw new IOException("Failed to update package lists: " + updateResult.stderr());
    }

    var packages = new ArrayList<>(BASELINE_PACKAGES);
    if (config.packages() != null) {
      packages.addAll(config.packages());
    }

    var cmd = new ArrayList<>(List.of("apt-get", "install", "-y", "-qq"));
    cmd.addAll(packages);
    var result = execInContainer(name, cmd, INSTALL_TIMEOUT);
    if (!result.ok()) {
      throw new IOException("Failed to install packages: " + result.stderr());
    }
    tracker.advance(currentPhase);
    stepDone("Installed " + packages.size() + " packages");
  }

  private void configureSsh(ProjectDefinition config, String name, HostYaml host) throws Exception {
    currentPhase = ProjectPhase.SSH_CONFIGURED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("SSH already configured");
      return;
    }
    step("Configuring SSH access...");

    var user = ProjectDefinition.USER;

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
    requireDevUid(name, user, image(config, host));

    var sudoResult = execInContainer(name, List.of("usermod", "-aG", "sudo", user));
    if (!sudoResult.ok()) {
      throw new IOException(
          "Failed to add user '" + user + "' to sudo group: " + sudoResult.stderr());
    }
    pushFileToContainer(name, "/etc/sudoers.d/" + user, user + " ALL=(ALL) NOPASSWD:ALL\n", "0440");

    writeAuthorizedKeys(name, user, trustedKeys(workstationKey));

    var sshResult = execInContainer(name, List.of("systemctl", "enable", "--now", "ssh"));
    if (!sshResult.ok()) {
      throw new IOException(
          "Failed to start SSH service: "
              + sshResult.stderr()
              + "\n  Remote access to the container will not work.");
    }

    tracker.advance(currentPhase);
    stepDone("Created user " + user + " (uid 1000)");
  }

  /** The keys a container trusts: the box's workstation key, its owner's laptop, alone. */
  private static List<String> trustedKeys(Optional<SshPublicKey> workstationKey) {
    return workstationKey.map(key -> List.of(key.line())).orElse(List.of());
  }

  /**
   * What tells one {@code authorized_keys} line from another: its key's fingerprint, whatever its
   * comment or options, or the line itself when it holds no key sail can read.
   */
  /**
   * Writes exactly the keys the container trusts, so a key no longer registered, or a workstation
   * key revoked, loses its access on the next provisioning run.
   */
  private void writeAuthorizedKeys(String name, String user, List<String> keys)
      throws IOException, InterruptedException, TimeoutException {
    var sshDir = "/home/" + user + "/.ssh";
    execInContainer(name, List.of("mkdir", "-p", sshDir));
    pushFileToContainer(
        name,
        sshDir + "/authorized_keys",
        keys.isEmpty() ? "" : String.join("\n", keys) + "\n",
        "0600");
    execInContainer(name, List.of("chmod", "700", sshDir));
    execInContainer(name, List.of("chown", "-R", user + ":" + user, sshDir));
  }

  /** The uid of the SSH user step 6 makes, the one account sail works in. */
  static final String SSH_UID = "1000";

  /**
   * Every agent, shell and hook runs as uid 1000 with {@code HOME=/home/dev} ({@link
   * ContainerExec}), so a {@code dev} at any other uid is a container nothing of sail's can work
   * in.
   */
  private void requireDevUid(String name, String user, String image)
      throws IOException, InterruptedException, TimeoutException {
    var uid = execInContainer(name, List.of("id", "-u", user));
    if (shell.isDryRun()) {
      return;
    }
    if (!uid.ok()) {
      throw new IOException(
          "Failed to look up user '" + user + "' in " + name + ": " + uid.stderr());
    }
    var actual = uid.stdout().strip();
    if (!SSH_UID.equals(actual)) {
      throw new IOException(
          "User '"
              + user
              + "' in "
              + name
              + " has uid "
              + actual
              + ", but sail runs every agent, shell and hook as uid 1000. The image '"
              + image
              + "' has another user at uid 1000; use an image without one (ubuntu/24.04 has only"
              + " 'ubuntu', which sail removes), then run '"
              + operator.sail("project destroy " + name)
              + "' and '"
              + operator.sail("project apply " + name)
              + "'.");
    }
  }

  private static String image(ProjectDefinition config, HostYaml host) {
    return Objects.requireNonNullElse(config.image(), host.image());
  }

  /**
   * What gives a launch from {@code image} a container image: host init caches the box's own again
   * (a re-run keeps the pool); a project's own image is the project's to change.
   */
  static Remedy healing(String image, HostYaml host) {
    return HostYaml.DEFAULT_IMAGE.equals(image)
        ? Remedy.of(
            "Cache the container image in its place: ",
            Remedy.sail(
                "host init", "--storage", host.storageBackend(), "--pool", host.pool(), "--yes"))
        : Remedy.of("Give the project a container image.");
  }

  private void installPodman(String name) throws Exception {
    currentPhase = ProjectPhase.PODMAN_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("Podman already installed");
      return;
    }
    step("Installing Podman...");

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

    var sshUser = ProjectDefinition.USER;
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
    stepDone("Podman (rootless) + socket + auto-restart");
  }

  /**
   * Points Testcontainers at the rootless Podman socket and disables Ryuk. Ryuk stays disabled by
   * measurement, not assumption: even a privileged Ryuk on this rootless Podman reaps only if its
   * request timeout is raised well past the Testcontainers default — the rootless force-remove
   * routinely exceeds 10s, so the default Ryuk times out and leaks anyway. The hourly→15-minute
   * {@code cleanup-containers.sh} cron (a plain {@code podman rm -f}, no client-side timeout to
   * lose) is the reliable sweep instead.
   */
  private void configureTestcontainers(ProjectDefinition config, String name) throws Exception {
    currentPhase = ProjectPhase.TESTCONTAINERS_CONFIGURED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("Testcontainers already configured");
      return;
    }
    step("Configuring Testcontainers environment...");

    var profilePath = "/etc/profile.d/testcontainers.sh";

    var check = execInContainer(name, List.of("test", "-f", profilePath));
    if (check.ok()) {
      tracker.advance(currentPhase);
      stepDone("already configured");
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
    stepDone("Testcontainers configured");
  }

  private void installJdk(ProjectDefinition config, String name) throws Exception {
    currentPhase = ProjectPhase.JDK_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("JDK already installed");
      return;
    }

    if (config.runtimes() == null || config.runtimes().jdk() == 0) {
      tracker.advance(currentPhase);
      stepSkipped("no JDK requested");
      return;
    }

    var version = config.runtimes().jdk();
    step("Installing JDK " + version + "...");

    var pkg = "temurin-" + version + "-jdk";

    var check = execInContainer(name, List.of("dpkg", "-s", pkg));
    if (check.ok()) {
      tracker.advance(currentPhase);
      stepDone("JDK " + version + " (Temurin, already installed)");
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
                    + " | gpg --batch --yes --dearmor -o /usr/share/keyrings/adoptium.gpg"),
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
    stepDone("JDK " + version + " (Temurin)");
  }

  private void installNode(ProjectDefinition config, String name) throws Exception {
    currentPhase = ProjectPhase.NODE_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("Node already installed");
      return;
    }

    if (config.runtimes() == null || config.runtimes().node() == null) {
      tracker.advance(currentPhase);
      stepSkipped("no Node requested");
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
    step("Installing Node.js " + version + "...");

    var check = execInContainer(name, List.of("node", "--version"));
    if (check.ok()) {
      var installed = check.stdout().strip();
      if (installed.startsWith("v" + version)) {
        tracker.advance(currentPhase);
        stepDone("Node.js " + installed.substring(1) + " (already installed)");
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
    stepDone("Node.js " + version);
  }

  private void configureGit(String name, LocalIdentity.GitIdentity git) throws Exception {
    currentPhase = ProjectPhase.GIT_CONFIGURED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("git already configured");
      return;
    }

    step("Configuring git identity...");

    if (git != null) {
      var nameResult =
          execAsDevUser(name, List.of("git", "config", "--global", "user.name", git.name()));
      if (!nameResult.ok()) {
        throw new IOException("Failed to set git user.name: " + nameResult.stderr());
      }
      var emailResult =
          execAsDevUser(name, List.of("git", "config", "--global", "user.email", git.email()));
      if (!emailResult.ok()) {
        throw new IOException("Failed to set git user.email: " + emailResult.stderr());
      }
    }

    tracker.advance(currentPhase);
    stepDone(git == null ? "No git identity: the instance is no one's" : "Git identity configured");
  }

  private void installMaven(ProjectDefinition config, String name) throws Exception {
    currentPhase = ProjectPhase.MAVEN_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("Maven already installed");
      return;
    }

    if (config.runtimes() == null || config.runtimes().maven() == null) {
      tracker.advance(currentPhase);
      stepSkipped("no Maven requested");
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
    step("Installing Maven " + version + "...");

    var check = execInContainer(name, List.of("/opt/maven/bin/mvn", "--version"));
    if (check.ok() && check.stdout().contains(version)) {
      tracker.advance(currentPhase);
      stepDone("Maven " + version + " (already installed)");
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
    stepDone("Maven " + version);
  }

  private static String mavenBinaryUrl(String version, String baseUrl) {
    return baseUrl + version + "/binaries/apache-maven-" + version + "-bin.tar.gz";
  }

  private void cloneRepos(ProjectDefinition config, String name, CheckedTokens gitTokens)
      throws Exception {
    currentPhase = ProjectPhase.REPOS_CLONED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("repos already cloned");
      return;
    }

    if (config.repos() == null || config.repos().isEmpty()) {
      tracker.advance(currentPhase);
      stepSkipped("no repos configured");
      return;
    }

    step("Cloning " + config.repos().size() + " repositories...");

    var user = ProjectDefinition.USER;
    var cloned = 0;

    var removed =
        WorkspaceCredentials.renew(
            shell, name, config.repos(), gitTokens.byHostFor(config.repos()));

    execAsDevUser(name, List.of("mkdir", "-p", "/home/" + user + "/workspace"));

    for (var repo : config.repos()) {
      var targetDir = "/home/" + user + "/workspace/" + repo.path();

      var check = execAsDevUser(name, List.of("test", "-d", targetDir));
      if (check.ok()) {
        RepoOrigin.require(shell, name, targetDir, repo);
        continue;
      }

      cloneWhole(config, name, repo, targetDir, gitTokens.byHostFor(config.repos()));
      cloned++;
    }

    tracker.advance(currentPhase);
    stepDone(
        "Cloned "
            + cloned
            + "/"
            + config.repos().size()
            + " repositories"
            + (removed == null ? "" : "; " + removed));
  }

  private void pushWorkspaceFiles(String name, Path filesDir) throws Exception {
    currentPhase = ProjectPhase.WORKSPACE_FILES_PUSHED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("workspace files already pushed");
      return;
    }

    if (filesDir == null || !Files.isDirectory(filesDir)) {
      tracker.advance(currentPhase);
      stepSkipped("no shared files");
      return;
    }

    var entries = WorkspaceFiles.listFiles(filesDir);
    if (entries.isEmpty()) {
      tracker.advance(currentPhase);
      stepSkipped("no shared files");
      return;
    }

    step("Pushing " + entries.size() + " workspace file(s)...");

    var user = ProjectDefinition.USER;
    var workspace = "/home/" + user + "/workspace/";
    for (var entry : entries) {
      var cmd =
          new ArrayList<>(List.of("incus", "file", "push", "-p", "--uid", "1000", "--gid", "1000"));
      cmd.addAll(List.of("--mode", String.format("%04o", WorkspaceFiles.mode(entry.hostPath()))));
      cmd.add(entry.hostPath().toString());
      cmd.add(name + workspace + entry.relativePath());
      var pushResult = shell.exec(cmd);
      if (!pushResult.ok()) {
        throw new IOException(
            "Failed to push workspace file '" + entry.relativePath() + "': " + pushResult.stderr());
      }
    }

    tracker.advance(currentPhase);
    stepDone("Pushed " + entries.size() + " workspace file(s)");
  }

  private void provisionServices(ProjectDefinition config, String name) throws Exception {
    currentPhase = ProjectPhase.SERVICES_PROVISIONED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("services already provisioned");
      return;
    }

    if (config.services() == null || config.services().isEmpty()) {
      tracker.advance(currentPhase);
      stepSkipped("no services configured");
      return;
    }

    step("Provisioning " + config.services().size() + " services...");

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
        "Started " + started + "/" + config.services().size() + " services (" + serviceNames + ")");
  }

  private void configurePruneCron(ProjectDefinition config, String name) throws Exception {
    currentPhase = ProjectPhase.PRUNE_CRON_CONFIGURED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("cleanup cron already configured");
      return;
    }
    step("Configuring stale container cleanup...");

    var user = ProjectDefinition.USER;

    var check = execAsDevUser(name, List.of("crontab", "-l"));
    if (check.ok() && check.stdout().contains(CleanupScripts.CONTAINER_CLEANUP_PATH)) {
      tracker.advance(currentPhase);
      stepDone("already configured");
      return;
    }

    execAsDevUser(name, List.of("mkdir", "-p", CleanupScripts.SAIL_DIR));
    pushFileToContainer(
        name,
        CleanupScripts.CONTAINER_CLEANUP_PATH,
        CleanupScripts.containerCleanupScript(),
        "0755");
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
    stepDone("Container cleanup scheduled (hourly) + agent cleanup helper installed");
  }

  private void installAgentTools(ProjectDefinition config, String name) throws Exception {
    currentPhase = ProjectPhase.AGENT_TOOLS_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("agent tools already installed");
      return;
    }

    var agentConfig = config.agent();
    if (agentConfig == null) {
      tracker.advance(currentPhase);
      stepSkipped("no agent configured");
      return;
    }

    var tools = agentConfig.installs().stream().map(Harnesses::of).toList();

    step("Installing " + tools.size() + " agent CLI(s)...");

    var installed = 0;

    for (var tool : tools) {
      var check = execAsDevUser(name, List.of("which", tool.binaryName()));
      if (check.ok()) {
        continue;
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
    var toolNames = tools.stream().map(Harness::yamlName).toList();
    stepDone(
        "Installed "
            + installed
            + "/"
            + tools.size()
            + " agent tools ("
            + String.join(", ", toolNames)
            + ")");
  }

  /**
   * Installs the project's skills and sail's {@code spec-board} into the skills folder of every
   * harness the project installs. Sail writes no context file: how the codebase is built is the
   * repository's own {@code AGENTS.md} to say, and the loop's rules are in the prompts.
   */
  private void installSkills(ProjectDefinition config, String name) throws Exception {
    currentPhase = ProjectPhase.SKILLS_INSTALLED;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("skills already installed");
      return;
    }

    var user = ProjectDefinition.USER;
    var workspace = "/home/" + user + "/workspace";
    execInContainer(name, List.of("mkdir", "-p", workspace));
    execInContainer(name, List.of("chown", user + ":" + user, workspace));

    var harnesses = installedHarnesses(config);
    if (harnesses.isEmpty()) {
      tracker.advance(currentPhase);
      stepSkipped("no agent configured");
      return;
    }
    step("Installing skills...");
    var detail = "";
    for (var harness : harnesses) {
      var report =
          ProjectSkillInstaller.installProject(shell, config.name(), name, harness, skills);
      detail =
          "Skills installed ("
              + String.join(", ", report.installed())
              + ")"
              + (report.skipped().isEmpty() ? "" : "; " + String.join("; ", report.skipped()));
    }
    tracker.advance(currentPhase);
    stepDone(detail);
  }

  /** The harnesses the project installs: {@code agent.install}, else {@code agent.type}. */
  public static List<Harness> installedHarnesses(ProjectDefinition config) {
    if (config.agent() == null) {
      return List.of();
    }
    return config.agent().installs().stream().map(Harnesses::of).toList();
  }

  private void writeProjectState(ProjectDefinition config, String name) throws Exception {
    currentPhase = ProjectPhase.COMPLETE;
    if (tracker.isCompleted(currentPhase)) {
      stepSkipped("project state already written");
      return;
    }
    step("Writing project state...");

    execInContainer(name, List.of("mkdir", "-p", "/etc/sail"));

    var yaml =
        """
            name: %s
            created_at: %s
            """
            .formatted(config.name(), DateTimeUtils.now());
    pushFileToContainer(name, "/etc/sail/project.yaml", yaml);
    var recorded =
        containerConfig.set(name, List.of(INPUTS_KEY + "=" + inputs(config, workstationKey)));
    if (!recorded.ok()) {
      throw new IOException("Failed to record the provisioned definition: " + recorded.stderr());
    }

    tracker.advance(currentPhase);
    stepDone("Project config saved");
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
    return shell.exec(ContainerExec.asRoot(name, command));
  }

  private ShellExec.Result execInContainer(String name, List<String> command, Duration timeout)
      throws IOException, InterruptedException, TimeoutException {
    return shell.exec(ContainerExec.asRoot(name, command), null, timeout);
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

  private void step(String description) {
    listener.onStep(currentPhase.step(), TOTAL_STEPS, description);
  }

  private void stepDone(String detail) {
    listener.onStepDone(currentPhase.step(), TOTAL_STEPS, detail);
  }

  private void stepSkipped(String detail) {
    listener.onStepSkipped(currentPhase.step(), TOTAL_STEPS, detail);
  }
}
