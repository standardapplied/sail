/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerSailSetup;
import ai.singlr.sail.engine.ContainerState;
import ai.singlr.sail.engine.DemoProject;
import ai.singlr.sail.engine.GitCredentials;
import ai.singlr.sail.engine.HostInfo;
import ai.singlr.sail.engine.LocalIdentity;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PairingTrust;
import ai.singlr.sail.engine.ProjectPhase;
import ai.singlr.sail.engine.ProjectProvisioner;
import ai.singlr.sail.engine.ProvisionListener;
import ai.singlr.sail.engine.ProvisionTracker;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.WorkstationIdentity;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.ssh.SshPublicKey;
import java.io.ByteArrayInputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "demo",
    description = "Set up a demo project with zero configuration.",
    mixinStandardHelpOptions = true)
public final class ProjectDemoCommand implements Runnable {

  @Spec private CommandSpec spec;

  static final String DEMO_PROJECT = DemoProject.NAME;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--git-token", description = "Access token for cloning private repos over HTTPS.")
  private String gitToken;

  /** A provisioning that installs {@code workstationKey} in the container it creates. */
  @FunctionalInterface
  interface Provisioning {
    void provision(Optional<SshPublicKey> workstationKey) throws Exception;
  }

  /**
   * Runs {@code provisioning} with the workstation key read under {@link PairingTrust}, held until
   * the key is installed, so a revocation scans the containers only after the key is in this one.
   */
  static void provisionTrusting(Path database, OperatorCommand operator, Provisioning provisioning)
      throws Exception {
    try (var held = PairingTrust.hold(database)) {
      provisioning.provision(WorkstationIdentity.registered());
    }
  }

  @Override
  public void run() {
    CliCommand.run(this::execute);
  }

  private void execute() throws Exception {
    var operator = OperatorCommand.here();
    var ansi = Ansi.AUTO;
    var out = System.out;

    if (!dryRun && !ConsoleHelper.isRoot()) {
      throw RootRequired.refusal(spec);
    }

    var hostYamlPath = SailPaths.hostConfigPath();
    if (!dryRun && !Files.exists(hostYamlPath)) {
      throw HostInitCommand.notInitialized(operator);
    }
    if (!dryRun) {
      ContainerSailSetup.requireApi(SailPaths.apiSocketHostDir());
      var shell = new ShellExecutor(false);
      var mgr = new ContainerManager(shell);
      var state = mgr.queryState(DEMO_PROJECT);
      if (state instanceof ContainerState.Running) {
        throw new IllegalStateException(
            "Demo project is already running."
                + "\n  Get a shell:    "
                + operator.sail("project shell " + DEMO_PROJECT)
                + "\n  Destroy first:  "
                + operator.sail("project destroy " + DEMO_PROJECT));
      }
      if (state instanceof ContainerState.Stopped) {
        throw new IllegalStateException(
            "Demo project exists but is stopped."
                + "\n  Start it:       "
                + operator.sail("project start " + DEMO_PROJECT)
                + "\n  Destroy first:  "
                + operator.sail("project destroy " + DEMO_PROJECT));
      }
    }

    if (!json) {
      Banner.printBranding(out, ansi);
      out.println();
      out.println(ansi.string("  @|bold Setting up demo project...|@"));
      out.println();
    }

    var config = loadDemoDefinition(out, ansi);
    var registered = WorkstationIdentity.registered();
    var git = LocalIdentity.detect().git();
    if (!json) {
      out.println();
      out.println(ansi.string("  @|bold Auto-detected configuration:|@"));
      out.println(ansi.string("    Git:         " + git.name() + " <" + git.email() + ">"));
      out.println(
          ansi.string(
              "    Laptop key:  "
                  + (registered.isPresent()
                      ? "@|green registered|@"
                      : "@|yellow not registered \u2014 remote access won't work|@")));
      out.println();
    }

    var filesShared = dryRun ? DemoProject.files().size() : shareDemoFiles(out, ansi);

    if (dryRun) {
      if (json) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", DEMO_PROJECT);
        map.put("source", "catalog");
        map.put("files_shared", filesShared);
        map.put("dry_run", true);
        out.println(YamlUtil.dumpJson(map));
      } else {
        out.println();
        out.println(ansi.string("  @|faint [dry-run] Would provision project 'demo'|@"));
      }
      return;
    }

    var hostYaml = HostYaml.fromMap(YamlUtil.parseFile(hostYamlPath));
    var shell = new ShellExecutor(false);

    if (!json) {
      out.println();
      Banner.printProjectSummary(config, out, ansi);
    }

    var stateFile = SailPaths.provisionState(config.name());
    var tracker = new ProvisionTracker<>(ProjectPhase.class, stateFile, false);
    tracker.reset();

    var listener = json ? ProvisionListener.NOOP : ConsoleProvisionListener.INSTANCE;
    provisionTrusting(
        SailPaths.controlPlaneDb(),
        operator,
        workstationKey ->
            new ProjectProvisioner(shell, tracker, listener, workstationKey, operator)
                .provision(
                    config,
                    hostYaml,
                    GitCredentials.singleTokenMap(gitToken),
                    git,
                    SailPaths.projectFilesDir(config.name())));

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", config.name());
      map.put("status", "created");
      map.put("files_shared", filesShared);
      out.println(YamlUtil.dumpJson(map));
      return;
    }

    out.println();
    Banner.printProjectCreated(config.name(), operator, out, ansi);

    var mgr = new ContainerManager(shell);
    var currentState = mgr.queryState(config.name());
    if (currentState instanceof ContainerState.Running r && r.ipv4() != null) {
      var serverIp = hostYaml.serverIp();
      var serverUser = System.getProperty("user.name");
      if (serverIp != null) {
        Banner.printSshConfig(
            config.name(),
            HostInfo.hostname(),
            serverIp,
            serverUser,
            r.ipv4(),
            WorkstationIdentity.identityFileFor(registered),
            operator,
            out,
            ansi);
      }

      var ports = ContainerExec.queryServicePorts(shell, config.name());
      if (!ports.isEmpty()) {
        Banner.printSshTunnels(config.name(), ProjectDefinition.USER, ports, out, ansi);
      }
    }
  }

  private ProjectDefinition loadDemoDefinition(PrintStream out, Ansi ansi) {
    try (var operations = OperationsFactory.open()) {
      var definition = operations.catalog().demoDefinition();
      if (!json) {
        out.println(ansi.string("  @|green \u2713|@ demo project loaded from the catalog"));
      }
      return definition;
    }
  }

  /**
   * Shares the demo's bundled files through the catalog's file store and writes them where apply
   * pushes a project's shared files from, so provisioning puts them in the container's workspace.
   */
  private int shareDemoFiles(PrintStream out, Ansi ansi) throws Exception {
    var files = DemoProject.files();
    try (var operations = OperationsFactory.open()) {
      var shared = operations.projectFiles(DEMO_PROJECT);
      Actor.call(
          operations.identity().operator(),
          () -> {
            for (var entry : files.entrySet()) {
              var bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
              shared.put(
                  entry.getKey(),
                  new ByteArrayInputStream(bytes),
                  bytes.length,
                  entry.getKey().endsWith(".sh") ? 0755 : 0644);
              if (!json) {
                out.println(ansi.string("  @|green \u2713|@ shared " + entry.getKey()));
              }
            }
            return null;
          });
      shared.materialize();
    }
    return files.size();
  }
}
