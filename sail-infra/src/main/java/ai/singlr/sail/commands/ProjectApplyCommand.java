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
import ai.singlr.sail.config.SailYaml;
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
import ai.singlr.sail.engine.ProjectDefinitions;
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
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The one verb that makes a project container match intent, from any starting state. Desired state
 * is the resolved descriptor plus the machinery this binary generates; observed state is the
 * container. Apply converges the difference: an absent container is provisioned, a stopped one is
 * started, and a running one has its services, repos, workspace files, agent tools, git config,
 * agent context, cleanup cron, sail machinery, and hostname reconciled. Idempotent: applying a
 * current project changes nothing.
 */
@Command(
    name = "apply",
    description =
        "Make a project match its descriptor: create it if absent, start it if stopped, and"
            + " converge services, repos, files, agent context, and the sail machinery.",
    mixinStandardHelpOptions = true)
public final class ProjectApplyCommand implements Runnable {

  @Parameters(
      index = "0",
      arity = "0..1",
      description =
          "Project name (uses ~/.sail/projects/<name>/sail.yaml if -f not given)."
              + " Omit when --all is set.")
  private String name;

  @Option(names = "--all", description = "Apply every existing project container.")
  private boolean all;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.")
  private String file;

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

  private final Supplier<Path> database;

  public ProjectApplyCommand() {
    this(SailPaths::controlPlaneDb);
  }

  ProjectApplyCommand(Supplier<Path> database) {
    this.database = database;
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
    if (all && file != null) {
      throw new IllegalArgumentException("--file applies to a single project, not --all.");
    }
    if (dryRun) {
      converge(operator);
      return;
    }
    try (var held = PairingTrust.hold(database.get())) {
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
    var definition = loadDefinition(operator);
    var sailYamlPath = definition.path();

    var identity =
        new InteractiveIdentity(
            LocalIdentity.detect(operator),
            InteractiveIdentity.canPrompt(yes, json, dryRun, ConsoleHelper.hasConsole()));
    SailYaml config =
        ProjectDefinitions.resolveForProvisioning(definition.text(), identity, operator);
    if (config.name() == null || config.name().isBlank()) {
      throw new IllegalStateException("sail.yaml must have a 'name' field.");
    }
    NameValidator.requireValidProjectName(config.name());
    requireMatchingName(name, config.name(), sailYamlPath);
    if (config.resources() == null) {
      throw new IllegalStateException(
          "sail.yaml must have a 'resources' section with cpu, memory, and disk.");
    }

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

    var workstationKey = WorkstationIdentity.registered(operator);
    var recorded = ProjectProvisioner.provisionedInputs(observer, config.name());
    if (!ProjectProvisioner.provisionedFrom(recorded, config, workstationKey)) {
      provision(
          config,
          sailYamlPath,
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

    var outcome = converge(observer, shell, config, sailYamlPath, operator);

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
   * Converges every Sail-managed container: one with a definition in the store, or a legacy
   * descriptor-less one an earlier release provisioned (recognizable by its API-socket device) —
   * the latter still gets its machinery and hostname healed, reported with a warning line, never a
   * failure. Unrelated Incus instances on the same host are never targets: starting one or
   * attaching the API-socket mount would hand the box credential to a container Sail did not
   * provision. Each target rides the same resumable lifecycle as single-project apply, so an
   * interrupted provisioning run finishes its remaining phases instead of being skipped. This is
   * the post-upgrade retrofit: one command brings the whole box current.
   */
  private void applyAll(OperatorCommand operator) throws Exception {
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

    var workstationKey = WorkstationIdentity.registered(operator);
    var rows = new ArrayList<LinkedHashMap<String, Object>>();
    var applied = 0;
    var failed = 0;

    for (var target : targets) {
      var project = target.name();
      var definition = definitionFor(project);
      try {
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
        var targetPlan = planTarget(project, target.state(), definition.orElse(null), operator);
        var action = targetPlan.action();
        if (action == Action.STARTED) {
          mgr.start(project);
          mgr.waitUntilReady(project);
        }
        Outcome outcome;
        if (targetPlan.config() != null) {
          var sailYamlPath = materializeUnlessDryRun(project, definition.get(), dryRun);
          var recorded = ProjectProvisioner.provisionedInputs(observer, project);
          if (!ProjectProvisioner.provisionedFrom(recorded, targetPlan.config(), workstationKey)) {
            provision(
                targetPlan.config(),
                sailYamlPath,
                shell,
                false,
                recorded.isPresent(),
                workstationKey,
                operator);
            action = Action.CREATED;
          }
          outcome = converge(observer, shell, targetPlan.config(), sailYamlPath, operator);
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

  record TargetPlan(Action action, SailYaml config) {}

  /**
   * Resolves and validates a bulk-apply target before any lifecycle mutation: a descriptor that
   * fails to parse or names a different project must fail while the container's observed state is
   * still untouched — the same fail-fast contract the single-project path keeps. A target without a
   * descriptor plans a machinery-only convergence ({@code config} null).
   */
  static TargetPlan planTarget(
      String project, ContainerState state, String definitionText, OperatorCommand operator) {
    var action = plan(state);
    if (definitionText == null) {
      return new TargetPlan(action, null);
    }
    var config = ProjectDefinitions.resolveForProvisioning(definitionText, operator);
    requireMatchingName(project, config.name(), null);
    return new TargetPlan(action, config);
  }

  private static Optional<String> definitionFor(String project) {
    return NameValidator.isValidProjectName(project)
        ? ProjectDefinitions.definition(project, null)
        : Optional.empty();
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

  /**
   * The requested name selects the target; the descriptor must agree. Silently letting the
   * descriptor's name win would redirect a destructive converge — undeclared-service removal
   * included — at a container the operator never named.
   */
  static void requireMatchingName(String requested, String declared, Path descriptorPath) {
    if (Strings.isNotBlank(requested) && !requested.equals(declared)) {
      var source = descriptorPath != null ? descriptorPath.toString() : "its catalog descriptor";
      throw new IllegalStateException(
          "Requested project '"
              + requested
              + "' but "
              + source
              + " declares name '"
              + declared
              + "'.\n  Fix the 'name' field or apply the project the descriptor names.");
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
   * The full convergence pass: descriptor state, sail machinery, skills, hostname. The {@code
   * observer} always executes, so every state probe sees the live container; only mutations ride
   * {@code shell}, which a dry run swaps for command narration.
   */
  private Outcome converge(
      ShellExecutor observer,
      ShellExecutor shell,
      SailYaml config,
      Path sailYamlPath,
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
    var sshUser = config.sshUser();
    var token = Strings.isNotBlank(gitToken) ? gitToken : null;

    var results = new ArrayList<ProjectApplier.ApplyResult>();
    results.add(applier.applyPackages(project, config.packages()));
    results.add(applier.applyServices(project, config.services()));
    results.add(applier.reconcileServices(project, config.services()));
    results.add(
        applier.applyRepos(
            project, config.repos(), sshUser, GitCredentials.singleTokenMap(token), config.git()));
    results.add(applier.applyWorkspaceFiles(project, sailYamlPath, sshUser));
    var agentInstall = config.agent() != null ? config.agent().installs() : null;
    results.add(applier.applyAgentTools(project, agentInstall));
    results.add(applier.applyGitConfig(project, config.git(), sshUser));
    HomeRoom.Outcome homeRoom;
    try (var operations = OperationsFactory.open(operator)) {
      results.add(
          applier.applySkills(project, config, new ProjectSkills(operations::projectFiles)));
      results.add(applier.applyCleanupCron(project, sshUser));
      homeRoom =
          dryRun
              ? null
              : ensureHomeRoom(
                  operations, config, HostSync.handle(), () -> CliOperator.current(operator));
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
      Operations operations, SailYaml config, String handle, Supplier<Actor> operator) {
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
        List.of("no descriptor in the catalog — converged machinery only"),
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
   * descriptor and this box's workstation key ({@link ProjectProvisioner#inputs}).
   */
  static String recheckLine(ProvisionState superseded, boolean recorded) {
    if (superseded != null && !ProjectPhase.COMPLETE.name().equals(superseded.completedPhase())) {
      return "The descriptor or this box's workstation key changed since the interrupted run: every"
          + " step is checked again.";
    }
    if (recorded) {
      return "The descriptor or this box's workstation key changed since the last provisioning run:"
          + " every step is checked again.";
    }
    return "No record of the descriptor this container was provisioned from: every step is checked"
        + " again.";
  }

  /** Only provisioning needs root; converging a live container does not. */
  static void requireRootToProvision(String name, boolean dryRun, boolean isRoot) {
    if (!dryRun && !isRoot) {
      throw new IllegalStateException(
          "Root privileges required to provision '"
              + name
              + "'. Run with: "
              + OperatorCommand.SUDO.sail("project apply " + name));
    }
  }

  /**
   * Provisions from the resolved descriptor with resumable progress: a fresh container from
   * scratch, the remaining phases of an interrupted earlier run, or every phase again for a
   * container whose descriptor changed since it was provisioned.
   *
   * @param fresh whether the container is created by this run
   * @param recorded whether the container records the descriptor it was last provisioned from
   */
  private void provision(
      SailYaml config,
      Path sailYamlPath,
      ShellExecutor shell,
      boolean fresh,
      boolean recorded,
      Optional<SshPublicKey> workstationKey,
      OperatorCommand operator)
      throws Exception {
    requireRootToProvision(config.name(), dryRun, ConsoleHelper.isRoot());
    ProjectCatalog.requireUnpruned(config.name());
    persistCanonicalBundle(config.name(), sailYamlPath, dryRun, operator);

    var hostYamlPath = SailPaths.hostConfigPath();
    if (!Files.exists(hostYamlPath)) {
      throw HostInitCommand.notInitialized(operator);
    }
    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(hostYamlPath));

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
      if (!yes && !json && !ConsoleHelper.confirm("Resume provisioning?")) {
        throw new IllegalStateException("Aborted.");
      }
    }

    if (!json) {
      Banner.printProjectSummary(config, System.out, Ansi.AUTO);
    }
    if (!yes && !dryRun && !json && fresh) {
      if (!ConsoleHelper.confirm("Create project " + config.name() + "?")) {
        throw new IllegalStateException("Aborted.");
      }
    }

    var gitTokens = resolveGitTokens(config, operator);
    var listener = json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE;
    try (var operations = OperationsFactory.open(operator)) {
      var provisioner =
          new ProjectProvisioner(
              shell,
              tracker,
              listener,
              new ProjectSkills(operations::projectFiles),
              workstationKey,
              operator);
      provisioner.provision(config, hostYaml, gitTokens, sailYamlPath);
    }

    if (!json) {
      System.out.println();
      Banner.printProjectCreated(config.name(), operator, System.out, Ansi.AUTO);
    }
  }

  private void printConnectHints(
      SailYaml config, ShellExecutor shell, ContainerManager mgr, OperatorCommand operator)
      throws Exception {
    var state = mgr.queryState(config.name());
    if (!(state instanceof ContainerState.Running r) || r.ipv4() == null) {
      return;
    }
    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(SailPaths.hostConfigPath()));
    var serverIp = hostYaml.serverIp();
    if (serverIp != null) {
      Banner.printSshConfig(
          config.name(),
          HostInfo.hostname(),
          serverIp,
          System.getProperty("user.name"),
          r.ipv4(),
          WorkstationIdentity.identityFile(operator),
          operator,
          System.out,
          Ansi.AUTO);
    } else {
      System.out.println();
      System.out.println(Ansi.AUTO.string(serverIpUnsetLines(config.name(), operator)));
    }
    var ports = ContainerExec.queryServicePorts(shell, config.name());
    if (!ports.isEmpty()) {
      Banner.printSshTunnels(config.name(), config.sshUser(), ports, System.out, Ansi.AUTO);
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

  private Map<String, String> resolveGitTokens(SailYaml config, OperatorCommand operator) {
    if (config.git() == null || !"token".equals(config.git().auth())) {
      return Map.of();
    }
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
                + operator.sail("project apply <name>"));
      }
    }
    return Map.copyOf(tokens);
  }

  private record Definition(String text, Path path) {}

  /**
   * Loads the definition text catalog-first (the source of truth), so apply converges a definition
   * edited or synced on main rather than a stale local copy. Falls back to the resolved descriptor
   * file when {@code -f} is given (an explicit override), the project is absent from the catalog (a
   * brand-new local authoring), or no name was supplied. The plan always reads the same desired
   * state; only a real apply re-materializes the canonical descriptor from the catalog.
   */
  /**
   * Whether apply reads the catalog: only when no {@code -f} was given and a project was named.
   * This command's {@code -f} has no default value, so any non-null path — including the literal
   * {@code sail.yaml} that older commands' default-value handling maps to absent — is an explicit
   * operator override and must win over the catalog.
   */
  static boolean usesCatalog(String file, String name) {
    return file == null && name != null;
  }

  private Definition loadDefinition(OperatorCommand operator) throws IOException {
    if (usesCatalog(file, name)) {
      NameValidator.requireValidProjectName(name);
      var catalog = ProjectDefinitions.definition(name, null);
      if (catalog.isPresent()) {
        return new Definition(catalog.get(), materializeUnlessDryRun(name, catalog.get(), dryRun));
      }
    }
    var sailYamlPath = resolveSailYamlPath(name, file);
    if (!Files.exists(sailYamlPath)) {
      throw new IllegalStateException(descriptorNotFound(sailYamlPath, operator));
    }
    return new Definition(Files.readString(sailYamlPath), sailYamlPath);
  }

  /** The refusal of an apply that finds no definition of its project at {@code path}. */
  static String descriptorNotFound(Path path, OperatorCommand operator) {
    return "Project descriptor not found: "
        + path.toAbsolutePath()
        + "\n  Run '"
        + operator.sail("project init")
        + "' to author one, sync it from main with '"
        + operator.sail("sync")
        + "', or specify one with --file.";
  }

  /**
   * A dry run promises to leave the host untouched, so it plans against the in-memory definition
   * and only resolves where the canonical descriptor would live; a real apply materializes it.
   */
  static Path materializeUnlessDryRun(String name, String definition, boolean dryRun)
      throws IOException {
    if (dryRun) {
      return defaultDescriptorPath(name);
    }
    return ProjectDefinitions.materialize(name, definition);
  }

  static Path defaultDescriptorPath(String name) {
    return SailPaths.projectDir(name).resolve(SailPaths.PROJECT_DESCRIPTOR);
  }

  static Path resolveSailYamlPath(String name, String file) {
    if (file != null) {
      return Path.of(file);
    }
    if (name != null) {
      var canonicalPath = defaultDescriptorPath(name);
      if (Files.exists(canonicalPath)) {
        return canonicalPath;
      }
      var namedPath = Path.of(name, SailPaths.PROJECT_DESCRIPTOR);
      if (Files.exists(namedPath)) {
        return namedPath;
      }
    }
    var cwdPath = Path.of(SailPaths.PROJECT_DESCRIPTOR);
    if (Files.exists(cwdPath)) {
      return cwdPath;
    }
    if (name != null) {
      return defaultDescriptorPath(name);
    }
    return cwdPath;
  }

  /**
   * Records the descriptor in the catalog as this box's operator, resolved first so a node that
   * cannot name it refuses before anything is written, then writes it as the canonical descriptor
   * and copies its {@code files/} directory beside it ({@link ProjectDefinitions#persist}: a
   * definition the catalog did not take is written nowhere). A dry run must not touch the host
   * filesystem at all — the plan is computed from the source descriptor, and the sync starts by
   * deleting the canonical files directory, so running it under dry-run would destroy locally
   * authored project files.
   */
  static void persistCanonicalBundle(
      String name, Path sailYamlPath, boolean dryRun, OperatorCommand operator) throws Exception {
    if (dryRun) {
      return;
    }
    var actor = CliOperator.current(operator);
    ProjectDefinitions.persist(name, null, Files.readString(sailYamlPath), actor);
    syncFilesDirectory(
        sailYamlPath.toAbsolutePath().getParent().resolve("files"),
        SailPaths.projectDir(name).resolve("files"));
  }

  static void syncFilesDirectory(Path sourceDir, Path targetDir) throws Exception {
    var source = sourceDir.toAbsolutePath().normalize();
    var target = targetDir.toAbsolutePath().normalize();
    if (source.equals(target)) {
      return;
    }
    if (!Files.isDirectory(source)) {
      deleteDirectory(target);
      return;
    }
    deleteDirectory(target);
    try (var walk = Files.walk(source)) {
      for (var path : walk.toList()) {
        var relative = source.relativize(path);
        var destination = target.resolve(relative);
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
          }
          Files.copy(
              path,
              destination,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
  }

  private static void deleteDirectory(Path dir) throws Exception {
    if (!Files.exists(dir)) {
      return;
    }
    try (var walk = Files.walk(dir)) {
      for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }
}
