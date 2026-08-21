/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.api.SyncTransitionEvents;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.FileMaterializer;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.ProjectResourceReconciler;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SshSyncChannel;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncPeer;
import ai.singlr.sail.store.SyncState;
import ai.singlr.sail.sync.StoreReplica;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncSession;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

/**
 * Reconciles this box's local spec replica with the main devbox over the SSH-key gateway. The
 * engine runs here on the node and drives a {@link RemoteMainReplica} across the channel:
 * local-only work pushes (main mints the rev), main-only work pulls, disjoint edits auto-merge, and
 * same-field conflicts are parked locally for {@code sail conflicts} — the node's row is never
 * clobbered. The round is idempotent; running it again after it converges does nothing. The local
 * replica is opened through {@link SyncDatabase}, so the schema is converged before any revision is
 * applied — a binary the auto-updater just replaced can never sync against the previous release's
 * schema.
 *
 * <p>With {@code --watch} it loops on an interval, staying up through a transient main outage and
 * resuming from the checkpoint when main returns. Each round that brings remote work (or raises a
 * conflict) emits a {@code board_updated} event onto the local event stream, so the CLI and GUI
 * clients surface an "updates available" banner. The notification is advisory — a sync that cannot
 * reach the event server still completes.
 */
@Command(
    name = "sync",
    description = "Reconcile this box's specs with the main devbox.",
    mixinStandardHelpOptions = true)
public final class SyncCommand implements Callable<Integer> {

  @Option(
      names = "--main",
      description =
          "SSH target of the main devbox, e.g. sail@maindevbox. Defaults to the configured main"
              + " (sail host sync --main <target>).")
  private String main;

  @Option(
      names = {"-w", "--watch"},
      description = "Keep syncing on an interval until interrupted.")
  private boolean watch;

  @Option(
      names = "--interval",
      paramLabel = "SECONDS",
      defaultValue = "30",
      description = "Seconds between rounds in --watch mode (default 30).")
  private int intervalSeconds;

  @Option(names = "--json", description = "Output the sync report as JSON.")
  private boolean json;

  private SailEventPublisher publisher;

  @Override
  public Integer call() throws Exception {
    if (watch && intervalSeconds <= 0) {
      System.err.println(
          Banner.errorLine("--interval must be a positive number of seconds.", Ansi.AUTO));
      return 1;
    }
    var sync = HostSync.config();
    var resolution = resolveMain(main, sync);
    if (resolution.target() == null) {
      System.out.println(Ansi.AUTO.string("  @|faint " + resolution.message() + "|@"));
      return 0;
    }
    var target = resolution.target();
    var host = HostInfo.hostname();
    var handle = sync.handle() == null ? "" : sync.handle();
    SyncDatabase replicaDb;
    try {
      replicaDb = SyncDatabase.converge(SailPaths.controlPlaneDb(), host);
    } catch (RuntimeException e) {
      System.err.println(Banner.errorLine(SyncCommand.reason(e), Ansi.AUTO));
      return 1;
    }
    try (replicaDb) {
      var boxes = boxes(replicaDb, host, handle);
      return watch ? watchLoop(boxes, target) : runOnce(boxes, target);
    }
  }

  private static Boxes boxes(SyncDatabase converged, String host, String handle) {
    var db = converged.db();
    var changeLog = new ChangeLog(db);
    var conflicts = new SyncConflicts(db);
    var syncState = new SyncState(db);
    var fileStore = new FileStore(db);
    var projectStore = new ProjectStore(db);
    var specStore = new SpecStore(db);
    var messageStore = new MessageStore(db);
    var runStore = new RunStore(db);
    return new Boxes(
        new StoreReplica(host, specStore, changeLog, conflicts, syncState),
        new StoreReplica(host, fileStore, changeLog, conflicts, syncState),
        new StoreReplica(host, projectStore, changeLog, conflicts, syncState),
        new StoreReplica(
            host,
            runStore,
            changeLog,
            conflicts,
            syncState,
            id -> runStore.pushableFrom(id, handle)),
        new StoreReplica(host, new ReviewStore(db), changeLog, conflicts, syncState),
        new StoreReplica(host, messageStore, changeLog, conflicts, syncState),
        new FdeStore(db),
        fileStore,
        projectStore,
        specStore,
        messageStore);
  }

  private record Boxes(
      StoreReplica spec,
      StoreReplica file,
      StoreReplica project,
      StoreReplica run,
      StoreReplica review,
      StoreReplica message,
      FdeStore fdes,
      FileStore files,
      ProjectStore projects,
      SpecStore specs,
      MessageStore messages) {}

  /**
   * Where this box syncs to. A non-null {@link MainTarget#target()} means reconcile against it; a
   * null target with a {@link MainTarget#message()} means there is nothing to sync — a single box,
   * or the main hub itself — which the caller reports as friendly info, not an error.
   */
  record MainTarget(String target, String message) {}

  static MainTarget resolveMain(String flag, SyncConfig sync) {
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

  private int runOnce(Boxes boxes, String target) {
    try {
      var round = reconcile(boxes, target);
      System.out.println(render(round.report(), json));
      notify(round);
      return 0;
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine("Sync with " + target + " failed: " + reason(e), Ansi.AUTO));
      return 1;
    }
  }

  /** A human-readable reason for a failed round, falling back to the exception type. */
  static String reason(Exception e) {
    var message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return message;
  }

  private int watchLoop(Boxes boxes, String target) throws InterruptedException {
    while (true) {
      try {
        var round = reconcile(boxes, target);
        System.out.println(render(round.report(), json));
        notify(round);
      } catch (InterruptedException e) {
        throw e;
      } catch (Exception e) {
        System.err.println(
            Banner.errorLine(
                "Sync round failed (" + reason(e) + "); retrying in " + intervalSeconds + "s.",
                Ansi.AUTO));
      }
      Thread.sleep(intervalSeconds * 1000L);
    }
  }

  /** One reconcile round's outcome: the summed report and the events the pull brought in. */
  private record Round(SyncEngine.Report report, List<Event> pulledMessages) {}

  private Round reconcile(Boxes boxes, String target) throws Exception {
    return SyncPeer.withChecked("main", () -> reconcileSession(boxes, target));
  }

  private Round reconcileSession(Boxes boxes, String target) throws Exception {
    try (var channel = SshSyncChannel.open(target);
        var session = new SyncSession(channel.reader(), channel.writer())) {
      var specReport = new SyncEngine().reconcile(boxes.spec(), session.replica("spec"));
      var fileReport = new SyncEngine().reconcile(boxes.file(), session.replica("file"));
      var projectReport = new SyncEngine().reconcile(boxes.project(), session.replica("project"));
      var runReport = new SyncEngine().reconcile(boxes.run(), session.replica("run"));
      var reviewReport = new SyncEngine().reconcile(boxes.review(), session.replica("review"));
      var knownMessages = boxes.messages().syncEntityIds();
      var messageReport = new SyncEngine().reconcile(boxes.message(), session.replica("message"));
      var pulledMessages =
          pulledMessageEvents(boxes.messages(), boxes.specs(), knownMessages, HostInfo.hostname());
      var rejected = applyFdes(boxes.fdes(), session.fetchFdes());
      if (!rejected.isEmpty()) {
        System.err.println(
            Banner.errorLine(
                "Skipped "
                    + rejected.size()
                    + " malformed identity entry(ies) from main: "
                    + String.join(", ", rejected),
                Ansi.AUTO));
      }
      materialize(boxes.files());
      materializeProjects(boxes.projects());
      reconcileLiveResources(boxes.projects(), projectReport);
      return new Round(
          combine(
              combine(
                  combine(combine(combine(specReport, fileReport), projectReport), runReport),
                  reviewReport),
              messageReport),
          pulledMessages);
    }
  }

  /**
   * The {@code spec_message_posted} events a pull round owes the local bus: one per message the
   * reconcile newly adopted from main, shaped exactly like the posting box's own event so the room
   * wake reactor hears a synced message the same as a local one. Messages this box already knew —
   * its own posts, and everything pushed outward — fired at post time and are excluded by the
   * pre-reconcile id snapshot. A message whose spec has not landed locally is skipped: the spec
   * replica reconciles first, so that only happens for a row orphaned on main.
   */
  static List<Event> pulledMessageEvents(
      MessageStore messages, SpecStore specs, Set<String> known, String host) {
    return messages.syncEntityIds().stream()
        .filter(id -> !known.contains(id))
        .map(messages::findById)
        .flatMap(Optional::stream)
        .map(
            row ->
                specs
                    .findById(row.specId())
                    .map(
                        spec ->
                            SyncTransitionEvents.messagePosted(
                                spec.project(),
                                row.specId(),
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
      var descriptor = SailPaths.projectDir(project.name()).resolve(SailPaths.PROJECT_DESCRIPTOR);
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
    var materializer = new FileMaterializer(files, SailPaths.projectsDir());
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

  /** Sums two reconcile reports (specs + files) into one round summary. */
  static SyncEngine.Report combine(SyncEngine.Report a, SyncEngine.Report b) {
    return new SyncEngine.Report(
        a.pulled() + b.pulled(),
        a.pushed() + b.pushed(),
        a.merged() + b.merged(),
        a.conflicts() + b.conflicts());
  }

  /**
   * Mirrors main's roster into the local FDE store, returning the handles of any entries rejected
   * for a malformed role or status — dropped, never written with a bad authorization.
   */
  static List<String> applyFdes(FdeStore fdes, List<Map<String, Object>> roster) {
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
      publishQuietly(boardUpdatedEvent(HostInfo.hostname(), round.report()));
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
  static boolean shouldNotify(SyncEngine.Report report) {
    return report.pulled() + report.merged() + report.conflicts() > 0;
  }

  static Event boardUpdatedEvent(String host, SyncEngine.Report report) {
    var data =
        Map.<String, Object>of(
            "pulled", report.pulled(),
            "merged", report.merged(),
            "conflicts", report.conflicts());
    return Event.of(
        Event.SAIL_AGENT, null, Event.WellKnownTypes.BOARD_UPDATED, Event.SAIL_AGENT, host, data);
  }

  static String render(SyncEngine.Report report, boolean json) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("pulled", report.pulled());
      map.put("pushed", report.pushed());
      map.put("merged", report.merged());
      map.put("conflicts", report.conflicts());
      return YamlUtil.dumpJson(map);
    }
    if (report.total() == 0) {
      return Ansi.AUTO.string("  @|green ✓|@ Already in sync with main.");
    }
    var summary =
        Ansi.AUTO.string(
            "  @|green ✓|@ Synced with main: @|bold "
                + report.pulled()
                + "|@ pulled, @|bold "
                + report.pushed()
                + "|@ pushed, @|bold "
                + report.merged()
                + "|@ merged.");
    if (report.conflicts() == 0) {
      return summary;
    }
    return summary
        + "\n"
        + Banner.errorLine(
            report.conflicts()
                + " conflict(s) need your decision. Run 'sail conflicts' to resolve.",
            Ansi.AUTO);
  }
}
