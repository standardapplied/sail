/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Model for {@code ~/.sail/config.yaml} — client-side configuration on the engineer's Mac that
 * specifies how to reach the remote host server. When this file exists and the host config is
 * absent, the CLI operates in client mode: forwarding commands to the host via SSH.
 *
 * <p>Only {@code host} is required — it can be an IP, hostname, or an SSH config alias (e.g. {@code
 * kubera-server}). SSH resolves the user, key, and proxy settings from {@code ~/.ssh/config}.
 *
 * <p>{@code user} is the host account that turns the engineer's SSH key into their FDE identity
 * (the forced-command gateway). It defaults to {@code sail}; commands the gateway accepts are
 * forwarded as {@code sail@host}, everything else as plain {@code host}. Set it to an empty string
 * to disable the gateway lane and forward everything as the SSH default user.
 *
 * @param host the remote host — IP, hostname, or SSH config alias
 * @param user the gateway account FDE commands are forwarded as, or blank to disable
 */
public record ClientConfig(String host, String user) {

  public static final String GATEWAY_USER = "sail";

  public ClientConfig(String host) {
    this(host, GATEWAY_USER);
  }

  public static ClientConfig fromMap(Map<String, Object> map) {
    var host = (String) map.get("host");
    if (Strings.isBlank(host)) {
      throw new IllegalArgumentException(
          "client config 'host' is required."
              + "\n  Set the host in ~/.sail/config.yaml: host: <server-ip-or-ssh-alias>");
    }
    var user = Objects.requireNonNullElse((String) map.get("user"), GATEWAY_USER);
    return new ClientConfig(host, user);
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    map.put("host", host);
    map.put("user", user);
    return map;
  }

  /** Returns true when FDE-gateway forwarding is configured (a non-blank gateway user). */
  public boolean gatewayEnabled() {
    return Strings.isNotBlank(user);
  }

  /** The SSH destination for gateway-forwarded commands, e.g. {@code sail@devbox}. */
  public String gatewayTarget() {
    return user + "@" + host;
  }

  /** Returns true if the client config file exists at {@code ~/.sail/config.yaml}. */
  public static boolean exists() {
    return Files.exists(SailPaths.clientConfigPath());
  }

  /** Loads the client config from {@code ~/.sail/config.yaml}. */
  public static ClientConfig load() throws IOException {
    var path = SailPaths.clientConfigPath();
    if (!Files.exists(path)) {
      throw new IOException(
          "Client config not found: "
              + path
              + "\n  Create it with: sail init <server-ip-or-ssh-alias>");
    }
    return fromMap(YamlUtil.parseFile(path));
  }

  /** Loads the client config from a specific path (for testing). */
  public static ClientConfig load(Path path) throws IOException {
    return fromMap(YamlUtil.parseFile(path));
  }
}
