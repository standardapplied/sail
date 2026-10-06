/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.api.HostCatalog;
import ai.singlr.sail.api.HostOperations;
import ai.singlr.sail.api.OperationsFactory;
import ai.singlr.sail.config.PlaceholderResolver;
import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.AgentContextInstaller;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.LocalIdentity;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SpecCliHelper;
import ai.singlr.sail.gen.AgentContextGenerator;
import ai.singlr.sail.gen.GeneratedFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Supplier;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "regen",
    description = "Regenerate the agent context files from the project's definition.",
    mixinStandardHelpOptions = true)
public final class AgentContextRegenCommand implements Runnable {

  @Parameters(
      index = "0",
      arity = "0..1",
      description = "Project name (default: the current project).")
  private String name;

  @Mixin private IgnoredFileOption file;

  @Option(names = "--json", description = "Output in JSON format.")
  private boolean json;

  @Option(names = "--dry-run", description = "Print commands instead of executing them.")
  private boolean dryRun;

  @Spec private CommandSpec spec;

  private final Supplier<HostOperations> operations;
  private final LocalIdentity identity;

  public AgentContextRegenCommand() {
    this(OperationsFactory::open, LocalIdentity.detect());
  }

  AgentContextRegenCommand(Supplier<HostOperations> operations, LocalIdentity identity) {
    this.operations = operations;
    this.identity = identity;
  }

  @Override
  public void run() {
    CliCommand.run(spec, this::execute);
  }

  private void execute() throws Exception {
    name = CurrentProject.require(name);
    NameValidator.requireValidProjectName(name);

    SailYaml config;
    try (var operations = this.operations.get()) {
      config = definitionWithBoxIdentity(operations.catalog(), name, identity);
    }

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);

    ContainerStateGuard.requireRunning(state, name);

    var contextFiles = AgentContextGenerator.generateFiles(config);

    if (contextFiles.isEmpty()) {
      throw new IllegalStateException(
          "Project '"
              + name
              + "' has no agent configured."
              + "\n  Add an 'agent:' section with 'sail project edit "
              + name
              + "'.");
    }

    var pushed = new ArrayList<String>();

    if (dryRun) {
      for (var file : contextFiles) {
        System.out.println(
            "[dry-run] Would push "
                + file.remotePath()
                + " ("
                + file.content().length()
                + " bytes)");
      }
    } else {
      var result = AgentContextInstaller.install(shell, name, config);
      pushed.addAll(result.pushed());
      installSpecCli(shell);
    }

    if (json) {
      var map = new LinkedHashMap<String, Object>();
      map.put("name", name);
      map.put("action", "regen_context");
      map.put("files", contextFiles.stream().map(GeneratedFile::remotePath).toList());
      System.out.println(YamlUtil.dumpJson(map));
      return;
    }

    if (dryRun) {
      return;
    }
    Banner.printBranding(System.out, Ansi.AUTO);
    System.out.println();
    if (pushed.isEmpty()) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|faint Nothing to regenerate \u2014 kept every engineer-owned file.|@"));
    }
    for (var path : pushed) {
      System.out.println(
          Ansi.AUTO.string("  @|bold,green \u2713 Agent context regenerated:|@ " + path));
    }
  }

  /**
   * The project's definition for the agent's context, which names the git identity the agent
   * commits as: the catalog row, with {@code ${GIT_NAME}} and {@code ${GIT_EMAIL}} replaced by this
   * box's git identity where the box has one. Any other placeholder, and either of these on a box
   * with no identity set, is left as the row holds it. A project not in the catalog, or one whose
   * row cannot be read, fails before anything is replaced.
   */
  static SailYaml definitionWithBoxIdentity(
      HostCatalog catalog, String name, LocalIdentity identity) {
    catalog.definitions().require(name);
    var text = catalog.project(name).orElseThrow().definition();
    var values = new LinkedHashMap<String, String>();
    for (var field : List.of(PlaceholderResolver.GIT_NAME, PlaceholderResolver.GIT_EMAIL)) {
      if (text.contains(PlaceholderResolver.token(field))) {
        identity.gitValue(field).ifPresent(value -> values.put(field, value));
      }
    }
    return SailYaml.fromMap(PlaceholderResolver.substitute(YamlUtil.parseMap(text), values));
  }

  /**
   * Installs (or refreshes) the in-container {@code spec} CLI so regen doubles as the retrofit for
   * projects created before specs moved to the database. Best-effort: the context files are the
   * primary deliverable, so a failure here only warns.
   */
  private void installSpecCli(ShellExecutor shell) {
    try {
      new SpecCliHelper(shell).install(name);
      if (!json) {
        System.out.println(
            Ansi.AUTO.string("  @|faint Installed the spec CLI (~/.sail/bin/spec)|@"));
      }
    } catch (Exception e) {
      System.err.println(
          Banner.errorLine("Could not install the spec CLI: " + e.getMessage(), Ansi.AUTO));
    }
  }
}
