/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.FileMaterializer;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.SshSyncChannel;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.FileStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import ai.singlr.sail.sync.FileReplica;
import ai.singlr.sail.sync.SpecReplica;
import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncSession;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

/**
 * Reconciles this box's local spec replica with the main devbox over the SSH-key gateway. The
 * engine runs here on the node and drives a {@link RemoteMainReplica} across the channel:
 * local-only work pushes (main mints the rev), main-only work pulls, disjoint edits auto-merge, and
 * same-field conflicts are parked locally for {@code sail conflicts} — the node's row is never
 * clobbered. The round is idempotent; running it again after it converges does nothing.
 *
 * <p>With {@code --watch} it loops on an interval, staying up through a transient main outage and
 * resuming from the checkpoint when main returns. Each round that brings remote work (or raises a
 * conflict) emits a {@code board_updated} event onto the local event stream, so the CLI and Mast
 * surface an "updates available" banner. The notification is advisory — a sync that cannot reach
 * the event server still completes.
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
    String target;
    try {
      target = resolveMain(main, hostSync());
    } catch (IllegalStateException e) {
      System.err.println(Banner.errorLine(e.getMessage(), Ansi.AUTO));
      return 1;
    }
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      var host = HostInfo.hostname();
      var changeLog = new ChangeLog(db);
      var conflicts = new SyncConflicts(db);
      var syncState = new SyncState(db);
      var fileStore = new FileStore(db);
      var boxes =
          new Boxes(
              new SpecReplica(host, new SpecStore(db), changeLog, conflicts, syncState),
              new FileReplica(host, fileStore, changeLog, conflicts, syncState),
              new FdeStore(db),
              fileStore);
      return watch ? watchLoop(boxes, target) : runOnce(boxes, target);
    }
  }

  private record Boxes(SpecReplica spec, FileReplica file, FdeStore fdes, FileStore files) {}

  /** The main target: the {@code --main} flag if given, else the configured one. */
  static String resolveMain(String flag, SyncConfig sync) {
    if (Strings.isNotBlank(flag)) {
      return flag;
    }
    if (sync.isMain()) {
      return throwUnresolved("This box is the main devbox; it has nothing to sync to.");
    }
    if (sync.main() != null) {
      return sync.main();
    }
    return throwUnresolved(
        "No main devbox configured. Set one with: sudo sail host sync --main <user@host>,"
            + " or pass --main.");
  }

  private static String throwUnresolved(String message) {
    throw new IllegalStateException(message);
  }

  private static SyncConfig hostSync() {
    var path = SailPaths.hostConfigPath();
    if (!Files.exists(path)) {
      return SyncConfig.unset();
    }
    try {
      return HostYaml.fromMap(YamlUtil.parseFile(path)).sync();
    } catch (IOException e) {
      return SyncConfig.unset();
    }
  }

  private int runOnce(Boxes boxes, String target) throws Exception {
    var report = reconcile(boxes, target);
    System.out.println(render(report, json));
    notifyBoardUpdated(report);
    return 0;
  }

  private int watchLoop(Boxes boxes, String target) throws InterruptedException {
    while (true) {
      try {
        var report = reconcile(boxes, target);
        System.out.println(render(report, json));
        notifyBoardUpdated(report);
      } catch (InterruptedException e) {
        throw e;
      } catch (Exception e) {
        System.err.println(
            Banner.errorLine(
                "Sync round failed (" + e.getMessage() + "); retrying in " + intervalSeconds + "s.",
                Ansi.AUTO));
      }
      Thread.sleep(intervalSeconds * 1000L);
    }
  }

  private SyncEngine.Report reconcile(Boxes boxes, String target) throws Exception {
    try (var channel = SshSyncChannel.open(target);
        var session = new SyncSession(channel.reader(), channel.writer())) {
      var specReport = new SyncEngine().reconcile(boxes.spec(), session.replica("spec"));
      var fileReport = new SyncEngine().reconcile(boxes.file(), session.replica("file"));
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
      return combine(specReport, fileReport);
    }
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

  private void notifyBoardUpdated(SyncEngine.Report report) {
    if (!shouldNotify(report)) {
      return;
    }
    try {
      if (publisher == null) {
        publisher = SailEventPublisher.localDefault();
      }
      publisher.publish(boardUpdatedEvent(HostInfo.hostname(), report));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine(
              "Could not publish board_updated event ("
                  + e.getMessage()
                  + "). sail-api may be unreachable; the sync itself is unaffected.",
              Ansi.AUTO));
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
