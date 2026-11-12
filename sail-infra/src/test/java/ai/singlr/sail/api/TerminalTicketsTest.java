/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The ticket door answers the terminal host's refusal in the host's words, as a refusal, and a host
 * it cannot reach as the server's own failure, saying why; the end-to-end scenarios are {@code
 * DirectTerminalTest}'s.
 */
class TerminalTicketsTest {

  private static final InetSocketAddress TERMINAL =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 7072);
  private static final Map<String, Object> ATTACH_LOUNGE =
      Map.of("action", "attach", "session", "lounge");

  @Test
  void theHostsRefusalIsForbiddenInItsWordsAndAnUnreachableHostIsTheServersFailure() {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      var catalog = new ProjectStore(db);
      var refusing =
          new TerminalTickets(
              catalog,
              (credential, action, target) -> {
                throw Remedied.io("", Remedy.of("Session 'lounge' is not yours to attach."));
              },
              TERMINAL);
      var unreachable =
          new TerminalTickets(
              catalog,
              (credential, action, target) -> {
                throw new IOException("No session host at /run/pty.sock.");
              },
              TERMINAL);

      var refused = assertThrows(ApiException.class, () -> refusing.issue("tok", ATTACH_LOUNGE));
      var failed = assertThrows(ApiException.class, () -> unreachable.issue("tok", ATTACH_LOUNGE));

      assertEquals(
          "403 forbidden Session 'lounge' is not yours to attach.",
          refused.status() + " " + refused.error().code() + " " + refused.error().message());
      assertEquals(
          "500 internal No session host at /run/pty.sock.",
          failed.status() + " " + failed.error().code() + " " + failed.error().message());
    }
  }
}
