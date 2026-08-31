/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.ApiException;
import ai.singlr.sail.api.DispatchOperations;
import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WatcherSpawner;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ReviewStore;
import ai.singlr.sail.store.RunStore;
import ai.singlr.sail.store.SpecStore;
import ai.singlr.sail.store.Sqlite;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Runs the entropy-sweep prompt as a foreground ad-hoc session through the shared {@link
 * DispatchOperations} launch machinery, so a sweep is a first-class run — reserved, recorded, and
 * mutually exclusive with any dispatched agent in the container.
 */
@Command(
    name = "sweep",
    description = "Run an entropy sweep to clean up codebase drift and inconsistencies.",
    mixinStandardHelpOptions = true)
public final class AgentSweepCommand implements Runnable {

  static final String SWEEP_PROMPT =
      """
      You are running an entropy sweep — a focused cleanup pass on this codebase. \
      Do NOT add new features or change behavior. Only clean up.

      Scan for and fix:
      1. Dead imports and unused variables
      2. Naming inconsistencies (methods, variables, files that don't match project conventions)
      3. Dead code (unreachable branches, commented-out code, unused methods)
      4. Documentation drift (outdated comments, stale README sections, wrong examples)
      5. Dependency issues (unused dependencies, version inconsistencies)
      6. Test coverage gaps for critical paths (add tests, don't modify production code)
      7. Formatting and style violations per project conventions

      For each category, scan the entire codebase systematically. Fix issues directly — \
      don't just report them. Run tests after each batch of fixes to ensure nothing breaks. \
      Commit each category as a separate commit with a clear message like \
      "sweep: remove dead imports" or "sweep: fix naming inconsistencies".

      When done, write a summary to ~/sweep-report.md listing what was found and fixed.""";

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (default: the current project).")
  private String name;

  @Option(
      names = {"-f", "--file"},
      description = "Path to sail.yaml project descriptor.",
      defaultValue = "sail.yaml")
  private String file;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    name = CurrentProject.require(name);
    NameValidator.requireValidProjectName(name);

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);

    ContainerStateGuard.requireRunning(state, name);

    var handle = Objects.toString(HostSync.handle(), "");
    var describeOnly = json || dryRun;
    var launchCommand = new AtomicReference<List<String>>();
    try (var db = Sqlite.open(SailPaths.controlPlaneDb())) {
      var operations = operations(shell, launchCommand, db);
      var request =
          new DispatchOperations.AdhocRequest(SWEEP_PROMPT, null, null, false, describeOnly);
      DispatchOperations.AdhocSession session;
      try {
        session = operations.startAdhoc(name, request, handle);
      } catch (ApiException e) {
        var action = e.failure().action();
        throw new IllegalStateException(
            Strings.isBlank(action) ? e.getMessage() : e.getMessage() + " " + action, e);
      }
      render(session, launchCommand.get());
    }
  }

  private DispatchOperations operations(
      ShellExecutor shell, AtomicReference<List<String>> launchCommand, Sqlite db) {
    var listener =
        new DispatchOperations.Listener() {
          @Override
          public void launching(boolean bg, List<String> command) {
            launchCommand.set(command);
            if (json) {
              return;
            }
            if (!dryRun) {
              Banner.printBranding(System.out, Ansi.AUTO);
            }
            System.out.println();
            System.out.println(Ansi.AUTO.string("  @|bold Launching entropy sweep...|@"));
            System.out.println(Ansi.AUTO.string("  @|faint " + String.join(" ", command) + "|@"));
            System.out.println();
          }
        };
    return new DispatchOperations(
        shell,
        file,
        new SpecStore(db),
        new ReviewStore(db),
        new RunStore(db),
        new FdeStore(db),
        event -> {},
        new WatcherSpawner(shell, WatcherSpawner::spawnProcess),
        (project, config) -> "",
        DispatchOperations.terminalLauncher(),
        listener,
        new PtyHostYield());
  }

  private void render(DispatchOperations.AdhocSession session, List<String> command) {
    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("action", "sweep");
      map.put("run_id", session.runId());
      map.put("ssh_command", command == null ? "" : String.join(" ", command));
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }
    if (dryRun) {
      System.out.println("[dry-run] " + (command == null ? "" : String.join(" ", command)));
      return;
    }
    if (session.exitCode() != null && session.exitCode() != 0) {
      System.err.println(
          Banner.errorLine("Sweep session exited with code " + session.exitCode(), Ansi.AUTO));
      return;
    }
    System.out.println(Ansi.AUTO.string("  @|bold,green ✓ Entropy sweep complete.|@"));
    System.out.println(Ansi.AUTO.string("  @|faint Report at:|@ ~/sweep-report.md"));
  }
}
