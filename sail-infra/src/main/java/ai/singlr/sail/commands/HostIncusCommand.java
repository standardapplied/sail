/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.HostPackages;
import ai.singlr.sail.engine.NodeIdentity;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.Spinner;
import ai.singlr.sail.pty.PtyMessage;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Incus, which sail installs and holds ({@link HostPackages}): it moves only when someone runs
 * {@code host incus upgrade}, at a moment they choose, since incusd's restart ends every terminal
 * and agent run in every container.
 */
@Command(
    name = "incus",
    description = "Manage the Incus sail installs and holds on this host.",
    mixinStandardHelpOptions = true,
    subcommands = {HostIncusCommand.Upgrade.class})
public final class HostIncusCommand implements Runnable {

  @Override
  public void run() {
    new picocli.CommandLine(this).usage(System.out);
  }

  @Command(
      name = "upgrade",
      description =
          "Move Incus to the version apt offers, after listing the sessions and agent runs its"
              + " restart ends.",
      mixinStandardHelpOptions = true)
  static final class Upgrade implements Runnable {

    @Spec private CommandSpec spec;

    @Option(names = "--yes", description = "Upgrade without asking (for non-interactive use).")
    private boolean yes;

    @Option(
        names = "--dry-run",
        description = "Show what would move and what its restart would end; change nothing.")
    private boolean dryRun;

    @Override
    public void run() {
      CliCommand.run(
          () -> {
            if (!ConsoleHelper.isRoot()) {
              throw RootRequired.refusal(spec);
            }
            upgrade(
                new ShellExecutor(false),
                () -> LiveWork.of(SailPaths.ptySocketPath(), liveRuns()),
                yes,
                dryRun,
                OperatorCommand.here(),
                System.out);
          });
    }

    private static List<RunStore.RunRow> liveRuns() {
      try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
        return new RunStore(db).liveUnder(NodeIdentity.handle());
      }
    }
  }

  /**
   * Moves Incus to apt's candidate once the operator says so, having refreshed apt's index (not on
   * a dry run, which reads the local one) and listed the live work the restart ends, read only once
   * Incus is known to move.
   */
  static void upgrade(
      ShellExec shell,
      Supplier<LiveWork> live,
      boolean yes,
      boolean dryRun,
      OperatorCommand operator,
      PrintStream out)
      throws IOException, InterruptedException, TimeoutException {
    if (!dryRun) {
      try (var spinner = Spinner.start(out, "Refreshing apt's index...")) {
        new HostPackages(shell, spinner::detail).refresh();
      }
    }
    var versions = new HostPackages(shell, detail -> {}).versions();
    if (versions.installed() == null) {
      throw HostInitCommand.notInitialized(operator);
    }
    if (!versions.movable()) {
      out.println(Ansi.AUTO.string("  @|green ✓|@ Incus " + versions.from() + " is current."));
      return;
    }
    var work = live.get();
    work.print(out);
    var move = versions.from() + " to " + versions.to();
    if (dryRun) {
      out.println("  Would upgrade Incus " + move + " (--dry-run: nothing installed).");
      return;
    }
    if (!yes) {
      ask(move, work);
    }
    try (var spinner = Spinner.start(out, "Upgrading Incus " + move + "...")) {
      new HostPackages(shell, spinner::detail).upgrade();
    }
    out.println(Ansi.AUTO.string("  @|green ✓|@ Incus upgraded from " + move + "."));
  }

  private static void ask(String move, LiveWork work) {
    var notDone = "Incus not upgraded";
    var question = "Upgrade Incus " + move + "? incusd restarts and ends ";
    if (work.sessionsUnread() != null) {
      ConsoleHelper.confirmDestructive(
          question + "every terminal session and " + count(work.runs().size(), "agent run") + ".",
          notDone,
          "--yes");
    } else if (work.isEmpty()) {
      ConsoleHelper.proceed(
          "Upgrade Incus " + move + "? No session or agent run is live to end.", notDone, "--yes");
    } else {
      ConsoleHelper.confirmDestructive(
          question
              + "the "
              + count(work.sessions().size(), "session")
              + " and "
              + count(work.runs().size(), "agent run")
              + " above.",
          notDone,
          "--yes");
    }
  }

  private static String count(int n, String noun) {
    return n + " " + noun + (n == 1 ? "" : "s");
  }

  /**
   * The work on this box an incusd restart ends: the pty host's live sessions, or why they could
   * not be listed, and the agent runs this box executes that have not finished.
   */
  record LiveWork(
      List<PtyMessage.SessionInfo> sessions, String sessionsUnread, List<RunStore.RunRow> runs) {

    /** The live sessions the pty host at {@code ptySocket} answers with, and {@code runs}. */
    static LiveWork of(Path ptySocket, List<RunStore.RunRow> runs) {
      try (var client = SessionClient.connect(ptySocket)) {
        var sessions = client.list().stream().filter(PtyMessage.SessionInfo::live).toList();
        return new LiveWork(sessions, null, runs);
      } catch (IOException e) {
        return new LiveWork(List.of(), CliCommand.describe(e), runs);
      }
    }

    boolean isEmpty() {
      return sessions.isEmpty() && runs.isEmpty();
    }

    void print(PrintStream out) {
      if (sessionsUnread != null) {
        out.println(
            Ansi.AUTO.string(
                "  @|yellow ⚠|@ Could not list terminal sessions (" + sessionsUnread + ")."));
      }
      if (isEmpty()) {
        return;
      }
      out.println("  Live on this box:");
      for (var session : sessions) {
        out.println(
            "    session    " + session.name() + "  " + String.join(" ", session.command()));
      }
      for (var run : runs) {
        out.println(
            "    agent run  "
                + run.id()
                + "  "
                + run.project()
                + (run.conversationId() == null ? "" : " " + run.conversationId())
                + " ("
                + run.role()
                + ")");
      }
    }
  }
}
