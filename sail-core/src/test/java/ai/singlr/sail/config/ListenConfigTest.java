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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  void onlyAPrivateAddressIsAcceptedAndNoShortenedOrSignedForm() {
    var accepted = new ArrayList<String>();
    var notPrivate = new ArrayList<String>();
    var unparsed = new ArrayList<String>();
    for (var value :
        List.of(
            "127.0.0.1:7071",
            "[::1]:7071",
            "10.1.2.3:7071",
            "172.16.0.1:7071",
            "172.31.255.254:7071",
            "192.168.1.1:7071",
            "100.64.0.7:7071",
            "100.127.255.254:7071",
            "[fd7a:115c:a1e0::7]:7071",
            "[fe80::1]:7071",
            "[fec0::1]:7071",
            "8.8.8.8:7071",
            "255.255.255.255:7071",
            "224.0.0.1:7071",
            "100.128.0.1:7071",
            "172.32.0.1:7071",
            "169.254.1.1:7071",
            "[2001:db8::1]:7071",
            "[ff02::1]:7071",
            "1.2.3:7071",
            "100.64.0.7:+7071",
            "0:7071",
            "0.0:7071",
            "localhost:7071",
            "100.64.0.256:7071")) {
      try {
        ListenConfig.address(ListenConfig.API, value);
        accepted.add(value);
      } catch (IllegalArgumentException refused) {
        (refused.getMessage().contains("not a private address") ? notPrivate : unparsed).add(value);
      }
    }

    assertEquals(
        List.of(
            "127.0.0.1:7071",
            "[::1]:7071",
            "10.1.2.3:7071",
            "172.16.0.1:7071",
            "172.31.255.254:7071",
            "192.168.1.1:7071",
            "100.64.0.7:7071",
            "100.127.255.254:7071",
            "[fd7a:115c:a1e0::7]:7071"),
        accepted);
    assertEquals(
        List.of(
            "[fe80::1]:7071",
            "[fec0::1]:7071",
            "8.8.8.8:7071",
            "255.255.255.255:7071",
            "224.0.0.1:7071",
            "100.128.0.1:7071",
            "172.32.0.1:7071",
            "169.254.1.1:7071",
            "[2001:db8::1]:7071",
            "[ff02::1]:7071"),
        notPrivate);
    assertEquals(
        List.of(
            "1.2.3:7071",
            "100.64.0.7:+7071",
            "0:7071",
            "0.0:7071",
            "localhost:7071",
            "100.64.0.256:7071"),
        unparsed);
    assertEquals(
        "'api-listen' is '8.8.8.8:7071', which is not a private address, and this door speaks plain"
            + " HTTP and TCP. Name the box's address on its private network: loopback, 10/8,"
            + " 172.16/12, 192.168/16, 100.64/10, or an IPv6 unique-local address.",
        assertThrows(
                IllegalArgumentException.class,
                () -> ListenConfig.address(ListenConfig.API, "8.8.8.8:7071"))
            .getMessage());
  }

  @Test
  void everyFormReviewTwoTriedIsReadAsTheRuleSays() {
    var accepted =
        List.of(
            "127.0.0.1:7071",
            "10.0.0.1:7071",
            "172.16.0.1:7071",
            "172.31.255.254:7071",
            "192.168.1.1:7071",
            "100.64.0.7:7071",
            "100.127.255.255:7071",
            "10.0.0.255:7071",
            "10.0.0.1:65535",
            "[::1]:7071",
            "[::ffff:10.0.0.1]:7071",
            "[fd7a:115c:a1e0::7]:7071",
            "[fc00::1]:7071",
            "[FD00::1]:7071");
    var refused =
        List.of(
            "172.32.0.1:7071",
            "100.128.0.1:7071",
            "100.63.255.255:7071",
            "169.254.1.1:7071",
            "8.8.8.8:7071",
            "0.0.0.0:7071",
            "255.255.255.255:7071",
            "224.0.0.1:7071",
            "010.0.0.1:7071",
            "0x0a.0.0.1:7071",
            "10.1:7071",
            "167772161:7071",
            "10.0.0.1:0",
            "10.0.0.1:65536",
            "10.0.0.1:07071",
            "10.0.0.1:+7071",
            "10.0.0.1",
            "[10.0.0.1]:7071",
            "[::]:7071",
            "[::0]:7071",
            "[::ffff:8.8.8.8]:7071",
            "[::ffff:0.0.0.0]:7071",
            "[::10.0.0.1]:7071",
            "[64:ff9b::a00:1]:7071",
            "[fe80::1]:7071",
            "[fe80::1%lo]:7071",
            "[fe80::1%1]:7071",
            "[fe80::1%nosuchif0]:7071",
            "[febf::1]:7071",
            "[fec0::1]:7071",
            "[ff02::1]:7071",
            "[2001:db8::1]:7071",
            "[2606:4700::1111]:7071",
            "[fd00::1%lo]:7071",
            "localhost:7071",
            "box.tailnet.ts.net:7071",
            " 10.0.0.1:7071",
            "10.0.0.1:7071\n",
            "10.0.0.\u0661:7071");
    var wrong = new ArrayList<String>();
    for (var value : accepted) {
      try {
        ListenConfig.address(ListenConfig.API, value);
      } catch (IllegalArgumentException no) {
        wrong.add("refused " + value);
      }
    }
    for (var value : refused) {
      try {
        ListenConfig.address(ListenConfig.API, value);
        wrong.add("accepted " + value);
      } catch (IllegalArgumentException no) {
        var unused = no;
      }
    }

    assertEquals(List.of(), wrong);
  }

  @Test
  void bothDoorsOnOneAddressAndPortAreRefusedWhenSetAndWhenRead() {
    var api = ListenConfig.none().with(ListenConfig.API, "100.64.0.7:7071");
    var both =
        "'api-listen' and 'terminal-listen' both name 100.64.0.7:7071; each door needs an address"
            + " and port of its own.";

    assertEquals(
        both,
        assertThrows(
                IllegalArgumentException.class,
                () -> api.with(ListenConfig.TERMINAL, "100.64.0.7:7071"))
            .getMessage());
    var hand = new ListenConfig("100.64.0.7:7071", "100.64.0.7:7071");
    assertEquals(both, assertThrows(IllegalArgumentException.class, hand::apiAddress).getMessage());
    assertEquals(
        both, assertThrows(IllegalArgumentException.class, hand::terminalAddress).getMessage());
    assertEquals(
        Optional.of(ListenConfig.address(ListenConfig.API, "100.64.0.7:7071")),
        new ListenConfig("100.64.0.7:7071", "0.0.0.0:7072").apiAddress(),
        "a door's own refusal is its own service's to say");
  }

  @Test
  void whatADoorSaysItIsBoundToIsReadBackUntilItSaysNoMore(@TempDir Path dir) throws Exception {
    var where = dir.resolve("api.listening");
    assertEquals(Optional.empty(), ListenConfig.said(where));

    ListenConfig.say(where, ListenConfig.address(ListenConfig.API, "100.64.0.7:7071"));
    assertEquals(Optional.of("100.64.0.7:7071"), ListenConfig.said(where));

    ListenConfig.unsay(where);
    assertEquals(Optional.empty(), ListenConfig.said(where));
    ListenConfig.unsay(where);
  }

  @Test
  void aDoorSetToNoneIsClosed() {
    var both = new ListenConfig("100.64.0.7:7071", "100.64.0.7:7072");

    assertEquals(
        new ListenConfig(null, "100.64.0.7:7072"), both.with(ListenConfig.API, ListenConfig.NONE));
    assertEquals(
        new ListenConfig("100.64.0.7:7071", null),
        both.with(ListenConfig.TERMINAL, ListenConfig.NONE));
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
