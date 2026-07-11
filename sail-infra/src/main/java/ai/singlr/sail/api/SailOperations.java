/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.Ids;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecDirectory;
import ai.singlr.sail.engine.AgentReporter;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.ConnectEnvironment;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

public final class SailOperations implements Operations {

  private final ShellExec shell;
  private final String file;
  private final WatcherSpawner watcherSpawner;
  private final EventBus eventBus;
  private final AuditPersister auditPersister;
  private final SpecStore specStore;
  private final ReviewStore reviewStore;
  private final RunStore runStore;
  private final ProjectStore projectStore;
  private final Supplier<ConnectEnvironment> connectEnvironment;
  private final SyncScheduler syncScheduler;
  private final ProjectLoader projects;
  private final GlobalSpecOperations globalSpecOps;
  private final ReviewOperations reviewOps;
  private final DispatchOperations dispatchOps;

  public SailOperations() {
    this(new ShellExecutor(false), SailPaths.PROJECT_DESCRIPTOR);
  }

  public SailOperations(ShellExec shell, String file) {
    this(shell, file, WatcherSpawner::spawnProcess);
  }

  SailOperations(ShellExec shell, String file, WatcherSpawner.ProcessSpawner watcherFallback) {
    this(shell, file, watcherFallback, null, null);
  }

  /** Construct with explicit event-bus wiring; used by {@link SailApiServer}. */
  public SailOperations(
      ShellExec shell, String file, EventBus eventBus, AuditPersister auditPersister) {
    this(shell, file, WatcherSpawner::spawnProcess, eventBus, auditPersister, null, null);
  }

  /** Construct with database-backed stores; used by the control plane server. */
  public SailOperations(
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
  public SailOperations(
      ShellExec shell,
      String file,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      SpecStore specStore) {
    this(shell, file, eventBus, auditSubscriber, specStore, null);
  }

  /** Construct with the project catalog included but no sync or run aggregate. */
  public SailOperations(
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
        null,
        projectStore,
        SyncScheduler.disabled(),
        null);
  }

  /**
   * As {@link #SailOperations(ShellExec, String, EventBus, EventSubscriber, SpecStore, ReviewStore,
   * ProjectStore)} with the node's sync-on-write scheduler, the run aggregate, and the FDE roster;
   * used by {@code sail server start} so spec mutations propagate to main, stale reads freshen
   * without a manual {@code sail sync}, dispatches record their runs — without the run store every
   * {@code /v1/runs} route refuses and API-lane dispatches silently record no run at all — and
   * dispatch trusts only handles present in the synced roster.
   */
  public SailOperations(
      ShellExec shell,
      String file,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore,
      ProjectStore projectStore,
      SyncScheduler syncScheduler,
      FdeStore fdeStore) {
    this(
        shell,
        file,
        WatcherSpawner::spawnProcess,
        eventBus,
        auditSubscriber instanceof AuditPersister ap ? ap : null,
        specStore,
        reviewStore,
        runStore,
        projectStore,
        ConnectEnvironment::detect,
        syncScheduler,
        fdeStore);
  }

  SailOperations(
      ShellExec shell,
      String file,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister) {
    this(shell, file, watcherFallback, eventBus, auditPersister, null, null);
  }

  SailOperations(
      ShellExec shell,
      String file,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore) {
    this(shell, file, watcherFallback, eventBus, auditPersister, specStore, reviewStore, null);
  }

  SailOperations(
      ShellExec shell,
      String file,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore) {
    this(
        shell,
        file,
        watcherFallback,
        eventBus,
        auditPersister,
        specStore,
        reviewStore,
        runStore,
        null,
        ConnectEnvironment::detect,
        SyncScheduler.disabled(),
        null);
  }

  SailOperations(
      ShellExec shell,
      String file,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore,
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
        runStore,
        projectStore,
        connectEnvironment,
        SyncScheduler.disabled(),
        null);
  }

  SailOperations(
      ShellExec shell,
      String file,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore,
      ProjectStore projectStore,
      Supplier<ConnectEnvironment> connectEnvironment,
      SyncScheduler syncScheduler) {
    this(
        shell,
        file,
        watcherFallback,
        eventBus,
        auditPersister,
        specStore,
        reviewStore,
        runStore,
        projectStore,
        connectEnvironment,
        syncScheduler,
        null);
  }

  SailOperations(
      ShellExec shell,
      String file,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore,
      ProjectStore projectStore,
      Supplier<ConnectEnvironment> connectEnvironment,
      SyncScheduler syncScheduler,
      FdeStore fdeStore) {
    this.shell = shell;
    this.file = file;
    this.watcherSpawner = new WatcherSpawner(shell, watcherFallback);
    this.eventBus = eventBus;
    this.auditPersister = auditPersister;
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.runStore = runStore;
    this.projectStore = projectStore;
    this.connectEnvironment = connectEnvironment;
    this.syncScheduler = syncScheduler;
    this.projects = new ProjectLoader(shell, file);
    this.globalSpecOps = new GlobalSpecOperations(specStore, reviewStore, eventBus, runStore);
    this.reviewOps = new ReviewOperations(reviewStore, specStore);
    this.dispatchOps =
        new DispatchOperations(
            shell,
            file,
            specStore,
            reviewStore,
            runStore,
            fdeStore,
            this::publishOnBus,
            this.watcherSpawner,
            DispatchOperations.autoSnapshotter(shell),
            DispatchOperations.shellLauncher(shell),
            DispatchOperations.Listener.NONE);
  }

  private void publishOnBus(Event event) {
    if (eventBus != null) {
      eventBus.publish(event);
    }
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
  public Result<AgentReportResponse> agentReport(String project, String localHandle) {
    return safe(() -> agentReportValue(project, localHandle));
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
  public Result<RunLogResponse> runLog(String runId, int tail, String localHandle, Actor actor) {
    return onLocalRun(runId, localHandle, actor, run -> safe(() -> runLogValue(run, tail)));
  }

  @Override
  public Result<StopRunResponse> stopRun(String runId, String localHandle, Actor actor) {
    return onLocalRun(runId, localHandle, actor, run -> safe(() -> stopRunValue(run)));
  }

  /**
   * Resolves a run, applies the provenance guard, then the resource-scoped {@link RunPolicy} before
   * handing it to {@code served}: an absent store is an internal error, an unknown run a 404, a run
   * that executed elsewhere a structured {@code run_on_other_node} refusal, and a caller who is
   * neither the run's spec assignee nor an admin a {@code forbidden_not_assignee}. The served
   * branch only ever sees a local run the caller may access — one guard for both the log tail and
   * stop.
   */
  private <T> Result<T> onLocalRun(
      String runId,
      String localHandle,
      Actor actor,
      java.util.function.Function<RunStore.RunRow, Result<T>> served) {
    if (runStore == null) {
      return Result.failure(
          ErrorCode.INTERNAL,
          "Run store not available. Start the server with 'sail server start'.");
    }
    var run = runStore.findById(runId).orElse(null);
    if (run == null) {
      return Result.failure(ErrorCode.RUN_NOT_FOUND, "No run '" + runId + "'.");
    }
    if (isForeign(run, localHandle)) {
      return foreignRun(run);
    }
    if (RunPolicy.access(actor, run.id(), run.specId(), specAssignee(run.specId()))
        instanceof AccessDecision.Refused refused) {
      return Result.failure(refused.code(), refused.message(), refused.fix());
    }
    return served.apply(run);
  }

  /**
   * The current assignee of {@code specId}, or null when the spec is absent or the store is not
   * wired.
   */
  private String specAssignee(String specId) {
    if (specStore == null || Strings.isBlank(specId)) {
      return null;
    }
    return specStore.findById(specId).map(SpecStore.SpecRow::assignee).orElse(null);
  }

  private RunStore requireRunStore() {
    if (runStore == null) {
      throw new ApiException(
          ErrorCode.INTERNAL,
          "Run store not available. Start the server with 'sail server start'.");
    }
    return runStore;
  }

  private static ApiException runNotFound(String runId) {
    return new ApiException(ErrorCode.RUN_NOT_FOUND, "No run '" + runId + "'.");
  }

  /**
   * Whether a run did not execute on this box — the provenance test, the inverse of {@link
   * #ownsRun}. A box with a handle serves only runs stamped with it, so a blank {@code node} fails
   * closed to foreign; a box with no handle serves only its own blank-node runs and never a synced
   * run stamped by another box.
   */
  static boolean isForeign(RunStore.RunRow run, String localHandle) {
    return !ownsRun(run.node(), localHandle);
  }

  /**
   * Whether a run whose execution node is {@code runNode} belongs to the box whose handle is {@code
   * localHandle}. One predicate for every ownership question — the read guard here and the
   * completion/report lookups in {@link RunStore#latestForProjectOnNode} — so they can never
   * disagree. A handled box owns the runs stamped with its handle (blank node → not owned, fail
   * closed); an unhandled box (standalone / not yet bound to an FDE) owns its own blank-node runs.
   */
  static boolean ownsRun(String runNode, String localHandle) {
    return Strings.isBlank(localHandle) ? Strings.isBlank(runNode) : localHandle.equals(runNode);
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
    var loaded = projects.load(project);
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
    var loaded = projects.loadRunning(project);
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
    projects.requireExists(project);
    var specs = specStore.projectSpecs(project);
    var summary = SpecDirectory.summarize(specs);
    return new SpecsResponse(
        project,
        specs.stream().map(spec -> specView(specs, spec)).toList(),
        summaryView(summary.counts()),
        boardSummaryView(summary));
  }

  private SpecResponse specValue(String project, String specId) {
    projects.requireExists(project);
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
    var outcome =
        dispatchOps.dispatch(
            project,
            new DispatchOperations.Request(
                request.specId(),
                request.mode(),
                request.dryRun(),
                request.repos(),
                request.restart()),
            actor,
            localHandle);
    return switch (outcome) {
      case DispatchOperations.NoSpecs ignored ->
          new DispatchResponse(project, false, "no_pending_specs", null, null, "", false, false);
      case DispatchOperations.Dispatched dispatched ->
          new DispatchResponse(
              project,
              true,
              null,
              dispatchedSpecView(dispatched.taskSpec(), dispatched.branch()),
              agentStatusView(dispatched.agentType(), request.mode(), dispatched.session()),
              dispatched.snapshotLabel(),
              dispatched.branchCreated(),
              dispatched.restarted());
    };
  }

  private AgentStatusResponse agentStatusValue(String project) {
    projects.requireExists(project);
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
   * Tails a local run's own log file — the run-scoped {@code ~/.sail/runs/<runId>/agent.log} — so a
   * log address names exactly one execution, never whatever the shared per-container file currently
   * holds. The path is derived from the run's canonical UUID and role rather than the persisted
   * {@code log_path}: run rows replicate over sync, so a stored path is untrusted input that could
   * point anywhere the container's dev user can read. The provenance guard already established the
   * run is local.
   */
  private RunLogResponse runLogValue(RunStore.RunRow run, int tail) {
    projects.requireExists(run.project());
    if (Strings.isBlank(run.logPath())) {
      return new RunLogResponse(run.id(), List.of(), "This run has no log file.");
    }
    var logPath =
        AgentUnit.fromRole(Objects.requireNonNullElse(run.role(), "build"))
            .runLogPath(Ids.requireUuid(run.id()));
    var cmd =
        ContainerExec.asDevUser(
            run.project(), List.of("tail", "-n", String.valueOf(tail), "--", logPath));
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

  /**
   * Stops the agent process only when {@code run} is genuinely the one executing now: an
   * already-finished run, or a run whose recorded pid does not match the live agent, is a no-op —
   * so stopping a stale run id can never kill a different, newer run of the same project. The
   * provenance guard has already established the run is local.
   */
  private StopRunResponse stopRunValue(RunStore.RunRow run) {
    projects.requireExists(run.project());
    if (!"running".equals(run.status())) {
      return new StopRunResponse(run.id(), false, "run_not_running", null);
    }
    var agentSession = new AgentSession(shell);
    var info = querySession(agentSession, run.project());
    if (info == null || !info.running()) {
      return new StopRunResponse(run.id(), false, "no_agent_running", null);
    }
    if (run.pid() == null || info.pid() != run.pid()) {
      return new StopRunResponse(run.id(), false, "run_not_active", info.pid());
    }
    try {
      agentSession.killAgent(run.project());
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_STOP_FAILED, "Failed to stop agent.", e);
    }
    return new StopRunResponse(run.id(), true, null, info.pid());
  }

  private AgentReportResponse agentReportValue(String project, String localHandle) {
    var loaded = projects.load(project);
    try {
      var specs = specStore != null ? specStore.projectSpecs(project) : List.<Spec>of();
      var session =
          runStore != null
              ? runStore.latestForProjectOnNode(project, localHandle).orElse(null)
              : null;
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

  /**
   * Relaunches the guardrail watcher for a project whose original watcher died (e.g. with a daemon
   * restart mid-run). Unit-or-nothing: the relaunch never falls back to a plain process, so a
   * doubled watcher is unrepresentable on this path — empty means the project declares no agent
   * block or no systemd scope accepted the unit. The relaunched {@code sail agent watch} recomputes
   * its deadlines from the session's original {@code started_at} inside the container, so a
   * re-armed agent keeps its remaining budget rather than getting a fresh one.
   */
  public Optional<WatcherSpawner.Unit> relaunchWatcher(String project) throws IOException {
    var loaded = projects.load(project);
    if (loaded.config().agent() == null) {
      return Optional.empty();
    }
    return watcherSpawner.spawnUnit(
        project,
        SailPaths.resolveSailYaml(project, file).toAbsolutePath(),
        SailPaths.projectDir(project).resolve("watch.log"));
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
          "Event bus is not wired into this SailOperations instance.",
          "Use the SailOperations constructor that accepts an EventBus.");
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

  /**
   * Maps an operation's outcome onto the wire contract the routers already speak: an {@link
   * ApiException} is a structured refusal, an {@link IllegalArgumentException} is a caller error —
   * a validation precondition like "repo not configured in sail.yaml" — surfaced as {@code
   * invalid_request} (400) with its message, the same convention {@code ApiRouter} and {@code
   * LocalApiRouter} apply to exceptions escaping their own routing. Only a truly unexpected
   * exception becomes a generic {@code internal} 500, and its stack trace goes to the journal
   * first.
   */
  private static <T> Result<T> safe(Supplier<T> supplier) {
    try {
      return Result.success(supplier.get());
    } catch (ApiException e) {
      return e.failure().asFailure();
    } catch (IllegalArgumentException e) {
      return Result.failure(ErrorCode.INVALID_REQUEST, e.getMessage(), e);
    } catch (Exception e) {
      ApiLog.unexpected("an API operation", e);
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
      String specId, SpecUpdateRequest request, Actor actor) {
    return safeWrite(() -> globalSpecOps.update(specId, request, actor));
  }

  @Override
  public Result<GlobalSpecDeletedResponse> deleteGlobalSpec(String specId, Actor actor) {
    return safeWrite(() -> globalSpecOps.delete(specId, actor));
  }

  @Override
  public Result<GlobalSpecContentResponse> globalSpecContent(String specId) {
    return safeRead(() -> globalSpecOps.content(specId));
  }

  @Override
  public Result<GlobalSpecContentResponse> setGlobalSpecContent(
      String specId, SpecContentRequest request, Actor actor) {
    return safeWrite(() -> globalSpecOps.setContent(specId, request, actor));
  }

  @Override
  public Result<GlobalSpecHistoryResponse> globalSpecHistory(String specId) {
    return safeRead(() -> globalSpecOps.history(specId));
  }

  @Override
  public Result<GlobalSpecRestoredResponse> restoreGlobalSpec(
      String specId, SpecRestoreRequest request, Actor actor) {
    return safeWrite(() -> globalSpecOps.restore(specId, request, actor));
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
  public Result<ReviewApproveResponse> approveReview(String reviewId, Actor actor) {
    return safeWrite(() -> reviewOps.approve(reviewId, actor));
  }

  @Override
  public Result<FindingDismissResponse> dismissFinding(
      String reviewId, String findingId, Actor actor) {
    return safeWrite(() -> reviewOps.dismissFinding(reviewId, findingId, actor));
  }
}
