/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.gen.AgentContextGenerator;
import ai.singlr.sail.gen.GeneratedFile;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Generates a project's agent context from its {@link SailYaml} and installs it into the running
 * container: the sail-owned home context file per agent ({@code ~/.claude/CLAUDE.md} / {@code
 * ~/.codex/AGENTS.md}), the methodology and spec-board skills, and the audit/guardrail hooks. Every
 * generated file is sail-owned and overwritten on every run; sail never writes into the engineer's
 * workspace, so there is nothing to preserve and no ownership to weigh.
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

  /** The remote paths an install overwrote. */
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
   * Regenerates and installs the sail-owned agent context for {@code config} into {@code
   * container}, overwriting every generated file, and returns the remote paths written. Returns
   * {@link Result#none()} when no agent is configured. Sail only ever writes its own home
   * namespace, so the install is an unconditional overwrite — nothing in the engineer's workspace
   * is read or touched.
   */
  public static Result install(ShellExec shell, String container, SailYaml config)
      throws IOException, InterruptedException, TimeoutException {
    Objects.requireNonNull(shell, "shell");
    NameValidator.requireValidProjectName(container);
    Objects.requireNonNull(config, "config");

    var files = new ArrayList<>(AgentContextGenerator.generateFiles(config));
    if (files.isEmpty()) {
      return Result.none();
    }

    var pushed = new ArrayList<String>();
    for (var file : files) {
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

  private static ShellExec.Result exec(ShellExec shell, String container, List<String> args)
      throws IOException, InterruptedException, TimeoutException {
    return shell.exec(ContainerExec.asDevUser(container, args));
  }

  private static String parentOf(String remotePath) {
    return remotePath.substring(0, remotePath.lastIndexOf('/'));
  }
}
