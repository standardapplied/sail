/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.authority.PostingRule;
import ai.singlr.sail.authority.RunAuthority;
import ai.singlr.sail.authority.WriteRefused;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.EngagementMode;
import ai.singlr.sail.config.FileLimits;
import ai.singlr.sail.config.RetentionConfig;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SpecCatalog;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentReporter;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.CliOperator;
import ai.singlr.sail.engine.ConflictOperations;
import ai.singlr.sail.engine.ConnectEnvironment;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SharedProjectFiles;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.Role;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.BoxCredentialStore;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncHealth;
import ai.singlr.sail.sync.SyncTransportException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class SailOperations implements HostOperations {

  private Sqlite controlPlane;
  private SyncOperations syncOperations;
  private Clock syncClock = Clock.systemUTC();
  private Path projectsDir;
  private Runnable closeAction = () -> {};
  private HostCatalog catalog;
  private HostIdentity identity;
  private HostPty pty;
  private HostSchema schema;

  /** The host facets exist once the control-plane database is known; the web lane needs none. */
  public SailOperations useControlPlane(
      Sqlite db, Path projectsDir, SyncOperations syncOperations) {
    this.controlPlane = Objects.requireNonNull(db, "db");
    this.projectsDir = Objects.requireNonNull(projectsDir, "projectsDir");
    this.syncOperations = Objects.requireNonNull(syncOperations, "syncOperations");
    this.pruner = new SpecPruner(db, eventBus, this::authoritative, DateTimeUtils::now);
    this.door = new EventDoor(db, () -> box().handle());
    this.schema = new HostLanes.Schema(db, syncOperations);
    this.catalog =
        new HostLanes.Catalog(
            db, projectStore, specStore, roomStore, schema, pruner, this::cliOperator);
    this.identity = new HostLanes.Identity(db, this::box, roles(), this::cliOperator);
    this.pty = new HostLanes.Pty(db, roles(), eventStore);
    projectSkills = new ProjectSkills(this::projectFiles);
    dispatchOps.useProjectSkills(projectSkills);
    return this;
  }

  /** The role rule over this box's roster and its sync configuration ({@link #box}). */
  private RoleRule roles() {
    return new RoleRule(this::box, fdeStore);
  }

  /**
   * This box's sync configuration, read afresh each time; a box with no control plane wired has
   * none.
   */
  private SyncConfig box() {
    return syncOperations == null ? SyncConfig.unset() : syncOperations.configuration();
  }

  /**
   * Main's daily retention and compaction over this box's database, reading the {@code retention}
   * block of {@code host.yaml} afresh at every sweep.
   */
  public RetentionSweeper retentionSweeper() {
    requireHost(controlPlane);
    return new RetentionSweeper(
        pruner, RetentionConfig::load, new BlobStore(controlPlane), this::authoritative);
  }

  /**
   * Whether this box authors erasures, sweeps retention and resolves the findings a shipped
   * follow-up was drafted from: main, or a box that syncs nobody.
   */
  private boolean authoritative() {
    return !box().isNode();
  }

  /** The operator of this box's root CLI, as {@link CliOperator} resolves it. */
  private Actor cliOperator() {
    return CliOperator.of(syncOperations.configuration(), fdeStore);
  }

  @Override
  public HostDispatching dispatching() {
    return new HostLanes.Dispatching(dispatchOps, stopOps, runStore, shell);
  }

  @Override
  public HostCatalog catalog() {
    return requireHost(catalog);
  }

  @Override
  public HostIdentity identity() {
    return requireHost(identity);
  }

  @Override
  public HostPty pty() {
    return requireHost(pty);
  }

  @Override
  public HostSchema schema() {
    return requireHost(schema);
  }

  private static <T> T requireHost(T facet) {
    if (facet == null) {
      throw new IllegalStateException(
          "This operations instance has no control-plane database; open it through"
              + " OperationsFactory.");
    }
    return facet;
  }

  SailOperations closeWith(Runnable action) {
    this.closeAction = action;
    return this;
  }

  @Override
  public void close() {
    closeAction.run();
  }

  @Override
  public synchronized SyncReport sync(SyncRequest request) throws Exception {
    var config = syncOperations.configuration();
    var target = SyncOperations.resolveMain(request.main(), config).target();
    if (target == null) return syncOperations.sync(request);
    var health = new SyncHealth(controlPlane);
    var attemptedAt = syncClock.instant();
    SyncReport round;
    try {
      syncOperations.prepare();
      health.begin(target, attemptedAt);
      round = syncOperations.sync(request);
    } catch (Exception e) {
      var kind =
          e instanceof SyncTransportException transport
              ? transport.kind()
              : e instanceof IOException || e instanceof UncheckedIOException
                  ? "unreachable"
                  : "store";
      var error = Remedied.whole(e);
      try {
        if (health.find(target).isEmpty()) health.begin(target, attemptedAt);
        if (health.failed(target, syncClock.instant(), kind, error) == 1) {
          publishSyncTransition(target, false, kind, error.plain());
        }
      } catch (RuntimeException recordingFailure) {
        e.addSuppressed(recordingFailure);
      }
      throw e;
    }
    if (health.succeeded(
        target,
        syncClock.instant(),
        round.report(),
        round.fetchedBytes(),
        round.sentBytes(),
        round.freedBytes(),
        round.denials())) {
      publishSyncTransition(target, true, null, null);
    }
    return round;
  }

  SailOperations useSyncClock(Clock clock) {
    this.syncClock = Objects.requireNonNull(clock, "clock");
    return this;
  }

  private void publishSyncTransition(String peer, boolean recovered, String kind, String error) {
    var config = syncOperations.configuration();
    var host = Objects.toString(config.handle(), HostInfo.hostname());
    var event = SyncTransitionEvents.health(peer, recovered, kind, error, host);
    if (eventBus != null) {
      publishOnBus(event);
    } else {
      new EventStore(controlPlane)
          .insert(
              new EventStore.EventRow(
                  0,
                  event.ts().toString(),
                  event.type(),
                  event.project(),
                  null,
                  event.agent(),
                  event.host(),
                  YamlUtil.dumpJson(event.data())));
    }
  }

  @Override
  public SyncStatus syncStatus() {
    var config = syncOperations.configuration();
    var health =
        new SchemaManager(controlPlane).currentVersion() < SchemaManager.SYNC_HEALTH_VERSION
            ? null
            : new SyncHealth(controlPlane).find(config.main()).orElse(null);
    return health == null
        ? SyncStatus.unattempted(config.role(), config.main())
        : new SyncStatus(
            config.role(),
            config.main(),
            health.lastReport(),
            health.state(),
            health.lastAttemptAt(),
            health.lastSuccessAt(),
            health.consecutiveFailures(),
            health.lastErrorKind(),
            health.lastError(),
            health.staleSince(),
            health.fetchedBytes(),
            health.sentBytes(),
            health.freedBytes(),
            health.denials());
  }

  @Override
  public List<SyncConflicts.Conflict> conflicts() {
    return new ConflictOperations(controlPlane).list();
  }

  @Override
  public SyncConflicts.Conflict conflict(String type, String id) {
    return new ConflictOperations(controlPlane).find(type, id);
  }

  @Override
  public String conflictMergeTemplate(String type, String id) {
    return new ConflictOperations(controlPlane).mergeTemplate(type, id);
  }

  /**
   * Settles a parked conflict. Keeping this box's side or merging is an edit, decided by the type's
   * rule as the journal records it ({@link WriteRefused}). Adopting main's side decides no row, yet
   * gives up what this box parked, so a read-only credential is refused that as well.
   */
  @Override
  public SyncConflicts.Conflict resolveConflict(String type, String id, Resolution resolution) {
    Refusals.requireWriter("resolve conflicts");
    var resolved = new ConflictOperations(controlPlane).resolve(type, id, resolution);
    triggerSyncAfterWrite();
    return resolved;
  }

  @Override
  public ProjectFiles projectFiles(String project) {
    return new SharedProjectFiles(
        new FileStore(controlPlane), projectsDir, readable(project), FileLimits.load());
  }

  private final ShellExec shell;
  private final WatcherSpawner watcherSpawner;
  private final EventBus eventBus;
  private final AuditPersister auditPersister;
  private final SpecStore specStore;
  private final ReviewStore reviewStore;
  private final RunStore runStore;
  private final ProjectStore projectStore;
  private final Supplier<ConnectEnvironment> connectEnvironment;
  private SyncScheduler syncScheduler;
  private final ProjectLoader projects;
  private final SnapshotOperations snapshotOps;
  private final GlobalSpecOperations globalSpecOps;
  private SpecPruner pruner;
  private EventDoor door;
  private final ReviewOperations reviewOps;
  private final DispatchOperations dispatchOps;
  private ProjectSkills projectSkills = ProjectSkills.none();
  private final StopOperations stopOps;
  private final FdeStore fdeStore;
  private Executor launchExecutor = Executors.newVirtualThreadPerTaskExecutor();
  private MessageStore messageStore;
  private RoomStore roomStore;
  private BoxCredentialStore boxCredentialStore;
  private EventStore eventStore;

  public SailOperations() {
    this(new ShellExecutor(false), ProjectReader.ofCatalog(null));
  }

  public SailOperations(ShellExec shell, ProjectReader definitions) {
    this(shell, definitions, WatcherSpawner::spawnProcess);
  }

  SailOperations(
      ShellExec shell, ProjectReader definitions, WatcherSpawner.ProcessSpawner watcherFallback) {
    this(shell, definitions, watcherFallback, null, null);
  }

  /** Construct with explicit event-bus wiring; used by {@link SailApiServer}. */
  public SailOperations(
      ShellExec shell,
      ProjectReader definitions,
      EventBus eventBus,
      AuditPersister auditPersister) {
    this(shell, definitions, WatcherSpawner::spawnProcess, eventBus, auditPersister, null, null);
  }

  /** Construct with database-backed stores; used by the control plane server. */
  public SailOperations(
      ShellExec shell,
      ProjectReader definitions,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      SpecStore specStore,
      ReviewStore reviewStore) {
    this(
        shell,
        definitions,
        WatcherSpawner::spawnProcess,
        eventBus,
        auditSubscriber instanceof AuditPersister ap ? ap : null,
        specStore,
        reviewStore);
  }

  /** Construct with database-backed spec store (no review store). */
  public SailOperations(
      ShellExec shell,
      ProjectReader definitions,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      SpecStore specStore) {
    this(shell, definitions, eventBus, auditSubscriber, specStore, null);
  }

  /** Construct with the project catalog included but no sync or run aggregate. */
  public SailOperations(
      ShellExec shell,
      ProjectReader definitions,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      SpecStore specStore,
      ReviewStore reviewStore,
      ProjectStore projectStore) {
    this(
        shell,
        definitions,
        eventBus,
        auditSubscriber,
        specStore,
        reviewStore,
        null,
        projectStore,
        SyncScheduler.disabled(),
        null,
        SessionYield.NONE);
  }

  /**
   * As {@link #SailOperations(ShellExec, ProjectReader, EventBus, EventSubscriber, SpecStore,
   * ReviewStore, ProjectStore)} with the node's sync-on-write scheduler, the run aggregate, the FDE
   * roster, and the pty host seam; used by {@code sail server start} so spec mutations propagate to
   * main, stale reads freshen without a manual {@code sail sync}, dispatches record their runs —
   * without the run store every {@code /v1/runs} route refuses and API-lane dispatches silently
   * record no run at all — dispatch trusts only handles present in the synced roster, and a claim
   * ends the resumed conversations it displaces. Every other constructor serves lanes with no pty
   * host.
   */
  public SailOperations(
      ShellExec shell,
      ProjectReader definitions,
      EventBus eventBus,
      EventSubscriber auditSubscriber,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore,
      ProjectStore projectStore,
      SyncScheduler syncScheduler,
      FdeStore fdeStore,
      SessionYield sessionYield) {
    this(
        shell,
        definitions,
        WatcherSpawner::spawnProcess,
        eventBus,
        auditSubscriber instanceof AuditPersister ap ? ap : null,
        specStore,
        reviewStore,
        runStore,
        projectStore,
        ConnectEnvironment.detector(),
        syncScheduler,
        fdeStore,
        sessionYield);
  }

  public SailOperations useSyncScheduler(SyncScheduler scheduler) {
    this.syncScheduler = Objects.requireNonNull(scheduler, "scheduler");
    if (controlPlane != null) scheduler.useHealth(this::syncStatus);
    return this;
  }

  /** Wires the message store into the operations and dispatch lanes; returns {@code this}. */
  public SailOperations useMessages(MessageStore messageStore) {
    this.messageStore = Objects.requireNonNull(messageStore, "messageStore");
    this.dispatchOps.useMessages(messageStore);
    return this;
  }

  /** Wires the room store — the authoritative home of membership state; returns {@code this}. */
  public SailOperations useRooms(RoomStore roomStore) {
    this.roomStore = Objects.requireNonNull(roomStore, "roomStore");
    this.dispatchOps.useRooms(roomStore);
    return this;
  }

  /**
   * Overrides the executor that runs the deferred half of a membership (the engage-time snapshot).
   * Production keeps the default virtual-thread executor; a test injects {@code Runnable::run} to
   * make the completion run inline, so its assertions and teardown see a finished launch. Returns
   * {@code this}.
   */
  SailOperations useLaunchExecutor(Executor executor) {
    this.launchExecutor = Objects.requireNonNull(executor, "executor");
    return this;
  }

  /** Wires the box's ambient credential store for the local socket lane; returns {@code this}. */
  public SailOperations useBoxCredentials(BoxCredentialStore boxCredentialStore) {
    this.boxCredentialStore = Objects.requireNonNull(boxCredentialStore, "boxCredentialStore");
    return this;
  }

  /** Wires the audit store so per-spec event history is servable; returns {@code this}. */
  public SailOperations useEvents(EventStore eventStore) {
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    return this;
  }

  /**
   * The FDE actor the box credential stands for, with the role {@link RoleRule} gives that FDE: a
   * disabled FDE, or one the roster does not know, refuses. Empty when the box store is not wired
   * (a lane that never authenticates is a lane that fails closed).
   */
  @Override
  public Optional<Actor> boxActorForCredential(String credential) {
    if (boxCredentialStore == null) {
      return Optional.empty();
    }
    return boxCredentialStore
        .resolve(credential)
        .flatMap(handle -> roles().actorOf(handle, Role.ADMIN, Actor.Lane.CLI));
  }

  SailOperations(
      ShellExec shell,
      ProjectReader definitions,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister) {
    this(shell, definitions, watcherFallback, eventBus, auditPersister, null, null);
  }

  SailOperations(
      ShellExec shell,
      ProjectReader definitions,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore) {
    this(
        shell,
        definitions,
        watcherFallback,
        eventBus,
        auditPersister,
        specStore,
        reviewStore,
        null);
  }

  SailOperations(
      ShellExec shell,
      ProjectReader definitions,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore) {
    this(
        shell,
        definitions,
        watcherFallback,
        eventBus,
        auditPersister,
        specStore,
        reviewStore,
        runStore,
        null,
        ConnectEnvironment.detector(),
        SyncScheduler.disabled(),
        null,
        SessionYield.NONE);
  }

  SailOperations(
      ShellExec shell,
      ProjectReader definitions,
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
        definitions,
        watcherFallback,
        eventBus,
        auditPersister,
        specStore,
        reviewStore,
        runStore,
        projectStore,
        connectEnvironment,
        SyncScheduler.disabled(),
        null,
        SessionYield.NONE);
  }

  SailOperations(
      ShellExec shell,
      ProjectReader definitions,
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
        definitions,
        watcherFallback,
        eventBus,
        auditPersister,
        specStore,
        reviewStore,
        runStore,
        projectStore,
        connectEnvironment,
        syncScheduler,
        null,
        SessionYield.NONE);
  }

  SailOperations(
      ShellExec shell,
      ProjectReader definitions,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore,
      ProjectStore projectStore,
      Supplier<ConnectEnvironment> connectEnvironment,
      SyncScheduler syncScheduler,
      FdeStore fdeStore,
      SessionYield sessionYield) {
    this(
        shell,
        definitions,
        watcherFallback,
        eventBus,
        auditPersister,
        specStore,
        reviewStore,
        runStore,
        projectStore,
        connectEnvironment,
        syncScheduler,
        fdeStore,
        sessionYield,
        new OperationHooks(
            event -> {
              if (eventBus != null) eventBus.publish(event);
            },
            new WatcherSpawner(shell, watcherFallback),
            DispatchOperations.autoSnapshotter(shell),
            DispatchOperations.shellLauncher(shell),
            DispatchOperations.Listener.NONE,
            StopOperations.Listener.NONE));
  }

  public SailOperations(
      ShellExec shell,
      ProjectReader definitions,
      WatcherSpawner.ProcessSpawner watcherFallback,
      EventBus eventBus,
      AuditPersister auditPersister,
      SpecStore specStore,
      ReviewStore reviewStore,
      RunStore runStore,
      ProjectStore projectStore,
      Supplier<ConnectEnvironment> connectEnvironment,
      SyncScheduler syncScheduler,
      FdeStore fdeStore,
      SessionYield sessionYield,
      OperationHooks hooks) {
    this.shell = shell;
    this.watcherSpawner = hooks.watcher();
    this.eventBus = eventBus;
    this.auditPersister = auditPersister;
    this.specStore = specStore;
    this.reviewStore = reviewStore;
    this.runStore = runStore;
    this.projectStore = projectStore;
    this.connectEnvironment = connectEnvironment;
    this.syncScheduler = syncScheduler;
    this.fdeStore = fdeStore;
    this.projects = new ProjectLoader(shell, definitions);
    this.snapshotOps = new SnapshotOperations(shell, projects, runStore, this::publishOnBus);
    this.globalSpecOps =
        new GlobalSpecOperations(
            specStore, reviewStore, eventBus, runStore, () -> roomStore, this::authoritative);
    this.reviewOps = new ReviewOperations(reviewStore, specStore);
    this.dispatchOps =
        new DispatchOperations(
            shell,
            definitions,
            specStore,
            reviewStore,
            runStore,
            fdeStore,
            hooks.events(),
            this.watcherSpawner,
            hooks.snapshotter(),
            hooks.launcher(),
            hooks.dispatchListener(),
            sessionYield);
    this.dispatchOps.liveMembers().useRoom(this::postAsRun);
    this.stopOps =
        specStore != null && runStore != null
            ? new StopOperations(
                shell,
                definitions,
                specStore,
                runStore,
                hooks.events(),
                StopOperations.sessionHalter(shell),
                hooks.stopListener())
            : null;
  }

  /** The live members of this box's rooms — see {@link DispatchOperations#liveMembers}. */
  public LiveMembers liveMembers() {
    return dispatchOps.liveMembers();
  }

  /**
   * A live member's reply, posted as {@code run}'s principal through the door the {@code spec} CLI
   * posts through, under the actor that CLI would be given: the message rule, the announcement and
   * the sync trigger are the same. Returns the id of the message.
   *
   * @throws ApiException when the run's FDE holds no role any more, or the room refuses the post
   */
  private String postAsRun(
      RunStore.RunRow run, String roomId, String text, MessageStore.Mark mark) {
    var actor =
        runActor(run)
            .orElseThrow(
                () ->
                    new ApiException(
                        ErrorCode.FORBIDDEN,
                        "Run " + run.id() + " acts for nobody this box knows, so it cannot post."));
    var posted =
        Actor.call(
            actor,
            () ->
                postRoomMessage(roomId, new SpecMessageRequest(text, null, mark), actor.handle()));
    return switch (posted) {
      case Result.Success<SpecMessageResponse> landed -> landed.value().message().id();
      case Result.Failure<SpecMessageResponse> refused -> throw ApiException.of(refused);
    };
  }

  /** Launches a room wake through the shared dispatch executor — see {@code startRoomRun} there. */
  public String startRoomRun(String project, String specId, String localHandle) {
    return dispatchOps.startRoomRun(project, specId, localHandle);
  }

  /**
   * Where a project's skills are read from on this box: the project's files once the control plane
   * is wired, none before. The launcher, provisioning and apply share it.
   */
  public ProjectSkills projectSkills() {
    return projectSkills;
  }

  @Override
  public SkillOperations skills() {
    return new SkillOperations(projectSkills, shell, projects);
  }

  @Override
  public StackOperations stack() {
    return new StackOperations(shell, projects, Thread::sleep, System::nanoTime);
  }

  /** The review and fix lanes the review pipeline launches through — see {@code reviewLanes}. */
  public ReviewLanes reviewLanes() {
    return dispatchOps.reviewLanes();
  }

  /** Runs the room commit guard through the shared dispatch executor. */
  public void guardRoomRun(String project, String runId) {
    dispatchOps.guardRoomRun(project, runId);
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
  public Result<FdesResponse> fdes() {
    if (fdeStore == null) {
      return storeUnavailable("FDE roster").failure().asFailure();
    }
    return safe(this::fdesValue);
  }

  /**
   * The roster as the caller may read it ({@link FdeStore#visibleTo}), each FDE's scope named to an
   * admin alone.
   */
  private FdesResponse fdesValue() {
    var reader = Actor.current();
    var roster =
        fdeStore.visibleTo(reader.actingFde(), reader.scope()).stream()
            .map(
                fde ->
                    new FdeSummaryView(
                        fde.handle(),
                        fde.displayName(),
                        fde.email(),
                        fde.role(),
                        reader.isAdmin() ? fdeStore.scopeOf(fde.handle()) : null))
            .toList();
    return new FdesResponse(roster);
  }

  /**
   * The known agent CLIs and their member-mode support, declared at the {@link Harness} seam: read
   * only is offered only where the harness enforces it, and the refusal reason travels so clients
   * grey the option out with the same words the launch gate refuses with.
   */
  @Override
  public Result<AgentsResponse> agents() {
    var agents =
        Harnesses.all().stream()
            .map(
                harness -> {
                  var refusal = harness.readOnlyRefusal();
                  return new AgentView(
                      harness.yamlName(),
                      harness.displayName(),
                      List.of(
                          new AgentModeView(
                              EngagementMode.READ_ONLY.wire(),
                              refusal.isEmpty(),
                              refusal.orElse(null)),
                          new AgentModeView(EngagementMode.FULL.wire(), true, null)));
                })
            .toList();
    return Result.success(new AgentsResponse(agents));
  }

  @Override
  public Result<EngageResponse> addRoomMember(
      String specId, EngageRequest request, String localHandle) {
    freshenForRead();
    var result =
        safe(
            () -> {
              var launch =
                  dispatchOps.engage(
                      specId,
                      request.agent(),
                      request.mode(),
                      request.model(),
                      request.snapshot(),
                      localHandle);
              if (launch.completion() != null) {
                launchExecutor.execute(Actor.carrying(launch.completion()));
              }
              return new EngageResponse(launch.agent(), launch.mode(), launch.snapshot());
            });
    if (result instanceof Result.Success<EngageResponse>) {
      triggerSyncAfterWrite();
    }
    return result;
  }

  @Override
  public Result<SpecMessagesResponse> roomMessages(
      String roomId, String before, String after, int limit) {
    return safeRead(
        () -> {
          if (before != null && after != null) {
            throw new ApiException(
                ErrorCode.BAD_REQUEST, "before and after are exclusive; pass at most one.");
          }
          var room = requireRoomOrSpec(roomId);
          List<SpecMessageView> messages;
          try {
            var store = requireMessageStore();
            var rows =
                after != null
                    ? store.listAfter(room.id(), after, limit)
                    : store.list(room.id(), before, limit);
            messages = rows.stream().map(SpecMessageView::from).toList();
          } catch (IllegalArgumentException invalid) {
            throw ApiException.of(ErrorCode.BAD_REQUEST, invalid);
          }
          return new SpecMessagesResponse(room.id(), messages);
        });
  }

  @Override
  public Result<SpecMessageResponse> postRoomMessage(
      String roomId, SpecMessageRequest request, String authorHandle) {
    return safeWrite(
        () -> {
          var room = requireRoomOrSpec(roomId);
          return appendMessage(room.project(), room.id(), request, authorHandle);
        });
  }

  /**
   * Appends into a room and publishes {@code spec_message_posted} under the project the message is
   * filed in — a reply's is its thread's, wherever the room has moved since — so a stream that
   * cannot read the message is never told of it; {@code project}, the room's, places a message the
   * journal filed nowhere.
   */
  private SpecMessageResponse appendMessage(
      String project, String roomId, SpecMessageRequest request, String author) {
    var store = requireMessageStore();
    MessageStore.MessageRow row;
    try {
      row = store.append(roomId, author, request.body(), request.replyTo(), request.mark());
    } catch (IllegalArgumentException invalid) {
      throw ApiException.of(ErrorCode.BAD_REQUEST, invalid);
    }
    var data = new LinkedHashMap<String, Object>();
    data.put(Event.WellKnownData.MESSAGE_ID, row.id());
    data.put("preview", preview(row.body()));
    if (row.question()) {
      data.put("question", true);
    }
    publishOnBus(
        Event.of(
            Objects.requireNonNullElse(store.projectOf(row), project),
            roomId,
            Event.WellKnownTypes.SPEC_MESSAGE_POSTED,
            author,
            HostInfo.hostname(),
            data));
    triggerSyncAfterWrite();
    return new SpecMessageResponse(SpecMessageView.from(row));
  }

  /**
   * The conversation behind {@code roomId} ({@link #roomOrSpec}) an operation addresses, to read or
   * to post in; {@code ROOM_NOT_FOUND} when there is none, or none the caller's scope admits.
   */
  private RoomStore.RoomRow requireRoomOrSpec(String roomId) {
    return Scoped.row(Actor.reader(), roomOrSpec(roomId), RoomStore.RoomRow::project)
        .orElseThrow(() -> Refusals.roomNotFound(roomId));
  }

  /**
   * The conversation behind {@code roomId}, resolved spec-first: a spec answers as its room, its
   * identity room or the room it was born in, even before that room's row has arrived; a genuinely
   * spec-less room answers from its own row.
   */
  private Optional<RoomStore.RoomRow> roomOrSpec(String roomId) {
    var spec = specStore == null ? null : specStore.findById(roomId).orElse(null);
    if (spec != null) {
      var room =
          roomStore == null ? null : roomStore.findById(spec.roomIdOrIdentity()).orElse(null);
      if (room != null) {
        return Optional.of(room);
      }
      return Optional.of(
          new RoomStore.RoomRow(
              spec.roomIdOrIdentity(),
              spec.project(),
              spec.title(),
              spec.assignee(),
              null,
              null,
              spec.createdBy(),
              spec.createdAt(),
              spec.updatedAt(),
              spec.updatedBy()));
    }
    return roomStore == null ? Optional.empty() : roomStore.findById(roomId);
  }

  @Override
  public Result<RoomDetailResponse> createRoom(RoomCreateRequest request, String localHandle) {
    var actor = Actor.current();
    freshenForRead();
    var result =
        safe(
            () -> {
              var store = requireRoomStore();
              if (request.id() == null || request.id().isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "room id is required.");
              }
              if (request.title() == null || request.title().isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "room title is required.");
              }
              if (request.project() == null || request.project().isBlank()) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "room project is required.");
              }
              NameValidator.requireValidSpecId(request.id());
              requireValidWake(request.wake());
              Function<String, RoomDetailResponse> create =
                  roster ->
                      store.atomically(
                          () -> {
                            requireUnclaimedRoomId(store, request.id());
                            store.create(
                                new RoomStore.RoomRow(
                                    request.id(),
                                    request.project(),
                                    request.title(),
                                    actor.handle(),
                                    request.wake(),
                                    roster,
                                    null,
                                    null,
                                    null,
                                    null));
                            return detailOf(store.findById(request.id()).orElseThrow());
                          });
              if (request.member() == null) {
                return create.apply(null);
              }
              NameValidator.requireValidProjectName(request.project());
              requireUnclaimedRoomId(store, request.id());
              return dispatchOps.createSeated(
                  request.id(),
                  request.project(),
                  actor.handle(),
                  request.member(),
                  localHandle,
                  create);
            });
    if (result instanceof Result.Success<RoomDetailResponse>) {
      triggerSyncAfterWrite();
    }
    return result;
  }

  /**
   * A room id must be free on both sides of the conversation namespace: {@link #requireRoomOrSpec}
   * resolves spec-first, so a room minted under an existing spec's id would be unaddressable —
   * every message to it would land in that spec's home room. Mirrors the reservation a spec birth
   * makes.
   */
  private void requireUnclaimedRoomId(RoomStore store, String id) {
    if (specStore != null && specStore.findById(id).isPresent()) {
      throw new ApiException(
          ErrorCode.CONFLICT,
          "Spec '" + id + "' already owns that conversation id.",
          "Pick a room id that is not a spec id.");
    }
    if (store.findById(id).isPresent()) {
      throw new ApiException(ErrorCode.CONFLICT, "Room '" + id + "' already exists.");
    }
  }

  /** Lists rooms, every project's or one project's, each with its attached specs. */
  @Override
  public Result<RoomsListResponse> rooms(String project) {
    return safeRead(
        () -> {
          var store = requireRoomStore();
          var rows =
              Scoped.rows(
                  Actor.reader(),
                  project == null || project.isBlank() ? store.listAll() : store.list(project),
                  RoomStore.RoomRow::project);
          var views = rows.stream().map(this::viewOf).toList();
          if (messageStore == null) {
            return new RoomsListResponse(views, Map.of(), Map.of());
          }
          return new RoomsListResponse(
              views,
              ofReadableRooms(messageStore.latestByRoom()),
              ofReadableRooms(messageStore.openQuestions()));
        });
  }

  /**
   * {@code byRoom}, a fact per room id, keyed only to the rooms the reader may see: a room is its
   * row's project, and one whose row has not arrived is its spec's.
   */
  private Map<String, String> ofReadableRooms(Map<String, String> byRoom) {
    var readable = new LinkedHashMap<String, String>();
    for (var room :
        Scoped.rows(Actor.reader(), List.copyOf(byRoom.keySet()), this::projectOfRoom)) {
      readable.put(room, byRoom.get(room));
    }
    return readable;
  }

  private String projectOfRoom(String roomId) {
    return roomOrSpec(roomId).map(RoomStore.RoomRow::project).orElse(null);
  }

  @Override
  public Result<RoomDetailResponse> room(String roomId) {
    return safeRead(
        () -> {
          var store = requireRoomStore();
          return detailOf(
              Scoped.row(Actor.reader(), store.findById(roomId), RoomStore.RoomRow::project)
                  .orElseThrow(() -> Refusals.roomNotFound(roomId)));
        });
  }

  @Override
  public Result<RoomDeletedResponse> deleteRoom(String roomId) {
    freshenForRead();
    var result =
        safe(
            () -> {
              var store = requireRoomStore();
              return store.atomically(
                  () -> {
                    Scoped.row(Actor.reader(), store.findById(roomId), RoomStore.RoomRow::project)
                        .orElseThrow(() -> Refusals.roomNotFound(roomId));
                    var attached = specsIn(roomId);
                    var seen = Scoped.rows(Actor.reader(), attached, SpecStore.SpecRow::project);
                    if (!attached.isEmpty()) {
                      throw new ApiException(
                          ErrorCode.CONFLICT,
                          seen.isEmpty()
                              ? "Room '" + roomId + "' holds work outside your project scope."
                              : "Room '" + roomId + "' holds " + seen.size() + " spec(s).",
                          rehoming(seen));
                    }
                    store.delete(roomId);
                    return new RoomDeletedResponse(roomId);
                  });
            });
    if (result instanceof Result.Success<RoomDeletedResponse>) {
      triggerSyncAfterWrite();
    }
    return result;
  }

  private RoomDetailResponse detailOf(RoomStore.RoomRow row) {
    var view = viewOf(row);
    if (messageStore == null) {
      return new RoomDetailResponse(view, null, null);
    }
    return new RoomDetailResponse(
        view,
        messageStore.latestByRoom().get(row.id()),
        messageStore.openQuestions().get(row.id()));
  }

  /** The room as its reader sees it, naming the specs attached to it that the reader may see. */
  private RoomView viewOf(RoomStore.RoomRow row) {
    var specs = Scoped.rows(Actor.reader(), specsIn(row.id()), SpecStore.SpecRow::project);
    return RoomView.from(row, specs.stream().map(SpecStore.SpecRow::id).toList());
  }

  /** What to do about {@code attached}, naming only the specs the reader may see. */
  /** How to empty a room that holds {@code seen}, the specs in it the caller may see. */
  private static String rehoming(List<SpecStore.SpecRow> seen) {
    return seen.isEmpty()
        ? "Ask an admin to re-home its specs: none is in a project this FDE is granted."
        : "Delete or re-home its specs first: "
            + seen.stream().map(SpecStore.SpecRow::id).collect(Collectors.joining(", "));
  }

  private List<SpecStore.SpecRow> specsIn(String roomId) {
    return specStore == null ? List.of() : specStore.listByRoom(roomId);
  }

  private RoomStore requireRoomStore() {
    if (roomStore == null) {
      throw new ApiException(
          ErrorCode.COMMAND_FAILED,
          "This box keeps no room aggregate, so rooms cannot be served.",
          "Wire the room store (useRooms) on this box's operations.");
    }
    return roomStore;
  }

  private static void requireValidWake(String wake) {
    if (wake != null && !Set.of("on", "mention", "off").contains(wake)) {
      throw new ApiException(
          ErrorCode.INVALID_REQUEST, "wake must be one of on, mention, off; got '" + wake + "'.");
    }
  }

  @Override
  public Result<RoomMembersResponse> roomMembers(String roomId) {
    freshenForRead();
    return safe(() -> new RoomMembersResponse(dispatchOps.roomMembers(roomId)));
  }

  @Override
  public Result<DisengageResponse> removeRoomMember(String specId, String localHandle) {
    freshenForRead();
    var result = safe(() -> new DisengageResponse(dispatchOps.disengage(specId, localHandle)));
    if (result instanceof Result.Success<DisengageResponse>) {
      triggerSyncAfterWrite();
    }
    return result;
  }

  @Override
  public Result<ProjectResponse> project(String project) {
    return safe(() -> projectValue(readable(project)));
  }

  @Override
  public Result<ConnectResponse> connect(String project) {
    return safe(() -> connectValue(readable(project)));
  }

  @Override
  public Result<SpecsResponse> specs(String project) {
    return safeRead(() -> specsValue(readable(project)));
  }

  @Override
  public Result<SpecResponse> spec(String project, String specId) {
    return safeRead(() -> specValue(readable(project), specId));
  }

  @Override
  public Result<DispatchResponse> dispatch(
      String project, DispatchRequest request, String localHandle) {
    freshenForRead();
    var result = safe(() -> dispatchValue(readable(project), request, localHandle));
    if (result instanceof Result.Success<DispatchResponse> success
        && success.value().dispatched()) {
      triggerSyncAfterWrite();
    }
    return result;
  }

  @Override
  public Result<SnapshotListResponse> snapshots(String project) {
    return safe(() -> snapshotOps.list(readable(project)));
  }

  @Override
  public Result<SnapshotActionResponse> restoreSnapshot(
      String project, String label, String localHandle) {
    return safe(() -> snapshotOps.restore(readable(project), label, localHandle));
  }

  @Override
  public Result<SnapshotActionResponse> deleteSnapshot(String project, String label) {
    return safe(() -> snapshotOps.delete(readable(project), label));
  }

  @Override
  public Result<AgentStatusResponse> agentStatus(String project, String localHandle) {
    return safe(() -> agentStatusValue(readable(project), localHandle));
  }

  @Override
  public Result<AgentReportResponse> agentReport(String project, String localHandle) {
    return safe(() -> agentReportValue(readable(project), localHandle));
  }

  /** What starts the server that keeps the stores and logs this box answers from. */
  static final Remedy START_SERVER =
      Remedy.of("Start the server with '", Remedy.sail("server start"), "'.");

  /** The refusal for a store this process opened none of. */
  static ApiException storeUnavailable(String store) {
    return new ApiException(ErrorCode.INTERNAL, store + " not available.", START_SERVER);
  }

  /**
   * {@code project} as a route addresses it, to read or to act on: one outside the caller's scope
   * is answered as one the catalog does not hold.
   */
  private String readable(String project) {
    if (!Actor.reader().scope().admits(project)) {
      throw ProjectReader.absent(project);
    }
    return project;
  }

  /**
   * A run acting for no FDE acts for this box's, so a run credential never outranks the FDE whose
   * box launched it; and it sees the projects that FDE does, so a run a scoped FDE started on main
   * reads no more than its owner may.
   */
  @Override
  public Optional<Actor> runActor(RunStore.RunRow run) {
    var lane = run.principalActor();
    var role =
        Strings.isBlank(run.owner())
            ? roles().roleOfUnbound(lane.role())
            : roles().roleOf(run.owner(), lane.role());
    return role.map(
        granted ->
            new Actor(lane.handle(), granted, lane.lane(), lane.owner())
                .withScope(roles().scopeOf(run.owner())));
  }

  @Override
  public Optional<RunStore.RunRow> runForCredential(String credential) {
    return runStore == null ? Optional.empty() : runStore.findByCredential(credential);
  }

  @Override
  public Result<RunListResponse> runs(String project, String spec) {
    return safe(
        () -> {
          syncScheduler.freshenRead();
          var rows =
              Scoped.rows(
                  Actor.reader(), requireRunStore().list(project, spec), RunStore.RunRow::project);
          return new RunListResponse(project, spec, rows.stream().map(RunView::from).toList());
        });
  }

  @Override
  public Result<RunDetailResponse> run(String runId) {
    return safe(
        () -> {
          syncScheduler.freshenRead();
          var run =
              Scoped.row(
                      Actor.reader(), requireRunStore().findById(runId), RunStore.RunRow::project)
                  .orElseThrow(() -> runNotFound(runId));
          return new RunDetailResponse(RunView.from(run));
        });
  }

  @Override
  public Result<RunLogResponse> runLog(String runId, int tail, String localHandle) {
    return onLocalRun(runId, localHandle, run -> safe(() -> runLogValue(run, tail)));
  }

  /**
   * Delegates to {@link StopOperations} — the single clean-stop lane shared with {@code sail agent
   * stop} — and renders its outcome. Any mutation (a halted agent, a cancelled spec, a released
   * run) schedules sync-on-write so the terminal state reaches every peer promptly.
   */
  @Override
  public Result<StopRunResponse> stopRun(String runId, String localHandle) {
    if (stopOps == null) {
      return storeUnavailable("Run store").failure().asFailure();
    }
    var outcome = safe(() -> stopOps.stop(new StopOperations.RunTarget(runId), localHandle, false));
    if (outcome instanceof Result.Failure<StopOperations.Outcome>) {
      return outcome.asFailure();
    }
    var value = outcome.orThrow();
    if (value.mutated()) {
      triggerSyncAfterWrite();
    }
    return Result.success(stopResponse(value));
  }

  private static StopRunResponse stopResponse(StopOperations.Outcome outcome) {
    return switch (outcome) {
      case StopOperations.Stopped stopped ->
          new StopRunResponse(stopped.runId(), true, null, stopped.pid(), stopped.specCancelled());
      case StopOperations.NotRunning notRunning ->
          new StopRunResponse(
              notRunning.runId(), false, notRunning.reason(), null, notRunning.specCancelled());
      case StopOperations.AlreadyTerminal terminal ->
          new StopRunResponse(terminal.runId(), false, terminal.reason(), null, false);
    };
  }

  /**
   * Resolves a run, applies the provenance guard, then the run-owner rule ({@link
   * RunAuthority#access}) before handing it to {@code served}: an absent store is an internal
   * error, an unknown run a 404, a run that executed elsewhere a structured {@code
   * run_on_other_node} refusal, and a caller who is neither the run's spec assignee nor an admin a
   * {@code forbidden_not_assignee}. The served branch only ever sees a local run the caller may
   * access — one guard for both the log tail and stop.
   */
  private <T> Result<T> onLocalRun(
      String runId, String localHandle, Function<RunStore.RunRow, Result<T>> served) {
    if (runStore == null) {
      return storeUnavailable("Run store").failure().asFailure();
    }
    var run =
        Scoped.row(Actor.reader(), runStore.findById(runId), RunStore.RunRow::project).orElse(null);
    if (run == null) {
      return Result.failure(ErrorCode.RUN_NOT_FOUND, "No run '" + runId + "'.");
    }
    if (isForeign(run, localHandle)) {
      return foreignRun(run);
    }
    var refused =
        RunAuthority.access(
            Actor.current(),
            run.id(),
            StopOperations.specIdOf(run),
            RunAuthority.owners(run, this::specOwner));
    if (refused.isPresent()) {
      return Refusals.failure(refused.get());
    }
    return served.apply(run);
  }

  /**
   * The current owner of {@code specId}, empty when the spec is absent or the store not wired, and
   * when the reader may not see the spec: a run stays in the project it ran in, so the spec it
   * worked may have moved to one outside the reader's scope, and its owner is not the run's to say.
   */
  private Optional<String> specOwner(String specId) {
    return specStore == null
        ? Optional.empty()
        : StopOperations.ownerSeen(Actor.reader(), specStore.findById(specId));
  }

  private RunStore requireRunStore() {
    if (runStore == null) {
      throw storeUnavailable("Run store");
    }
    return runStore;
  }

  private static ApiException runNotFound(String runId) {
    return new ApiException(ErrorCode.RUN_NOT_FOUND, "No run '" + runId + "'.");
  }

  /**
   * Whether a run did not execute on this box — the provenance test, the inverse of {@link
   * RunStore.RunRow#ownedBy}. A box with a handle serves only runs stamped with it, so a blank
   * {@code node} fails closed to foreign; a box with no handle serves only its own blank-node runs
   * and never a synced run stamped by another box.
   */
  static boolean isForeign(RunStore.RunRow run, String localHandle) {
    return !run.ownedBy(localHandle);
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
    var declared = loaded.config().agent();
    var agent = declared == null ? null : AgentConfigView.from(loaded.config());
    var member = declared == null ? null : declared.projectMember();
    var handle = Actor.reader().actingFde();
    var homeRoom =
        member == null || Strings.isBlank(handle) ? null : HomeRoom.idOf(project, handle);
    return new ProjectResponse(project, statusName(loaded.state()), agent, member, homeRoom);
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
        Scoped.rows(
            Actor.reader(),
            statuses.entrySet().stream()
                .map(entry -> new ProjectListItemView(entry.getKey(), entry.getValue()))
                .toList(),
            ProjectListItemView::name));
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
    ConnectEnvironment environment;
    try {
      environment = connectEnvironment.get();
    } catch (IllegalStateException damagedKey) {
      throw ApiException.of(ErrorCode.PRECONDITION_FAILED, damagedKey);
    }
    if (Strings.isBlank(environment.serverIp())) {
      throw new ApiException(
          ErrorCode.SERVER_IP_NOT_CONFIGURED,
          "Server IP is not configured on this node.",
          Remedy.of("Run: ", Remedy.sail("host config set server-ip", "<your-server-ip>")));
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
    var summary = SpecCatalog.summarize(specs);
    return new SpecsResponse(
        project,
        specs.stream().map(spec -> SpecView.from(specs, spec)).toList(),
        SpecSummaryView.from(summary.counts()),
        BoardSummaryView.from(summary));
  }

  private SpecResponse specValue(String project, String specId) {
    projects.requireExists(project);
    var specs = specStore.projectSpecs(project);
    var spec = SpecCatalog.findById(specs, specId);
    if (spec == null) {
      throw new ApiException(ErrorCode.SPEC_NOT_FOUND, "Spec '" + specId + "' was not found.");
    }
    var content =
        specStore
            .getContent(specId)
            .map(SpecStore.SpecContent::body)
            .filter(body -> !body.isBlank())
            .orElse(null);
    return new SpecResponse(project, SpecView.from(specs, spec), null, content != null, content);
  }

  private DispatchResponse dispatchValue(
      String project, DispatchRequest request, String localHandle) {
    var outcome =
        dispatchOps.dispatch(
            project,
            new DispatchOperations.Request(
                request.specId(),
                request.mode(),
                request.dryRun(),
                request.repos(),
                request.restart()),
            localHandle);
    return switch (outcome) {
      case DispatchOperations.NoSpecs ignored ->
          new DispatchResponse(project, false, "no_pending_specs", null, null, "", false, false);
      case DispatchOperations.Dispatched dispatched ->
          new DispatchResponse(
              project,
              true,
              null,
              DispatchedSpecView.from(dispatched.taskSpec(), dispatched.branch()),
              AgentStatusView.from(dispatched.agentType(), request.mode(), dispatched.session()),
              dispatched.snapshotLabel(),
              dispatched.branchCreated(),
              dispatched.restarted());
    };
  }

  /**
   * The agent status of a project that may host several concurrent runs: every local {@code
   * running} session run — dispatched or ad-hoc — is probed on its own recorded identity and
   * listed, with the newest one filling the single-session fields so the common one-run case reads
   * exactly as before. No run rows means no session: every agent session is a run.
   */
  private AgentStatusResponse agentStatusValue(String project, String localHandle) {
    projects.requireExists(project);
    var agentSession = new AgentSession(shell);
    var running =
        runStore == null
            ? List.<RunStore.RunRow>of()
            : runStore.listForProject(project).stream()
                .filter(DispatchOperations::ownsLiveAgent)
                .filter(run -> run.ownedBy(localHandle))
                .toList();
    if (running.isEmpty()) {
      return AgentStatusResponse.from(project, null, List.of());
    }
    var runs =
        running.stream()
            .map(
                run ->
                    AgentRunView.from(
                        run, querySession(agentSession, project, StopOperations.runUnit(run))))
            .toList();
    var newest = querySession(agentSession, project, StopOperations.runUnit(running.getFirst()));
    return AgentStatusResponse.from(project, newest, runs);
  }

  /**
   * Tails a local run's own log file — the run-scoped {@code ~/.sail/runs/<runId>/agent.log} — so a
   * log address names exactly one execution, never whatever the shared per-container file currently
   * holds. The path is derived from the run's canonical UUID rather than the persisted {@code
   * log_path}: run rows replicate over sync, so a stored path is untrusted input that could point
   * anywhere the container's dev user can read; the stored one only tells a pre-upgrade review's
   * {@code review.log} from an {@code agent.log} ({@link AgentUnit#readableLogPath}). The
   * provenance guard already established the run is local.
   */
  private RunLogResponse runLogValue(RunStore.RunRow run, int tail) {
    projects.requireExists(run.project());
    if (Strings.isBlank(run.logPath())) {
      return new RunLogResponse(run.id(), List.of(), "This run has no log file.");
    }
    var logPath = AgentUnit.readableLogPath(run.id(), run.logPath());
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

  private AgentReportResponse agentReportValue(String project, String localHandle) {
    var loaded = projects.load(project);
    try {
      var specs = specStore != null ? specStore.projectSpecs(project) : List.<Spec>of();
      var session =
          runStore != null
              ? runStore.latestForProjectOnNode(project, localHandle).orElse(null)
              : null;
      return AgentReportResponse.from(
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

  /** Relaunches a run's watcher — see {@link DispatchOperations#relaunchWatcher}. */
  public Optional<WatcherSpawner.Unit> relaunchWatcher(RunStore.RunRow run) throws IOException {
    return dispatchOps.relaunchWatcher(run);
  }

  private AgentSession.SessionInfo querySession(
      AgentSession session, String project, AgentUnit unit) {
    try {
      return session.queryStatus(project, unit);
    } catch (Exception e) {
      throw new ApiException(ErrorCode.AGENT_STATUS_FAILED, "Failed to query agent status.", e);
    }
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

  /**
   * Publishes a client's event through the one {@link EventDoor}: the bound actor must be allowed
   * to drive what the event names, and the bus takes it as the server stamped it.
   */
  @Override
  public Result<EventPublishResponse> publishEvent(Event event) {
    if (eventBus == null) {
      return Result.failure(
          ErrorCode.INTERNAL,
          "Event bus is not wired into this SailOperations instance.",
          "Use the SailOperations constructor that accepts an EventBus.");
    }
    if (door == null) {
      return Result.failure(
          ErrorCode.INTERNAL,
          "This SailOperations instance has no control-plane database to decide an event by.",
          "Open it through OperationsFactory.");
    }
    var result =
        safe(
            () -> {
              var stamped = eventBus.publish(door.admit(event));
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
          var scope = Actor.reader().scope();
          var events = auditPersister.recent(limit, event -> scope.admits(event.project()));
          var maps = events.stream().map(Event::toMap).toList();
          return new RecentEventsResponse(limit, maps.size(), maps);
        });
  }

  @Override
  public Result<SpecEventsResponse> specEvents(String specId, Long since, int limit) {
    if (Strings.isBlank(specId)) {
      return Result.failure(ErrorCode.INVALID_REQUEST, "spec is required");
    }
    if (since != null && since < 0) {
      return Result.failure(ErrorCode.INVALID_REQUEST, "since must be non-negative, got " + since);
    }
    if (limit <= 0 || limit > 5000) {
      return Result.failure(
          ErrorCode.INVALID_REQUEST, "limit must be between 1 and 5000, got " + limit);
    }
    if (eventStore == null) {
      return Result.success(new SpecEventsResponse(specId, since, limit, 0, List.of()));
    }
    return safe(
        () -> {
          var rows =
              eventStore.forSpec(
                  specId,
                  since,
                  limit,
                  Event.WellKnownTypes.TELEMETRY_TYPES,
                  Actor.reader().scope());
          var maps = rows.stream().map(SailOperations::eventRowMap).toList();
          return new SpecEventsResponse(specId, since, limit, maps.size(), maps);
        });
  }

  /**
   * The wire view of a stored event row, matching the shape live SSE frames and {@code /recent}
   * carry so one client-side decoder serves all three. A row whose data payload or publisher no
   * longer parses is served without it — one damaged row must not take the whole history read down.
   */
  private static Map<String, Object> eventRowMap(EventStore.EventRow row) {
    var map = new LinkedHashMap<String, Object>();
    map.put("v", Event.CURRENT_VERSION);
    map.put("id", row.id());
    map.put("ts", row.timestamp());
    map.put("project", row.project());
    if (Strings.isNotBlank(row.specId())) {
      map.put("spec", row.specId());
    }
    map.put("type", row.type());
    map.put("agent", row.agent());
    map.put("host", row.host());
    putParsed(map, "data", row.data(), row.id());
    putParsed(map, "publisher", row.publisher(), row.id());
    return map;
  }

  private static void putParsed(Map<String, Object> map, String key, String json, long eventId) {
    if (Strings.isBlank(json)) {
      return;
    }
    try {
      var parsed = YamlUtil.parseMap(json);
      if (!parsed.isEmpty()) {
        map.put(key, parsed);
      }
    } catch (RuntimeException e) {
      ApiLog.unexpected("parsing stored event " + key + " for event " + eventId, e);
    }
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
   * ApiException} is a structured refusal, a {@link WriteRefused} is the journal's, answered with
   * its kind's code, an {@link IllegalArgumentException} is a caller error — a validation
   * precondition like "repo not configured in sail.yaml" — surfaced as {@code invalid_request}
   * (400) with its message, the same convention {@code ApiRouter} and {@code LocalApiRouter} apply
   * to exceptions escaping their own routing. Only a truly unexpected exception becomes a generic
   * {@code internal} 500, and its stack trace goes to the journal first.
   */
  private static <T> Result<T> safe(Supplier<T> supplier) {
    try {
      return Result.success(supplier.get());
    } catch (ApiException e) {
      return e.failure().asFailure();
    } catch (WriteRefused e) {
      return Refusals.failure(e.refusal());
    } catch (IllegalArgumentException e) {
      return ApiException.of(ErrorCode.INVALID_REQUEST, e).failure().asFailure();
    } catch (Exception e) {
      if (e instanceof Remedied) {
        return ApiException.of(ErrorCode.INTERNAL, e).failure().asFailure();
      }
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
    return safeRead(
        () -> {
          var listed = globalSpecOps.list(filter);
          if (messageStore == null) {
            return listed;
          }
          return new GlobalSpecsListResponse(
              listed.specs(),
              listed.total(),
              ofReadableRooms(messageStore.latestByRoom()),
              ofReadableRooms(messageStore.openQuestions()));
        });
  }

  @Override
  public Result<GlobalSpecDetailResponse> globalSpec(String specId) {
    return safeRead(
        () -> {
          var detail = globalSpecOps.get(specId);
          if (messageStore == null) {
            return detail;
          }
          return new GlobalSpecDetailResponse(
              detail.spec(),
              detail.body(),
              detail.plan(),
              detail.openFindings(),
              detail.latestRun(),
              ofReadableRooms(messageStore.openQuestions()).get(detail.spec().roomId()));
        });
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
  public Result<RunInboxResponse> runInbox(String runId) {
    return safeRead(
        () -> {
          var run = requireRun(runId);
          if (Strings.isBlank(run.specId())) {
            return new RunInboxResponse(runId, null, List.of(), false);
          }
          var store = requireMessageStore();
          var fetched =
              store.listUndelivered(
                  runRoomId(run), runId, Objects.toString(run.principal(), ""), INBOX_LIMIT + 1);
          var hasMore = fetched.size() > INBOX_LIMIT;
          var fresh = fetched.stream().limit(INBOX_LIMIT).map(SpecMessageView::from).toList();
          return new RunInboxResponse(runId, run.specId(), fresh, hasMore);
        });
  }

  /** The room a run's messages live in: its spec's room, or the raw id for a spec-less run. */
  private String runRoomId(RunStore.RunRow run) {
    if (Strings.isBlank(run.specId()) || specStore == null) {
      return run.specId();
    }
    return specStore
        .findById(run.specId())
        .map(SpecStore.SpecRow::roomIdOrIdentity)
        .orElse(run.specId());
  }

  @Override
  public Result<RunAckResponse> ackRunMessages(String runId, List<String> delivered) {
    return safeWrite(
        () -> {
          var run = requireRun(runId);
          if (delivered.isEmpty()) {
            throw new ApiException(
                ErrorCode.BAD_REQUEST, "delivered must name at least one message id.");
          }
          var store = requireMessageStore();
          for (var messageId : delivered) {
            var onSpec =
                !Strings.isBlank(messageId)
                    && Scoped.row(Actor.reader(), store.findById(messageId), store::projectOf)
                        .filter(message -> message.roomId().equals(runRoomId(run)))
                        .isPresent();
            if (!onSpec) {
              throw new ApiException(
                  ErrorCode.BAD_REQUEST,
                  "delivered must name messages on spec '" + run.specId() + "'.");
            }
          }
          runStore.markDelivered(runId, delivered);
          return new RunAckResponse(runId, delivered.size());
        });
  }

  @Override
  public Result<RunSessionResponse> recordRunSession(
      String runId, String sessionId, String source, String transcriptPath) {
    return safeWrite(
        () -> {
          requireRun(runId);
          if (Strings.isBlank(sessionId)) {
            throw new ApiException(
                ErrorCode.BAD_REQUEST,
                "session_id must not be blank; a prior report is never erased.");
          }
          var session = sessionId.strip();
          var sessionSource = Strings.isBlank(source) ? null : source.strip();
          var transcript = Strings.isBlank(transcriptPath) ? null : transcriptPath.strip();
          runStore.recordSession(runId, session, sessionSource, transcript);
          return new RunSessionResponse(runId, session, sessionSource);
        });
  }

  @Override
  public Result<RoomConversationResponse> recordRoomConversation(
      String roomId, String agent, String sessionId, String source, String transcriptPath) {
    var actor = Actor.current();
    return safe(
        () -> {
          if (Strings.isBlank(sessionId)) {
            throw new ApiException(ErrorCode.BAD_REQUEST, "session_id must not be blank.");
          }
          var room = requireRoomOrSpec(roomId);
          Refusals.enforce(
              PostingRule.decide(actor, room.id(), requireRoomStore().owners(room.id())));
          var cli = Strings.isBlank(agent) ? null : agent.strip();
          var data = new LinkedHashMap<String, Object>();
          data.put("room_id", room.id());
          data.put("session_id", sessionId.strip());
          if (cli != null) {
            data.put("agent", cli);
          }
          if (Strings.isNotBlank(source)) {
            data.put("session_source", source.strip());
          }
          if (Strings.isNotBlank(transcriptPath)) {
            data.put("transcript_path", transcriptPath.strip());
          }
          publishOnBus(
              Event.of(
                  room.project(),
                  room.id(),
                  Event.WellKnownTypes.AGENT_CONVERSATION_STARTED,
                  actor.handle(),
                  HostInfo.hostname(),
                  data));
          return new RoomConversationResponse(room.id(), sessionId.strip(), cli);
        });
  }

  /**
   * The run a delivery or a session report addresses; {@code RUN_NOT_FOUND} when there is none, or
   * its project is outside the caller's scope: a run's credential outlives a revoked grant, and the
   * scope it is resolved with each request does not.
   */
  private RunStore.RunRow requireRun(String runId) {
    if (runStore == null) {
      throw storeUnavailable("Run store");
    }
    return Scoped.row(Actor.reader(), runStore.findById(runId), RunStore.RunRow::project)
        .orElseThrow(
            () -> new ApiException(ErrorCode.RUN_NOT_FOUND, "Run '" + runId + "' was not found."));
  }

  private static final int INBOX_LIMIT = 20;

  private MessageStore requireMessageStore() {
    if (messageStore == null) {
      throw storeUnavailable("Message store");
    }
    return messageStore;
  }

  private static String preview(String body) {
    return Strings.clip(body.replaceAll("\\s+", " ").strip(), 160);
  }

  @Override
  public Result<GlobalSpecHistoryResponse> globalSpecHistory(String specId) {
    return safeRead(() -> globalSpecOps.history(specId));
  }

  @Override
  public Result<PruneReport> pruneSpecs(PruneRequest request) {
    return safeWrite(
        () -> {
          if (pruner == null) {
            throw new ApiException(
                ErrorCode.INTERNAL,
                "This box keeps no erasure log, so nothing can be pruned here.",
                START_SERVER);
          }
          return pruner.prune(request);
        });
  }

  @Override
  public Result<GlobalSpecRestoredResponse> restoreGlobalSpec(
      String specId, SpecRestoreRequest request) {
    return safeWrite(() -> globalSpecOps.restore(specId, request));
  }

  @Override
  public Result<GlobalBoardResponse> globalBoard(String project) {
    return safeRead(
        () -> {
          var board = globalSpecOps.board(project);
          if (messageStore == null) {
            return board;
          }
          var open = ofReadableRooms(messageStore.openQuestions()).keySet();
          var count =
              globalSpecOps
                  .readable(new SpecStore.SpecFilter(project, null, null, null, null))
                  .stream()
                  .filter(row -> open.contains(row.id()))
                  .count();
          return new GlobalBoardResponse(board.board(), board.doneOpenFindings(), (int) count);
        });
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
  public Result<ReviewApproveResponse> approveReview(String reviewId) {
    return safeWrite(() -> reviewOps.approve(reviewId));
  }

  @Override
  public Result<FindingDismissResponse> dismissFinding(String reviewId, String findingId) {
    return safeWrite(() -> reviewOps.dismissFinding(reviewId, findingId));
  }
}
