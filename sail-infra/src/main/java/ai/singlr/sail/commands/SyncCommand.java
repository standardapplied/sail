/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.Event;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.Operations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.api.SyncReport;
import ai.singlr.sail.api.SyncRequest;
import ai.singlr.sail.api.SyncViews;
import ai.singlr.sail.config.SyncConfig;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.SyncOperations;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.MessageStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.sync.SyncDatabase;
import ai.singlr.sail.sync.SyncEngine;
import ai.singlr.sail.sync.SyncSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;

/**
 * Reconciles this box's local replicas with the main devbox over the SSH-key gateway. The engine
 * runs here on the node and drives one page of main's change log at a time across the channel:
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
    mixinStandardHelpOptions = true,
    subcommands = SyncCommand.Status.class)
public final class SyncCommand implements Callable<Integer> {

  @Command(
      name = "status",
      description = "Show this node's stored sync health.",
      mixinStandardHelpOptions = true)
  static final class Status implements Callable<Integer> {
    @Option(names = "--json", description = "Output sync health as JSON.")
    private boolean json;

    private final Supplier<HostOperations> operations;

    Status() {
      this(OperationsFactory::open);
    }

    Status(Supplier<HostOperations> operations) {
      this.operations = operations;
    }

    @Override
    public Integer call() throws Exception {
      try (var ops = operations.get()) {
        var status = SyncViews.status(ops.syncStatus());
        status.put("pending_conflicts", ops.conflicts().size());
        System.out.println(json ? YamlUtil.dumpJson(status) : renderStatus(status));
      }
      return 0;
    }
  }

  static String renderStatus(Map<String, Object> status) {
    var main = status.get("main");
    var health =
        switch (Objects.toString(status.get("state"), "")) {
          case "syncing" -> "Syncing with " + main;
          case "stale" ->
              "Stale since " + status.get("stale_since") + " — " + status.get("last_error");
          case "in_sync" -> "In sync with " + main;
          default ->
              main == null ? "Not a node: nothing to sync with." : "No sync round yet with " + main;
        };
    return status.get("pending_conflicts") instanceof Integer pending && pending > 0
        ? health + " — " + pending + " conflict(s) need your decision: sail conflicts"
        : health;
  }

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
    try (var operations = OperationsFactory.open()) {
      return watch ? watchLoop(operations, target) : runOnce(operations, target);
    } catch (RuntimeException e) {
      System.err.println(Banner.errorLine(reason(e), Ansi.AUTO));
      return 1;
    }
  }

  /**
   * Where this box syncs to. A non-null {@link MainTarget#target()} means reconcile against it; a
   * null target with a {@link MainTarget#message()} means there is nothing to sync — a single box,
   * or the main hub itself — which the caller reports as friendly info, not an error.
   */
  record MainTarget(String target, String message) {}

  static MainTarget resolveMain(String flag, SyncConfig sync) {
    var target = SyncOperations.resolveMain(flag, sync);
    return new MainTarget(target.target(), target.message());
  }

  private int runOnce(Operations operations, String target) {
    try {
      var round = operations.sync(new SyncRequest(target));
      System.out.println(render(round, json));
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

  private int watchLoop(Operations operations, String target) throws InterruptedException {
    while (true) {
      try {
        var round = operations.sync(new SyncRequest(target));
        System.out.println(render(round, json));
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

  static List<Event> pulledMessageEvents(
      MessageStore messages, SpecStore specs, Set<String> known, String host) {
    return SyncOperations.pulledMessageEvents(messages, specs, known, host);
  }

  static List<String> applyFdes(FdeStore fdes, List<Map<String, Object>> roster) {
    return SyncOperations.applyFdes(fdes, roster);
  }

  static boolean shouldNotify(SyncEngine.Report report) {
    return SyncOperations.shouldNotify(report);
  }

  static Event boardUpdatedEvent(String host, SyncEngine.Report report) {
    return SyncOperations.boardUpdatedEvent(host, report);
  }

  static String render(SyncReport round, boolean json) {
    var report = round.report();
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("pulled", report.pulled());
      map.put("pushed", report.pushed());
      map.put("merged", report.merged());
      map.put("conflicts", report.conflicts());
      map.put("types", round.types().stream().map(SyncViews::type).toList());
      return YamlUtil.dumpJson(map);
    }
    var detail =
        round.types().stream().filter(SyncCommand::worthALine).map(SyncCommand::line).toList();
    if (report.total() == 0 && detail.isEmpty()) {
      return Ansi.AUTO.string("  @|green ✓|@ Already in sync with main.");
    }
    var lines = new ArrayList<String>();
    lines.add(
        Ansi.AUTO.string(
            "  @|green ✓|@ Synced with main: @|bold "
                + report.pulled()
                + "|@ pulled, @|bold "
                + report.pushed()
                + "|@ pushed, @|bold "
                + report.merged()
                + "|@ merged."));
    lines.addAll(detail);
    if (report.conflicts() > 0) {
      lines.add(
          Banner.errorLine(
              report.conflicts()
                  + " conflict(s) need your decision. Run 'sail conflicts' to resolve.",
              Ansi.AUTO));
    }
    return String.join("\n", lines);
  }

  private static boolean worthALine(SyncSession.TypeReport type) {
    return type.failure() != null || type.report().total() > 0;
  }

  private static String line(SyncSession.TypeReport type) {
    if (type.failure() != null) {
      return Banner.errorLine(type.type() + ": " + type.failure(), Ansi.AUTO);
    }
    var counts = type.report();
    var conflicts = counts.conflicts() == 0 ? "" : ", " + counts.conflicts() + " conflict(s)";
    return Ansi.AUTO.string(
        "    @|faint "
            + type.type()
            + ":|@ "
            + counts.pulled()
            + " pulled, "
            + counts.pushed()
            + " pushed, "
            + counts.merged()
            + " merged"
            + conflicts
            + " @|faint ("
            + type.pages()
            + " page(s), "
            + type.entries()
            + " entries)|@");
  }
}
