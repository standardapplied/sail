/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

/**
 * A resolver that asks no one: an address literal is itself, as the system's resolver reads it, a
 * name is what {@code table} says, and any other name is unknown.
 */
record KnownHosts(Map<String, String> table) implements WebhookUrlSafety.Resolver {

  /** The public webhook hosts the tests name, each at a documentation address (RFC 5737). */
  static final KnownHosts PUBLIC =
      new KnownHosts(
          Map.of(
              "ntfy.sh", "203.0.113.1",
              "hooks.slack.com", "203.0.113.2",
              "discord.com", "203.0.113.3"));

  @Override
  public InetAddress[] addresses(String host) throws UnknownHostException {
    try {
      return new InetAddress[] {InetAddress.ofLiteral(host)};
    } catch (IllegalArgumentException aName) {
      var address = table.get(host);
      if (address == null) {
        throw new UnknownHostException(host);
      }
      return new InetAddress[] {InetAddress.ofLiteral(address)};
    }
  }
}
