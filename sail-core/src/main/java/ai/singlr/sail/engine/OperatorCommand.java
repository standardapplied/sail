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
 * {@code sail} command a box prints for its operator to type is rendered here, so a hint runs as
 * printed for the account that reads it.
 */
public record OperatorCommand(boolean sudo) {

  /** A reader whose plain {@code sail} runs: root logged in directly, or a box's own account. */
  public static final OperatorCommand PLAIN = new OperatorCommand(false);

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
  static OperatorCommand of(
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
    return (sudo ? "sudo sail " : "sail ") + args;
  }
}
