/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.BranchPolicy;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecDirectory;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentReporter;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.DispatchRepos;
import ai.singlr.sail.engine.GitSpecSync;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.engine.SpecWorkspace;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class SailApiOperations implements ApiOperations {

  private static final Duration SNAPSHOT_INTERVAL = Duration.ofHours(24);

  private final ShellExec shell;
  private final String file;
  private final WatcherLauncher watcherLauncher;
  private final EventBus eventBus;
  private final AuditPersister auditPersister;
  private final SpecStore specStore;

  public SailApiOperations() {
    this(new ShellExecutor(false), SailPaths.PROJECT_DESCRIPTOR);
  }

  public SailApiOperations(ShellExec shell, String file) {
    this(shell, file, SailApiOperations::launchWatcherProcess);
  }

  SailApiOperations(ShellExec shell, String file, WatcherLauncher watcherLauncher) {
    this(shell, file, watcherLauncher, null, null);
  }

  /** Construct with explicit event-bus wiring; used by {@link SailApiServer}. */
  public SailApiOperations(
      ShellExec shell, String file, EventBus eventBus, AuditPersister auditPersister) {
    this(shell, file, SailApiOperations::launchWatcherProcess, eventBus, auditPersister, null);
  }

  /** Construct with database-backed spec store; used by the control plane server. */
  public SailApiOperations(
      ShellExec shell,
      String file,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      SpecStore specStore) {
    this(
        shell,
        file,
        SailApiOperations::launchWatcherProcess,
        eventBus,
        auditSubscriber instanceof AuditPersister ap ? ap : null,
        specStore);
  }

  SailApiOperations(
      ShellExec shell,
      String file,
      WatcherLauncher watcherLauncher,
      EventBus eventBus,
      AuditPersister auditPersister) {
    this(shell, file, watcherLauncher, eventBus, auditPersister, null);
  }

  SailApiOperations(
      ShellExec shell,
      String file,
      WatcherLauncher watcherLauncher,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore) {
    this.shell = shell;
    this.file = file;
    this.watcherLauncher = watcherLauncher;
    this.eventBus = eventBus;
    this.auditPersister = auditPersister;
    this.specStore = specStore;
  }

  @Override
  public Result<HealthResponse> health() {
    return Result.success(new HealthResponse("ok"));
  }

  @Override
  public Result<ProjectResponse> project(String project) {
    return safe(() -> projectValue(project));
  }

  @Override
  public Result<SpecsResponse> specs(String project) {
    return safe(() -> specsValue(project));
  }

  @Override
  public Result<SpecResponse> spec(String project, String specId) {
    return safe(() -> specValue(project, specId));
  }

  @Override
  public Result<SpecSyncResponse> specSyncStatus(String project) {
    return safe(() -> specSyncStatusValue(project));
  }

  @Override
  public Result<SpecSyncResponse> specSync(String project, SpecSyncRequest request) {
    return safe(() -> specSyncValue(project, request));
  }

  @Override
  public Result<DispatchResponse> dispatch(String project, DispatchRequest request) {
    return safe(() -> dispatchValue(project, request));
  }

  @Override
  public Result<AgentStatusResponse> agentStatus(String project) {
    return safe(() -> agentStatusValue(project));
  }

  @Override
  public Result<AgentLogResponse> agentLog(String project, int tail) {
    return safe(() -> agentLogValue(project, tail));
  }

  @Override
  public Result<StopAgentResponse> stopAgent(String project) {
    return safe(() -> stopAgentValue(project));
  }

  @Override
  public Result<AgentReportResponse> agentReport(String project) {
    return safe(() -> agentReportValue(project));
  }

  private ProjectResponse projectValue(String project) {
    var loaded = loadProject(project);
    var agent = loaded.config().agent() != null ? agentConfigView(loaded.config()) : null;
    return new ProjectResponse(project, statusName(loaded.state()), agent);
  }

  private SpecsResponse specsValue(String project) {
    var loaded = loadRunningProject(project);
    var specs = readSpecs(workspace(loaded));
    var summary = SpecDirectory.summarize(specs);
    return new SpecsResponse(
        project,
        specs.stream().map(spec -> specView(specs, spec)).toList(),
        summaryView(summary.counts()),
        boardSummaryView(summary));
  }

  private SpecResponse specValue(String project, String specId) {
    var loaded = loadRunningProject(project);
    var workspace = workspace(loaded);
    var specs = readSpecs(workspace);
    var spec = SpecDirectory.findById(specs, specId);
    if (spec == null) {
      throw new ApiException(ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found.");
    }
    var content = readSpecBody(workspace, specId);
    return new SpecResponse(
        project,
        specView(specs, spec),
        workspace.specMarkdownPath(specId),
        content != null,
        content);
  }

  private SpecSyncResponse specSyncStatusValue(String project) {
    var loaded = loadRunningProject(project);
    try {
      return specSyncResponse(project, specSync(loaded).status());
    } catch (Exception e) {
      throw new ApiException(ErrorCode.SPEC_SYNC_FAILED, "Failed to read spec sync status.", e);
    }
  }

  private SpecSyncResponse specSyncValue(String project, SpecSyncRequest request) {
    var loaded = loadRunningProject(project);
    NameValidator.requireValidGitRef(request.branch(), "branch");
    try {
      var sync = specSync(loaded);
      return switch (request.operation().toLowerCase()) {
        case "status" -> specSyncResponse(project, sync.status());
        case "pull" -> specSyncResponse(project, sync.pull());
        case "push" -> specSyncResponse(project, sync.push());
        case "init" -> specSyncResponse(project, sync.init(request.remote(), request.branch()));
        default ->
            throw new ApiException(
                ErrorCode.INVALID_REQUEST,
                "Unknown spec sync operation: " + request.operation() + ".",
                "Use status, pull, push, or init.");
      };
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(ErrorCode.SPEC_SYNC_FAILED, "Failed to synchronize specs.", e);
    }
  }

  private DispatchResponse dispatchValue(String project, DispatchRequest request) {
    var loaded = loadRunningProject(project);
    if (!request.mode().equals("background") && !request.mode().equals("foreground")) {
      throw new ApiException(
          ErrorCode.INVALID_MODE, "Dispatch mode must be background or foreground.");
    }

    var agentSession = new AgentSession(shell);
    var existing = querySession(agentSession, project);
    if (existing != null && existing.running()) {
      throw new ApiException(
          ErrorCode.AGENT_ALREADY_RUNNING,
          "Agent is already running for project '" + project + "'.",
          "Stop the active agent before dispatching another spec.");
    }

    var workspace = workspace(loaded);
    var specs = readSpecs(workspace);
    var nextSpec = resolveSpec(specs, request.specId());
    if (nextSpec == null) {
      return new DispatchResponse(project, false, "no_pending_specs", null, null, "", false);
    }

    var targetRepos = DispatchRepos.resolve(loaded.config(), nextSpec, request.repos());
    var taskSpec = withTargetRepos(nextSpec, targetRepos);
    updateStatus(workspace, nextSpec.id(), "in_progress");
    var specBody = Objects.requireNonNullElse(readSpecBody(workspace, nextSpec.id()), "");
    var task = buildTaskPrompt(taskSpec, specBody.isBlank() ? nextSpec.title() : specBody);
    var agentType = taskSpec.agent() != null ? taskSpec.agent() : loaded.config().agent().type();
    var branch = branchName(loaded.config(), nextSpec);
    publishDispatched(project, nextSpec.id(), agentType, branch, request.mode());
    var snapshot = createSnapshotIfNeeded(project, loaded.config());
    if (!snapshot.isEmpty()) {
      publishSnapshotCreated(project, snapshot);
    }
    var branchCreated = createBranchIfNeeded(project, loaded.config(), targetRepos, branch);

    if (!request.dryRun()) {
      launchAgent(
          project, loaded.config(), targetRepos, task, branch, request.mode(), taskSpec, agentType);
      var status = querySession(agentSession, project);
      if (status != null && status.running()) {
        publishAgentSessionStarted(project, nextSpec.id(), agentType, status.pid());
      }
      return new DispatchResponse(
          project,
          true,
          null,
          dispatchedSpecView(taskSpec, branch),
          agentStatusView(agentType, request.mode(), status),
          snapshot,
          branchCreated);
    }

    return new DispatchResponse(
        project,
        true,
        null,
        dispatchedSpecView(taskSpec, branch),
        agentStatusView(agentType, request.mode(), null),
        snapshot,
        branchCreated);
  }

  private void publishDispatched(
      String project, String specId, String agentType, String branch, String mode) {
    if (eventBus == null) {
      return;
    }
    var data = new LinkedHashMap<String, Object>();
    if (branch != null && !branch.isBlank()) {
      data.put("branch", branch);
    }
    data.put("mode", mode);
    eventBus.publish(
        Event.of(
            project,
            specId,
            Event.WellKnownTypes.SPEC_DISPATCHED,
            Event.SAIL_AGENT,
            HostInfo.hostname(),
            data));
  }

  private void publishSnapshotCreated(String project, String label) {
    if (eventBus == null) {
      return;
    }
    eventBus.publish(
        Event.of(
            project,
            null,
            Event.WellKnownTypes.SNAPSHOT_CREATED,
            Event.SAIL_AGENT,
            HostInfo.hostname(),
            Map.of("label", label)));
  }

  private void publishAgentSessionStarted(
      String project, String specId, String agentType, Integer pid) {
    if (eventBus == null) {
      return;
    }
    var data = new LinkedHashMap<String, Object>();
    if (pid != null) {
      data.put("pid", pid);
    }
    eventBus.publish(
        Event.of(
            project,
            specId,
            Event.WellKnownTypes.AGENT_SESSION_STARTED,
            agentType,
            HostInfo.hostname(),
            data));
  }

  private AgentStatusResponse agentStatusValue(String project) {
    requireProjectExists(project);
    var info = querySession(new AgentSession(shell), project);
    return new AgentStatusResponse(
        project,
        info != null && info.running(),
        info != null ? info.pid() : null,
        info != null ? info.task() : null,
        info != null ? info.startedAt() : null,
        info != null ? info.branch() : null,
        info != null ? info.logPath() : null);
  }

  private AgentLogResponse agentLogValue(String project, int tail) {
    requireProjectExists(project);
    var cmd =
        ContainerExec.asDevUser(
            project, List.of("tail", "-n", String.valueOf(tail), AgentSession.logPath()));
    var result = exec(cmd);
    if (!result.ok()) {
      if (result.stderr().contains("No such file")) {
        return new AgentLogResponse(project, List.of(), "No agent log found");
      }
      throw new ApiException(ErrorCode.AGENT_LOG_FAILED, "Failed to read agent log.");
    }
    var lines = Arrays.stream(result.stdout().split("\n")).filter(line -> !line.isEmpty()).toList();
    return new AgentLogResponse(project, lines, null);
  }

  private StopAgentResponse stopAgentValue(String project) {
    requireProjectExists(project);
    var agentSession = new AgentSession(shell);
    var info = querySession(agentSession, project);
    if (info == null || !info.running()) {
      return new StopAgentResponse(project, false, "no_agent_running", null);
    }
    try {
      agentSession.killAgent(project);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_STOP_FAILED, "Failed to stop agent.", e);
    }
    return new StopAgentResponse(project, true, null, info.pid());
  }

  private AgentReportResponse agentReportValue(String project) {
    var loaded = loadProject(project);
    try {
      return agentReportView(new AgentReporter(shell).generate(project, loaded.config()));
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_REPORT_FAILED, "Failed to generate agent report.", e);
    }
  }

  private static List<Spec> readSpecs(SpecWorkspace workspace) {
    try {
      return workspace.readSpecs();
    } catch (Exception e) {
      throw new ApiException(ErrorCode.SPECS_READ_FAILED, "Failed to read spec metadata.", e);
    }
  }

  private static String readSpecBody(SpecWorkspace workspace, String specId) {
    try {
      return workspace.readSpecBody(specId);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.SPEC_READ_FAILED, "Failed to read spec content.", e);
    }
  }

  private static void updateStatus(SpecWorkspace workspace, String specId, String status) {
    try {
      workspace.updateStatus(specId, status);
    } catch (Exception e) {
      throw new ApiException(
          ErrorCode.SPEC_STATUS_UPDATE_FAILED, "Failed to update spec status.", e);
    }
  }

  private static String statusName(ContainerState state) {
    return switch (state) {
      case ContainerState.Running ignored -> "running";
      case ContainerState.Stopped ignored -> "stopped";
      case ContainerState.NotCreated ignored -> "not_created";
      case ContainerState.Error ignored -> "error";
    };
  }

  private LoadedProject loadRunningProject(String project) {
    var loaded = loadProject(project);
    switch (loaded.state()) {
      case ContainerState.Running ignored -> {
        return loaded;
      }
      case ContainerState.Stopped ignored ->
          throw new ApiException(
              ErrorCode.PROJECT_STOPPED,
              "Project '" + project + "' is stopped.",
              "Start it with sail project start " + project + ".");
      case ContainerState.NotCreated ignored ->
          throw new ApiException(
              ErrorCode.PROJECT_NOT_CREATED, "Project '" + project + "' does not exist.");
      case ContainerState.Error error ->
          throw new ApiException(ErrorCode.CONTAINER_ERROR, error.message());
    }
  }

  private LoadedProject loadProject(String project) {
    var singYamlPath = SailPaths.resolveSailYaml(project, file);
    if (!Files.exists(singYamlPath)) {
      throw new ApiException(
          ErrorCode.PROJECT_DESCRIPTOR_NOT_FOUND,
          "Project descriptor was not found: " + singYamlPath.toAbsolutePath());
    }
    try {
      var config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));
      var state = new ContainerManager(shell).queryState(project);
      return new LoadedProject(config, state);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.PROJECT_LOAD_FAILED, "Failed to load project.", e);
    }
  }

  private void requireProjectExists(String project) {
    var state = loadProject(project).state();
    if (state instanceof ContainerState.NotCreated) {
      throw new ApiException(
          ErrorCode.PROJECT_NOT_CREATED, "Project '" + project + "' does not exist.");
    }
    if (state instanceof ContainerState.Error error) {
      throw new ApiException(ErrorCode.CONTAINER_ERROR, error.message());
    }
  }

  private GitSpecSync specSync(LoadedProject loaded) {
    var specsDir = specsDir(loaded.config());
    return new GitSpecSync(
        shell, ContainerExec.asDevUser(loaded.config().name(), List.of("git", "-C", specsDir)));
  }

  private SpecWorkspace workspace(LoadedProject loaded) {
    return new SpecWorkspace(shell, loaded.config().name(), specsDir(loaded.config()));
  }

  private static String specsDir(SailYaml config) {
    if (config.agent() == null || config.agent().specsDir() == null) {
      throw new ApiException(
          ErrorCode.SPECS_NOT_CONFIGURED,
          "No specs_dir configured in the project agent block.",
          "Add specs_dir to sail.yaml.");
    }
    NameValidator.requireSafePath(config.agent().specsDir(), "agent.specs_dir");
    NameValidator.requireValidSshUser(config.sshUser());
    return "/home/" + config.sshUser() + "/workspace/" + config.agent().specsDir();
  }

  private static Spec resolveSpec(List<Spec> specs, String specId) {
    if (specId == null || specId.isBlank()) {
      return SpecDirectory.nextReady(specs);
    }
    var spec = SpecDirectory.findById(specs, specId);
    if (spec == null) {
      throw new ApiException(ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found.");
    }
    if (!SpecDirectory.isReady(specs, spec)) {
      throw new ApiException(
          ErrorCode.SPEC_NOT_READY,
          "Spec '" + specId + "' is not ready for dispatch.",
          "Resolve dependencies or choose a ready spec.");
    }
    return spec;
  }

  private static SpecView specView(List<Spec> specs, Spec spec) {
    return new SpecView(
        spec.id(),
        spec.title(),
        spec.status(),
        spec.assignee(),
        spec.dependsOn(),
        spec.repos(),
        spec.agent(),
        spec.model(),
        spec.reasoningEffort(),
        spec.branch(),
        SpecDirectory.isReady(specs, spec),
        SpecDirectory.isBlocked(specs, spec),
        SpecDirectory.unmetDependencies(specs, spec));
  }

  private static SpecSyncResponse specSyncResponse(String project, GitSpecSync.Status status) {
    return new SpecSyncResponse(
        project, null, false, status.message(), specSyncStatusView(status), null);
  }

  private static SpecSyncResponse specSyncResponse(
      String project, GitSpecSync.OperationResult result) {
    return new SpecSyncResponse(
        project,
        result.operation(),
        result.changed(),
        result.message(),
        specSyncStatusView(result.after()),
        specSyncStatusView(result.before()));
  }

  private static SpecSyncStatusView specSyncStatusView(GitSpecSync.Status status) {
    return new SpecSyncStatusView(
        status.state(),
        status.branch(),
        status.upstream(),
        status.ahead(),
        status.behind(),
        status.dirty(),
        status.conflicted(),
        status.repository(),
        status.message());
  }

  private static DispatchedSpecView dispatchedSpecView(Spec spec, String branch) {
    return new DispatchedSpecView(
        spec.id(),
        spec.title(),
        "in_progress",
        spec.repos(),
        spec.agent(),
        spec.model(),
        spec.reasoningEffort(),
        branch != null && !branch.isBlank() ? branch : null);
  }

  private static Spec withTargetRepos(Spec spec, List<SailYaml.Repo> targetRepos) {
    return new Spec(
        spec.id(),
        spec.title(),
        spec.status(),
        spec.assignee(),
        spec.dependsOn(),
        targetRepos.stream().map(SailYaml.Repo::path).toList(),
        spec.agent(),
        spec.model(),
        spec.reasoningEffort(),
        spec.branch());
  }

  private static String buildTaskPrompt(Spec spec, String description) {
    var targetRepos =
        spec.repos().isEmpty()
            ? ""
            : "\nTarget repo"
                + (spec.repos().size() == 1 ? "" : "s")
                + ": "
                + String.join(", ", spec.repos())
                + "\n";
    var targetAgent = spec.agent() == null ? "" : "\nTarget agent: " + spec.agent() + "\n";
    var targetModel = spec.model() == null ? "" : "\nTarget model: " + spec.model() + "\n";
    var targetReasoning =
        spec.reasoningEffort() == null
            ? ""
            : "\nTarget reasoning effort: " + spec.reasoningEffort() + "\n";
    return "Your current spec: \""
        + spec.title()
        + "\" (id: "
        + spec.id()
        + ")."
        + targetRepos
        + targetAgent
        + targetModel
        + targetReasoning
        + "\n"
        + description;
  }

  private static String branchName(SailYaml config, Spec spec) {
    return BranchPolicy.branchName(config, spec);
  }

  private String createSnapshotIfNeeded(String project, SailYaml config) {
    if (config.agent() == null || !config.agent().autoSnapshot()) {
      return "";
    }
    var snapMgr = new SnapshotManager(shell);
    if (!shouldSnapshot(snapMgr, project)) {
      return "";
    }
    var label = SnapshotManager.defaultLabel();
    try {
      snapMgr.create(project, label);
      return label;
    } catch (Exception e) {
      throw new ApiException(ErrorCode.SNAPSHOT_FAILED, "Failed to create dispatch snapshot.", e);
    }
  }

  private boolean createBranchIfNeeded(
      String project, SailYaml config, List<SailYaml.Repo> targetRepos, String branch) {
    if (branch == null || branch.isBlank() || targetRepos.isEmpty()) {
      return false;
    }
    var created = false;
    for (var repo : targetRepos) {
      var repoDir = "/home/" + config.sshUser() + "/workspace/" + repo.path();
      var repoExists =
          exec(ContainerExec.asDevUser(project, List.of("test", "-d", repoDir + "/.git")));
      if (!repoExists.ok()) {
        continue;
      }
      var result =
          exec(
              ContainerExec.asDevUser(
                  project, List.of("git", "-C", repoDir, "checkout", "-b", branch)));
      if (!result.ok()) {
        throw new ApiException(
            ErrorCode.BRANCH_CREATE_FAILED,
            "Failed to create branch '" + branch + "' in repo '" + repo.path() + "'.");
      }
      created = true;
    }
    return created;
  }

  private void launchAgent(
      String project,
      SailYaml config,
      List<SailYaml.Repo> targetRepos,
      String task,
      String branch,
      String mode,
      Spec spec,
      String agentType) {
    try {
      ensureSailSetup(project);
      var session = new AgentSession(shell);
      session.ensureDirectory(project);
      session.writeTaskFile(project, task);
      session.writeSession(project, task, Objects.requireNonNullElse(branch, ""));
      var agentCli = AgentCli.fromYamlName(agentType);
      var workDir = AgentSession.launchWorkDir(config.sshUser(), targetRepos);
      var command =
          mode.equals("background")
              ? AgentSession.buildBackgroundLaunchCommand(
                  project,
                  config.sshUser(),
                  workDir,
                  true,
                  agentCli,
                  spec.model(),
                  spec.reasoningEffort(),
                  spec.id(),
                  agentType)
              : AgentSession.buildForegroundTaskCommand(
                  project,
                  config.sshUser(),
                  workDir,
                  true,
                  agentCli,
                  spec.model(),
                  spec.reasoningEffort(),
                  spec.id(),
                  agentType);
      var result = exec(command);
      if (!result.ok()) {
        throw new ApiException(ErrorCode.AGENT_LAUNCH_FAILED, "Failed to launch agent.");
      }
      launchWatcherIfGuardrails(project, config);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_LAUNCH_FAILED, "Failed to launch agent.", e);
    }
  }

  private void ensureSailSetup(String project) {
    try {
      ContainerSailSetup.ensureInstalled(shell, project);
    } catch (Exception e) {
      System.err.println(
          "  [api] Warning: failed to backfill sail event helpers in "
              + project
              + ": "
              + e.getMessage());
    }
  }

  private void launchWatcherIfGuardrails(String project, SailYaml config) throws IOException {
    if (config.agent() == null || config.agent().guardrails() == null) {
      return;
    }
    var cmd =
        List.of(
            "nohup",
            SailPaths.binaryPath().toString(),
            "agent",
            "watch",
            project,
            "-f",
            SailPaths.resolveSailYaml(project, file).toAbsolutePath().toString());
    var watchLog = SailPaths.projectDir(project).resolve("watch.log");
    Files.createDirectories(watchLog.getParent());
    watcherLauncher.launch(cmd, watchLog);
  }

  static void launchWatcherProcess(List<String> command, Path logPath) throws IOException {
    new ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.to(logPath.toFile()))
        .redirectErrorStream(true)
        .start();
  }

  private static boolean shouldSnapshot(SnapshotManager snapMgr, String project) {
    try {
      var snapshots = snapMgr.list(project);
      if (snapshots.isEmpty()) {
        return true;
      }
      var latestTime = OffsetDateTime.parse(snapshots.getLast().createdAt()).toInstant();
      return Instant.now().isAfter(latestTime.plus(SNAPSHOT_INTERVAL));
    } catch (Exception ignored) {
      return true;
    }
  }

  private AgentSession.SessionInfo querySession(AgentSession session, String project) {
    try {
      return session.queryStatus(project);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_STATUS_FAILED, "Failed to query agent status.", e);
    }
  }

  private static AgentConfigView agentConfigView(SailYaml config) {
    var agent = config.agent();
    return new AgentConfigView(
        agent.type(), agent.autoSnapshot(), agent.autoBranch(), agent.specsDir());
  }

  private static AgentStatusView agentStatusView(
      String agentType, String mode, AgentSession.SessionInfo info) {
    return new AgentStatusView(
        agentType,
        mode,
        info != null && info.running(),
        info != null ? info.pid() : null,
        info != null ? info.task() : null,
        info != null ? info.startedAt() : null,
        info != null ? info.branch() : null,
        info != null ? info.logPath() : null);
  }

  private static AgentReportResponse agentReportView(AgentReporter.Report report) {
    return new AgentReportResponse(
        report.name(),
        report.sessionStatus(),
        report.startedAt(),
        report.endedAt(),
        report.duration(),
        report.branch(),
        report.specs().stream().map(spec -> specView(report.specs(), spec)).toList(),
        report.commitCount(),
        report.lastCommitMinutesAgo() >= 0 ? report.lastCommitMinutesAgo() : null,
        report.guardrailTriggered(),
        report.guardrailReason(),
        report.guardrailAction(),
        report.rolledBack(),
        report.rollbackSnapshot());
  }

  private static SpecSummaryView summaryView(java.util.Map<String, Integer> counts) {
    return new SpecSummaryView(
        counts.getOrDefault("pending", 0),
        counts.getOrDefault("in_progress", 0),
        counts.getOrDefault("review", 0),
        counts.getOrDefault("done", 0));
  }

  private static BoardSummaryView boardSummaryView(SpecDirectory.Summary summary) {
    return new BoardSummaryView(
        summaryView(summary.counts()),
        summary.readyCount(),
        summary.blockedCount(),
        summary.nextReadyId());
  }

  private ShellExec.Result exec(List<String> command) {
    try {
      return shell.exec(command);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, "A sail system command failed.", e);
    }
  }

  @Override
  public Result<EventPublishResponse> publishEvent(Event event) {
    if (eventBus == null) {
      return Result.failure(
          ErrorCode.INTERNAL,
          "Event bus is not wired into this SailApiOperations instance.",
          "Use the SailApiOperations constructor that accepts an EventBus.");
    }
    return safe(
        () -> {
          var stamped = eventBus.publish(event);
          return new EventPublishResponse(stamped.id(), stamped.toMap());
        });
  }

  @Override
  public Result<RecentEventsResponse> recentEvents(int limit) {
    if (limit <= 0 || limit > 5000) {
      return Result.failure(
          ErrorCode.INVALID_REQUEST, "limit must be between 1 and 5000, got " + limit);
    }
    if (auditPersister == null) {
      return Result.success(new RecentEventsResponse(limit, 0, List.of()));
    }
    return safe(
        () -> {
          var events = auditPersister.recent(limit);
          var maps = events.stream().map(Event::toMap).toList();
          return new RecentEventsResponse(limit, maps.size(), maps);
        });
  }

  @Override
  public Result<EventBusStatsResponse> eventBusStats() {
    if (eventBus == null) {
      return Result.success(new EventBusStatsResponse(0L, 0L, List.of()));
    }
    return safe(
        () -> {
          var stats = eventBus.stats();
          var subs =
              stats.subscribers().stream()
                  .map(s -> new SubscriberStatsView(s.name(), s.capacity(), s.depth(), s.dropped()))
                  .toList();
          return new EventBusStatsResponse(stats.published(), stats.rejectedSubscribers(), subs);
        });
  }

  private static <T> Result<T> safe(Supplier<T> supplier) {
    try {
      return Result.success(supplier.get());
    } catch (ApiException e) {
      return e.failure().asFailure();
    } catch (Exception e) {
      return Result.failure(ErrorCode.INTERNAL, "sail API operation failed.", e);
    }
  }

  @Override
  public Result<GlobalSpecsListResponse> globalSpecs(SpecStore.SpecFilter filter) {
    return safe(
        () -> {
          requireSpecStore();
          var specs = specStore.list(filter).stream().map(GlobalSpecView::from).toList();
          return new GlobalSpecsListResponse(specs, specs.size());
        });
  }

  @Override
  public Result<GlobalSpecDetailResponse> globalSpec(String specId) {
    return safe(
        () -> {
          requireSpecStore();
          var row =
              specStore
                  .findById(specId)
                  .orElseThrow(
                      () ->
                          new ApiException(
                              ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
          var content = specStore.getContent(specId).orElse(null);
          return new GlobalSpecDetailResponse(
              GlobalSpecView.from(row),
              content != null ? content.body() : null,
              content != null ? content.plan() : null);
        });
  }

  @Override
  public Result<GlobalSpecCreatedResponse> createGlobalSpec(SpecCreateRequest request) {
    return safe(
        () -> {
          requireSpecStore();
          if (request.id() == null || request.id().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "spec id is required.");
          }
          if (request.title() == null || request.title().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "spec title is required.");
          }
          NameValidator.requireValidSpecId(request.id());
          var row =
              new SpecStore.SpecRow(
                  request.id(),
                  request.title(),
                  request.status(),
                  request.assignee(),
                  request.agent(),
                  request.model(),
                  request.reasoningEffort(),
                  request.branch(),
                  request.priority(),
                  null,
                  "",
                  "",
                  request.dependsOn(),
                  request.repos());
          specStore.create(row);
          if (request.body() != null || request.plan() != null) {
            specStore.setContent(
                request.id(),
                Objects.requireNonNullElse(request.body(), ""),
                Objects.requireNonNullElse(request.plan(), ""));
          }
          var created = specStore.findById(request.id()).orElseThrow();
          return new GlobalSpecCreatedResponse(GlobalSpecView.from(created));
        });
  }

  @Override
  public Result<GlobalSpecUpdatedResponse> updateGlobalSpec(
      String specId, SpecUpdateRequest request) {
    return safe(
        () -> {
          requireSpecStore();
          var existing =
              specStore
                  .findById(specId)
                  .orElseThrow(
                      () ->
                          new ApiException(
                              ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
          var updated =
              new SpecStore.SpecRow(
                  specId,
                  request.title() != null ? request.title() : existing.title(),
                  request.status() != null ? request.status() : existing.status(),
                  request.assignee() != null ? request.assignee() : existing.assignee(),
                  request.agent() != null ? request.agent() : existing.agent(),
                  request.model() != null ? request.model() : existing.model(),
                  request.reasoningEffort() != null
                      ? request.reasoningEffort()
                      : existing.reasoningEffort(),
                  request.branch() != null ? request.branch() : existing.branch(),
                  request.priority() != null ? request.priority() : existing.priority(),
                  existing.createdBy(),
                  existing.createdAt(),
                  existing.updatedAt(),
                  request.dependsOn() != null ? request.dependsOn() : existing.dependsOn(),
                  request.repos() != null ? request.repos() : existing.repos());
          specStore.update(updated);
          var result = specStore.findById(specId).orElseThrow();
          return new GlobalSpecUpdatedResponse(GlobalSpecView.from(result));
        });
  }

  @Override
  public Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId) {
    return safe(
        () -> {
          requireSpecStore();
          specStore
              .findById(specId)
              .orElseThrow(
                  () ->
                      new ApiException(
                          ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
          specStore.delete(specId);
          return new GlobalSpecDeletedResponse(specId);
        });
  }

  @Override
  public Result<GlobalSpecContentResponse> globalSpecContent(String specId) {
    return safe(
        () -> {
          requireSpecStore();
          specStore
              .findById(specId)
              .orElseThrow(
                  () ->
                      new ApiException(
                          ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
          var content = specStore.getContent(specId).orElse(new SpecStore.SpecContent("", "", ""));
          return new GlobalSpecContentResponse(specId, content.body(), content.plan());
        });
  }

  @Override
  public Result<GlobalSpecContentResponse> setGlobalSpecContent(
      String specId, SpecContentRequest request) {
    return safe(
        () -> {
          requireSpecStore();
          specStore
              .findById(specId)
              .orElseThrow(
                  () ->
                      new ApiException(
                          ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found."));
          specStore.setContent(
              specId,
              Objects.requireNonNullElse(request.body(), ""),
              Objects.requireNonNullElse(request.plan(), ""));
          var content = specStore.getContent(specId).orElseThrow();
          return new GlobalSpecContentResponse(specId, content.body(), content.plan());
        });
  }

  @Override
  public Result<GlobalBoardResponse> globalBoard() {
    return safe(
        () -> {
          requireSpecStore();
          return new GlobalBoardResponse(specStore.board());
        });
  }

  private void requireSpecStore() {
    if (specStore == null) {
      throw new ApiException(
          ErrorCode.INTERNAL,
          "Spec store not available. Start the server with 'sail server start'.");
    }
  }

  private record LoadedProject(SailYaml config, ContainerState state) {}

  @FunctionalInterface
  interface WatcherLauncher {
    void launch(List<String> command, Path logPath) throws IOException;
  }
}
