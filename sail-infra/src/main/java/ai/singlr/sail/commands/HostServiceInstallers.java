/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExecutor;
import ai.singlr.sail.engine.SystemdServiceInstaller;
import java.nio.file.Path;

/** Construction helpers for {@link SystemdServiceInstaller}; centralizes default arguments. */
final class HostServiceInstallers {

  private HostServiceInstallers() {}

  static SystemdServiceInstaller create(
      ShellExecutor shell, String bindHost, int bindPort, String username) {
    return new SystemdServiceInstaller(
        shell,
        Path.of(System.getProperty("user.home")),
        SailPaths.binaryPath(),
        bindHost,
        bindPort,
        username);
  }

  static String currentUsername() {
    var name = System.getProperty("user.name");
    if (name == null || name.isBlank()) {
      throw new IllegalStateException(
          "Could not determine current username (user.name system property is empty).");
    }
    return name;
  }
}
