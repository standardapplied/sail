/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.Banner;
import ai.singlr.sail.engine.ContainerExec;
import ai.singlr.sail.engine.ContainerManager;
import ai.singlr.sail.engine.ContainerStateGuard;
import ai.singlr.sail.engine.NameValidator;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SpecCliHelper;
import ai.singlr.sail.gen.AgentAuditFiles;
import ai.singlr.sail.gen.AgentContextGenerator;
import ai.singlr.sail.gen.GeneratedFile;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "regen",
    description = "Regenerate the agent context file from sail.yaml.",
    mixinStandardHelpOptions = true)
public final class AgentContextRegenCommand implements Runnable {

  @Parameters(index = "0", description = "Project name.")
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
    NameValidator.requireValidProjectName(name);

    var singYamlPath = SailPaths.resolveSailYaml(name, file);
    if (!Files.exists(singYamlPath)) {
      throw new IllegalStateException(
          "Project descriptor not found: "
              + singYamlPath.toAbsolutePath()
              + "\n  Create a sail.yaml in the current directory, or specify one with --file.");
    }
    var config = SailYaml.fromMap(YamlUtil.parseFile(singYamlPath));

    var shell = new ShellExecutor(dryRun);
    var mgr = new ContainerManager(shell);
    var state = mgr.queryState(name);

    ContainerStateGuard.requireRunning(state, name);

    var contextFiles = AgentContextGenerator.generateFiles(config);
    var auditFiles = AgentAuditFiles.assemble(config);

    if (contextFiles.isEmpty()) {
      throw new IllegalStateException(
          "No agent configured in sail.yaml."
              + "\n  Add an 'agent:' section to generate context files.");
    }

    if (dryRun) {
      for (var file : contextFiles) {
        System.out.println(
            "[dry-run] Would push "
                + file.remotePath()
                + " ("
                + file.content().length()
                + " bytes)");
      }
      for (var auditFile : auditFiles) {
        System.out.println(
            "[dry-run] Would push "
                + auditFile.remotePath()
                + " ("
                + auditFile.content().length()
                + " bytes"
                + (auditFile.executable() ? ", executable" : "")
                + ")");
      }
    } else {
      var sshUser = config.sshUser();
      var workspacePath = "/home/" + sshUser + "/workspace";
      shell.exec(ContainerExec.asDevUser(name, List.of("mkdir", "-p", workspacePath)));

      var existingFiles = listWorkspaceFiles(shell, name, workspacePath);
      var pushed = new ArrayList<String>();
      var skipped = new ArrayList<String>();

      for (var file : contextFiles) {
        var fileName = file.remotePath().substring(file.remotePath().lastIndexOf('/') + 1);
        if (file.skipIfExists() && containsCaseInsensitive(existingFiles, fileName)) {
          skipped.add(fileName);
          continue;
        }
        pushFile(shell, name, file.remotePath(), file.content(), sshUser);
        pushed.add(file.remotePath());
      }
      for (var auditFile : auditFiles) {
        var parentDir =
            auditFile.remotePath().substring(0, auditFile.remotePath().lastIndexOf('/'));
        shell.exec(ContainerExec.asDevUser(name, List.of("mkdir", "-p", parentDir)));
        pushFile(shell, name, auditFile.remotePath(), auditFile.content(), sshUser);
        if (auditFile.executable()) {
          shell.exec(ContainerExec.asDevUser(name, List.of("chmod", "+x", auditFile.remotePath())));
        }
      }

      if (!skipped.isEmpty() && !json) {
        for (var s : skipped) {
          System.out.println(Ansi.AUTO.string("  @|faint Kept existing " + s + " from repo|@"));
        }
      }

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

    Banner.printBranding(System.out, Ansi.AUTO);
    System.out.println();
    for (var file : contextFiles) {
      System.out.println(
          Ansi.AUTO.string(
              "  @|bold,green \u2713 Agent context regenerated:|@ " + file.remotePath()));
    }
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

  private static Set<String> listWorkspaceFiles(
      ShellExecutor shell, String containerName, String dirPath) {
    try {
      var result = shell.exec(ContainerExec.asDevUser(containerName, List.of("ls", "-1", dirPath)));
      if (!result.ok() || result.stdout().isBlank()) {
        return Set.of();
      }
      return Set.copyOf(List.of(result.stdout().strip().split("\n")));
    } catch (Exception e) {
      return Set.of();
    }
  }

  private static boolean containsCaseInsensitive(Set<String> files, String target) {
    var lowerTarget = target.toLowerCase();
    return files.stream().anyMatch(f -> f.toLowerCase().equals(lowerTarget));
  }

  /** Pushes content to a file inside the container via temp file + incus file push + chown. */
  private static void pushFile(
      ShellExecutor shell, String containerName, String remotePath, String content, String sshUser)
      throws Exception {
    var tmpFile = Files.createTempFile("sail-push-", ".tmp");
    try {
      Files.writeString(tmpFile, content);
      var result =
          shell.exec(
              List.of("incus", "file", "push", tmpFile.toString(), containerName + remotePath));
      if (!result.ok()) {
        throw new IOException("Failed to push file " + remotePath + ": " + result.stderr());
      }
    } finally {
      Files.deleteIfExists(tmpFile);
    }
    shell.exec(
        ContainerExec.asDevUser(
            containerName, List.of("chown", sshUser + ":" + sshUser, remotePath)));
  }
}
