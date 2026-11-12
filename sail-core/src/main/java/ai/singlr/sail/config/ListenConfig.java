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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where the box listens beyond loopback and its unix sockets, the {@code listen} block of {@code
 * host.yaml}: {@code api}, one more address for the HTTP API ({@value #API}), and {@code terminal},
 * the address the terminal host takes ticketed connections on ({@value #TERMINAL}). Each is an IP
 * address and a port on the private network the clients share; with neither, nothing more is bound.
 * An address is read when it is bound and refused, naming its setting, when it is not one or not a
 * private one ({@link #address}). A setting set to {@value #NONE} names none.
 */
public record ListenConfig(String api, String terminal) {

  /** What a setting is set to, to name no address: the door it names is closed. */
  public static final String NONE = "none";

  private static final Pattern FORM =
      Pattern.compile(
          "(?:((?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3})"
              + "|\\[([0-9A-Fa-f:.]+)])"
              + ":(0|[1-9]\\d{0,4})");

  /** The setting that names the API's private address. */
  public static final String API = "api-listen";

  /** The setting that names the terminal host's private address. */
  public static final String TERMINAL = "terminal-listen";

  public static ListenConfig none() {
    return new ListenConfig(null, null);
  }

  /**
   * The block of this box's {@code host.yaml}, read alone, so no other block can stop a service
   * that reads only this one; none when the file or the block is absent.
   */
  @SuppressWarnings("unchecked")
  public static ListenConfig load() {
    var path = SailPaths.hostConfigPath();
    if (!Files.exists(path)) {
      return none();
    }
    try {
      return fromMap((Map<String, Object>) YamlUtil.parseFile(path).get("listen"));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the listen addresses from " + path, e);
    }
  }

  /** The API's private address, when one is named; refused when it is the terminal's too. */
  public Optional<InetSocketAddress> apiAddress() {
    requireDistinct();
    return Optional.ofNullable(api).map(value -> address(API, value));
  }

  /** The terminal host's private address, when one is named; refused when it is the API's too. */
  public Optional<InetSocketAddress> terminalAddress() {
    requireDistinct();
    return Optional.ofNullable(terminal).map(value -> address(TERMINAL, value));
  }

  /**
   * Refuses the two doors on one address and port, where one would bind and the other wait on it
   * for good. A setting that names no address of its own is its own door's to refuse.
   */
  private void requireDistinct() {
    if (api == null || terminal == null) {
      return;
    }
    InetSocketAddress apiAt;
    InetSocketAddress terminalAt;
    try {
      apiAt = address(API, api);
      terminalAt = address(TERMINAL, terminal);
    } catch (IllegalArgumentException eachItsOwn) {
      return;
    }
    if (apiAt.equals(terminalAt)) {
      throw new IllegalArgumentException(
          "'"
              + API
              + "' and '"
              + TERMINAL
              + "' both name "
              + hostPort(apiAt)
              + "; each door needs an address and port of its own.");
    }
  }

  /** This block with {@code setting} set to {@code value}, or unset when it is {@link #NONE}. */
  public ListenConfig with(String setting, String value) {
    var named = NONE.equals(value) ? null : value;
    if (named != null) {
      address(setting, named);
    }
    var changed =
        API.equals(setting) ? new ListenConfig(named, terminal) : new ListenConfig(api, named);
    changed.requireDistinct();
    return changed;
  }

  /**
   * The address {@code value} of {@code setting} names: four decimal octets, or an IPv6 address in
   * brackets, a colon and a port, with no name to look up and no shortened form. It must be a
   * private address, since these doors speak plain HTTP and TCP and the private network is their
   * encryption: loopback, RFC 1918, 100.64.0.0/10 (Tailscale's), or an IPv6 unique-local address.
   * Anything else, a wildcard, link-local, multicast or broadcast address or a port with a leading
   * zero included, is refused naming the setting.
   */
  public static InetSocketAddress address(String setting, String value) {
    var form = FORM.matcher(value);
    InetAddress ip;
    int port;
    try {
      if (!form.matches()) {
        throw new IllegalArgumentException(value);
      }
      var host = form.group(1) != null ? form.group(1) : form.group(2);
      if (form.group(2) != null && !host.contains(":")) {
        throw new IllegalArgumentException(value);
      }
      ip = InetAddress.ofLiteral(host);
      port = Integer.parseInt(form.group(3));
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
    if (!isPrivate(ip)) {
      throw new IllegalArgumentException(
          "'"
              + setting
              + "' is '"
              + value
              + "', which is not a private address, and this door speaks plain HTTP and TCP. Name"
              + " the box's address on its private network: loopback, 10/8, 172.16/12, 192.168/16,"
              + " 100.64/10, or an IPv6 unique-local address.");
    }
    return new InetSocketAddress(ip, port);
  }

  /** Whether {@code ip} lies on a private network: one a door may serve in the clear. */
  private static boolean isPrivate(InetAddress ip) {
    var bytes = ip.getAddress();
    var first = bytes[0] & 0xff;
    var second = bytes[1] & 0xff;
    return ip.isLoopbackAddress()
        || bytes.length == 4
            && (first == 10
                || first == 172 && (second & 0xf0) == 16
                || first == 192 && second == 168
                || first == 100 && (second & 0xc0) == 64)
        || bytes.length == 16 && (first & 0xfe) == 0xfc;
  }

  /**
   * Says in {@code where} that a running service's door is bound to {@code bound}: what {@code sail
   * host status} reports, rather than what the settings say, which a restart has yet to apply.
   */
  public static void say(Path where, InetSocketAddress bound) throws IOException {
    Files.writeString(where, hostPort(bound));
  }

  /** Says no more in {@code where} that a door is bound: it is not, or its service ended. */
  public static void unsay(Path where) {
    try {
      Files.deleteIfExists(where);
    } catch (IOException unremoved) {
      System.err.println("Could not remove " + where + ": " + unremoved.getMessage());
    }
  }

  /** What {@code where} says a running door is bound to, when it says anything. */
  public static Optional<String> said(Path where) {
    try {
      return Files.isRegularFile(where)
          ? Optional.of(Files.readString(where).strip())
          : Optional.empty();
    } catch (IOException unread) {
      return Optional.empty();
    }
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
