/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.config.ClientConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Determines whether the CLI is running on the host server (with Incus) or on an engineer's client
 * machine (Mac). Host mode executes commands locally; client mode forwards commands to the host via
 * SSH.
 */
public sealed interface RuntimeMode {

  /** Running on the host server — commands execute locally via {@code incus exec}. */
  record Host() implements RuntimeMode {}

  /** Running on a client machine — commands are forwarded to the host via SSH. */
  record Client(ClientConfig config) implements RuntimeMode {}

  /**
   * Running as an account that does not own this box: sail-api is root's system service, and this
   * account sees no host config, so its own {@code ~/.sail} would silently diverge from root's.
   */
  record Refused() implements RuntimeMode {}

  /**
   * Detects the runtime mode. Host if a host config is visible, client if {@code
   * ~/.sail/config.yaml} loads, refused when the box's sail-api is root's system unit and this
   * non-root account names no data directory of its own, host otherwise. The no-config host default
   * is deliberate first-run UX: host initialization must work before either config file exists.
   */
  static RuntimeMode detect() {
    return detect(
        SailPaths.hostConfigPath(),
        SailPaths.clientConfigPath(),
        SailPaths.SYSTEM_UNIT,
        SailPaths.isRoot(),
        System.getenv("SAIL_DATA_DIR"));
  }

  /** Pure of the process and its environment, so every outcome is exercised directly. */
  static RuntimeMode detect(
      Path hostConfigPath,
      Path clientConfigPath,
      Path systemUnit,
      boolean root,
      String dataDirEnv) {
    if (Files.exists(hostConfigPath)) {
      return new Host();
    }
    var client = clientConfig(clientConfigPath);
    if (client.isPresent()) {
      return new Client(client.get());
    }
    if (Files.exists(systemUnit) && !root && Strings.isBlank(dataDirEnv)) {
      return new Refused();
    }
    return new Host();
  }

  private static Optional<ClientConfig> clientConfig(Path path) {
    if (!Files.exists(path)) {
      return Optional.empty();
    }
    try {
      return Optional.of(ClientConfig.load(path));
    } catch (Exception notAClientConfig) {
      return Optional.empty();
    }
  }
}
