/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import ai.singlr.sail.common.Strings;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Read-only facts about the machine sail is running on. */
public final class HostInfo {

  private HostInfo() {}

  /**
   * Returns the local machine hostname. Falls back to the {@code HOSTNAME} environment variable,
   * then to {@code "unknown"} when {@link InetAddress#getLocalHost()} is unavailable.
   */
  public static String hostname() {
    try {
      var name = InetAddress.getLocalHost().getHostName();
      if (Strings.isNotBlank(name)) {
        return name;
      }
    } catch (UnknownHostException ignored) {
      // fall through to env/unknown
    }
    var envName = System.getenv("HOSTNAME");
    if (Strings.isNotBlank(envName)) {
      return envName;
    }
    return "unknown";
  }
}
