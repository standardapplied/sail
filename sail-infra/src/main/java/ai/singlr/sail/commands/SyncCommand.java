/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.SailEventPublisher;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.SshSyncChannel;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.SyncConflicts;
import ai.singlr.sail.store.SyncState;
import ai.singlr.sail.sync.RemoteMainReplica;
import ai.singlr.sail.sync.SpecReplica;
import ai.singlr.sail.sync.SyncEngine;
import java.util.LinkedHashMap;
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
      required = true,
      description = "SSH target of the main devbox, e.g. sail@maindevbox.")
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
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      var local =
          new SpecReplica(
              HostInfo.hostname(),
              new SpecStore(db),
              new ChangeLog(db),
              new SyncConflicts(db),
              new SyncState(db));
      return watch ? watchLoop(local) : runOnce(local);
    }
  }

  private int runOnce(SpecReplica local) throws Exception {
    var report = reconcile(local);
    System.out.println(render(report, json));
    notifyBoardUpdated(report);
    return 0;
  }

  private int watchLoop(SpecReplica local) throws InterruptedException {
    while (true) {
      try {
        var report = reconcile(local);
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

  private SyncEngine.Report reconcile(SpecReplica local) throws Exception {
    try (var channel = SshSyncChannel.open(main);
        var remote = new RemoteMainReplica(channel.reader(), channel.writer())) {
      return new SyncEngine().reconcile(local, remote);
    }
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
