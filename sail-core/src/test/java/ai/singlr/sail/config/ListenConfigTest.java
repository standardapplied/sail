/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The listen block names IP addresses and ports on the private network, never a wildcard nor a name
 * to look up, and is refused naming its setting otherwise; with neither address set it is absent
 * from {@code host.yaml}, and the box binds nothing more.
 */
class ListenConfigTest {

  @Test
  void anIpv4OrBracketedIpv6AddressAndAPortIsTheAddressBoundAndDialled() {
    var v4 = ListenConfig.address(ListenConfig.API, "100.64.0.7:7071");
    var v6 = ListenConfig.address(ListenConfig.TERMINAL, "[fd7a:115c:a1e0::7]:7072");

    assertEquals(new InetSocketAddress(InetAddress.ofLiteral("100.64.0.7"), 7071), v4);
    assertEquals("100.64.0.7:7071", ListenConfig.hostPort(v4));
    assertEquals("[fd7a:115c:a1e0:0:0:0:0:7]:7072", ListenConfig.hostPort(v6));
  }

  @Test
  void anythingElseIsRefusedNamingItsSetting() {
    var unparsed =
        "', which is not an address and a port. Name the box's address on the private network,"
            + " e.g. 100.64.0.7:7071 or [fd7a:115c:a1e0::7]:7071.";
    for (var value :
        new String[] {
          "100.64.0.7", "fd7a::7:7071", "[100.64.0.7]:7071", "sail.local:7071", ":7071", "1.2.3.4:x"
        }) {
      assertEquals(
          "'terminal-listen' is '" + value + unparsed,
          assertThrows(
                  IllegalArgumentException.class,
                  () -> ListenConfig.address(ListenConfig.TERMINAL, value))
              .getMessage());
    }
    assertEquals(
        "'api-listen' names port 70000; a port is 1 to 65535.",
        assertThrows(
                IllegalArgumentException.class,
                () -> ListenConfig.address(ListenConfig.API, "100.64.0.7:70000"))
            .getMessage());
    assertEquals(
        "'api-listen' names port 0; a port is 1 to 65535.",
        assertThrows(
                IllegalArgumentException.class,
                () -> ListenConfig.address(ListenConfig.API, "100.64.0.7:0"))
            .getMessage());
  }

  @Test
  void theBlockRoundTripsHostYamlAndIsAbsentWhenEmpty() {
    var both = ListenConfig.none().with(ListenConfig.API, "100.64.0.7:7071");
    both = both.with(ListenConfig.TERMINAL, "100.64.0.7:7072");

    assertEquals(new ListenConfig("100.64.0.7:7071", "100.64.0.7:7072"), both);
    assertEquals(both, ListenConfig.fromMap(both.toMap()));
    assertEquals(
        Map.of("api", "100.64.0.7:7071"), new ListenConfig("100.64.0.7:7071", null).toMap());
    assertTrue(ListenConfig.none().isEmpty());
    assertEquals(Optional.empty(), ListenConfig.none().apiAddress());
    assertEquals(Optional.empty(), ListenConfig.none().terminalAddress());
    assertEquals(
        Optional.of(ListenConfig.address(ListenConfig.TERMINAL, "100.64.0.7:7072")),
        both.terminalAddress());
    var host = new HostYaml("zfs", "p", "d", "b", "prof", "img", "6", "10.0.0.1", "t");
    assertEquals(
        both, HostYaml.fromMap(host.withListen(both).toMap()).listen(), "kept through host.yaml");
    assertFalse(host.toMap().containsKey("listen"));
    assertEquals(
        "listen.web is not a listen setting; use api or terminal",
        assertThrows(
                IllegalArgumentException.class,
                () -> ListenConfig.fromMap(Map.of("web", "100.64.0.7:80")))
            .getMessage());
    assertThrows(
        IllegalArgumentException.class,
        () -> ListenConfig.none().with(ListenConfig.API, "0.0.0.0:7071"));
  }
}
