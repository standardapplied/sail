/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.config.HostYaml;
import ai.singlr.sail.config.YamlUtil;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * The node-side half of an SSH connect target: the jump host's IP and login user (from {@code
 * host.yaml}) and whether a workstation key is registered on this box. The API's connect endpoint
 * reads this fresh per request so a re-run of {@code sail host config set server-ip} is picked up
 * without a server restart.
 */
public record ConnectEnvironment(String serverIp, String serverUser, boolean workstationKeySet) {

  /** Reads the live environment each time it is asked. */
  public static Supplier<ConnectEnvironment> detector() {
    return ConnectEnvironment::detect;
  }

  /** Detects the live environment from {@code host.yaml} and the registered workstation key. */
  private static ConnectEnvironment detect() {
    return detect(SailPaths.hostConfigPath(), WorkstationIdentity.registered().isPresent());
  }

  static ConnectEnvironment detect(Path hostYaml, boolean workstationKeySet) {
    return new ConnectEnvironment(
        serverIpFrom(hostYaml), System.getProperty("user.name"), workstationKeySet);
  }

  private static String serverIpFrom(Path hostYaml) {
    try {
      return HostYaml.fromMap(YamlUtil.parseFile(hostYaml)).serverIp();
    } catch (Exception e) {
      return null;
    }
  }
}
