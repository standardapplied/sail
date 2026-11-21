/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.common.DateTimeUtils;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.Spec;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.engine.AgentSession;
import ai.singlr.sail.engine.BoxInstances;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.DemoSeeder;
import ai.singlr.sail.engine.HostAccess;
import ai.singlr.sail.engine.HostToken;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ProjectCatalogRename;
import ai.singlr.sail.engine.SessionLogin;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.engine.WorkspaceOwner;
import ai.singlr.sail.engine.Workspaces;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.RoleRule;
import ai.singlr.sail.pty.PtyAdmission;
import ai.singlr.sail.pty.PtyIdentity;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.ssh.SshGateway;
import ai.singlr.sail.store.AttentionStore;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.BlobStore;
import ai.singlr.sail.store.DispatchGate;
import ai.singlr.sail.store.EventStore;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.LoginStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.RoomStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** The host facets as thin adapters over the stores and the shared executors. */
final class HostLanes {
  private HostLanes() {}

  record Dispatching(
      DispatchOperations dispatchOps,
      StopOperations stopOps,
      RunStore runs,
      Function<String, Optional<SpecStore.SpecRow>> specs,
      ShellExec shell)
      implements HostDispatching {
    @Override
    public DispatchOperations.Outcome dispatch(
        String project, DispatchOperations.Request request, String localHandle) {
      return dispatchOps.dispatch(project, request, localHandle);
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
        StopOperations.Target target, String localHandle, boolean dryRun) {
      return stopOps.stop(target, localHandle, dryRun);
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
    public List<RunStore.RunRow> liveRuns(String node) {
      return runs.liveUnder(node);
    }

    @Override
    public AgentSession.SessionInfo projectSession(String project, String node) throws Exception {
      return StopOperations.resolveSession(shell, runs, project, node);
    }

    @Override
    public boolean endLiveMember(String runId, String why) {
      return dispatchOps.liveMembers().endHolder(runId, why);
    }

    @Override
    public Optional<RunStore.RunRow> latestOperable(
        String project, String node, Predicate<RunStore.RunRow> which) {
      return operable(
          project,
          node,
          () ->
              runs.listForProject(project).stream()
                  .filter(run -> run.ownedBy(node))
                  .filter(which)
                  .findFirst());
    }

    /**
     * The run {@code latest} finds, once the reader reads {@code project}, if it may operate it.
     */
    private Optional<RunStore.RunRow> operable(
        String project, String node, Supplier<Optional<RunStore.RunRow>> latest) {
      var actor = Actor.reader();
      ProjectGate.requireReads(project);
      return latest
          .get()
          .map(
              run ->
                  switch (LogAdmission.of(actor, Optional.of(run), run.id(), node, specs)) {
                    case Result.Success<RunStore.RunRow> admitted -> admitted.value();
                    case Result.Failure<RunStore.RunRow> refused -> throw ApiException.of(refused);
                  });
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
    public ProjectStore.ProjectRow readable(String project) {
      return ProjectGate.readable(projectStore, project);
    }

    @Override
    public ProjectReader definitions() {
      schema.initialize();
      return ProjectReader.ofCatalog(projectStore);
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
    public ProjectDefinition demoDefinition() {
      schema.initialize();
      DemoSeeder.seedIfAbsent(db);
      return projectStore
          .findByName("demo")
          .map(ProjectStore.ProjectRow::definition)
          .orElseThrow(
              () ->
                  Remedied.state(
                      "The demo project isn't on this box.",
                      Remedy.of(
                          "On main or a standalone box, '",
                          Remedy.sail("migrate"),
                          "' seeds it; a node gets it from main once an admin grants it there: ",
                          Remedy.sail("fde grant", "<handle>", "demo"),
                          ".")));
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
      return Actor.call(actor, () -> pruner.prune(PruneRequest.project(name, dryRun)));
    }

    @Override
    public void requireRenamable(String from) {
      Refusals.enforce(Actor.call(operator.get(), () -> new ProjectStore(db).decideRename(from)));
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

  record Identity(
      Sqlite db, Supplier<SyncConfig> boxConfig, RoleRule roles, Supplier<Actor> cliOperator)
      implements HostIdentity {
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
    public void mintHostToken(Path configPath) throws IOException {
      HostToken.mint(new TokenStore(db), TokenStore.DEFAULT_TTL, configPath);
    }

    @Override
    public SyncConfig box() {
      return boxConfig.get();
    }

    @Override
    public boolean revokeToken(String name) {
      return new TokenStore(db).revoke(name);
    }

    @Override
    public Optional<FdeStore.Fde> fde(String handle) {
      return new FdeStore(db).byHandle(handle);
    }

    @Override
    public boolean activePerson(String fdeId) {
      return new FdeStore(db).byId(fdeId).filter(FdeStore.Fde::active).isPresent();
    }

    @Override
    public List<FdeSshKeyStore.SshKeyInfo> sshKeys() {
      return new FdeSshKeyStore(db).admitted();
    }

    @Override
    public WorkspaceOwner workspaceOwner(String handle) {
      return WorkspaceOwner.onThisBox(new FdeStore(db), handle, boxConfig.get().handle());
    }

    @Override
    public Optional<Map<String, String>> gitTokens() {
      var fdes = new FdeStore(db);
      return Optional.ofNullable(boxConfig.get().handle())
          .flatMap(fdes::byHandle)
          .map(fde -> fdes.logins().gitTokens(fde.id()));
    }

    @Override
    public SshGateway.Decision authorizeGateway(String command, String handle) {
      return SshGateway.authorize(
          command, handle, new FdeStore(db), roles, new AuthSessionStore(db));
    }

    @Override
    public SshGateway.Decision authorizeGatewayById(String command, String fdeId) {
      return SshGateway.authorizeById(
          command, fdeId, new FdeStore(db), roles, new AuthSessionStore(db));
    }
  }

  record Pty(
      Sqlite db,
      RoleRule roles,
      EventStore events,
      BoxInstances box,
      Workspaces workspaces,
      ProjectReader definitions,
      Function<String, WorkspaceOwner> owners,
      ShellExec shell)
      implements HostPty {

    /** How long a login's hand-over to a workspace may take. */
    static final Duration HAND_OVER = Duration.ofSeconds(30);

    @Override
    public PtyIdentity.Person identity(String token) throws IOException {
      return new HostAccess(db, roles).identity(token);
    }

    @Override
    public PtyIdentity.Person holder(String credentialHash) throws IOException {
      return new HostAccess(db, roles).holder(credentialHash);
    }

    @Override
    public void admitBox(PtyIdentity.Person who) throws IOException {
      new HostAccess(db, roles).box(who);
    }

    /**
     * Admits {@code identity} into {@code project} when it holds the project on this box ({@link
     * ProjectGate#readable}): the catalog holds it and its person's scope admits it, whatever their
     * role. One it does not hold is not in the catalog, as a project this box does not have.
     */
    @Override
    public void admitProject(String project, PtyIdentity.Person who) throws IOException {
      try {
        Actor.run(who.actor(), () -> ProjectGate.readable(new ProjectStore(db), project));
      } catch (ApiException absent) {
        throw Remedied.io(absent.reason(), absent.remedy());
      }
    }

    /**
     * Refuses an open in {@code project} when the instance of its name is a workspace, a template
     * or a copy, not its container (W10), and is {@link PtyAdmission.Unavailable} when Incus fails
     * or cannot say within {@value BoxInstances#LOOKUP_SECONDS} s what it is: asked of the one
     * instance by name, once per open.
     */
    @Override
    public void admitInstance(String project) throws IOException {
      Optional<BoxInstances.Instance> instance;
      try {
        instance = box.lookup(project, Duration.ofSeconds(BoxInstances.LOOKUP_SECONDS));
      } catch (IOException | TimeoutException failed) {
        throw new PtyAdmission.Unavailable(failed);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new PtyAdmission.Unavailable(interrupted);
      }
      var taken = BoxInstances.taken(project, instance);
      if (taken.isPresent()) {
        throw Remedied.io(taken.get().reason(), taken.get().remedy());
      }
    }

    @Override
    public void admitWorkspace(String project, String workspace, PtyIdentity.Person who)
        throws IOException {
      decide(project, workspace, found(project, workspace), who, WorkspaceDoors.TERMINAL);
    }

    /**
     * Admits {@code who} to the files of workspace {@code workspace} of {@code project} as entering
     * it is decided ({@link #admitWorkspace}, N1 on a node included), in the act's own words, and
     * refuses it {@link PtyAdmission.Stopped} unless it is running: nothing here starts it.
     */
    @Override
    public void admitFiles(String project, String workspace, PtyIdentity.Person who)
        throws IOException {
      var found = found(project, workspace);
      decide(project, workspace, found, who, WorkspaceDoors.FILES);
      if (!(found.state() instanceof ContainerState.Running)) {
        throw new PtyAdmission.Stopped(
            "Workspace "
                + workspace
                + " of "
                + project
                + " is stopped, and reading or changing its"
                + " files does not start it.",
            Remedy.of(
                "Start it with: ",
                Remedy.sail("project workspace start", project, workspace),
                "."));
      }
    }

    /**
     * The stored login {@code agent} starts with in {@code who}'s own workspace {@code workspace},
     * empty when it signs in inside. Whose a workspace is, is {@link AgentStarts#person}: its
     * labelled person, or on a node the node's own person only for one no one is labelled for; its
     * login is looked up by the FDE id the workspace is labelled for, as its git tokens are.
     */
    @Override
    public Optional<LoginStore.Login> admitAgent(
        String project, String workspace, PtyMessage.Agent agent, PtyIdentity.Person who)
        throws IOException {
      var found = found(project, workspace);
      var owner = personOf(found);
      var harness = Harnesses.of(agent.harness());
      var refused =
          WorkspaceDoors.labelledForAnother(
                  project,
                  workspace,
                  found,
                  owners,
                  WorkspaceDoors.AGENT,
                  roles.nodeFde().isPresent())
              .or(() -> AgentStarts.notTheirs(workspace, owner, who.actor().handle()))
              .or(() -> AgentStarts.notInstalled(definitions.require(project), harness));
      if (refused.isPresent()) {
        throw told(refused.get());
      }
      if (agent.inside()) {
        return Optional.empty();
      }
      try {
        return Optional.of(
            AgentStarts.login(
                new FdeStore(db), owners.apply(owner).id(), owner, harness, workspace));
      } catch (ApiException noLogin) {
        throw told(noLogin);
      }
    }

    /** Whose workspace {@code instance} is for an agent start ({@link AgentStarts#person}). */
    private String personOf(BoxInstances.Instance instance) {
      return AgentStarts.person(instance, owners, roles.nodeFde().orElse(null));
    }

    /**
     * Writes the login under the workspace's lock, once its person is read again there and is still
     * {@code who}: a re-own while the workspace was entered refuses it.
     */
    @Override
    public void handOver(
        String project, String workspace, String file, String lines, PtyIdentity.Person who)
        throws IOException {
      try {
        workspaces.underLock(
            project,
            workspace,
            instance -> who.actor().handle().equals(personOf(instance)),
            instance -> {
              var ran =
                  shell.execWithInput(asDev(instance, SessionLogin.HAND, file), lines, HAND_OVER);
              if (!ran.ok()) {
                throw new IOException("the hand-over ended " + ran.exitCode());
              }
            });
      } catch (TimeoutException late) {
        throw new IOException("the hand-over did not end within " + HAND_OVER.toSeconds() + " s");
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IOException("the hand-over was interrupted");
      }
    }

    @Override
    public void withdraw(String instance, String file) {
      try {
        shell.exec(asDev(instance, SessionLogin.REMOVE, file));
      } catch (IOException | TimeoutException failed) {
        System.err.println("  [pty] a login file was not withdrawn: " + failed.getMessage());
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    private static List<String> asDev(String instance, String script, String file) {
      return ContainerExec.asDevUser(
          instance, "/", Map.of(), List.of("sh", "-c", script, "sail-login", file));
    }

    /**
     * The instance of workspace {@code workspace} of {@code project}: {@link PtyAdmission.Absent}
     * when there is none, {@link PtyAdmission.Unavailable} when Incus cannot say.
     */
    private BoxInstances.Instance found(String project, String workspace) throws IOException {
      Optional<BoxInstances.Instance> found;
      try {
        found = workspaces.find(project, workspace);
      } catch (IOException | TimeoutException failed) {
        throw new PtyAdmission.Unavailable(failed);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new PtyAdmission.Unavailable(interrupted);
      }
      if (found.isEmpty()) {
        var absent = Workspaces.absent(project, workspace);
        throw new PtyAdmission.Absent(absent.reason(), absent.remedy());
      }
      return found.get();
    }

    @Override
    public void enterWorkspace(String project, String workspace, PtyIdentity.Person who)
        throws IOException {
      try {
        workspaces.startExisting(
            definitions.require(project),
            workspace,
            owners,
            found -> {
              decide(project, workspace, found, who, WorkspaceDoors.TERMINAL);
              Workspaces.goneOwner(found, owners)
                  .ifPresent(
                      gone -> {
                        throw WorkspaceDoors.goneOwner(
                            project,
                            workspace,
                            found,
                            gone,
                            WorkspaceDoors.STARTED,
                            roles.nodeFde().isPresent());
                      });
            });
      } catch (Exception failed) {
        if (failed instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
        throw told(failed);
      }
    }

    /**
     * What a person is told of a workspace start's {@code failure}: words written for one, its
     * reason and remedy as they are; anything else, a tool's output say, {@link
     * PtyAdmission.Unavailable} with what it said as its cause, for the log.
     */
    private static IOException told(Exception failure) {
      return failure instanceof Remedied remedied
          ? Remedied.io(remedied.reason(), remedied.remedy(), remedied.code())
          : new PtyAdmission.Unavailable(PtyAdmission.Unavailable.UNFINISHED, failure);
    }

    /**
     * Refuses {@code who} workspace {@code found} unless they may enter it, as every workspace door
     * decides, the node's own person keeping what nobody owns on a node.
     */
    private void decide(
        String project,
        String workspace,
        BoxInstances.Instance found,
        PtyIdentity.Person who,
        String act)
        throws IOException {
      var keeper = roles.nodeFde().orElse(null);
      var another =
          keeper == null
              ? Optional.<ApiException>empty()
              : WorkspaceDoors.labelledForAnother(project, workspace, found, owners, act, true);
      var refused =
          Actor.call(
              who.actor(),
              () ->
                  WorkspaceDoors.accessRefusal(
                      Actor.current(),
                      project,
                      workspace,
                      found,
                      owners,
                      keeper,
                      another.isPresent()));
      if (refused.isPresent()) {
        throw Remedied.io(refused.get().message(), refused.get().fix());
      }
      if (another.isPresent()) {
        throw told(another.get());
      }
    }

    @Override
    public void admitRoom(String room, String project, PtyIdentity.Person who) throws IOException {
      new HostAccess(db, roles).admit(room, project, who);
    }

    @Override
    public void recordEvent(EventStore.EventRow event) {
      events.insert(event);
    }

    @Override
    public void recordTyping(String person, String room) {
      new AttentionStore(db)
          .record(person, room, DateTimeUtils.now(), AttentionStore.Channel.TERMINAL_INPUT);
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
