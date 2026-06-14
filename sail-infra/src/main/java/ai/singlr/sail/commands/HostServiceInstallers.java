/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import java.nio.file.Path;

/**
 * Construction helpers for {@link SystemdServiceInstaller}; centralizes default arguments and picks
 * the install mode based on whether the invoking process is root.
 */
final class HostServiceInstallers {

  private HostServiceInstallers() {}

  static SystemdServiceInstaller create(
      ShellExecutor shell, String bindHost, int bindPort, String username) {
    var mode =
        ConsoleHelper.isRoot()
            ? SystemdServiceInstaller.Mode.SYSTEM
            : SystemdServiceInstaller.Mode.USER;
    return new SystemdServiceInstaller(
        shell,
        mode,
        Path.of(System.getProperty("user.home")),
        SailPaths.binaryPath(),
        bindHost,
        bindPort,
        username);
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
