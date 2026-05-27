/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Resolves server connection details. Resolution order for URL: {@code --server} flag → {@code
 * SAIL_SERVER} env → {@code ~/.sail/client.yaml} → {@code http://localhost:7070}. Resolution order
 * for token: {@code --token} flag → {@code SAIL_TOKEN} env → {@code ~/.sail/client.yaml}.
 */
public record ServerConnectionConfig(String serverUrl, String token) {

  private static final String DEFAULT_URL = "http://localhost:7070";

  public static ServerConnectionConfig resolve() throws IOException {
    return resolve(null, null, SailPaths.clientConfigPath());
  }

  public static ServerConnectionConfig resolve(String serverFlag, String tokenFlag)
      throws IOException {
    return resolve(serverFlag, tokenFlag, SailPaths.clientConfigPath());
  }

  @SuppressWarnings("unchecked")
  static ServerConnectionConfig resolve(
      String serverFlag, String tokenFlag, java.nio.file.Path configPath) throws IOException {
    var url = serverFlag;
    var token = tokenFlag;

    if (url == null) url = envOrProperty("SAIL_SERVER");
    if (token == null) token = envOrProperty("SAIL_TOKEN");

    if ((url == null || token == null) && Files.exists(configPath)) {
      var config = YamlUtil.parseFile(configPath);
      if (url == null) url = (String) config.get("server");
      if (token == null) token = (String) config.get("token");
    }

    if (url == null) url = DEFAULT_URL;
    if (token == null) {
      throw new IOException("No API token configured. Set SAIL_TOKEN or run 'sail client init'.");
    }
    return new ServerConnectionConfig(url, token);
  }

  private static String envOrProperty(String name) {
    var env = System.getenv(name);
    return env != null ? env : System.getProperty(name);
  }
}
