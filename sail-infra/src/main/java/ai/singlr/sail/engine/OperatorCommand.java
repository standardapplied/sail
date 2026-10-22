/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.common.Strings;
import java.util.List;
import picocli.CommandLine.Help.Ansi;

/**
 * How this process tells the person reading it to run sail on this box: with {@code sudo} when that
 * person came through {@code sudo}, since root's sail acts on root's state and the caller's own
 * plain {@code sail} never reaches it, and plain otherwise; a verb only root runs carries {@code
 * sudo} for everyone but root logged in directly. A command the CLI prints is rendered here, so a
 * hint runs as printed for the account that reads it: {@link #sail} for the caller on this box and
 * {@link #client} for a thin client. A {@link Remedy} from code that cannot see its reader (the
 * stores, the sync lane, sail-api) is rendered here too, at the door that prints it ({@link
 * #render}); that code never holds an operator.
 */
public record OperatorCommand(boolean sudo, boolean rootLogin) {

  /**
   * The verbs that refuse a caller who is not root, so every reader but root logged in directly
   * types them with {@code sudo}, whatever this box has installed so far.
   */
  public static final List<String> ROOT_ONLY =
      List.of(
          "init",
          "host init",
          "host keys sync",
          "host ssh-identity",
          "host status",
          "host update",
          "host config set",
          "host sync",
          "project apply",
          "project destroy",
          "project rename",
          "project resources set",
          "project demo");

  /** Root logged in directly: every command runs as typed. */
  public static final OperatorCommand PLAIN = new OperatorCommand(false, true);

  /**
   * A reader who types {@code sudo}: the caller root acts for, a non-root caller refused a command
   * that needs root.
   */
  public static final OperatorCommand SUDO = new OperatorCommand(true, false);

  /** A non-root process: its account's own plain {@code sail} runs, and only root's take sudo. */
  public static final OperatorCommand UNPRIVILEGED = new OperatorCommand(false, false);

  public OperatorCommand {
    if (rootLogin && sudo) {
      throw new IllegalArgumentException("Root logged in directly never types sudo.");
    }
  }

  /** The reader of this process: the {@code sudo} caller when root acts for one, else itself. */
  public static OperatorCommand here() {
    return of(SailPaths.isRoot(), System.getenv("SUDO_USER"));
  }

  /** Pure of the process: root acting for a {@code sudo} caller tells that caller to type sudo. */
  public static OperatorCommand of(boolean root, String sudoUser) {
    if (!root) {
      return UNPRIVILEGED;
    }
    return Strings.isBlank(sudoUser) || "root".equals(sudoUser) ? PLAIN : SUDO;
  }

  /**
   * {@code sail <args>} as the reader types it, with {@code sudo} for a {@link #ROOT_ONLY} verb.
   */
  public String sail(String args) {
    return ROOT_ONLY.stream().anyMatch(verb -> args.equals(verb) || args.startsWith(verb + " "))
        ? root(args)
        : (sudo ? "sudo " : "") + "sail " + args;
  }

  /**
   * {@code sail <args>} run as root, as the reader types it: a command whose privilege the caller
   * decides, such as {@code host service install} for a system service.
   */
  public String root(String args) {
    return (rootLogin ? "" : "sudo ") + "sail " + args;
  }

  /**
   * {@code command} on this process's own files, such as {@code ssh-keygen -R} on its {@code
   * known_hosts}, as the reader types it: with {@code sudo} when the reader reached this process
   * through it.
   */
  public String asThisProcess(String command) {
    return (sudo ? "sudo " : "") + command;
  }

  /** {@code sail <args>} on a thin client, where sail never runs as root. */
  public static String client(String args) {
    return "sail " + args;
  }

  /** {@code remedy} as this reader reads it, each command rendered for them. */
  public String render(Remedy remedy) {
    return remedy.render(this::command);
  }

  /** {@code failure}'s reason and remedy as one line this reader reads. */
  public String render(Remedied failure) {
    return failure.render(this::command);
  }

  /**
   * Prints {@code notice}, a round's warning, to this process's terminal as this reader reads it.
   */
  public void warn(Remedy notice) {
    System.err.println(Banner.warnLine(render(notice), Ansi.AUTO));
  }

  private String command(Remedy.Command command) {
    return switch (command.runs()) {
      case SAIL -> sail(command.line());
      case CLIENT -> client(command.line());
      case PROGRAM -> asThisProcess(command.line());
    };
  }
}
