/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.EventBus;
import ai.singlr.sail.api.EventRetentionSweeper;
import ai.singlr.sail.api.MissedStopReconciler;
import ai.singlr.sail.api.ReviewWiring;
import ai.singlr.sail.api.RunTracker;
import ai.singlr.sail.api.SailApiServer;
import ai.singlr.sail.api.SailOperations;
import ai.singlr.sail.api.ServerConnectionConfig;
import ai.singlr.sail.api.SessionAwareAuth;
import ai.singlr.sail.api.SlackReactor;
import ai.singlr.sail.api.SpecStoreAuditPersister;
import ai.singlr.sail.api.TokenAuth;
import ai.singlr.sail.api.WatcherRearmer;
import ai.singlr.sail.api.WebauthnAuthHandler;
import ai.singlr.sail.auth.EnrollmentService;
import ai.singlr.sail.auth.PasskeyService;
import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.WebauthnConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.BindPolicy;
import ai.singlr.sail.engine.GracefulShutdown;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.DataMigration;
import ai.singlr.sail.store.EnrollmentTicketStore;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.ExpiredRowSweeper;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.MigrationRunner;
import ai.singlr.sail.store.PendingChallengeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SlackThreadStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.StuckSpecReconciler;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.store.WebauthnCredentialStore;
import ai.singlr.sail.webauthn.RelyingParty;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Starts the control-plane server. The API is a single-trust-level surface: every issued token is a
 * full-access operator credential (there is no role separation), and it can dispatch agents — which
 * is code execution inside project containers. The security boundary is therefore the bind address,
 * which defaults to loopback. Binding a non-loopback address exposes that surface to the network
 * over plaintext HTTP and must be opted into explicitly (put it behind a TLS reverse proxy).
 */
@Command(
    name = "start",
    description = "Start the Sail control plane server.",
    mixinStandardHelpOptions = true)
public final class ServerStartCommand implements Runnable {

  @Option(
      names = "--host",
      description = "Host to bind. Defaults to loopback; use 0.0.0.0 to expose on the network.",
      defaultValue = "127.0.0.1")
  private String host;

  @Option(names = "--port", description = "Port to bind.", defaultValue = "7070")
  private int port;

  @Option(
      names = "--allow-remote",
      description =
          "Permit binding a non-loopback address. The API is plaintext HTTP and any token can"
              + " dispatch agents; only expose it behind a TLS reverse proxy with restricted access.")
  private boolean allowRemote;

  @Option(
      names = "--rp-id",
      description =
          "WebAuthn Relying Party ID for passkey login (the registrable domain the proxy serves)."
              + " Overrides the host.yaml webauthn block.")
  private String rpId;

  @Option(
      names = "--rp-name",
      description = "Human-facing Relying Party name shown during passkey enrollment.")
  private String rpName;

  @Option(
      names = "--origin",
      description =
          "Allowed passkey origin (e.g. https://sail.example.dev). Repeatable. Overrides the"
              + " host.yaml webauthn origins.")
  private List<String> origins;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    BindPolicy.requireBindable(host, allowRemote);
    var dbPath = SailPaths.controlPlaneDb();
    SailPaths.ensureDataDir(dbPath.getParent());

    var db = Sqlite.open(dbPath);
    var shutdown = new GracefulShutdown().register(db);
    Runtime.getRuntime().addShutdownHook(new Thread(shutdown::shutdown, "sail-shutdown"));
    var migrationResult =
        MigrationRunner.applyAll(
            db, MigrateCommand.REGISTRY, DataMigration.Prompter.NON_INTERACTIVE);
    if (migrationResult.schemaAfter() > migrationResult.schemaBefore()) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Schema migrated: "
                  + migrationResult.schemaBefore()
                  + " → "
                  + migrationResult.schemaAfter()));
    }
    for (var run : migrationResult.dataRuns()) {
      if (!run.alreadyApplied()
          && (run.report().applied() > 0
              || run.report().ambiguous() > 0
              || run.report().skipped() > 0)) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ "
                    + run.name()
                    + ": "
                    + run.report().applied()
                    + " applied, "
                    + run.report().ambiguous()
                    + " ambiguous, "
                    + run.report().skipped()
                    + " skipped"));
      }
    }

    var tokenStore = new TokenStore(db);
    var configPath = SailPaths.clientConfigPath();
    if (tokenStore.list().isEmpty()) {
      var created = tokenStore.create("admin", "admin");
      ServerConnectionConfig.saveLocalToken(created.token(), configPath);
      System.out.println(
          Ansi.AUTO.string("  @|green ✓|@ API token created and saved to " + configPath));
      System.out.println();
    }
    var specStore = new SpecStore(db);
    var eventStore = new EventStore(db);
    var bus = new EventBus();
    var persister = new SpecStoreAuditPersister(eventStore);
    var reviewStore = new ReviewStore(db);
    var runStore = new RunStore(db);
    var messageStore = new MessageStore(db);
    var syncScheduler = NodeSync.scheduler(false);
    shutdown.register(syncScheduler);
    var operations =
        new SailOperations(
            new ShellExecutor(false),
            SailPaths.PROJECT_DESCRIPTOR,
            bus,
            persister,
            specStore,
            reviewStore,
            runStore,
            messageStore,
            new ProjectStore(db),
            syncScheduler,
            new FdeStore(db));
    var orphaned = reviewStore.failOrphanedRunning();
    var orphanedRuns = runStore.failRunningReviewsOnNode(NodeIdentity.handle());
    if (orphanedRuns > 0) {
      syncScheduler.afterWrite();
    }
    if (orphaned > 0) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|yellow ⚠|@ Failed "
                  + orphaned
                  + " review(s) interrupted by a restart (they were blocking their specs)"));
    }
    var reviewController =
        ReviewWiring.controller(
                specStore,
                reviewStore,
                bus,
                ServerStartCommand::loadProjectYaml,
                new ShellExecutor(false),
                syncScheduler::afterWrite,
                runStore,
                NodeIdentity::handle)
            .useMessages(messageStore);
    bus.subscribe(new RunTracker(runStore, syncScheduler, NodeIdentity::handle));
    if (narratesSlack(HostSync.config())) {
      bus.subscribe(SlackReactor.withDefaults(new SlackThreadStore(db), specStore));
    } else {
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint Slack narration is main's job — this node's work is announced there once"
                  + " it syncs.|@"));
    }

    var webauthn = resolveWebauthn();
    var configured = webauthn.isConfigured();
    var passkeyService = configured ? buildPasskeyService(db, webauthn) : null;
    var enrollment =
        configured ? new EnrollmentService(new EnrollmentTicketStore(db), new FdeStore(db)) : null;
    var passkeyHandler =
        new WebauthnAuthHandler(
            passkeyService,
            enrollment,
            new TokenAuth(tokenStore),
            configured ? webauthn.origins() : null);
    var auth =
        new SessionAwareAuth(new AuthSessionStore(db), new FdeStore(db), new TokenAuth(tokenStore));

    var server =
        new SailApiServer(
            host,
            port,
            operations,
            auth,
            bus,
            persister,
            SailPaths.apiSocketPath(),
            passkeyHandler,
            specStore,
            reviewController);
    var sweeper = new ExpiredRowSweeper(dbPath);
    var eventSweeper = new EventRetentionSweeper(eventStore);
    var reconciler =
        new StuckSpecReconciler(
            dbPath, StuckSpecReconciler.DEFAULT_THRESHOLD, stranded -> surface(bus, stranded));
    var reconcileShell = new ShellExecutor(false);
    var unitProbe = MissedStopReconciler.systemdUnitProbe(reconcileShell);
    var missedStops =
        new MissedStopReconciler(
            specStore,
            runStore,
            eventStore,
            reviewStore,
            bus,
            unitProbe,
            NodeIdentity::handle,
            DateTimeUtils::now);
    var watcherSpawner = new WatcherSpawner(reconcileShell, null);
    var rearmer =
        new WatcherRearmer(
            runStore,
            WatcherRearmer.systemdUnitActiveProbe(reconcileShell),
            watcherSpawner::watcherProcessRunningForRun,
            WatcherRearmer.livingProcess(),
            NodeIdentity::handle,
            operations::relaunchWatcher);
    shutdown
        .register(server)
        .register(sweeper)
        .register(eventSweeper)
        .register(reconciler)
        .register(missedStops)
        .register(rearmer);
    try {
      server.start();
      sweeper.start();
      eventSweeper.start();
      reconciler.start();
      var replayed = missedStops.sweep();
      if (replayed > 0) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Replayed " + replayed + " agent stop(s) missed while offline"));
      }
      missedStops.start();
      var rearmed = rearmer.rearm();
      rearmer.start();
      if (rearmed > 0) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|green ✓|@ Re-armed "
                    + rearmed
                    + " guardrail watcher(s) for agents still running unwatched"));
      }
      System.out.println(
          Ansi.AUTO.string(
              "  @|green ✓|@ Sail server listening on http://" + host + ":" + server.port()));
      if (webauthn.isConfigured()) {
        System.out.println(
            Ansi.AUTO.string("    @|faint Passkey login enabled for " + webauthn.rpId() + "|@"));
      }
      if (!BindPolicy.isLoopback(host)) {
        System.out.println(
            Ansi.AUTO.string(
                "  @|yellow ⚠|@ Bound to a non-loopback address over plaintext HTTP. Any holder"
                    + " of a token can dispatch agents (code execution in containers). Put this"
                    + " behind a TLS reverse proxy and restrict network access."));
      }
      System.out.println(Ansi.AUTO.string("    @|faint Database: " + dbPath + "|@"));
      System.out.println(Ansi.AUTO.string("    @|faint Press Ctrl+C to stop.|@"));
      new CountDownLatch(1).await();
    } finally {
      shutdown.shutdown();
    }
  }

  private WebauthnConfig resolveWebauthn() throws Exception {
    var hostConfigPath = SailPaths.hostConfigPath();
    var base =
        Files.exists(hostConfigPath)
            ? HostYaml.fromMap(YamlUtil.parseFile(hostConfigPath)).webauthn()
            : WebauthnConfig.disabled();
    return new WebauthnConfig(
        rpId != null ? rpId : base.rpId(),
        rpName != null ? rpName : base.rpName(),
        origins != null && !origins.isEmpty() ? origins : base.origins(),
        base.sessionTtlHours());
  }

  private static PasskeyService buildPasskeyService(Sqlite db, WebauthnConfig webauthn) {
    var relyingParty =
        new RelyingParty(
            webauthn.rpId(), webauthn.resolvedRpName(), Set.copyOf(webauthn.origins()));
    return new PasskeyService(
        relyingParty,
        new FdeStore(db),
        new WebauthnCredentialStore(db),
        new AuthSessionStore(db),
        new PendingChallengeStore(db),
        webauthn.sessionTtl());
  }

  /**
   * Whether this box is the single Slack notification authority. Main and a standalone box narrate
   * their own state; a node never posts — its transitions sync to main, whose {@code _sync} process
   * turns them into the lifecycle events main's reactor announces. Exactly one notifier per fleet.
   */
  public static boolean narratesSlack(SyncConfig sync) {
    return !HostSync.isNode(sync);
  }

  /**
   * Surfaces stranded specs as triage signals: a server log line and a {@code spec_stranded} event.
   */
  private static void surface(EventBus bus, java.util.List<SpecStore.SpecRow> stranded) {
    for (var s : stranded) {
      System.err.println(
          "  [reconciler] spec '"
              + s.id()
              + "' ("
              + s.project()
              + ") stranded in "
              + s.status().wire()
              + " since "
              + s.updatedAt());
      bus.publish(
          Event.of(
              s.project(),
              s.id(),
              Event.WellKnownTypes.SPEC_STRANDED,
              Event.SAIL_AGENT,
              HostInfo.hostname(),
              java.util.Map.of(
                  "status", s.status().wire(), "since", String.valueOf(s.updatedAt()))));
    }
  }

  /** Loads a project's {@code sail.yaml}, or {@code null} when it is missing or unreadable. */
  private static SailYaml loadProjectYaml(String project) {
    try {
      return SailYaml.fromMap(
          YamlUtil.parseFile(SailPaths.resolveSailYaml(project, SailPaths.PROJECT_DESCRIPTOR)));
    } catch (Exception e) {
      return null;
    }
  }
}
