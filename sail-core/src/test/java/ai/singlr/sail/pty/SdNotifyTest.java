/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.pty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class SdNotifyTest {

  @TempDir Path dir;

  @Test
  void aStoredDescriptorArrivesAsARealDuplicateAndRemovalRoundTripsItsName() throws Exception {
    try (var receiver = SdNotify.Receiver.bind(dir.resolve("notify.sock"));
        var pty = Pty.open(80, 24)) {
      var store = SdNotify.to(receiver.path().toString());
      assertTrue(store.enabled());

      store.storeFd("s1", pty.fd());
      var stored = receiver.receive();
      assertEquals("FDSTORE=1\nFDPOLL=0\nFDNAME=s1\n", stored.text());
      assertEquals("1", stored.field("FDSTORE"));
      assertEquals("0", stored.field("FDPOLL"), "a hangup in the restart gap must not drop it");
      assertEquals("s1", stored.field("FDNAME"));
      assertEquals("", stored.field("FDSTOREREMOVE"), "an absent field is blank");
      assertEquals(1, stored.fds().size(), "one SCM_RIGHTS descriptor rides along");
      var copy = stored.fds().getFirst();
      assertTrue(copy != pty.fd(), "the receiver gets its own descriptor, not the number");

      pty.spawn(List.of("sh", "-c", "echo via-copy"), Map.of("TERM", "dumb"), dir).waitFor();
      try (var adopted = Pty.adopt(copy)) {
        var buf = new byte[256];
        var seen = new StringBuilder();
        for (var n = adopted.read(buf); n > 0 && !seen.toString().contains("via-copy"); ) {
          seen.append(new String(buf, 0, n, StandardCharsets.UTF_8));
          n = adopted.read(buf);
        }
        assertTrue(seen.toString().contains("via-copy"), "bytes cross the duplicate: " + seen);
      }

      store.removeFd("s1");
      var removed = receiver.receive();
      assertEquals("FDSTOREREMOVE=1\nFDNAME=s1\n", removed.text());
      assertTrue(removed.fds().isEmpty(), "a removal carries no descriptor");
    }
  }

  @Test
  void withoutANotifySocketTheStoreIsANoOpThatNeverThrows() throws Exception {
    assertFalse(SdNotify.NONE.enabled());
    assertFalse(SdNotify.to("").enabled());
    assertFalse(SdNotify.to("   ").enabled());
    try (var pty = Pty.open(80, 24)) {
      SdNotify.NONE.storeFd("s1", pty.fd());
      SdNotify.NONE.removeFd("s1");
    }
  }

  @Test
  void aNotifySocketThatPointsNowhereWarnsOnceAndTheCallerProceeds() throws Exception {
    var stderr = new ByteArrayOutputStream();
    var original = System.err;
    System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
    try (var pty = Pty.open(80, 24)) {
      var store = SdNotify.to(dir.resolve("nobody-listens.sock").toString());
      store.storeFd("s1", pty.fd());
      store.storeFd("s2", pty.fd());
      store.removeFd("s1");
    } finally {
      System.setErr(original);
    }
    var warnings = stderr.toString(StandardCharsets.UTF_8);
    assertEquals(1, warnings.split("\n").length, "exactly one warning: " + warnings);
    assertTrue(warnings.contains("nobody-listens.sock"), warnings);
    assertTrue(warnings.contains("will not survive"), warnings);
  }

  @Test
  void listenFdsAreNamedFromThreeUpAndOnlyWhenAddressedToThisPid() {
    var env = Map.of("LISTEN_PID", "42", "LISTEN_FDS", "3", "LISTEN_FDNAMES", "s1::s3");

    assertEquals(Map.of("s1", 3, "fd4", 4, "s3", 5), SdNotify.listenFds(env, 42));
    assertTrue(SdNotify.listenFds(env, 43).isEmpty(), "another pid's inheritance is not ours");
    assertTrue(SdNotify.listenFds(Map.of(), 42).isEmpty());
    assertTrue(
        SdNotify.listenFds(Map.of("LISTEN_PID", "42", "LISTEN_FDS", "many"), 42).isEmpty(),
        "a garbled count inherits nothing rather than guessing");
  }
}
