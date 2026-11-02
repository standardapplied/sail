/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HomeRoom;
import ai.singlr.sail.api.Operations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.ProjectSkills;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.GitCredentials;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.IncusDeviceManager;
import ai.singlr.sail.engine.LocalIdentity;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PairingTrust;
import ai.singlr.sail.engine.ProjectApplier;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.engine.ProjectPhase;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.ProvisionState;
import ai.singlr.sail.engine.ProvisionTracker;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WorkstationIdentity;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.ssh.SshPublicKey;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * The one verb that makes a project container match intent, from any starting state. Desired state
 * is the project's definition in the catalog plus the machinery this binary generates; observed
 * state is the container. Apply converges the difference: an absent container is provisioned, a
 * stopped one is started, and a running one has its services, repos, agent tools, git identity,
 * skills, cleanup cron, sail machinery, and hostname reconciled. Idempotent: applying a current
 * project changes nothing.
 */
@Command(
    name = "apply",
    description =
        "Make a project's container match its definition: create it if absent, start it if"
            + " stopped, and converge services, repos, agent tools and the sail machinery.",
    mixinStandardHelpOptions = true)
public final class ProjectApplyCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name, as the catalog holds it. Omit when --all is set.")
  private String name;

  @Option(names = "--all", description = "Apply every existing project container.")
  private boolean all;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--yes", description = "Skip confirmation prompts (for non-interactive use).")
  private boolean yes;

  @Option(
      names = "--git-token",
      description = "Access token for cloning private repos over HTTPS.",
      defaultValue = "${GITHUB_TOKEN}")
  private String gitToken;

  private final Supplier<Box> box;

  public ProjectApplyCommand() {
    this(Box::here);
  }

  ProjectApplyCommand(Supplier<Box> box) {
    this.box = box;
  }

  /** The box apply provisions on: its database, its host.yaml and sail-api's socket directory. */
  record Box(Path database, Path hostConfig, Path apiSockets) {
    static Box here() {
      return new Box(
          SailPaths.controlPlaneDb(), SailPaths.hostConfigPath(), SailPaths.apiSocketHostDir());
    }
  }

  enum Action {
    CREATED,
    STARTED,
    CONVERGED
  }

  /** The lifecycle decision, pure in the observed state: absent → create, stopped → start. */
  static Action plan(ContainerState state) {
    return switch (state) {
      case ContainerState.Running ignored -> Action.CONVERGED;
      case ContainerState.Stopped ignored -> Action.STARTED;
      case ContainerState.NotCreated ignored -> Action.CREATED;
      case ContainerState.Error e ->
          throw new IllegalStateException("Container error: " + e.message());
    };
  }

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    if (json && dryRun) {
      throw new IllegalArgumentException(
          "--json and --dry-run cannot be combined: dry-run narrates the underlying shell"
              + " commands on stdout, which would corrupt the JSON document. Drop one of the two"
              + " flags.");
    }
    if (all && Strings.isNotBlank(name)) {
      throw new IllegalArgumentException("Pass a project name OR --all, not both.");
    }
    if (!all && Strings.isBlank(name)) {
      throw new IllegalArgumentException("Name the project to apply, or pass --all.");
    }
    if (dryRun) {
      converge(operator);
      return;
    }
    try (var held = PairingTrust.hold(box.get().database())) {
      converge(operator);
    }
  }

  private void converge(OperatorCommand operator) throws Exception {
    if (all) {
      applyAll(operator);
    } else {
      applyOne(operator);
    }
  }

  private void applyOne(OperatorCommand operator) throws Exception {
    NameValidator.requireValidProjectName(name);
    var config =
        ProjectMutations.requireResources(
            catalog().read(name).orElseThrow(() -> ProjectMutations.notFound(name, operator)),
            operator);
    requireReadyBox(operator);
    var git = LocalIdentity.detect().git();

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
    }

    var observer = new ShellExecutor(false);
    var shell = new ShellExecutor(dryRun);
    var observed = new ContainerManager(observer);
    var mgr = new ContainerManager(shell);
    var action = plan(observed.queryState(config.name()));

    if (action == Action.STARTED) {
      if (!json) {
        System.out.println(Ansi.AUTO.string("  @|bold Starting|@ " + config.name() + "..."));
      }
      mgr.start(config.name());
      mgr.waitUntilReady(config.name());
    }

    var workstationKey = WorkstationIdentity.registered();
    var recorded = ProjectProvisioner.provisionedInputs(observer, config.name());
    if (!ProjectProvisioner.provisionedFrom(recorded, config, workstationKey)) {
      provision(
          config,
          git,
          shell,
          action == Action.CREATED,
          recorded.isPresent(),
          workstationKey,
          operator);
      action = Action.CREATED;
    }

    if (!json) {
      System.out.println(Ansi.AUTO.string("  @|bold Applying|@ " + config.name() + "..."));
      System.out.println();
    }

    var outcome = converge(observer, shell, config, git, operator);

    if (json) {
      System.out.println(YamlUtil.dumpJson(jsonSummary(config.name(), action, outcome)));
      return;
    }
    System.out.println();
    for (var warning : outcome.warnings()) {
      System.out.println(Ansi.AUTO.string("  @|yellow ⚠|@ " + warning));
    }
    System.out.println(Ansi.AUTO.string("  @|bold,green ✓|@ " + summaryLine(action, outcome)));
    var homeRoom = homeRoomLine(outcome.homeRoom());
    if (homeRoom != null) {
      System.out.println("    " + homeRoom);
    }
    if (action == Action.CREATED) {
      printConnectHints(config, observer, observed, operator);
    }
  }

  /**
   * Refuses before anything is created or started on a box where {@code host init} has not run, or
   * sail-api has not, since every project container mounts its socket directory.
   */
  private void requireReadyBox(OperatorCommand operator) {
    var here = box.get();
    if (!Files.exists(here.hostConfig())) {
      throw HostInitCommand.notInitialized(operator);
    }
    ContainerSailSetup.requireApi(here.apiSockets());
  }

  /**
   * Converges every Sail-managed container: one with a definition in the store, or a legacy
   * definition-less one an earlier release provisioned (recognizable by its API-socket device) —
   * the latter still gets its machinery and hostname healed, reported with a warning line, never a
   * failure. Unrelated Incus instances on the same host are never targets: starting one or
   * attaching the API-socket mount would hand the box credential to a container Sail did not
   * provision. Each target rides the same resumable lifecycle as single-project apply, so an
   * interrupted provisioning run finishes its remaining phases instead of being skipped. This is
   * the post-upgrade retrofit: one command brings the whole box current.
   */
  private void applyAll(OperatorCommand operator) throws Exception {
    requireReadyBox(operator);
    var observer = new ShellExecutor(false);
    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var devices = new IncusDeviceManager(observer);
    var targets = new ContainerManager(observer).listAll();

    if (!json) {
      Banner.printBranding(System.out, Ansi.AUTO);
      System.out.println();
      var mode = dryRun ? " (dry run)" : "";
      System.out.println(Ansi.AUTO.string("  @|bold Applying|@ all projects" + mode));
      System.out.println();
    }

    var workstationKey = WorkstationIdentity.registered();
    var git = LocalIdentity.detect().git();
    var rows = new ArrayList<LinkedHashMap<String, Object>>();
    var applied = 0;
    var failed = 0;

    for (var target : targets) {
      var project = target.name();
      try {
        var definition = definitionFor(project);
        if (!devices.sailManaged(project)) {
          var warning = unmanagedSkipWarning(project, definition.isPresent(), operator);
          if (warning != null) {
            if (json) {
              var row = new LinkedHashMap<String, Object>();
              row.put("name", project);
              row.put("skipped", warning);
              rows.add(row);
            } else {
              System.out.println(Ansi.AUTO.string("  @|yellow ⚠|@ " + warning));
            }
          }
          continue;
        }
        if (definition.isPresent()) {
          ProjectMutations.requireResources(definition.get(), operator);
        }
        var targetPlan = planTarget(target.state(), definition.orElse(null));
        var action = targetPlan.action();
        if (action == Action.STARTED) {
          mgr.start(project);
          mgr.waitUntilReady(project);
        }
        Outcome outcome;
        if (targetPlan.config() != null) {
          var recorded = ProjectProvisioner.provisionedInputs(observer, project);
          if (!ProjectProvisioner.provisionedFrom(recorded, targetPlan.config(), workstationKey)) {
            provision(
                targetPlan.config(),
                git,
                shell,
                false,
                recorded.isPresent(),
                workstationKey,
                operator);
            action = Action.CREATED;
          }
          outcome = converge(observer, shell, targetPlan.config(), git, operator);
        } else {
          outcome = convergeMachineryOnly(observer, shell, project);
        }
        applied++;
        if (json) {
          rows.add(jsonSummary(project, action, outcome));
        } else {
          for (var warning : outcome.warnings()) {
            System.out.println(Ansi.AUTO.string("  @|yellow ⚠|@ " + project + ": " + warning));
          }
          System.out.println(
              Ansi.AUTO.string("  @|green ✓|@ " + project + " — " + summaryLine(action, outcome)));
          var homeRoom = homeRoomLine(outcome.homeRoom());
          if (homeRoom != null) {
            System.out.println("    " + homeRoom);
          }
        }
      } catch (Exception e) {
        failed++;
        if (json) {
          var row = new LinkedHashMap<String, Object>();
          row.put("name", project);
          row.put("error", CliCommand.describe(e));
          rows.add(row);
        } else {
          System.err.println(
              Banner.errorLine(
                  "Could not apply " + project + ": " + CliCommand.describe(e), Ansi.AUTO));
        }
      }
    }

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("dry_run", dryRun);
      map.put("projects", rows);
      System.out.println(YamlUtil.dumpJson(map));
    } else {
      System.out.println();
      System.out.println(Ansi.AUTO.string(allSummaryLine(applied, failed)));
    }
    requireAllApplied(failed);
  }

  /**
   * A catalog row pointing at a container the trust gate refused is a disagreement worth narrating,
   * with the explicit escape hatch; a foreign container with no row is not Sail's to mention.
   */
  static String unmanagedSkipWarning(
      String project, boolean hasDefinition, OperatorCommand operator) {
    if (!hasDefinition) {
      return null;
    }
    return project
        + ": catalog entry exists but the container carries no sail-api-sock device — possibly a"
        + " foreign container reusing the name. Skipped; claim it explicitly with '"
        + operator.sail("project apply " + project)
        + "' if it is yours.";
  }

  record TargetPlan(Action action, ProjectDefinition config) {}

  /**
   * A bulk-apply target's plan, decided before any lifecycle mutation: a target without a
   * definition in the catalog plans a machinery-only convergence ({@code config} null).
   */
  static TargetPlan planTarget(ContainerState state, ProjectDefinition config) {
    return new TargetPlan(plan(state), config);
  }

  private Optional<ProjectDefinition> definitionFor(String project) {
    return NameValidator.isValidProjectName(project) ? catalog().read(project) : Optional.empty();
  }

  static String allSummaryLine(int applied, int failed) {
    var style = failed == 0 ? "bold,green ✓" : "bold,red ✗";
    return "  @|"
        + style
        + "|@ Applied "
        + applied
        + (failed > 0 ? ", failed " + failed : "")
        + ".";
  }

  static void requireAllApplied(int failed) {
    if (failed > 0) {
      throw new IllegalStateException("Failed to apply " + failed + " project(s).");
    }
  }

  private static final PrintStream DISCARD =
      new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8);

  /** JSON mode promises one parseable document on stdout, so progress narration is discarded. */
  static PrintStream progressOut(boolean json) {
    return json ? DISCARD : System.out;
  }

  record Outcome(
      int added,
      int removed,
      int skipped,
      ContainerSailSetup.Result machinery,
      boolean hostnameRealigned,
      List<String> warnings,
      HomeRoom.Outcome homeRoom) {}

  /**
   * The full convergence pass: the definition's state, sail machinery, skills, hostname. The {@code
   * observer} always executes, so every state probe sees the live container; only mutations ride
   * {@code shell}, which a dry run swaps for command narration.
   */
  private Outcome converge(
      ShellExecutor observer,
      ShellExecutor shell,
      ProjectDefinition config,
      LocalIdentity.GitIdentity git,
      OperatorCommand operator)
      throws Exception {
    var project = config.name();
    var observed = new ContainerManager(observer);
    var mgr = new ContainerManager(shell);
    var hostnameRealigned = !observed.hostnameMatches(project) && mgr.setHostname(project);
    var machinery = ContainerSailSetup.ensureInstalled(observer, shell, project);

    var applier = new ProjectApplier(observer, shell, progressOut(json));
    var info = observed.queryInfo(project);
    var warnings =
        new ArrayList<>(applier.checkUnsupportedChanges(config, info.limits(), operator));
    var sshUser = ProjectDefinition.USER;
    var token = Strings.isNotBlank(gitToken) ? gitToken : null;

    var results = new ArrayList<ProjectApplier.ApplyResult>();
    results.add(applier.applyPackages(project, config.packages()));
    results.add(applier.applyServices(project, config.services()));
    results.add(applier.reconcileServices(project, config.services()));
    results.add(
        applier.applyRepos(project, config.repos(), sshUser, GitCredentials.singleTokenMap(token)));
    results.add(applier.applyWorkspaceFiles(project, SailPaths.projectFilesDir(project), sshUser));
    var agentInstall = config.agent() != null ? config.agent().installs() : null;
    results.add(applier.applyAgentTools(project, agentInstall));
    results.add(applier.applyGitConfig(project, git));
    HomeRoom.Outcome homeRoom;
    try (var operations = OperationsFactory.open()) {
      results.add(
          applier.applySkills(project, config, new ProjectSkills(operations::projectFiles)));
      results.add(applier.applyCleanupCron(project, sshUser));
      homeRoom =
          dryRun
              ? null
              : ensureHomeRoom(operations, config, HostSync.handle(), () -> CliOperator.current());
    }

    var added = results.stream().mapToInt(ProjectApplier.ApplyResult::added).sum();
    var removed = results.stream().mapToInt(ProjectApplier.ApplyResult::removed).sum();
    var skipped = results.stream().mapToInt(ProjectApplier.ApplyResult::skipped).sum();
    results.forEach(result -> warnings.addAll(result.warnings()));
    return new Outcome(added, removed, skipped, machinery, hostnameRealigned, warnings, homeRoom);
  }

  /**
   * The home room of this box's FDE, created and engaged as that FDE once the container runs with
   * its harnesses installed. A box that names no FDE acts as no one: it has no home room to make.
   */
  static HomeRoom.Outcome ensureHomeRoom(
      Operations operations, ProjectDefinition config, String handle, Supplier<Actor> operator) {
    if (Strings.isBlank(handle)) {
      return HomeRoom.ensure(operations, config, handle);
    }
    return Actor.call(operator.get(), () -> HomeRoom.ensure(operations, config, handle));
  }

  private Outcome convergeMachineryOnly(ShellExecutor observer, ShellExecutor shell, String project)
      throws Exception {
    var observed = new ContainerManager(observer);
    var mgr = new ContainerManager(shell);
    var hostnameRealigned = !observed.hostnameMatches(project) && mgr.setHostname(project);
    var machinery = ContainerSailSetup.ensureInstalled(observer, shell, project);
    return new Outcome(
        0,
        0,
        0,
        machinery,
        hostnameRealigned,
        List.of("no definition in the catalog — converged machinery only"),
        null);
  }

  static String summaryLine(Action action, Outcome outcome) {
    var machinery =
        outcome.machinery() == ContainerSailSetup.Result.UPDATED ? "updated" : "current";
    return action.name().toLowerCase(Locale.ROOT)
        + ": "
        + outcome.added()
        + " added, "
        + outcome.removed()
        + " removed, "
        + outcome.skipped()
        + " skipped, machinery "
        + machinery
        + (outcome.hostnameRealigned() ? ", hostname realigned" : "");
  }

  static LinkedHashMap<String, Object> jsonSummary(String project, Action action, Outcome outcome) {
    var map = new LinkedHashMap<String, Object>();
    map.put("name", project);
    map.put("action", action.name().toLowerCase(Locale.ROOT));
    map.put("added", outcome.added());
    map.put("removed", outcome.removed());
    map.put("skipped", outcome.skipped());
    map.put(
        "machinery",
        outcome.machinery() == ContainerSailSetup.Result.UPDATED ? "updated" : "current");
    map.put("hostname_realigned", outcome.hostnameRealigned());
    if (!outcome.warnings().isEmpty()) {
      map.put("warnings", outcome.warnings());
    }
    if (outcome.homeRoom() != null) {
      map.put("home_room", outcome.homeRoom().toMap());
    }
    return map;
  }

  /** The apply output's one line about the home room, or null when this apply made no decision. */
  static String homeRoomLine(HomeRoom.Outcome homeRoom) {
    if (homeRoom == null) {
      return null;
    }
    if (homeRoom.skipped() != null) {
      return "No home room: " + homeRoom.skipped() + ".";
    }
    return "Home room "
        + homeRoom.id()
        + (homeRoom.created() ? ": created" : ": present")
        + (homeRoom.engaged() ? ", engaged." : ", no agent engaged.");
  }

  /**
   * Why an existing container is provisioned again from its first step: the interrupted run's
   * progress ({@code superseded}) was recorded for other inputs, the container was last provisioned
   * from other inputs ({@code recorded}), or the container records none at all. The inputs are the
   * definition and this box's workstation key ({@link ProjectProvisioner#inputs}).
   */
  static String recheckLine(ProvisionState superseded, boolean recorded) {
    if (superseded != null && !ProjectPhase.COMPLETE.name().equals(superseded.completedPhase())) {
      return "The definition or this box's workstation key changed since the interrupted run: every"
          + " step is checked again.";
    }
    if (recorded) {
      return "The definition or this box's workstation key changed since the last provisioning"
          + " run: every step is checked again.";
    }
    return "No record of the definition this container was provisioned from: every step is checked"
        + " again.";
  }

  /** Only provisioning needs root; converging a live container does not. */
  static void requireRootToProvision(
      String name, boolean dryRun, boolean isRoot, CommandSpec spec) {
    if (!dryRun && !isRoot) {
      throw RootRequired.refusal("Root privileges required to provision '" + name + "'.", spec);
    }
  }

  /** The catalog of the box this command runs on. */
  private ProjectCatalog catalog() {
    return new ProjectCatalog(box.get().database());
  }

  /**
   * Provisions from the definition with resumable progress: a fresh container from scratch, the
   * remaining phases of an interrupted earlier run, or every phase again for a container whose
   * definition changed since it was provisioned.
   *
   * @param git this box's git identity, the one the container commits as
   * @param fresh whether the container is created by this run
   * @param recorded whether the container records the definition it was last provisioned from
   */
  private void provision(
      ProjectDefinition config,
      LocalIdentity.GitIdentity git,
      ShellExecutor shell,
      boolean fresh,
      boolean recorded,
      Optional<SshPublicKey> workstationKey,
      OperatorCommand operator)
      throws Exception {
    requireRootToProvision(config.name(), dryRun, ConsoleHelper.isRoot(), spec);
    catalog().requireUnpruned(config.name());

    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(box.get().hostConfig()));

    var tracker =
        new ProvisionTracker<>(ProjectPhase.class, SailPaths.provisionState(config.name()), dryRun)
            .load(ProjectProvisioner.inputs(config, workstationKey));
    if (fresh && (tracker.hasIncompleteRun() || tracker.isComplete()) && !json) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint Stale state detected — container '"
                  + config.name()
                  + "' no longer exists. Starting fresh.|@"));
    }
    if (fresh || tracker.isComplete()) {
      tracker.reset();
    }
    var superseded = tracker.superseded();
    var resuming = tracker.hasIncompleteRun() && superseded.isEmpty();
    if (!fresh && !resuming && !json) {
      System.out.println(
          Ansi.AUTO.string("  @|faint " + recheckLine(superseded.orElse(null), recorded) + "|@"));
    }
    if (resuming) {
      if (!json) {
        Banner.printResumeInfo(tracker.currentState(), System.out, Ansi.AUTO);
      }
      if (!yes && !dryRun && !json) {
        ConsoleHelper.proceed("Resume provisioning?", "Not provisioned", "--yes");
      }
    }

    if (!json) {
      Banner.printProjectSummary(config, System.out, Ansi.AUTO);
    }
    if (!yes && !dryRun && !json && fresh) {
      ConsoleHelper.proceed("Create project " + config.name() + "?", "Not provisioned", "--yes");
    }

    var gitTokens = resolveGitTokens(config, operator);
    var listener = json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE;
    try (var operations = OperationsFactory.open()) {
      var provisioner =
          new ProjectProvisioner(
              shell,
              tracker,
              listener,
              new ProjectSkills(operations::projectFiles),
              workstationKey,
              operator);
      provisioner.provision(
          config, hostYaml, gitTokens, git, SailPaths.projectFilesDir(config.name()));
    }

    if (!json) {
      System.out.println();
      Banner.printProjectCreated(config.name(), operator, System.out, Ansi.AUTO);
    }
  }

  private void printConnectHints(
      ProjectDefinition config, ShellExecutor shell, ContainerManager mgr, OperatorCommand operator)
      throws Exception {
    var state = mgr.queryState(config.name());
    if (!(state instanceof ContainerState.Running r) || r.ipv4() == null) {
      return;
    }
    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(box.get().hostConfig()));
    var serverIp = hostYaml.serverIp();
    if (serverIp != null) {
      Banner.printSshConfig(
          config.name(),
          HostInfo.hostname(),
          serverIp,
          System.getProperty("user.name"),
          r.ipv4(),
          WorkstationIdentity.identityFile(),
          operator,
          System.out,
          Ansi.AUTO);
    } else {
      System.out.println();
      System.out.println(Ansi.AUTO.string(serverIpUnsetLines(config.name(), operator)));
    }
    var ports = ContainerExec.queryServicePorts(shell, config.name());
    if (!ports.isEmpty()) {
      Banner.printSshTunnels(config.name(), ProjectDefinition.USER, ports, System.out, Ansi.AUTO);
    }
  }

  /** What apply prints for a box with no server IP: the command that sets it, then connect's. */
  static String serverIpUnsetLines(String project, OperatorCommand operator) {
    return "    @|yellow Server IP not configured.|@ Run: "
        + operator.sail("host config set server-ip <your-server-ip>")
        + "\n    Then: "
        + operator.sail("project connect " + project)
        + " for SSH config snippet.";
  }

  private Map<String, String> resolveGitTokens(ProjectDefinition config, OperatorCommand operator) {
    if (Strings.isNotBlank(gitToken)) {
      return GitCredentials.singleTokenMap(gitToken);
    }
    var hosts = GitCredentials.extractHttpsHosts(config.repos());
    if (hosts.isEmpty()) {
      return Map.of();
    }
    var tokens = new LinkedHashMap<String, String>();
    for (var host : hosts) {
      var envToken = GitCredentials.resolveTokenForHost(host, null);
      if (Strings.isNotBlank(envToken)) {
        tokens.put(host, envToken);
      }
    }
    var missingHosts = hosts.stream().filter(h -> !tokens.containsKey(h)).toList();
    if (missingHosts.isEmpty() || yes || json || dryRun) {
      return Map.copyOf(tokens);
    }
    for (var host : missingHosts) {
      try {
        var prompted =
            ConsoleHelper.readPassword("  Git access token for " + host + " (blank to skip): ");
        if (Strings.isNotBlank(prompted)) {
          tokens.put(host, prompted);
        }
      } catch (EchoDisabledUnavailableException e) {
        throw new IllegalArgumentException(
            "Unable to read git access token interactively in this terminal.\n\n"
                + "Provide the token via one of:\n"
                + "  --git-token <token>\n"
                + "  GITHUB_TOKEN environment variable\n\n"
                + "Then re-run: "
                + operator.sail("project apply " + config.name()));
      }
    }
    return Map.copyOf(tokens);
  }
}
