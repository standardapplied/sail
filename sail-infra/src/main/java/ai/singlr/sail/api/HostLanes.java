/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.Spec;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.AgentUnit;
import ai.singlr.sail.engine.DemoSeeder;
import ai.singlr.sail.engine.HostAccess;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectCatalogRename;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** The host facets as thin adapters over the stores and the shared executors. */
final class HostLanes {
  private HostLanes() {}

  record Dispatching(
      DispatchOperations dispatchOps,
      StopOperations stopOps,
      RunStore runs,
      ReviewStore reviews,
      ShellExec shell)
      implements HostDispatching {
    @Override
    public DispatchOperations.Outcome dispatch(
        String project, DispatchOperations.Request request, Actor actor, String localHandle) {
      return dispatchOps.dispatch(project, request, actor, localHandle);
    }

    @Override
    public DispatchOperations.AdhocSession startAdhoc(
        String project, DispatchOperations.AdhocRequest request, String localHandle) {
      return dispatchOps.startAdhoc(project, request, localHandle);
    }

    @Override
    public DispatchOperations.AdhocSession startAdhoc(
        String project,
        DispatchOperations.AdhocRequest request,
        String localHandle,
        DispatchOperations.AdhocPreparer preparer) {
      return dispatchOps.startAdhoc(project, request, localHandle, preparer);
    }

    @Override
    public StopOperations.Outcome stop(
        StopOperations.Target target, Actor actor, String localHandle, boolean dryRun) {
      return stopOps.stop(target, actor, localHandle, dryRun);
    }

    @Override
    public Optional<RunStore.RunRow> latestRun(String project, String node) {
      return runs.latestForProjectOnNode(project, node);
    }

    @Override
    public Optional<RunStore.RunRow> activeRun(String project, String node) {
      return runs.runningForProjectOnNode(project, node);
    }

    @Override
    public List<DispatchGate.RunningRun> runningRuns(String project, String node) {
      return runs.runningOnNode(project, node);
    }

    @Override
    public AgentSession.SessionInfo projectSession(String project, String node) throws Exception {
      return StopOperations.resolveSession(shell, runs, project, node);
    }

    @Override
    public String reviewLog(String project, String node) {
      return runs.listForProject(project).stream()
          .filter(RunStore.RunRow::buildRole)
          .filter(run -> Objects.toString(run.node(), "").equals(Objects.toString(node, "")))
          .findFirst()
          .map(RunStore.RunRow::specId)
          .flatMap(reviews::latestReviewForSpec)
          .map(review -> AgentUnit.forReview(review.id()).logPath())
          .orElseGet(AgentUnit.REVIEW::logPath);
    }
  }

  record Catalog(
      Sqlite db,
      ProjectStore projectStore,
      SpecStore specs,
      RoomStore rooms,
      HostSchema schema,
      SpecPruner pruner,
      Supplier<Actor> operator)
      implements HostCatalog {
    @Override
    public Optional<ProjectStore.ProjectRow> project(String project) {
      return projectStore.findByName(project);
    }

    @Override
    public List<ProjectStore.ProjectRow> projects() {
      return projectStore.list();
    }

    @Override
    public List<Spec> projectSpecs(String project) {
      return specs.projectSpecs(project);
    }

    @Override
    public Optional<SpecStore.SpecContent> specContent(String id) {
      return specs.getContent(id);
    }

    @Override
    public boolean roomKnown(String room) {
      return room != null && rooms.findById(room).isPresent();
    }

    @Override
    public String demoDefinition() {
      schema.initialize();
      DemoSeeder.seedIfAbsent(db);
      return projectStore
          .findByName("demo")
          .map(ProjectStore.ProjectRow::definition)
          .orElseThrow(
              () ->
                  new IllegalStateException(
                      "Demo project is missing from the catalog. Run 'sudo sail migrate'."));
    }

    @Override
    public List<String> projectsWithFiles() {
      return List.copyOf(new FileStore(db).projectsWithFiles());
    }

    /**
     * A purge is a prune of the whole project through the one prune method: its catalog row, specs,
     * rooms, runs, files and every history of them, erased everywhere. On a node it is asked of
     * main, which decides it on its own copy, and this box follows on its next sync.
     */
    @Override
    public Destroyed destroy(String name, boolean purge) {
      NameValidator.requireValidProjectName(name);
      if (!purge) {
        return new Destroyed(name, false, false);
      }
      var report = purge(name, false);
      return new Destroyed(
          name, report.requested() || !report.entries().isEmpty(), report.requested());
    }

    @Override
    public String purgeSummary(String name) {
      NameValidator.requireValidProjectName(name);
      return purge(name, true).summary();
    }

    private PruneReport purge(String name, boolean dryRun) {
      schema.initialize();
      var actor = operator.get();
      return Actor.call(actor, () -> pruner.prune(PruneRequest.project(name, dryRun), actor));
    }

    @Override
    public Renamed rename(String from, String to) {
      return Actor.call(operator.get(), () -> ProjectCatalogRename.rename(db, from, to));
    }

    @Override
    public void undoRename(Renamed renamed) {
      Actor.run(operator.get(), () -> ProjectCatalogRename.restore(db, renamed));
    }
  }

  record Identity(Sqlite db, FdeStore fdes, Supplier<Actor> cliOperator) implements HostIdentity {
    @Override
    public Actor operator() {
      return cliOperator.get();
    }

    @Override
    public List<TokenStore.TokenInfo> tokens() {
      return new TokenStore(db).list();
    }

    @Override
    public TokenStore.CreatedToken createToken(
        String name, String role, String fdeId, Duration ttl) {
      return new TokenStore(db).create(name, role, fdeId, ttl);
    }

    @Override
    public boolean revokeToken(String name) {
      return new TokenStore(db).revoke(name);
    }

    @Override
    public Optional<FdeStore.Fde> fde(String handle) {
      return fdes.byHandle(handle);
    }

    @Override
    public List<FdeSshKeyStore.SshKeyInfo> sshKeys() {
      return new FdeSshKeyStore(db).list();
    }

    @Override
    public SshGateway.Decision authorizeGateway(String command, String handle) {
      return SshGateway.authorize(command, handle, fdes, new AuthSessionStore(db));
    }
  }

  record Pty(Sqlite db, EventStore events) implements HostPty {
    @Override
    public PtyIdentity identity(String token, String boxHandle) throws IOException {
      return new HostAccess(db).identity(token, boxHandle);
    }

    @Override
    public void admitRoom(String room, String project, PtyIdentity identity) throws IOException {
      new HostAccess(db).admit(room, project, identity);
    }

    @Override
    public void recordEvent(EventStore.EventRow event) {
      events.insert(event);
    }
  }

  record Schema(Sqlite db, SyncOperations sync) implements HostSchema {
    @Override
    public int version() {
      return new SchemaManager(db).currentVersion();
    }

    @Override
    public Migration initialize() {
      var manager = new SchemaManager(db);
      var before = manager.currentVersion();
      manager.migrate();
      return new Migration(before, manager.currentVersion());
    }

    @Override
    public void prepareSync() {
      sync.prepare();
    }

    @Override
    public BlobStore.Collected collectContent() {
      prepareSync();
      return new BlobStore(db).gc(BlobStore.Compaction.ALL, true);
    }
  }
}
