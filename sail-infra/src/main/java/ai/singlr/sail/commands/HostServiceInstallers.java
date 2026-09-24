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

/**
 * Construction helpers for {@link SystemdServiceInstaller}; centralizes default arguments and picks
 * the install mode based on whether the invoking process is root.
 */
final class HostServiceInstallers {

  private HostServiceInstallers() {}

  /**
   * An installer that writes the sail-api unit — an install, or an upgrade's reconcile. The unit
   * runs the installed binary, so one must be installed.
   */
  static SystemdServiceInstaller create(
      ShellExec shell, String bindHost, int bindPort, String username) {
    return new SystemdServiceInstaller(
        shell, mode(), userHome(), SailPaths.installedBinary(), bindHost, bindPort, username);
  }

  /**
   * An installer for the sail-api unit already on this box: start, stop, restart, status, logs,
   * uninstall. None of them runs sail, so a missing or broken installed binary cannot keep a box
   * from managing, or cleaning up, what is left of it.
   */
  static SystemdServiceInstaller existing(ShellExec shell) {
    return existing(shell, mode(), userHome());
  }

  static SystemdServiceInstaller existing(
      ShellExec shell, SystemdServiceInstaller.Mode mode, Path userHome) {
    return new SystemdServiceInstaller(
        shell, mode, userHome, SailPaths.INSTALLED_BINARY, "127.0.0.1", 7070, currentUsername());
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
