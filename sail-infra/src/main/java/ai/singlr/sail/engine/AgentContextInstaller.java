/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.gen.AgentAuditFiles;
import ai.singlr.sail.gen.AgentContextGenerator;
import ai.singlr.sail.gen.ContextMerge;
import ai.singlr.sail.gen.GeneratedFile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Generates a project's agent context from its {@link SailYaml} and installs it into the running
 * container: the per-agent context file (CLAUDE.md / AGENTS.md), SECURITY.md, the methodology and
 * spec-board skill, and the audit/guardrail hooks. Each file is installed by its policy: a {@link
 * GeneratedFile#mergeMarker merge} file (the per-agent context and SECURITY.md) refreshes its
 * generated body above the marker while preserving the engineer's personal region below it (see
 * {@link ContextMerge}); everything else (the spec-board skill, methodology, agent hooks) is
 * overwritten outright. {@code force} resets a merge file's personal region to the default stub.
 *
 * <p>Each file's parent directory is created before the push, so deeply nested generated files —
 * the {@code .claude/skills/spec-board/} skill in particular — land even on a container that never
 * had them. Pure orchestration over {@link ShellExec}: generate (no I/O), then push via {@link
 * ContainerFilePush} owned by the dev user. It deliberately does not touch the in-container
 * machinery (event socket, {@code spec} CLI, agent hook configs) — that is {@link
 * ContainerSailSetup}'s single responsibility.
 *
 * <p>Shared by {@code agent context regen} and {@code project reconfigure} so both refresh an
 * identical set.
 */
public final class AgentContextInstaller {

  private AgentContextInstaller() {}

  /** The remote paths an install wrote (merged or overwritten). */
  public record Result(List<String> pushed) {

    public Result {
      pushed = List.copyOf(pushed);
    }

    /** No agent configured — nothing to generate or install. */
    public static Result none() {
      return new Result(List.of());
    }

    /** True when the project has no agent context to install. */
    public boolean isEmpty() {
      return pushed.isEmpty();
    }
  }

  /**
   * Regenerates and installs the agent context for {@code config}, preserving each merge file's
   * personal region. Equivalent to {@link #install(ShellExec, String, SailYaml, boolean)} with
   * {@code force=false}.
   */
  public static Result install(ShellExec shell, String container, SailYaml config)
      throws IOException, InterruptedException, TimeoutException {
    return install(shell, container, config, false);
  }

  /**
   * Regenerates and installs the agent context for {@code config} into {@code container}, returning
   * the remote paths written. Returns {@link Result#none()} when no agent is configured.
   *
   * <p>Merge files (the per-agent context and SECURITY.md) refresh their generated body while
   * preserving the engineer's personal region below the marker; machinery (the spec-board skill,
   * methodology, agent hooks) is overwritten. {@code force} resets a merge file's personal region
   * to the default stub.
   */
  public static Result install(ShellExec shell, String container, SailYaml config, boolean force)
      throws IOException, InterruptedException, TimeoutException {
    Objects.requireNonNull(shell, "shell");
    NameValidator.requireValidProjectName(container);
    Objects.requireNonNull(config, "config");

    var contextFiles = AgentContextGenerator.generateFiles(config);
    var auditFiles = AgentAuditFiles.assemble(config);
    if (contextFiles.isEmpty() && auditFiles.isEmpty()) {
      return Result.none();
    }

    var pushed = new ArrayList<String>();
    for (var file : contextFiles) {
      if (file.mergeMarker() != null) {
        var prior = force ? null : readFile(shell, container, file.remotePath());
        pushContent(
            shell,
            container,
            file.remotePath(),
            ContextMerge.merge(prior, file.content()),
            file.executable());
      } else {
        push(shell, container, file);
      }
      pushed.add(file.remotePath());
    }
    for (var file : auditFiles) {
      push(shell, container, file);
      pushed.add(file.remotePath());
    }
    return new Result(pushed);
  }

  private static void push(ShellExec shell, String container, GeneratedFile file)
      throws IOException, InterruptedException, TimeoutException {
    pushContent(shell, container, file.remotePath(), file.content(), file.executable());
  }

  private static void pushContent(
      ShellExec shell, String container, String remotePath, String content, boolean executable)
      throws IOException, InterruptedException, TimeoutException {
    exec(shell, container, List.of("mkdir", "-p", parentOf(remotePath)));
    var flags =
        new ArrayList<>(List.of("--uid", ContainerExec.DEV_UID, "--gid", ContainerExec.DEV_GID));
    if (executable) {
      flags.addAll(List.of("--mode", "0755"));
    }
    ContainerFilePush.push(shell, container, remotePath, content, flags);
  }

  /** The current content of a container file, or {@code null} if it is absent or unreadable. */
  private static String readFile(ShellExec shell, String container, String remotePath) {
    try {
      var result = exec(shell, container, List.of("cat", remotePath));
      return result.ok() ? result.stdout() : null;
    } catch (IOException | InterruptedException | TimeoutException e) {
      return null;
    }
  }

  private static ShellExec.Result exec(ShellExec shell, String container, List<String> args)
      throws IOException, InterruptedException, TimeoutException {
    return shell.exec(ContainerExec.asDevUser(container, args));
  }

  private static String parentOf(String remotePath) {
    return remotePath.substring(0, remotePath.lastIndexOf('/'));
  }
}
