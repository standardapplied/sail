/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.BranchPolicy;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecDirectory;
import ai.singlr.sail.config.SpecStatus;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentCli;
import ai.singlr.sail.engine.AgentReporter;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentTaskPrompt;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.DispatchRepos;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.SessionStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
  private final ReviewStore reviewStore;
  private final SessionStore sessionStore;
  private final GlobalSpecOperations globalSpecOps;
  private final ReviewOperations reviewOps;

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
    this(
        shell, file, SailApiOperations::launchWatcherProcess, eventBus, auditPersister, null, null);
  }

  /** Construct with database-backed stores; used by the control plane server. */
  public SailApiOperations(
      ShellExec shell,
      String file,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      SpecStore specStore,
      ReviewStore reviewStore) {
    this(
        shell,
        file,
        SailApiOperations::launchWatcherProcess,
        eventBus,
        auditSubscriber instanceof AuditPersister ap ? ap : null,
        specStore,
        reviewStore);
  }

  /** Construct with database-backed spec store (no review store). */
  public SailApiOperations(
      ShellExec shell,
      String file,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      SpecStore specStore) {
    this(shell, file, eventBus, auditSubscriber, specStore, null);
  }

  SailApiOperations(
      ShellExec shell,
      String file,
      WatcherLauncher watcherLauncher,
      EventBus eventBus,
      AuditPersister auditPersister) {
    this(shell, file, watcherLauncher, eventBus, auditPersister, null, null);
  }

  SailApiOperations(
      ShellExec shell,
      String file,
      WatcherLauncher watcherLauncher,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore) {
    this(shell, file, watcherLauncher, eventBus, auditPersister, specStore, reviewStore, null);
  }

  SailApiOperations(
      ShellExec shell,
      String file,
      WatcherLauncher watcherLauncher,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore,
      SessionStore sessionStore) {
    this.shell = shell;
    this.file = file;
    this.watcherLauncher = watcherLauncher;
    this.eventBus = eventBus;
    this.auditPersister = auditPersister;
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.sessionStore = sessionStore;
    this.globalSpecOps = new GlobalSpecOperations(specStore);
    this.reviewOps = new ReviewOperations(reviewStore, specStore);
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
    requireProjectExists(project);
    var specs = specStore.projectSpecs(project);
    var summary = SpecDirectory.summarize(specs);
    return new SpecsResponse(
        project,
        specs.stream().map(spec -> specView(specs, spec)).toList(),
        summaryView(summary.counts()),
        boardSummaryView(summary));
  }

  private SpecResponse specValue(String project, String specId) {
    requireProjectExists(project);
    var specs = specStore.projectSpecs(project);
    var spec = SpecDirectory.findById(specs, specId);
    if (spec == null) {
      throw new ApiException(ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found.");
    }
    var content =
        specStore
            .getContent(specId)
            .map(SpecStore.SpecContent::body)
            .filter(body -> !body.isBlank())
            .orElse(null);
    return new SpecResponse(project, specView(specs, spec), null, content != null, content);
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

    var specs = specStore.projectSpecs(project);
    var nextSpec = resolveSpec(specs, request.specId());
    if (nextSpec == null) {
      return new DispatchResponse(project, false, "no_pending_specs", null, null, "", false);
    }

    var targetRepos = DispatchRepos.resolve(loaded.config(), nextSpec, request.repos());
    var taskSpec = DispatchRepos.withTargetRepos(nextSpec, targetRepos);
    specStore.updateStatus(nextSpec.id(), SpecStatus.IN_PROGRESS);
    var specBody = specStore.getContent(nextSpec.id()).map(SpecStore.SpecContent::body).orElse("");
    var task = AgentTaskPrompt.build(taskSpec, specBody.isBlank() ? nextSpec.title() : specBody);
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
    if (Strings.isNotBlank(branch)) {
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

  private static Spec resolveSpec(List<Spec> specs, String specId) {
    if (Strings.isBlank(specId)) {
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
        spec.status().wire(),
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

  private static DispatchedSpecView dispatchedSpecView(Spec spec, String branch) {
    return new DispatchedSpecView(
        spec.id(),
        spec.title(),
        "in_progress",
        spec.repos(),
        spec.agent(),
        spec.model(),
        spec.reasoningEffort(),
        Strings.isNotBlank(branch) ? branch : null);
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
    if (Strings.isBlank(branch) || targetRepos.isEmpty()) {
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
      return DateTimeUtils.now().isAfter(latestTime.plus(SNAPSHOT_INTERVAL));
    } catch (Exception e) {
      System.err.println(
          "  [snapshot] Could not determine snapshot age for '"
              + project
              + "', taking one to be safe: "
              + e.getMessage());
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

  private static SpecSummaryView summaryView(Map<String, Integer> counts) {
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
    return safe(() -> globalSpecOps.list(filter));
  }

  @Override
  public Result<GlobalSpecDetailResponse> globalSpec(String specId) {
    return safe(() -> globalSpecOps.get(specId));
  }

  @Override
  public Result<GlobalSpecCreatedResponse> createGlobalSpec(SpecCreateRequest request) {
    return safe(() -> globalSpecOps.create(request));
  }

  @Override
  public Result<GlobalSpecUpdatedResponse> updateGlobalSpec(
      String specId, SpecUpdateRequest request) {
    return safe(() -> globalSpecOps.update(specId, request));
  }

  @Override
  public Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId) {
    return safe(() -> globalSpecOps.delete(specId));
  }

  @Override
  public Result<GlobalSpecContentResponse> globalSpecContent(String specId) {
    return safe(() -> globalSpecOps.content(specId));
  }

  @Override
  public Result<GlobalSpecContentResponse> setGlobalSpecContent(
      String specId, SpecContentRequest request) {
    return safe(() -> globalSpecOps.setContent(specId, request));
  }

  @Override
  public Result<GlobalSpecHistoryResponse> globalSpecHistory(String specId) {
    return safe(() -> globalSpecOps.history(specId));
  }

  @Override
  public Result<GlobalSpecRestoredResponse> restoreGlobalSpec(
      String specId, SpecRestoreRequest request) {
    return safe(() -> globalSpecOps.restore(specId, request));
  }

  @Override
  public Result<GlobalBoardResponse> globalBoard(String project) {
    return safe(() -> globalSpecOps.board(project));
  }

  @Override
  public Result<ReviewListResponse> reviewsForSpec(String specId) {
    return safe(() -> reviewOps.listForSpec(specId));
  }

  @Override
  public Result<ReviewDetailResponse> reviewDetail(String reviewId) {
    return safe(() -> reviewOps.detail(reviewId));
  }

  @Override
  public Result<ReviewApproveResponse> approveReview(String reviewId, String actor) {
    return safe(() -> reviewOps.approve(reviewId, actor));
  }

  @Override
  public Result<FindingDismissResponse> dismissFinding(String reviewId, String findingId) {
    return safe(() -> reviewOps.dismissFinding(reviewId, findingId));
  }

  @Override
  public Result<SessionListResponse> agentSessions(String project) {
    return safe(
        () -> {
          if (sessionStore == null) {
            throw new ApiException(
                ErrorCode.INTERNAL,
                "Session store not available. Start the server with 'sail server start'.");
          }
          var sessions =
              sessionStore.listForProject(project).stream().map(SessionView::from).toList();
          return new SessionListResponse(project, sessions);
        });
  }

  private record LoadedProject(SailYaml config, ContainerState state) {}

  @FunctionalInterface
  interface WatcherLauncher {
    void launch(List<String> command, Path logPath) throws IOException;
  }
}
