/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

/**
 * Resolves the human behind a catalog mutation for attribution. Under {@code sudo} the real
 * engineer is {@code SUDO_USER}, not {@code root}; otherwise it is the process owner. The
 * resolution is pure so it can be unit-tested without touching the environment.
 */
final class Actor {

  private Actor() {}

  /** The actor for the current process, read from {@code SUDO_USER} then {@code user.name}. */
  static String current() {
    return resolve(System.getenv("SUDO_USER"), System.getProperty("user.name"));
  }

  static String resolve(String sudoUser, String userName) {
    if (sudoUser != null && !sudoUser.isBlank() && !"root".equals(sudoUser)) {
      return sudoUser;
    }
    return userName == null || userName.isBlank() ? "local" : userName;
  }
}
