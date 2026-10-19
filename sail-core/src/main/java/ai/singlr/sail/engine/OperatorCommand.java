/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * How this process tells the person reading it to run sail on this box: with {@code sudo} when that
 * person came through {@code sudo} from an account whose own plain {@code sail} is {@link
 * RuntimeMode.Refused}, as on a box {@code sudo sail init} set up, and plain otherwise. Every
 * {@code sail} command sail prints is rendered here, so a hint runs as printed for the account that
 * reads it: {@link #sail} for the caller on this box, {@link #elsewhere} for an operator this box
 * cannot know, and {@link #client} for a thin client.
 */
public record OperatorCommand(boolean sudo) {

  /** A reader whose plain {@code sail} runs: root logged in directly, or a box's own account. */
  public static final OperatorCommand PLAIN = new OperatorCommand(false);

  /**
   * A reader who types {@code sudo}: a non-root caller refused a command that needs root, or a
   * reader this box cannot know, told the README's form (see {@link #elsewhere}).
   */
  public static final OperatorCommand SUDO = new OperatorCommand(true);

  /** The reader of this process: the {@code sudo} caller when root acts for one, else itself. */
  public static OperatorCommand here() {
    return of(
        SailPaths.isRoot(),
        System.getenv("SUDO_USER"),
        SailPaths::accountHome,
        SailPaths.SYSTEM_UNIT);
  }

  /**
   * Pure of the process: the {@code sudo} caller's own {@code sail} is decided by {@link
   * RuntimeMode#detect} from that account's home, as it would decide it were the caller to run
   * {@code sail} without {@code sudo}.
   */
  public static OperatorCommand of(
      boolean root, String sudoUser, Function<String, Path> homeOf, Path systemUnit) {
    if (!root || Strings.isBlank(sudoUser) || "root".equals(sudoUser)) {
      return PLAIN;
    }
    var sailDir = homeOf.apply(sudoUser).resolve(".sail");
    var callersOwn =
        RuntimeMode.detect(
            sailDir.resolve("host.yaml"), sailDir.resolve("config.yaml"), systemUnit, false, null);
    return new OperatorCommand(callersOwn instanceof RuntimeMode.Refused);
  }

  /** {@code sail <args>} as the reader types it. */
  public String sail(String args) {
    return typed("sail " + args);
  }

  /**
   * {@code command}, a root command on this box such as {@code ssh-keygen}, as the reader types it.
   */
  public String typed(String command) {
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
