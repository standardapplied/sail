/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.gen.AgentAuditFiles;
import ai.singlr.sail.gen.AgentContextGenerator;
import ai.singlr.sail.gen.GeneratedFile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Generates a project's agent context from its {@link SailYaml} and installs it into the running
 * container: the sail-owned {@code .sail/context.md} core, the per-agent entry file (CLAUDE.md /
 * AGENTS.md), SECURITY.md, the methodology and spec-board skills, and the audit/guardrail hooks.
 * Each file is installed by its {@link GeneratedFile.Ownership ownership}: sail-owned files (the
 * core, skills, hooks) are overwritten every run; engineer-owned files (the per-agent entry and
 * SECURITY.md) are written only when absent — sail scaffolds them once and never clobbers the
 * engineer's copy. {@code force} overwrites the engineer-owned files too.
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
   * Regenerates and installs the agent context for {@code config}, leaving engineer-owned files
   * that already exist untouched. Equivalent to {@link #install(ShellExec, String, SailYaml,
   * boolean)} with {@code force=false}.
   */
  public static Result install(ShellExec shell, String container, SailYaml config)
      throws IOException, InterruptedException, TimeoutException {
    return install(shell, container, config, false);
  }

  /**
   * Regenerates and installs the agent context for {@code config} into {@code container}, returning
   * the remote paths actually written. Returns {@link Result#none()} when no agent is configured.
   *
   * <p>Sail-owned files (the {@code .sail} core, skills, agent hooks) are overwritten every run.
   * Engineer-owned files (the per-agent {@code CLAUDE.md}/{@code AGENTS.md} and {@code
   * SECURITY.md}) are written only when absent — sail scaffolds them once and never clobbers the
   * engineer's copy. {@code force} overwrites them too, resetting them to the generated template.
   */
  public static Result install(ShellExec shell, String container, SailYaml config, boolean force)
      throws IOException, InterruptedException, TimeoutException {
    Objects.requireNonNull(shell, "shell");
    NameValidator.requireValidProjectName(container);
    Objects.requireNonNull(config, "config");

    var files = new ArrayList<>(AgentContextGenerator.generateFiles(config));
    files.addAll(AgentAuditFiles.assemble(config));
    if (files.isEmpty()) {
      return Result.none();
    }

    var pushed = new ArrayList<String>();
    for (var file : files) {
      if (shouldInstall(shell, container, file, force)) {
        push(shell, container, file);
        pushed.add(file.remotePath());
      }
    }
    return new Result(pushed);
  }

  /** Sail-owned files always install; engineer-owned files install only when absent or forced. */
  private static boolean shouldInstall(
      ShellExec shell, String container, GeneratedFile file, boolean force)
      throws IOException, InterruptedException, TimeoutException {
    return switch (file.ownership()) {
      case SAIL -> true;
      case ENGINEER -> force || !fileExists(shell, container, file.remotePath());
    };
  }

  private static boolean fileExists(ShellExec shell, String container, String remotePath)
      throws IOException, InterruptedException, TimeoutException {
    return exec(shell, container, List.of("test", "-f", remotePath)).ok();
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

  private static ShellExec.Result exec(ShellExec shell, String container, List<String> args)
      throws IOException, InterruptedException, TimeoutException {
    return shell.exec(ContainerExec.asDevUser(container, args));
  }

  private static String parentOf(String remotePath) {
    return remotePath.substring(0, remotePath.lastIndexOf('/'));
  }
}
