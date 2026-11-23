/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class KnownHostsTest {

  /** A test JVM that could reach DNS again would resolve this; losing the cut is noticed here. */
  @Test
  void aNameNoTestMayResolveIsUnknown() {
    assertThrows(UnknownHostException.class, () -> InetAddress.getByName("example.com"));
  }

  @Test
  void aKnownNameIsWhatTheTableSays() throws Exception {
    assertEquals("203.0.113.1", InetAddress.getByName("ntfy.sh").getHostAddress());
    assertEquals("169.254.169.254", InetAddress.getByName(KnownHosts.REBOUND).getHostAddress());
  }

  @Test
  void localhostIsTheLoopbackAndOnlyTheLoopbackHasAName() throws Exception {
    for (var address : InetAddress.getAllByName("localhost")) {
      assertTrue(address.isLoopbackAddress(), address.toString());
    }
    assertEquals("localhost", InetAddress.getByName("127.0.0.1").getHostName());
    assertEquals("203.0.113.9", InetAddress.getByName("203.0.113.9").getHostName());
  }
}
