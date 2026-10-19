/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.util.List;

/**
 * How this process tells the person reading it to run sail on this box: with {@code sudo} when that
 * person came through {@code sudo}, since root's sail acts on root's state and the caller's own
 * plain {@code sail} never reaches it, and plain otherwise; a verb only root runs carries {@code
 * sudo} for everyone but root logged in directly. Every {@code sail} command sail prints is
 * rendered here, so a hint runs as printed for the account that reads it: {@link #sail} for the
 * caller on this box, {@link #elsewhere} for an operator this box cannot know, and {@link #client}
 * for a thin client.
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
   * that needs root, or a reader this box cannot know, told the README's form (see {@link
   * #elsewhere}).
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

  /**
   * The reader of a process that cannot see who reads it, such as sail-api or a watcher: as root it
   * answers the README's form, the one a {@code sudo} caller types; as another account it answers
   * that account.
   */
  public static OperatorCommand unknownReader() {
    return unknownReader(SailPaths.isRoot());
  }

  static OperatorCommand unknownReader(boolean root) {
    return root ? SUDO : UNPRIVILEGED;
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

  /**
   * {@code sail <args>} for another box's operator, whom this box cannot know: the README's form,
   * as a box {@code sudo sail init} set up runs it.
   */
  public static String elsewhere(String args) {
    return SUDO.sail(args);
  }

  /** {@code sail <args>} on a thin client, where sail never runs as root. */
  public static String client(String args) {
    return "sail " + args;
  }
}
