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
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ConnectEnvironment;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.DispatchRepos;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.RunRetention;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SnapshotManager;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

public final class SailApiOperations implements ApiOperations {

  private static final Duration SNAPSHOT_INTERVAL = Duration.ofHours(24);

  private final ShellExec shell;
  private final String file;
  private final WatcherSpawner watcherSpawner;
  private final EventBus eventBus;
  private final AuditPersister auditPersister;
  private final SpecStore specStore;
  private final ReviewStore reviewStore;
  private final RunStore sessionStore;
  private final ProjectStore projectStore;
  private final Supplier<ConnectEnvironment> connectEnvironment;
  private final SyncScheduler syncScheduler;
  private final GlobalSpecOperations globalSpecOps;
  private final ReviewOperations reviewOps;

  public SailApiOperations() {
    this(new ShellExecutor(false), SailPaths.PROJECT_DESCRIPTOR);
  }

  public SailApiOperations(ShellExec shell, String file) {
    this(shell, file, WatcherSpawner::spawnProcess);
  }

  SailApiOperations(ShellExec shell, String file, WatcherSpawner.ProcessSpawner watcherFallback) {
    this(shell, file, watcherFallback, null, null);
  }

  /** Construct with explicit event-bus wiring; used by {@link SailApiServer}. */
  public SailApiOperations(
      ShellExec shell, String file, EventBus eventBus, AuditPersister auditPersister) {
    this(shell, file, WatcherSpawner::spawnProcess, eventBus, auditPersister, null, null);
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
        WatcherSpawner::spawnProcess,
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

  /** Construct with the project catalog included; used by {@code sail server start}. */
  public SailApiOperations(
      ShellExec shell,
      String file,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      SpecStore specStore,
      ReviewStore reviewStore,
      ProjectStore projectStore) {
    this(
        shell,
        file,
        eventBus,
        auditSubscriber,
        specStore,
        reviewStore,
        projectStore,
        SyncScheduler.disabled());
  }

  /**
   * As {@link #SailApiOperations(ShellExec, String, EventBus, EventSubscriber, SpecStore,
   * ReviewStore, ProjectStore)} with the node's sync-on-write scheduler; used by {@code sail server
   * start} so spec mutations propagate to main and stale reads freshen without a manual {@code sail
   * sync}.
   */
  public SailApiOperations(
      ShellExec shell,
      String file,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      SpecStore specStore,
      ReviewStore reviewStore,
      ProjectStore projectStore,
      SyncScheduler syncScheduler) {
    this(
        shell,
        file,
        WatcherSpawner::spawnProcess,
        eventBus,
        auditSubscriber instanceof AuditPersister ap ? ap : null,
        specStore,
        reviewStore,
        null,
        projectStore,
        ConnectEnvironment::detect,
        syncScheduler);
  }

  SailApiOperations(
      ShellExec shell,
      String file,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister) {
    this(shell, file, watcherFallback, eventBus, auditPersister, null, null);
  }

  SailApiOperations(
      ShellExec shell,
      String file,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore) {
    this(shell, file, watcherFallback, eventBus, auditPersister, specStore, reviewStore, null);
  }

  SailApiOperations(
      ShellExec shell,
      String file,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore sessionStore) {
    this(
        shell,
        file,
        watcherFallback,
        eventBus,
        auditPersister,
        specStore,
        reviewStore,
        sessionStore,
        null,
        ConnectEnvironment::detect,
        SyncScheduler.disabled());
  }

  SailApiOperations(
      ShellExec shell,
      String file,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore sessionStore,
      ProjectStore projectStore,
      Supplier<ConnectEnvironment> connectEnvironment) {
    this(
        shell,
        file,
        watcherFallback,
        eventBus,
        auditPersister,
        specStore,
        reviewStore,
        sessionStore,
        projectStore,
        connectEnvironment,
        SyncScheduler.disabled());
  }

  SailApiOperations(
      ShellExec shell,
      String file,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore sessionStore,
      ProjectStore projectStore,
      Supplier<ConnectEnvironment> connectEnvironment,
      SyncScheduler syncScheduler) {
    this.shell = shell;
    this.file = file;
    this.watcherSpawner = new WatcherSpawner(shell, watcherFallback);
    this.eventBus = eventBus;
    this.auditPersister = auditPersister;
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.sessionStore = sessionStore;
    this.projectStore = projectStore;
    this.connectEnvironment = connectEnvironment;
    this.syncScheduler = syncScheduler;
    this.globalSpecOps = new GlobalSpecOperations(specStore, reviewStore, eventBus, sessionStore);
    this.reviewOps = new ReviewOperations(reviewStore, specStore);
  }

  @Override
  public Result<HealthResponse> health() {
    return Result.success(new HealthResponse("ok"));
  }

  @Override
  public Result<ProjectListResponse> projects() {
    return safe(this::projectsValue);
  }

  @Override
  public Result<ProjectResponse> project(String project) {
    return safe(() -> projectValue(project));
  }

  @Override
  public Result<ConnectResponse> connect(String project) {
    return safe(() -> connectValue(project));
  }

  @Override
  public Result<SpecsResponse> specs(String project) {
    return safeRead(() -> specsValue(project));
  }

  @Override
  public Result<SpecResponse> spec(String project, String specId) {
    return safeRead(() -> specValue(project, specId));
  }

  @Override
  public Result<DispatchResponse> dispatch(
      String project, DispatchRequest request, Actor actor, String localHandle) {
    freshenForRead();
    var result = safe(() -> dispatchValue(project, request, actor, localHandle));
    if (result instanceof Result.Success<DispatchResponse> success
        && success.value().dispatched()) {
      triggerSyncAfterWrite();
    }
    return result;
  }

  @Override
  public Result<AgentStatusResponse> agentStatus(String project) {
    return safe(() -> agentStatusValue(project));
  }

  @Override
  public Result<AgentReportResponse> agentReport(String project) {
    return safe(() -> agentReportValue(project));
  }

  @Override
  public Result<RunListResponse> runs(String project, String spec) {
    return safe(
        () -> {
          syncScheduler.freshenRead();
          var rows = requireRunStore().list(project, spec).stream().map(RunView::from).toList();
          return new RunListResponse(project, spec, rows);
        });
  }

  @Override
  public Result<RunDetailResponse> run(String runId) {
    return safe(
        () -> {
          syncScheduler.freshenRead();
          var run = requireRunStore().findById(runId).orElseThrow(() -> runNotFound(runId));
          return new RunDetailResponse(RunView.from(run));
        });
  }

  @Override
  public Result<RunLogResponse> runLog(String runId, int tail, String localHandle) {
    return onLocalRun(runId, localHandle, run -> safe(() -> runLogValue(run, tail)));
  }

  @Override
  public Result<StopRunResponse> stopRun(String runId, String localHandle) {
    return onLocalRun(runId, localHandle, run -> safe(() -> stopRunValue(run)));
  }

  /**
   * Resolves a run and applies the provenance guard before handing it to {@code served}: an absent
   * store is an internal error, an unknown run a 404, and a run that executed elsewhere a
   * structured {@code run_on_other_node} refusal. The served branch only ever sees a run that ran
   * on this box.
   */
  private <T> Result<T> onLocalRun(
      String runId,
      String localHandle,
      java.util.function.Function<RunStore.RunRow, Result<T>> served) {
    if (sessionStore == null) {
      return Result.failure(
          ErrorCode.INTERNAL,
          "Run store not available. Start the server with 'sail server start'.");
    }
    var run = sessionStore.findById(runId).orElse(null);
    if (run == null) {
      return Result.failure(ErrorCode.RUN_NOT_FOUND, "No run '" + runId + "'.");
    }
    if (isForeign(run, localHandle)) {
      return foreignRun(run);
    }
    return served.apply(run);
  }

  private RunStore requireRunStore() {
    if (sessionStore == null) {
      throw new ApiException(
          ErrorCode.INTERNAL,
          "Run store not available. Start the server with 'sail server start'.");
    }
    return sessionStore;
  }

  private static ApiException runNotFound(String runId) {
    return new ApiException(ErrorCode.RUN_NOT_FOUND, "No run '" + runId + "'.");
  }

  /**
   * Whether a run did not execute on this box — the provenance test. A blank {@code node} fails
   * closed to foreign (refuse, name the problem) rather than being served as if local.
   */
  static boolean isForeign(RunStore.RunRow run, String localHandle) {
    return Strings.isBlank(run.node()) || !run.node().equals(localHandle);
  }

  /**
   * The structured {@code run_on_other_node} refusal: the worst case becomes an honest, actionable
   * no ("logs live on {node}'s box") rather than a foreign box's local file tailed as if it were
   * this run's. The clashing {@code node}/{@code spec}/{@code project} ride as field errors so a
   * client can offer to switch.
   */
  private static <T> Result<T> foreignRun(RunStore.RunRow run) {
    var node = Strings.isBlank(run.node()) ? "an unknown node" : run.node();
    return Result.failure(
        ErrorCode.RUN_ON_OTHER_NODE,
        "Run " + run.id() + " executed on " + node + "; its logs live there, not on this box.",
        List.of(
            new FieldError("node", Objects.toString(run.node(), "")),
            new FieldError("spec", Objects.toString(run.specId(), "")),
            new FieldError("project", Objects.toString(run.project(), ""))));
  }

  private ProjectResponse projectValue(String project) {
    var loaded = loadProject(project);
    var agent = loaded.config().agent() != null ? agentConfigView(loaded.config()) : null;
    return new ProjectResponse(project, statusName(loaded.state()), agent);
  }

  /**
   * The full project roster, mirroring {@code sail project list}: every catalogued project plus any
   * container without a catalog entry. Catalogued-but-unprovisioned projects surface as {@code
   * not_created}; a live container's state wins over the catalog placeholder.
   */
  private ProjectListResponse projectsValue() {
    var statuses = new TreeMap<String, String>();
    if (projectStore != null) {
      for (var row : projectStore.list()) {
        statuses.put(row.name(), statusName(new ContainerState.NotCreated()));
      }
    }
    for (var container : listContainers()) {
      statuses.put(container.name(), statusName(container.state()));
    }
    return new ProjectListResponse(
        statuses.entrySet().stream()
            .map(entry -> new ProjectListItemView(entry.getKey(), entry.getValue()))
            .toList());
  }

  private List<ContainerManager.ContainerInfo> listContainers() {
    try {
      return new ContainerManager(shell).listAll();
    } catch (Exception e) {
      throw new ApiException(ErrorCode.COMMAND_FAILED, "Failed to list project containers.", e);
    }
  }

  private ConnectResponse connectValue(String project) {
    var loaded = loadRunningProject(project);
    var containerIp = ((ContainerState.Running) loaded.state()).ipv4();
    if (containerIp == null) {
      throw new ApiException(
          ErrorCode.CONTAINER_IP_UNAVAILABLE,
          "Project '" + project + "' is running but has no IP address yet.",
          "Wait a moment and retry.");
    }
    var environment = connectEnvironment.get();
    if (Strings.isBlank(environment.serverIp())) {
      throw new ApiException(
          ErrorCode.SERVER_IP_NOT_CONFIGURED,
          "Server IP is not configured on this node.",
          "Run: sudo sail host config set server-ip <your-server-ip>");
    }
    return new ConnectResponse(
        project,
        environment.serverIp(),
        environment.serverUser(),
        containerIp,
        loaded.config().sshUser(),
        environment.workstationKeySet());
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

  private DispatchResponse dispatchValue(
      String project, DispatchRequest request, Actor actor, String localHandle) {
    var loaded = loadRunningProject(project);
    if (!request.mode().equals("background") && !request.mode().equals("foreground")) {
      throw new ApiException(
          ErrorCode.INVALID_MODE, "Dispatch mode must be background or foreground.");
    }
    if (Strings.isBlank(localHandle)) {
      throw refusal(DispatchPolicy.nodeHandleUnset());
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
    var nextSpec = resolveSpec(specs, request.specId(), localHandle);
    if (nextSpec == null) {
      return new DispatchResponse(project, false, "no_pending_specs", null, null, "", false);
    }
    if (DispatchPolicy.check(actor, nextSpec, localHandle)
        instanceof DispatchDecision.Refused refused) {
      throw refusal(refused);
    }

    var targetRepos = DispatchRepos.resolve(loaded.config(), nextSpec, request.repos());
    var taskSpec = DispatchRepos.withTargetRepos(nextSpec, targetRepos);
    specStore.updateReposAndStatus(nextSpec.id(), taskSpec.repos(), SpecStatus.IN_PROGRESS);
    if (reviewStore != null) {
      reviewStore.supersedeForSpec(nextSpec.id());
    }
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
      var runId = DateTimeUtils.newId().toString();
      var runLogPath = AgentUnit.BUILD.runLogPath(runId);
      var watcher =
          launchAgent(
              project,
              loaded.config(),
              targetRepos,
              task,
              branch,
              request.mode(),
              taskSpec,
              agentType,
              runLogPath);
      var status = querySession(agentSession, project);
      recordRun(
          runId,
          project,
          nextSpec.id(),
          localHandle,
          agentType,
          branch,
          task,
          runLogPath,
          status,
          watcher);
      if (status != null && status.running()) {
        publishAgentSessionStarted(project, nextSpec.id(), agentType, status.pid(), watcher);
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
    eventBus.publish(
        Event.of(
            project,
            specId,
            Event.WellKnownTypes.SPEC_DISPATCHED,
            Event.SAIL_AGENT,
            HostInfo.hostname(),
            DispatchEvents.dispatchedData(branch, mode)));
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

  /**
   * Records the launched execution as a run stamped with this box's handle as its {@code node}, so
   * the provenance guard can serve local runs and refuse foreign ones, and prunes the container's
   * oldest run-log directories. Never fatal: the agent is already running, so a bookkeeping failure
   * only forfeits the run's metadata, never the launch. A run store is absent only in tests that do
   * not exercise dispatch.
   */
  private void recordRun(
      String runId,
      String project,
      String specId,
      String node,
      String agentType,
      String branch,
      String task,
      String logPath,
      AgentSession.SessionInfo status,
      Optional<WatcherSpawner.Spawned> watcher) {
    if (sessionStore == null) {
      return;
    }
    try {
      Integer watcherPid =
          watcher.orElse(null) instanceof WatcherSpawner.Fallback fallback
              ? (int) fallback.pid()
              : null;
      sessionStore.create(
          runId,
          project,
          specId,
          node,
          "build",
          agentType,
          branch,
          task,
          status != null ? status.pid() : null,
          watcherPid,
          logPath);
      var ids = sessionStore.listForProject(project).stream().map(RunStore.RunRow::id).toList();
      RunRetention.prune(shell, project, ids, RunRetention.DEFAULT_KEEP);
    } catch (Exception e) {
      System.err.println("  [api] Warning: could not record run " + runId + ": " + e.getMessage());
    }
  }

  private void publishAgentSessionStarted(
      String project,
      String specId,
      String agentType,
      Integer pid,
      Optional<WatcherSpawner.Spawned> watcher) {
    if (eventBus == null) {
      return;
    }
    var data = new LinkedHashMap<String, Object>();
    if (pid != null) {
      data.put("pid", pid);
    }
    if (watcher.orElse(null) instanceof WatcherSpawner.Fallback fallback) {
      data.put(Event.WellKnownData.WATCHER_PID, fallback.pid());
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

  /**
   * Tails a local run's own log file — the run-scoped {@code ~/.sail/runs/<runId>/agent.log} the
   * run row records — so a log address names exactly one execution, never whatever the shared
   * per-container file currently holds. The provenance guard already established the run is local.
   */
  private RunLogResponse runLogValue(RunStore.RunRow run, int tail) {
    requireProjectExists(run.project());
    var logPath = run.logPath();
    if (Strings.isBlank(logPath)) {
      return new RunLogResponse(run.id(), List.of(), "This run has no log file.");
    }
    var cmd =
        ContainerExec.asDevUser(
            run.project(), List.of("tail", "-n", String.valueOf(tail), logPath));
    var result = exec(cmd);
    if (!result.ok()) {
      if (result.stderr().contains("No such file")) {
        return new RunLogResponse(run.id(), List.of(), "No log found for this run.");
      }
      throw new ApiException(ErrorCode.AGENT_LOG_FAILED, "Failed to read run log.");
    }
    var lines = Arrays.stream(result.stdout().split("\n")).filter(line -> !line.isEmpty()).toList();
    return new RunLogResponse(run.id(), lines, null);
  }

  private StopRunResponse stopRunValue(RunStore.RunRow run) {
    requireProjectExists(run.project());
    var agentSession = new AgentSession(shell);
    var info = querySession(agentSession, run.project());
    if (info == null || !info.running()) {
      return new StopRunResponse(run.id(), false, "no_agent_running", null);
    }
    try {
      agentSession.killAgent(run.project());
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_STOP_FAILED, "Failed to stop agent.", e);
    }
    return new StopRunResponse(run.id(), true, null, info.pid());
  }

  private AgentReportResponse agentReportValue(String project) {
    var loaded = loadProject(project);
    try {
      var specs = specStore != null ? specStore.projectSpecs(project) : List.<Spec>of();
      var session =
          sessionStore != null ? sessionStore.latestForProject(project).orElse(null) : null;
      return agentReportView(
          new AgentReporter(shell).generate(project, loaded.config(), specs, session));
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

  private static ApiException refusal(DispatchDecision.Refused refused) {
    return new ApiException(refused.code(), refused.message(), refused.fix());
  }

  private static Spec resolveSpec(List<Spec> specs, String specId, String localHandle) {
    if (Strings.isBlank(specId)) {
      return SpecDirectory.nextReadyAssignedTo(specs, localHandle);
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

  private Optional<WatcherSpawner.Spawned> launchAgent(
      String project,
      SailYaml config,
      List<SailYaml.Repo> targetRepos,
      String task,
      String branch,
      String mode,
      Spec spec,
      String agentType,
      String logPath) {
    try {
      ensureSailSetup(project);
      var session = new AgentSession(shell);
      session.ensureDirectory(project);
      session.writeTaskFile(project, task);
      session.writeSession(
          project, task, Objects.requireNonNullElse(branch, ""), spec.id(), agentType);
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
                  agentType,
                  logPath)
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
      return launchWatcherIfAgent(project, config);
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

  /**
   * Relaunches the guardrail watcher for a project whose original watcher died (e.g. with a daemon
   * restart mid-run). Unit-or-nothing: the relaunch never falls back to a plain process, so a
   * doubled watcher is unrepresentable on this path — empty means the project declares no agent
   * block or no systemd scope accepted the unit. The relaunched {@code sail agent watch} recomputes
   * its deadlines from the session's original {@code started_at} inside the container, so a
   * re-armed agent keeps its remaining budget rather than getting a fresh one.
   */
  public Optional<WatcherSpawner.Unit> relaunchWatcher(String project) throws IOException {
    var loaded = loadProject(project);
    if (loaded.config().agent() == null) {
      return Optional.empty();
    }
    return watcherSpawner.spawnUnit(
        project,
        SailPaths.resolveSailYaml(project, file).toAbsolutePath(),
        SailPaths.projectDir(project).resolve("watch.log"));
  }

  /**
   * Spawns the detached watcher whenever the project declares an agent block — supervision is on by
   * default, with {@code Guardrails.defaults()} applying when none are declared, and the watcher is
   * also the authoritative stop observer the review pipeline depends on.
   */
  private Optional<WatcherSpawner.Spawned> launchWatcherIfAgent(String project, SailYaml config)
      throws IOException {
    if (config.agent() == null) {
      return Optional.empty();
    }
    return Optional.of(
        watcherSpawner.spawn(
            project,
            SailPaths.resolveSailYaml(project, file).toAbsolutePath(),
            SailPaths.projectDir(project).resolve("watch.log")));
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
        counts.getOrDefault("awaiting_merge", 0),
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

  /**
   * Event types whose arrival implies a spec mutation on this box that main has not seen: a CLI
   * dispatch's claim, a status flip, and an agent stop (which the lifecycle/review reactors turn
   * into a status transition moments later — well inside the sync debounce window). Publishing one
   * on a node schedules the same debounced propagation as a direct API mutation, so the CLI lane
   * needs no sync wrapper of its own.
   */
  private static final Set<String> SYNC_TRIGGER_EVENTS =
      Set.of(
          Event.WellKnownTypes.SPEC_DISPATCHED,
          Event.WellKnownTypes.SPEC_RESTARTED,
          Event.WellKnownTypes.SPEC_STATUS_CHANGED,
          Event.WellKnownTypes.AGENT_SESSION_STOPPED);

  @Override
  public Result<EventPublishResponse> publishEvent(Event event) {
    if (eventBus == null) {
      return Result.failure(
          ErrorCode.INTERNAL,
          "Event bus is not wired into this SailApiOperations instance.",
          "Use the SailApiOperations constructor that accepts an EventBus.");
    }
    var result =
        safe(
            () -> {
              var stamped = eventBus.publish(event);
              return new EventPublishResponse(stamped.id(), stamped.toMap());
            });
    if (result instanceof Result.Success<EventPublishResponse>
        && SYNC_TRIGGER_EVENTS.contains(event.type())) {
      triggerSyncAfterWrite();
    }
    return result;
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

  /** Serves a spec read after the TTL-gated freshen, unless the request opted out of sync. */
  private <T> Result<T> safeRead(Supplier<T> supplier) {
    freshenForRead();
    return safe(supplier);
  }

  /** Runs a spec mutation and, when it succeeds, schedules the debounced propagation to main. */
  private <T> Result<T> safeWrite(Supplier<T> supplier) {
    var result = safe(supplier);
    if (result instanceof Result.Success<T>) {
      triggerSyncAfterWrite();
    }
    return result;
  }

  private void freshenForRead() {
    if (!SyncControl.noSync()) {
      syncScheduler.freshenRead();
    }
  }

  private void triggerSyncAfterWrite() {
    if (!SyncControl.noSync()) {
      syncScheduler.afterWrite();
    }
  }

  @Override
  public Result<GlobalSpecsListResponse> globalSpecs(SpecStore.SpecFilter filter) {
    return safeRead(() -> globalSpecOps.list(filter));
  }

  @Override
  public Result<GlobalSpecDetailResponse> globalSpec(String specId) {
    return safeRead(() -> globalSpecOps.get(specId));
  }

  @Override
  public Result<GlobalSpecCreatedResponse> createGlobalSpec(SpecCreateRequest request) {
    return safeWrite(() -> globalSpecOps.create(request));
  }

  @Override
  public Result<FollowupSpecResponse> createFollowupSpec(
      String specId, FollowupCreateRequest request) {
    return safeWrite(() -> reviewOps.createFollowup(specId, request));
  }

  @Override
  public Result<GlobalSpecUpdatedResponse> updateGlobalSpec(
      String specId, SpecUpdateRequest request) {
    return safeWrite(() -> globalSpecOps.update(specId, request));
  }

  @Override
  public Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId) {
    return safeWrite(() -> globalSpecOps.delete(specId));
  }

  @Override
  public Result<GlobalSpecContentResponse> globalSpecContent(String specId) {
    return safeRead(() -> globalSpecOps.content(specId));
  }

  @Override
  public Result<GlobalSpecContentResponse> setGlobalSpecContent(
      String specId, SpecContentRequest request) {
    return safeWrite(() -> globalSpecOps.setContent(specId, request));
  }

  @Override
  public Result<GlobalSpecHistoryResponse> globalSpecHistory(String specId) {
    return safeRead(() -> globalSpecOps.history(specId));
  }

  @Override
  public Result<GlobalSpecRestoredResponse> restoreGlobalSpec(
      String specId, SpecRestoreRequest request) {
    return safeWrite(() -> globalSpecOps.restore(specId, request));
  }

  @Override
  public Result<GlobalBoardResponse> globalBoard(String project) {
    return safeRead(() -> globalSpecOps.board(project));
  }

  @Override
  public Result<ReviewListResponse> reviewsForSpec(String specId) {
    return safeRead(() -> reviewOps.listForSpec(specId));
  }

  @Override
  public Result<ReviewDetailResponse> reviewDetail(String reviewId) {
    return safeRead(() -> reviewOps.detail(reviewId));
  }

  @Override
  public Result<ReviewApproveResponse> approveReview(String reviewId, String actor) {
    return safeWrite(() -> reviewOps.approve(reviewId, actor));
  }

  @Override
  public Result<FindingDismissResponse> dismissFinding(String reviewId, String findingId) {
    return safeWrite(() -> reviewOps.dismissFinding(reviewId, findingId));
  }

  private record LoadedProject(SailYaml config, ContainerState state) {}
}
