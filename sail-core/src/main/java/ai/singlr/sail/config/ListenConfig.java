/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.config;

import ai.singlr.sail.engine.SailPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where the box listens beyond loopback and its unix sockets, the {@code listen} block of {@code
 * host.yaml}: {@code api}, one more address for the HTTP API ({@value #API}), and {@code terminal},
 * the address the terminal host takes ticketed connections on ({@value #TERMINAL}). Each is an IP
 * address and a port on the private network the clients share; with neither, nothing more is bound.
 * An address is read when it is bound and refused, naming its setting, when it is not one, or when
 * it is a wildcard, which would listen on every network the box is on.
 */
public record ListenConfig(String api, String terminal) {

  /** The setting that names the API's private address. */
  public static final String API = "api-listen";

  /** The setting that names the terminal host's private address. */
  public static final String TERMINAL = "terminal-listen";

  public static ListenConfig none() {
    return new ListenConfig(null, null);
  }

  /** The block of this box's {@code host.yaml}; none when the file or the block is absent. */
  public static ListenConfig load() {
    var path = SailPaths.hostConfigPath();
    if (!Files.exists(path)) {
      return none();
    }
    try {
      return HostYaml.fromMap(YamlUtil.parseFile(path)).listen();
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the listen addresses from " + path, e);
    }
  }

  /** The API's private address, when one is named. */
  public Optional<InetSocketAddress> apiAddress() {
    return Optional.ofNullable(api).map(value -> address(API, value));
  }

  /** The terminal host's private address, when one is named. */
  public Optional<InetSocketAddress> terminalAddress() {
    return Optional.ofNullable(terminal).map(value -> address(TERMINAL, value));
  }

  /** This block with {@code setting} set to {@code value}. */
  public ListenConfig with(String setting, String value) {
    address(setting, value);
    return API.equals(setting) ? new ListenConfig(value, terminal) : new ListenConfig(api, value);
  }

  /**
   * The address {@code value} of {@code setting} names: an IPv4 address or a bracketed IPv6 one, a
   * colon and a port, with no name to look up. Refused, naming the setting, when it is anything
   * else or a wildcard.
   */
  public static InetSocketAddress address(String setting, String value) {
    var colon = value.lastIndexOf(':');
    var host = colon < 0 ? "" : value.substring(0, colon);
    var bracketed = host.startsWith("[") && host.endsWith("]");
    if (bracketed) {
      host = host.substring(1, host.length() - 1);
    }
    InetAddress ip;
    int port;
    try {
      if (host.contains(":") != bracketed) {
        throw new IllegalArgumentException(value);
      }
      ip = InetAddress.ofLiteral(host);
      port = Integer.parseInt(value.substring(colon + 1));
    } catch (IllegalArgumentException unparsed) {
      throw new IllegalArgumentException(
          "'"
              + setting
              + "' is '"
              + value
              + "', which is not an address and a port. Name the box's address on the private"
              + " network, e.g. 100.64.0.7:7071 or [fd7a:115c:a1e0::7]:7071.");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(
          "'" + setting + "' names port " + port + "; a port is 1 to 65535.");
    }
    if (ip.isAnyLocalAddress()) {
      throw new IllegalArgumentException(
          "'"
              + setting
              + "' is '"
              + value
              + "', a wildcard, which would listen on every network the box is on. Name the box's"
              + " address on the private network, e.g. 100.64.0.7:7071.");
    }
    return new InetSocketAddress(ip, port);
  }

  /** {@code address} as a client dials it: {@code 100.64.0.7:7072}, or {@code [fd7a::7]:7072}. */
  public static String hostPort(InetSocketAddress address) {
    var host = address.getAddress().getHostAddress();
    return (host.contains(":") ? "[" + host + "]" : host) + ":" + address.getPort();
  }

  public static ListenConfig fromMap(Map<String, Object> map) {
    if (map == null) {
      return none();
    }
    for (var key : map.keySet()) {
      if (!"api".equals(key) && !"terminal".equals(key)) {
        throw new IllegalArgumentException(
            "listen." + key + " is not a listen setting; use api or terminal");
      }
    }
    return new ListenConfig(text(map.get("api")), text(map.get("terminal")));
  }

  public boolean isEmpty() {
    return api == null && terminal == null;
  }

  public Map<String, Object> toMap() {
    var map = new LinkedHashMap<String, Object>();
    if (api != null) map.put("api", api);
    if (terminal != null) map.put("terminal", terminal);
    return map;
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }
}
