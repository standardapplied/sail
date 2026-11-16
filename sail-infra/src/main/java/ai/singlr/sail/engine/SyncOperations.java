/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.SailVersion;
import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.ErrorCode;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.api.SyncReport;
import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.api.SyncTransitionEvents;
import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.Erasure;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MaterializedFiles;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.Snapshots;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncHealth;
import ai.singlr.sail.store.SyncScope;
import ai.singlr.sail.sync.NodeRound;
import ai.singlr.sail.sync.Settlement;
import ai.singlr.sail.sync.StoreReplica;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncSession;
import ai.singlr.sail.sync.SyncTransportException;
import ai.singlr.sail.sync.SyncWire;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import picocli.CommandLine.Help.Ansi;

/**
 * Runs the shared node-to-main round and its existing local projections. The round begins by
 * agreeing with main who this node is and stamping every run main has not taken ({@link
 * NodeRound#begin}); a node main knows by another handle fails there, having done nothing. It then
 * walks the entity registry in its order — the dependency order, a run before what its agent wrote,
 * a room before its messages — and one type's failure is recorded against that type while the rest
 * of the round, post-steps included, still runs; the first failure is thrown afterwards with the
 * others suppressed, so a broken type never silences the materialization of the ones that
 * succeeded.
 */
public final class SyncOperations {
  public interface Channel extends AutoCloseable {
    InputStream reader();

    OutputStream writer();

    /** The transport's own last word on why it closed, or empty when it has none. */
    default String diagnostic() {
      return "";
    }

    /**
     * The line main's operator runs to authorise the key this channel presented as {@code handle},
     * or empty when it cannot say it.
     */
    default Optional<String> authorisation(String handle) {
      return Optional.empty();
    }

    @Override
    void close() throws IOException;
  }

  @FunctionalInterface
  public interface Channels {
    Channel open(String target) throws IOException;
  }

  private static final String HOST_KEY_FAILED = "Host key verification failed.";
  private static final String KEY_REFUSED = "Permission denied (publickey";

  /** The kind of a round refused because main no longer knows this box's person. */
  public static final String REMOVED = "removed";

  private final Sqlite db;
  private final String host;
  private final Path projectsDir;
  private final Supplier<SyncConfig> configuration;
  private final Channels channels;
  private final Consumer<Event> events;
  private final Consumer<Remedy> notice;
  private final ShellExec shell;
  private final AuthorizedKeysSync gatewayKeys;
  private SailEventPublisher publisher;

  /** A round whose notices print as {@link #PLAIN_NOTICES} prints them. */
  public SyncOperations(
      Sqlite db,
      String host,
      Path projectsDir,
      Supplier<SyncConfig> configuration,
      Channels channels) {
    this(db, host, projectsDir, configuration, channels, PLAIN_NOTICES);
  }

  public SyncOperations(
      Sqlite db,
      String host,
      Path projectsDir,
      Supplier<SyncConfig> configuration,
      Channels channels,
      Consumer<Remedy> notice) {
    this(db, host, projectsDir, configuration, channels, null, notice);
  }

  /**
   * {@code events} receives every event a round publishes; {@code null} means the local API. A
   * round's notices, and the commands they name, go to {@code notice}, which the door that opened
   * this renders for its reader.
   */
  public SyncOperations(
      Sqlite db,
      String host,
      Path projectsDir,
      Supplier<SyncConfig> configuration,
      Channels channels,
      Consumer<Event> events,
      Consumer<Remedy> notice) {
    this(db, host, projectsDir, configuration, channels, events, notice, new ShellExecutor(false));
  }

  /** As above, asking Incus through {@code shell}. */
  public SyncOperations(
      Sqlite db,
      String host,
      Path projectsDir,
      Supplier<SyncConfig> configuration,
      Channels channels,
      Consumer<Event> events,
      Consumer<Remedy> notice,
      ShellExec shell) {
    this(
        db,
        host,
        projectsDir,
        configuration,
        channels,
        events,
        notice,
        shell,
        new AuthorizedKeysSync());
  }

  /**
   * As above, keeping the SSH gateway's keys through {@code gatewayKeys}, which every round brings
   * into line with the roster it leaves.
   */
  public SyncOperations(
      Sqlite db,
      String host,
      Path projectsDir,
      Supplier<SyncConfig> configuration,
      Channels channels,
      Consumer<Event> events,
      Consumer<Remedy> notice,
      ShellExec shell,
      AuthorizedKeysSync gatewayKeys) {
    this.db = db;
    this.host = host;
    this.projectsDir = projectsDir;
    this.configuration = configuration;
    this.channels = channels;
    this.events = events == null ? this::publishQuietly : events;
    this.notice = Objects.requireNonNull(notice, "notice");
    this.shell = Objects.requireNonNull(shell, "shell");
    this.gatewayKeys = Objects.requireNonNull(gatewayKeys, "gatewayKeys");
  }

  public SyncConfig configuration() {
    return configuration.get();
  }

  public void prepare() {
    SyncDatabase.prepare(db, host);
  }

  public synchronized SyncReport sync(SyncRequest request) throws Exception {
    try (var rounds = FileMutex.holdRounds(db.path())) {
      var config = configuration.get();
      var target = resolveMain(request.main(), config);
      if (target.target() == null) {
        return new SyncReport(SyncEngine.Report.NONE, target.message());
      }
      var round = Actor.call(Actor.main(), () -> reconcileSession(target.target(), config));
      return new SyncReport(round.report(), null, round.types(), round.settled());
    }
  }

  private record Round(
      SyncEngine.Report report,
      List<SyncSession.TypeReport> types,
      List<Event> pulledMessages,
      List<Settlement.Settled> settled) {}

  private Round reconcileSession(String target, SyncConfig config) throws Exception {
    var messages = new MessageStore(db);
    var specs = new SpecStore(db);
    var files = new FileStore(db);
    var projects = new ProjectStore(db);
    try (var channel = channels.open(target);
        var session = open(channel, config, db, target, notice)) {
      var handle = Objects.toString(config.handle(), "");
      var replicas = SyncedEntities.replicas(db, config.boxId(), handle);
      return reconcile(session, handle, replicas, messages, specs, files, projects);
    }
  }

  private Round reconcile(
      SyncSession session,
      String handle,
      Map<String, StoreReplica> replicas,
      MessageStore messages,
      SpecStore specs,
      FileStore files,
      ProjectStore projects)
      throws Exception {
    var failures = new ArrayList<SyncTransportException>();
    List<Map<String, Object>> roster = null;
    try {
      roster = session.fetchFdes();
    } catch (RuntimeException e) {
      failures.add(transportFailure("fde", e));
    }
    if (roster != null) {
      requireNoStrangers(new FdeStore(db), roster, handle);
    }
    DemoSeeder.unseed(db);
    var settled = new ArrayList<>(NodeRound.begin(session, db, handle));
    session.keep(NodeRound.kept(settled));
    var types = new ArrayList<SyncSession.TypeReport>();
    var knownMessages = messages.syncEntityIds();
    var knownProjects = names(projects);
    for (var entity : SyncedEntities.all()) {
      try {
        types.add(session.reconcile(entity.type(), replicas.get(entity.type())));
      } catch (RuntimeException e) {
        var failure = transportFailure(entity.type(), e);
        failures.add(failure);
        types.add(SyncSession.TypeReport.failed(entity.type(), failure.getMessage()));
      }
    }
    settled.addAll(NodeRound.end(db, handle));
    settled.forEach(settlement -> notice.accept(Remedy.of(settlement.describe())));
    var pulledMessages = pulledMessageEvents(messages, specs, knownMessages, host);
    if (roster != null) {
      try {
        reportRejectedFdes(applyFdes(new FdeStore(db), roster, handle, notice));
      } catch (RuntimeException e) {
        failures.add(transportFailure("fde", e));
      }
      keepGatewayKeys(failures);
    }
    materialize(files);
    announceNewProjects(projects, knownProjects);
    reconcileLiveResources(projects, reportFor(types, "project"));
    tearDownOutOfScope(dropped(settled));
    var summed =
        types.stream()
            .map(SyncSession.TypeReport::report)
            .reduce(SyncEngine.Report.NONE, SyncEngine.Report::plus);
    var round = new Round(summed, List.copyOf(types), pulledMessages, List.copyOf(settled));
    notify(round);
    if (!failures.isEmpty()) {
      var first = failures.getFirst();
      failures.stream().skip(1).forEach(first::addSuppressed);
      throw first;
    }
    return round;
  }

  /**
   * Asks the main the node {@code config} syncs to, over one session opened through {@code
   * channels}, which of this box's runs it took whose answer was lost, and acknowledges them
   * ({@link NodeRound#acknowledgeHeld}). Nothing is offered, and whose handle main knows this box
   * by is not checked: a handle change asks it before deciding which runs it may re-stamp. Returns
   * the runs main holds.
   */
  public static Set<String> acknowledgeHeld(
      Sqlite db, SyncConfig config, Channels channels, Consumer<Remedy> notice) throws Exception {
    try (var channel = channels.open(config.main());
        var session = open(channel, config, db, config.main(), notice)) {
      return NodeRound.acknowledgeHeld(session, db);
    }
  }

  /**
   * Whether main holds this box's sync key as {@code handle}: one session opened through {@code
   * channels} as the box {@code config} names is welcomed as that handle. Anything short of that,
   * main unreachable included, is no evidence that it does. The probe says nothing of its own: the
   * answer it gives is what the caller shows.
   */
  public static boolean welcomedAs(SyncConfig config, Channels channels, String handle) {
    try (var channel = channels.open(config.main());
        var session =
            SyncSession.open(
                channel.reader(), channel.writer(), hello(config), unsaid -> {}, unsaid -> {})) {
      return handle.equals(session.handle());
    } catch (Exception notWelcomed) {
      return false;
    }
  }

  /**
   * Opens a sync session to main at {@code target} over {@code channel} as the box whose config is
   * {@code config}, refused unless main serves the database this box synced with ({@link
   * NodeRound#admit}).
   */
  private static SyncSession open(
      Channel channel, SyncConfig config, Sqlite db, String target, Consumer<Remedy> notice) {
    var session = welcome(channel, config, db, target, notice);
    try {
      NodeRound.admit(session, db, target);
      return session;
    } catch (RuntimeException refused) {
      try {
        session.close();
      } catch (RuntimeException closing) {
        refused.addSuppressed(closing);
      }
      throw refused;
    }
  }

  private static SyncSession welcome(
      Channel channel, SyncConfig config, Sqlite db, String target, Consumer<Remedy> notice) {
    try {
      return SyncSession.open(
          channel.reader(),
          channel.writer(),
          hello(config),
          notice,
          denial -> notice.accept(denial.describe()),
          db);
    } catch (SyncTransportException e) {
      if (!"unreachable".equals(e.kind())) {
        throw e;
      }
      var said = channel.diagnostic();
      if (Strings.isBlank(said)) {
        throw e;
      }
      throw closed(e, said, channel, config.handle(), target, syncedWith(db, target));
    }
  }

  /**
   * A channel ssh closed saying {@code said}: a key main refused, with the line that authorises it
   * before ssh's words, else ssh's words and {@link #explained}'s remedy.
   */
  private static SyncTransportException closed(
      SyncTransportException e,
      String said,
      Channel channel,
      String handle,
      String target,
      boolean synced) {
    var authorise =
        said.contains(KEY_REFUSED) ? channel.authorisation(handle) : Optional.<String>empty();
    if (authorise.isEmpty()) {
      return new SyncTransportException(
          "unreachable", e.getMessage() + " ssh said: " + said, explained(said, target), e);
    }
    return new SyncTransportException(
        synced ? REMOVED : "unreachable",
        e.getMessage(),
        Remedy.of(
            (synced
                    ? "Main no longer knows this box's person, "
                        + handle
                        + ", or their key: main removed them. This box keeps running as them but"
                        + " syncs no more until an admin on main runs this line; then sync again:"
                    : "Main has not authorised this box's key yet. Send its operator this line,"
                        + " then sync again:")
                + "\n  "
                + authorise.get()
                + "\n  ssh said: "
                + said),
        e);
  }

  /**
   * Whether this box has had a round with the main at {@code target}: only a main it synced with
   * can have removed its person. A box pointed at a main that never knew it is told that main has
   * not authorised it yet, whoever another main described here.
   */
  private static boolean syncedWith(Sqlite db, String target) {
    return new SyncHealth(db).find(target).map(SyncHealth.Health::lastSuccessAt).isPresent();
  }

  /** The remedy for a channel ssh closed saying {@code said}, when it names one. */
  static Remedy explained(String said, String target) {
    return HOST_KEY_FAILED.equals(said) ? MainHostKey.rebuiltRecovery(target) : Remedy.NONE;
  }

  private static SyncWire.Hello hello(SyncConfig config) {
    return SyncWire.Hello.of(SailVersion.version(), requireBoxId(config));
  }

  private static String requireBoxId(SyncConfig config) {
    if (Strings.isBlank(config.boxId())) {
      throw Remedied.state(
          "This box has no sync box id.",
          Remedy.of("Join main again to mint one: '", Remedy.sail("join", "<main>"), "'."));
    }
    return config.boxId();
  }

  /**
   * Brings the SSH gateway's {@code authorized_keys} into line with the roster this round left, in
   * the same round: a person removed, withdrawn, moved aside or replaced keeps no line. A round
   * that cannot write it fails loudly, and the next round tries again; one run without root writes
   * nothing, and the next round of {@code sail-api}, which runs as root, writes it.
   */
  private void keepGatewayKeys(List<SyncTransportException> failures) {
    try {
      gatewayKeys.sync(db);
    } catch (Exception e) {
      failures.add(
          new SyncTransportException(
              "store", "the SSH gateway's keys could not be rewritten:", Remedied.whole(e), e));
    }
  }

  private static SyncTransportException transportFailure(String context, RuntimeException e) {
    if (e instanceof SyncTransportException transport) {
      return transport;
    }
    return new SyncTransportException(
        e instanceof UncheckedIOException ? "unreachable" : "store",
        context + ":",
        Remedied.whole(e),
        e);
  }

  private static SyncEngine.Report reportFor(List<SyncSession.TypeReport> types, String type) {
    return types.stream()
        .filter(report -> report.type().equals(type))
        .map(SyncSession.TypeReport::report)
        .findFirst()
        .orElse(SyncEngine.Report.NONE);
  }

  /**
   * A round's notices as a process that cannot see its reader prints them, sail-api's sync lane
   * among them: each command in its plain form.
   */
  public static final Consumer<Remedy> PLAIN_NOTICES =
      remedy -> System.err.println(Banner.warnLine(remedy.plain(), Ansi.AUTO));

  private static void reportRejectedFdes(List<String> rejected) {
    if (!rejected.isEmpty()) {
      System.err.println(
          Banner.errorLine(
              "Skipped "
                  + rejected.size()
                  + " malformed identity entry(ies) from main: "
                  + String.join(", ", rejected),
              Ansi.AUTO));
    }
  }

  public record MainTarget(String target, Remedy message) {}

  /**
   * The main this box syncs with: {@code flag}, else its configured main. The main box itself syncs
   * with nothing, whatever it is asked: a round adopts the other side's versions and roster, which
   * would overwrite the fleet's source of truth.
   */
  public static MainTarget resolveMain(String flag, SyncConfig sync) {
    if (sync.isMain()) {
      return new MainTarget(
          null,
          Remedy.of(
              "This box is the main devbox — other boxes sync to it; it has nothing to sync to."));
    }
    if (Strings.isNotBlank(flag)) {
      return new MainTarget(flag, null);
    }
    if (Strings.isNotBlank(sync.main())) {
      return new MainTarget(sync.main(), null);
    }
    return new MainTarget(
        null,
        Remedy.of("Single devbox — nothing to sync. Set up sync with: ")
            .then(Remedy.setUp())
            .then("."));
  }

  public static List<Event> pulledMessageEvents(
      MessageStore messages, SpecStore specs, Set<String> known, String host) {
    return messages.syncEntityIds().stream()
        .filter(id -> !known.contains(id))
        .map(messages::findById)
        .flatMap(Optional::stream)
        .map(
            row ->
                specs.listByRoom(row.roomId()).stream()
                    .findFirst()
                    .map(
                        spec ->
                            SyncTransitionEvents.messagePosted(
                                spec.project(),
                                row.roomId(),
                                row.id(),
                                row.author(),
                                row.body(),
                                row.question(),
                                host))
                    .orElse(null))
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * After a sync that changed projects, resizes each project's live container to the synced
   * definition — CPU, memory, and disk, in place — so a resource edit on another box expands or
   * contracts this box's container without anyone re-provisioning. Best-effort and never fatal:
   * when incus is unreachable (an unprivileged sync) it is a quiet no-op, and a disk shrink the
   * backend refuses is reported and skipped. Only runs when the round actually pulled or merged a
   * project, so an unchanged sync touches no containers.
   */
  private void reconcileLiveResources(ProjectStore projects, SyncEngine.Report projectReport) {
    if (projectReport.pulled() + projectReport.merged() == 0) {
      return;
    }
    var reconciler = new ProjectResourceReconciler(new ContainerManager(shell));
    var outcome = reconciler.reconcileCatalog(projects.list());
    for (var skipped : outcome.diskSkipped()) {
      System.err.println(Banner.errorLine("Kept disk size for " + skipped, Ansi.AUTO));
    }
    if (!outcome.resized().isEmpty()) {
      System.err.println(
          Ansi.AUTO.string(
              "  @|faint resized to match main: " + String.join(", ", outcome.resized()) + "|@"));
    }
  }

  private static Set<String> dropped(List<Settlement.Settled> settled) {
    return settled.stream()
        .filter(
            each ->
                Erasure.PROJECT.equals(each.type()) && each.how() instanceof Settlement.How.Dropped)
        .map(Settlement.Settled::id)
        .collect(Collectors.toUnmodifiableSet());
  }

  /**
   * Takes off this box each project the scope main named does not admit and the box holds nothing
   * of: its {@code ~/.sail/projects/<name>} directory, its container when Sail provisioned it
   * ({@link IncusDeviceManager#sailManaged}), and its workspaces and template, told by their keys
   * ({@link BoxInstances}), as {@code project destroy} without {@code --purge} would. A copy
   * carrying a leaving project's keys under another name is not Sail's and stays, said once, in the
   * round that drops the project. A workspace or template of a project in scope is never touched,
   * nor taken for a project of its own name. It is decided from what the box holds, not from what
   * this round {@code dropped}, so a directory or container an earlier release, a failed round or
   * an unreachable Incus left behind goes in the first round that can take it. A project kept for a
   * run still under way here is still held, and keeps both. An unscoped box is never asked, nor is
   * Incus of a project in scope.
   */
  private void tearDownOutOfScope(Set<String> dropped) {
    var scope = new SyncScope(db).held();
    if (scope.unrestricted()) {
      return;
    }
    var held = Set.copyOf(new ChangeLog(db).heldProjects());
    Predicate<String> leaving =
        name ->
            NameValidator.isValidProjectName(name) && !scope.admits(name) && !held.contains(name);
    var teardown = new ProjectTeardown(shell, projectsDir);
    try {
      for (var name : directoriesUnder(projectsDir)) {
        if (leaving.test(name)) {
          takeOff(teardown, name, List.of());
        }
      }
      List<BoxInstances.Instance> instances;
      try {
        instances = new BoxInstances(shell).all();
      } catch (IOException | TimeoutException unreachable) {
        dropped.forEach(
            project ->
                notice.accept(
                    Remedy.of(
                        "project "
                            + project
                            + ": its container, if any, is deleted by the next round that can"
                            + " reach Incus (sail-api within a minute, or '",
                        Remedy.sail("sync"),
                        "')")));
        return;
      }
      var devices = new IncusDeviceManager(shell);
      for (var instance : instances) {
        var name = instance.name();
        switch (instance.kind()) {
          case BoxInstances.Plain _ when leaving.test(name) -> {
            if (devices.sailManaged(name)) {
              if (takeOff(teardown, name, List.of(name))) {
                notice.accept(
                    Remedy.of(
                        "project "
                            + name
                            + ": its container deleted, workspace and uncommitted work included —"
                            + " main no longer grants this box the project"));
              }
            } else if (dropped.contains(name)) {
              notice.accept(
                  Remedy.of(
                      "project "
                          + name
                          + ": container '"
                          + name
                          + "' kept — Sail did not provision it; remove it yourself if it holds the"
                          + " project's work"));
            }
          }
          case BoxInstances.Workspace workspace when leaving.test(workspace.project()) -> {
            if (takeOff(teardown, workspace.project(), List.of(name))) {
              notice.accept(
                  Remedy.of(
                      "project "
                          + workspace.project()
                          + ": its workspace "
                          + workspace.workspace()
                          + " deleted, uncommitted work included — main no longer grants this box"
                          + " the project"));
            }
          }
          case BoxInstances.Template template when leaving.test(template.project()) -> {
            if (takeOff(teardown, template.project(), List.of(name))) {
              notice.accept(
                  Remedy.of(
                      "project "
                          + template.project()
                          + ": its template deleted — main no longer grants this box the project"));
            }
          }
          case BoxInstances.Stray stray when dropped.contains(stray.project()) ->
              notice.accept(
                  Remedy.of(
                      "project "
                          + stray.project()
                          + ": instance '"
                          + name
                          + "' kept — a copy carrying the project's keys under another name is not"
                          + " Sail's; delete it yourself if it is not wanted, as it holds the"
                          + " project's work"));
          default -> {}
        }
      }
    } catch (IOException | TimeoutException e) {
      System.err.println(
          Banner.errorLine(
              "Could not list what this box holds of projects main no longer grants it: "
                  + e.getMessage(),
              Ansi.AUTO));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Tears {@code name} down, saying so when it cannot: the next round finds it still held on disk
   * or in Incus and tries again. Returns whether it went.
   */
  private static boolean takeOff(ProjectTeardown teardown, String name, List<String> instances)
      throws InterruptedException {
    try {
      teardown.tearDown(name, instances);
      return true;
    } catch (IOException | UncheckedIOException | TimeoutException e) {
      System.err.println(
          Banner.errorLine(
              "Could not take project "
                  + name
                  + " off this box (the next sync tries again): "
                  + e.getMessage(),
              Ansi.AUTO));
      return false;
    }
  }

  private static List<String> directoriesUnder(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (var children = Files.list(dir)) {
      return children
          .filter(Files::isDirectory)
          .map(child -> child.getFileName().toString())
          .sorted()
          .toList();
    }
  }

  private static Set<String> names(ProjectStore projects) {
    return projects.list().stream()
        .map(ProjectStore.ProjectRow::name)
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /**
   * Names the projects this round brought to the catalog that it did not hold before, each with the
   * command that makes its container here, and once, in this round alone, each that cannot have one
   * because the instance of its name is a workspace, a template or a copy (W10), with what frees
   * the name. A box that cannot read Incus names them all.
   */
  private void announceNewProjects(ProjectStore projects, Set<String> known) {
    var created = names(projects);
    created.removeAll(known);
    if (created.isEmpty()) {
      return;
    }
    for (var taken : takenAmong(created)) {
      created.remove(taken.project());
      notice.accept(Remedied.whole(taken));
    }
    if (created.isEmpty()) {
      return;
    }
    var applies =
        created.stream()
            .map(name -> Remedy.of("\n    ", Remedy.sail("project apply", name)))
            .toArray();
    notice.accept(
        Remedy.of(
                created.size()
                    + " new project(s) synced from main: "
                    + String.join(", ", created)
                    + ". Provision each with:")
            .then(applies));
  }

  private List<BoxInstances.Taken> takenAmong(Set<String> names) {
    List<BoxInstances.Instance> instances;
    try {
      instances = new BoxInstances(shell).all();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    } catch (IOException | TimeoutException e) {
      return List.of();
    }
    return instances.stream()
        .filter(instance -> names.contains(instance.name()))
        .flatMap(instance -> BoxInstances.taken(instance.name(), Optional.of(instance)).stream())
        .toList();
  }

  /** Projects the synced files onto disk, warning about any local edits it deliberately left. */
  private void materialize(FileStore files) {
    var materializer = new FileMaterializer(files, projectsDir);
    for (var project : files.projectsWithFiles()) {
      try {
        var report = materializer.materialize(project);
        if (!report.skipped().isEmpty()) {
          notice.accept(
              Remedy.of(
                  "Kept "
                      + report.skipped().size()
                      + " locally-modified file(s) in '"
                      + project
                      + "' (capture with '",
                  Remedy.sail("project files add"),
                  "', or delete to take main's): " + String.join(", ", report.skipped())));
        }
        if (!report.undecided().isEmpty()) {
          notice.accept(
              Remedy.of(
                      "Kept "
                          + report.undecided().size()
                          + " file(s) in '"
                          + project
                          + "' the upgrade could not tell from your edits (")
                  .then(MaterializedFiles.UNDECIDED_REMEDY)
                  .then("): " + String.join(", ", report.undecided())));
        }
      } catch (IOException e) {
        System.err.println(
            Banner.errorLine(
                "Could not write files for '" + project + "': " + e.getMessage(), Ansi.AUTO));
      }
    }
  }

  /**
   * Mirrors main's roster into the local FDE store, returning the handles of any entries rejected
   * for a malformed role or status — dropped, never written with a bad authorization. The roster
   * lists the people main shows this box and, by id alone, everyone else main still holds. Once it
   * names this box's own FDE, {@code own}: a person this box holds under a main id main no longer
   * holds is removed with their credentials (main removed them, or gave their handle to someone
   * new); one main holds but no longer lists here is withdrawn, disabled and their name and email
   * forgotten, but what they stored kept for when they are listed again. A person main never
   * described is disabled, and never removed by a round: one whose handle main shows for someone
   * else (not the same email) is moved aside, renamed and disabled with what they stored, and said
   * once, but at this box's first round with this main, which refuses instead ({@link
   * #requireNoStrangers}). The box's own FDE is never removed, withdrawn or disabled. A roster from
   * a main that sends no ids removes no one.
   */
  public static List<String> applyFdes(
      FdeStore fdes, List<Map<String, Object>> roster, String own, Consumer<Remedy> notice) {
    var listed = roster.stream().filter(entry -> entry.get("handle") != null).toList();
    var handles = listed.stream().map(entry -> str(entry, "handle")).toList();
    var ids = roster.stream().map(entry -> str(entry, "id")).toList();
    var strangers = requireNoStrangers(fdes, roster, own);
    var removed = List.<String>of();
    var withdrawn = List.<String>of();
    if (ids.contains(null) || !handles.contains(own)) {
      var kept = strangers.stream().map(Stranger::handle).toList();
      listed = listed.stream().filter(entry -> !kept.contains(str(entry, "handle"))).toList();
    } else {
      removed = fdes.removeUndescribed(ids, own);
      withdrawn = fdes.withdraw(listed.stream().map(entry -> str(entry, "id")).toList(), own);
      for (var stranger : strangers) {
        var moved = fdes.moveAside(stranger.handle());
        notice.accept(
            Remedy.of(
                "roster: "
                    + stranger.handle()
                    + " on this box is not main's "
                    + stranger.handle()
                    + ": their emails differ, so main gave the handle to someone else. It is kept"
                    + " as "
                    + moved
                    + ", disabled,"
                    + " with what it stored, and main's "
                    + stranger.handle()
                    + " takes the handle. Remove it with '",
                Remedy.sail("fde rm", moved, "--force"),
                "', or leave it: it stays, unusable."));
      }
    }
    fdes.disableUnlisted(handles, own);
    if (!removed.isEmpty()) {
      System.err.println(
          "  roster: removed "
              + String.join(", ", removed)
              + ": main removed them, or gave the handle to someone new.");
    }
    if (!withdrawn.isEmpty()) {
      System.err.println(
          "  roster: withdrew "
              + String.join(", ", withdrawn)
              + ": no longer on this box's projects; what they stored here is kept.");
    }
    var rejected = new ArrayList<String>();
    for (var entry : listed) {
      try {
        fdes.replicate(
            str(entry, "handle"),
            str(entry, "id"),
            str(entry, "display_name"),
            str(entry, "email"),
            str(entry, "role"),
            str(entry, "status"),
            Snapshots.instant(entry, "created_at"));
      } catch (IllegalArgumentException invalid) {
        rejected.add(str(entry, "handle"));
      }
    }
    return List.copyOf(rejected);
  }

  /** A person made here under a handle main lists for someone else. */
  record Stranger(String handle, String here, String main) {
    String said() {
      return handle
          + " on this box ("
          + here
          + ") is not main's "
          + handle
          + " ("
          + main
          + "): main gave the handle to someone else.";
    }
  }

  /**
   * The people made here under a handle main's {@code roster} shows for someone else: never
   * described by main, not this box's {@code own}, and not of the same email (a blank one is the
   * same as no one's). At this box's first round with this main, when it holds no one this main's
   * ids describe, they are refused, naming each and the ways out, and nothing changes.
   */
  static List<Stranger> requireNoStrangers(
      FdeStore fdes, List<Map<String, Object>> roster, String own) {
    var strangers = new ArrayList<Stranger>();
    for (var entry : roster) {
      var handle = str(entry, "handle");
      var here = handle == null || !fdes.undescribed(handle) ? null : fdes.byHandle(handle).get();
      var main = Objects.toString(str(entry, "email"), "");
      if (here != null && !handle.equals(own) && !sameEmail(here.email(), main)) {
        strangers.add(new Stranger(handle, Objects.toString(here.email(), ""), main));
      }
    }
    if (strangers.isEmpty()
        || fdes.describedBy(roster.stream().map(entry -> str(entry, "id")).toList())) {
      return strangers;
    }
    var told = new ArrayList<Object>();
    for (var stranger : strangers) {
      var h = stranger.handle();
      var remove = Remedy.sail("fde rm", h, "--force");
      if (Strings.isBlank(stranger.main()) || Strings.isBlank(stranger.here())) {
        told.addAll(
            List.of(
                stranger.said()
                    + " This box cannot see both emails, so it cannot tell whether they are the"
                    + " same person. Remove "
                    + h
                    + " here, which deletes what it stored on this box: '",
                remove,
                "'. "));
      } else {
        told.addAll(
            List.of(
                stranger.said() + " If they are different people, remove " + h + " here: '",
                remove,
                "'. If they are the same person, correct the email that is wrong: here '",
                Remedy.sail("fde update", h, "--email", stranger.main()),
                "', or on main '",
                Remedy.sail("fde update", h, "--email", stranger.here()),
                "'. "));
      }
    }
    throw new ApiException(
        ErrorCode.CONFLICT,
        "This box's first round with this main changes no one: a handle it holds is someone"
            + " else's on main.",
        Remedy.of(told.toArray()));
  }

  private static boolean sameEmail(String here, String main) {
    return Strings.isNotBlank(here)
        && Strings.isNotBlank(main)
        && here.strip().toLowerCase(Locale.ROOT).equals(main.strip().toLowerCase(Locale.ROOT));
  }

  private static String str(Map<String, Object> map, String key) {
    var value = map.get(key);
    return value == null ? null : value.toString();
  }

  /**
   * Publishes what the round committed. Runs before a partial failure is thrown: the pulled
   * messages are already checkpointed, so a round that never reached this point would leave them
   * known-but-unannounced forever.
   */
  private void notify(Round round) {
    for (var event : round.pulledMessages()) {
      events.accept(event);
    }
    if (shouldNotify(round.report())) {
      events.accept(boardUpdatedEvent(host, round.report()));
    }
  }

  private void publishQuietly(Event event) {
    try {
      if (publisher == null) {
        publisher = SailEventPublisher.localDefault();
      }
      publisher.publish(event);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception ignored) {
      System.err.println(
          Ansi.AUTO.string(
              "  @|faint Event notification skipped — sail-api isn't running here; the sync is"
                  + " unaffected.|@"));
    }
  }

  /** A round is worth announcing only when it brought remote work in or raised a conflict. */
  public static boolean shouldNotify(SyncEngine.Report report) {
    return report.pulled() + report.merged() + report.conflicts() > 0;
  }

  public static Event boardUpdatedEvent(String host, SyncEngine.Report report) {
    var data =
        Map.<String, Object>of(
            "pulled", report.pulled(),
            "merged", report.merged(),
            "conflicts", report.conflicts());
    return Event.of(
        Event.SAIL_AGENT, null, Event.WellKnownTypes.BOARD_UPDATED, Event.SAIL_AGENT, host, data);
  }
}
