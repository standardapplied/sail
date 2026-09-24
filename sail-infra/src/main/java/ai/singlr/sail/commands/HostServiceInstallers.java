/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Construction helpers for {@link SystemdServiceInstaller}; centralizes default arguments and picks
 * the install mode based on whether the invoking process is root.
 */
final class HostServiceInstallers {

  private HostServiceInstallers() {}

  /**
   * The sail-api unit on this box. The unit runs the installed binary, asked for only when the unit
   * is rendered — an install or an upgrade's reconcile — so start, stop, status, logs and uninstall
   * never need one, and a box whose binary is gone can still be managed and cleaned up.
   */
  static SystemdServiceInstaller create(
      ShellExec shell, String bindHost, int bindPort, String username) {
    return new SystemdServiceInstaller(
        shell, mode(), userHome(), SailPaths::installedBinary, bindHost, bindPort, username);
  }

  /** The sail-api unit on this box, at the default endpoint, for everything but writing it. */
  static SystemdServiceInstaller create(ShellExec shell) {
    return create(shell, mode(), userHome(), SailPaths::installedBinary);
  }

  static SystemdServiceInstaller create(
      ShellExec shell,
      SystemdServiceInstaller.Mode mode,
      Path userHome,
      Supplier<Path> installedBinary) {
    return new SystemdServiceInstaller(
        shell, mode, userHome, installedBinary, "127.0.0.1", 7070, currentUsername());
  }

  static SystemdServiceInstaller.Mode mode() {
    return ConsoleHelper.isRoot()
        ? SystemdServiceInstaller.Mode.SYSTEM
        : SystemdServiceInstaller.Mode.USER;
  }

  static Path userHome() {
    return Path.of(System.getProperty("user.home"));
  }

  static String currentUsername() {
    var name = System.getProperty("user.name");
    if (Strings.isBlank(name)) {
      throw new IllegalStateException(
          "Could not determine current username (user.name system property is empty).");
    }
    return name;
  }
}
