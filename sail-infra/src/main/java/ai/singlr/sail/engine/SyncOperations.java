/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.SailVersion;
import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.api.SyncReport;
import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.api.SyncTransitionEvents;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncPeer;
import ai.singlr.sail.sync.StoreReplica;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncSession;
import ai.singlr.sail.sync.SyncTransportException;
import ai.singlr.sail.sync.SyncWire;
import ai.singlr.sail.sync.SyncedEntities;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import picocli.CommandLine.Help.Ansi;

/**
 * Runs the shared node-to-main round and its existing local projections. The round walks the entity
 * registry in its order — the dependency order, a spec before its runs, a room before its messages
 * — and one type's failure is recorded against that type while the rest of the round, post-steps
 * included, still runs; the first failure is thrown afterwards with the others suppressed, so a
 * broken type never silences the materialization of the ones that succeeded.
 */
public final class SyncOperations {
  public interface Channel extends AutoCloseable {
    Reader reader();

    Writer writer();

    @Override
    void close() throws IOException;
  }

  @FunctionalInterface
  public interface Channels {
    Channel open(String target) throws IOException;
  }

  private final Sqlite db;
  private final String host;
  private final Path projectsDir;
  private final Supplier<SyncConfig> configuration;
  private final Channels channels;
  private SailEventPublisher publisher;

  public SyncOperations(
      Sqlite db,
      String host,
      Path projectsDir,
      Supplier<SyncConfig> configuration,
      Channels channels) {
    this.db = db;
    this.host = host;
    this.projectsDir = projectsDir;
    this.configuration = configuration;
    this.channels = channels;
  }

  public SyncConfig configuration() {
    return configuration.get();
  }

  public void prepare() {
    SyncDatabase.prepare(db, host);
  }

  public synchronized SyncReport sync(SyncRequest request) throws Exception {
    var config = configuration.get();
    var target = resolveMain(request.main(), config);
    if (target.target() == null) {
      return new SyncReport(SyncEngine.Report.NONE, target.message());
    }
    var round = SyncPeer.withChecked("main", () -> reconcileSession(target.target(), config));
    notify(round);
    return new SyncReport(round.report(), null, round.types());
  }

  private record Round(
      SyncEngine.Report report, List<SyncSession.TypeReport> types, List<Event> pulledMessages) {}

  private Round reconcileSession(String target, SyncConfig config) throws Exception {
    var messages = new MessageStore(db);
    var specs = new SpecStore(db);
    var files = new FileStore(db);
    var projects = new ProjectStore(db);
    try (var channel = channels.open(target)) {
      var boxId = requireBoxId(config);
      var replicas = SyncedEntities.replicas(db, boxId, Objects.toString(config.handle(), ""));
      var hello = SyncWire.Hello.of(SailVersion.version(), boxId);
      return reconcile(channel, hello, replicas, messages, specs, files, projects);
    }
  }

  private Round reconcile(
      Channel channel,
      SyncWire.Hello hello,
      Map<String, StoreReplica> replicas,
      MessageStore messages,
      SpecStore specs,
      FileStore files,
      ProjectStore projects)
      throws Exception {
    try (var session =
        SyncSession.open(channel.reader(), channel.writer(), hello, SyncOperations::notice)) {
      var types = new ArrayList<SyncSession.TypeReport>();
      var failures = new ArrayList<SyncTransportException>();
      var knownMessages = messages.syncEntityIds();
      for (var entity : SyncedEntities.all()) {
        try {
          types.add(session.reconcile(entity.type(), replicas.get(entity.type())));
        } catch (RuntimeException e) {
          var failure = transportFailure(entity.type(), e);
          failures.add(failure);
          types.add(SyncSession.TypeReport.failed(entity.type(), failure.getMessage()));
        }
      }
      var pulledMessages = pulledMessageEvents(messages, specs, knownMessages, host);
      try {
        reportRejectedFdes(applyFdes(new FdeStore(db), session.fetchFdes()));
      } catch (RuntimeException e) {
        failures.add(transportFailure("fde", e));
      }
      materialize(files);
      materializeProjects(projects);
      reconcileLiveResources(projects, reportFor(types, "project"));
      if (!failures.isEmpty()) {
        var first = failures.getFirst();
        failures.stream().skip(1).forEach(first::addSuppressed);
        throw first;
      }
      var summed =
          types.stream()
              .map(SyncSession.TypeReport::report)
              .reduce(SyncEngine.Report.NONE, SyncEngine.Report::plus);
      return new Round(summed, List.copyOf(types), pulledMessages);
    }
  }

  private static String requireBoxId(SyncConfig config) {
    if (Strings.isBlank(config.boxId())) {
      throw new IllegalStateException(
          "This box has no sync box id. Run 'sudo sail migrate' to assign one, then sync again.");
    }
    return config.boxId();
  }

  private static SyncTransportException transportFailure(String context, RuntimeException e) {
    if (e instanceof SyncTransportException transport) {
      return transport;
    }
    return new SyncTransportException(
        e instanceof UncheckedIOException ? "unreachable" : "store",
        context + ": " + e.getMessage(),
        e);
  }

  private static SyncEngine.Report reportFor(List<SyncSession.TypeReport> types, String type) {
    return types.stream()
        .filter(report -> report.type().equals(type))
        .map(SyncSession.TypeReport::report)
        .findFirst()
        .orElse(SyncEngine.Report.NONE);
  }

  private static void notice(String line) {
    System.err.println(Banner.errorLine(line, Ansi.AUTO));
  }

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

  public record MainTarget(String target, String message) {}

  public static MainTarget resolveMain(String flag, SyncConfig sync) {
    if (Strings.isNotBlank(flag)) {
      return new MainTarget(flag, null);
    }
    if (Strings.isNotBlank(sync.main())) {
      return new MainTarget(sync.main(), null);
    }
    if (sync.isMain()) {
      return new MainTarget(
          null, "This box is the main devbox — other boxes sync to it; it has nothing to sync to.");
    }
    return new MainTarget(
        null,
        "Single devbox — nothing to sync. Add a second box with: sail host sync --main <user@host>.");
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
    var reconciler = new ProjectResourceReconciler(new ContainerManager(new ShellExecutor(false)));
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

  /**
   * Writes a freshly-synced project's descriptor to its canonical {@code
   * ~/.sail/projects/<name>/sail.yaml}, next to the {@code files/} bundle the file sync
   * materialized, so the provisioner sees the whole project together. Only writes when the
   * descriptor is absent — it never clobbers a local copy; the database stays the source of truth,
   * and a project that already exists on this box keeps its file. Reports the names it newly
   * materialized so the caller can point the engineer at provisioning.
   */
  private List<String> materializeProjects(ProjectStore projects) {
    var created = new ArrayList<String>();
    for (var project : projects.list()) {
      var descriptor = projectsDir.resolve(project.name()).resolve(SailPaths.PROJECT_DESCRIPTOR);
      if (Files.exists(descriptor)) {
        continue;
      }
      try {
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, project.definition());
        created.add(project.name());
      } catch (IOException e) {
        System.err.println(
            Banner.errorLine(
                "Could not write descriptor for '" + project.name() + "': " + e.getMessage(),
                Ansi.AUTO));
      }
    }
    if (!created.isEmpty()) {
      System.err.println(
          Ansi.AUTO.string(
              "  @|faint "
                  + created.size()
                  + " new project(s) synced from main: "
                  + String.join(", ", created)
                  + ". Provision with 'sudo sail project apply <name>'.|@"));
    }
    return created;
  }

  /** Projects the synced files onto disk, warning about any local edits it deliberately left. */
  private void materialize(FileStore files) {
    var materializer = new FileMaterializer(files, projectsDir);
    for (var project : files.projectsWithFiles()) {
      try {
        var report = materializer.materialize(project);
        if (!report.skipped().isEmpty()) {
          System.err.println(
              Banner.errorLine(
                  "Kept "
                      + report.skipped().size()
                      + " locally-modified file(s) in '"
                      + project
                      + "' (capture with 'sail project files add', or delete to take main's): "
                      + String.join(", ", report.skipped()),
                  Ansi.AUTO));
        }
      } catch (IOException e) {
        System.err.println(
            Banner.errorLine(
                "Could not write files for '" + project + "': " + e.getMessage(), Ansi.AUTO));
      }
    }
  }

  /** Sums two reconcile reports into one round summary. */
  public static SyncEngine.Report combine(SyncEngine.Report a, SyncEngine.Report b) {
    return a.plus(b);
  }

  /**
   * Mirrors main's roster into the local FDE store, returning the handles of any entries rejected
   * for a malformed role or status — dropped, never written with a bad authorization.
   */
  public static List<String> applyFdes(FdeStore fdes, List<Map<String, Object>> roster) {
    var rejected = new ArrayList<String>();
    for (var entry : roster) {
      try {
        fdes.replicate(
            str(entry, "handle"),
            str(entry, "display_name"),
            str(entry, "email"),
            str(entry, "role"),
            str(entry, "status"),
            str(entry, "created_at"));
      } catch (IllegalArgumentException invalid) {
        rejected.add(str(entry, "handle"));
      }
    }
    return List.copyOf(rejected);
  }

  private static String str(Map<String, Object> map, String key) {
    var value = map.get(key);
    return value == null ? null : value.toString();
  }

  private void notify(Round round) {
    for (var event : round.pulledMessages()) {
      publishQuietly(event);
    }
    if (shouldNotify(round.report())) {
      publishQuietly(boardUpdatedEvent(host, round.report()));
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
