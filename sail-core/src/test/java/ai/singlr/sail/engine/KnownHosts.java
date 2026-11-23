/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The only resolver a test JVM has: a name resolves to what {@link #TABLE} says and any other name
 * is unknown, so no test asks DNS and none rests on what a resolver answers. Registered for every
 * test JVM of every module, however it is started, through the JDK's resolver service, which
 * sail-core's test resources name and its test-jar carries to the others; production code keeps the
 * system's resolver. An address literal never reaches a resolver; an address is named by no one but
 * the loopback.
 */
public final class KnownHosts extends InetAddressResolverProvider {

  /** The rebound name: public-looking, resolving to the cloud metadata address. */
  public static final String REBOUND = "hooks.rebound.example";

  /**
   * Every name a test may resolve: the loopback, the public webhook hosts tests name, each at a
   * documentation address (RFC 5737), and {@link #REBOUND}.
   */
  private static final Map<String, List<String>> TABLE =
      Map.of(
          "localhost",
          List.of("127.0.0.1", "::1"),
          "ntfy.sh",
          List.of("203.0.113.1"),
          "hooks.slack.com",
          List.of("203.0.113.2"),
          "discord.com",
          List.of("203.0.113.3"),
          REBOUND,
          List.of("169.254.169.254"));

  @Override
  public InetAddressResolver get(Configuration configuration) {
    return new InetAddressResolver() {
      @Override
      public Stream<InetAddress> lookupByName(String host, LookupPolicy policy)
          throws UnknownHostException {
        var addresses = TABLE.get(host.toLowerCase());
        if (addresses == null) {
          throw new UnknownHostException(host + " is not a name a test may resolve");
        }
        var allowed =
            addresses.stream()
                .map(InetAddress::ofLiteral)
                .filter(at -> admits(policy, at))
                .toList();
        if (allowed.isEmpty()) {
          throw new UnknownHostException(host + " has no address of the family asked for");
        }
        return allowed.stream();
      }

      @Override
      public String lookupByAddress(byte[] address) throws UnknownHostException {
        if (InetAddress.getByAddress(address).isLoopbackAddress()) {
          return "localhost";
        }
        throw new UnknownHostException("no test names an address but the loopback");
      }
    };
  }

  @Override
  public String name() {
    return "the tests' known hosts";
  }

  private static boolean admits(InetAddressResolver.LookupPolicy policy, InetAddress address) {
    var family =
        address.getAddress().length == 4
            ? InetAddressResolver.LookupPolicy.IPV4
            : InetAddressResolver.LookupPolicy.IPV6;
    return (policy.characteristics() & family) != 0;
  }
}
