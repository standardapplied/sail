/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.auth.EnrollmentTickets;
import ai.singlr.sail.auth.Passkeys;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.store.WebauthnCredentialStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class FdeCommandTest {

  private static WebauthnCredentialStore.Credential credential(String id, String label) {
    return new WebauthnCredentialStore.Credential(
        id.getBytes(StandardCharsets.UTF_8),
        "fde-1",
        new byte[] {1},
        -7,
        0,
        null,
        false,
        false,
        label,
        "2026-07-06T10:15:30Z",
        null);
  }

  @Test
  void rosterEditOnNodeMessageNamesTheActionAndPointsAtMain() {
    var message = FdeCommand.rosterEditOnNodeMessage("Adding an FDE");
    assertTrue(message.contains("Adding an FDE"));
    assertTrue(message.contains("main devbox"));
    assertTrue(message.contains("Run this on main"));
    assertTrue(message.contains("overwritten on the next sync"));
  }

  @Test
  void passkeyNoMatchMessageListsRegisteredCredentials() {
    var credentials = List.of(credential("alpha", "macbook"), credential("bravo", null));
    var message = FdeCommand.Passkey.noMatchMessage("uday", "zz", credentials);
    assertTrue(message.contains("No passkey of 'uday' matches 'zz'"));
    assertTrue(message.contains(Passkeys.encodeId("alpha".getBytes(StandardCharsets.UTF_8))));
    assertTrue(message.contains("macbook"));
    assertTrue(message.contains("passkey · 2026-07-06"), "unlabeled rows show the default label");
  }

  @Test
  void passkeyNoMatchMessagePointsAtEnrollWhenNoneRegistered() {
    var message = FdeCommand.Passkey.noMatchMessage("uday", "zz", List.of());
    assertTrue(message.contains("'uday' has no passkeys"));
    assertTrue(message.contains("sail fde enroll uday"));
  }

  @Test
  void enrollTicketJsonCarriesTicketFdeAndExpiry() {
    var ticket = new EnrollmentTickets.Ticket("tkt_abc", "uday", "2026-07-07T10:15:30Z");
    var json = YamlUtil.dumpJson(FdeCommand.Enroll.ticketJson(ticket, "https://sail.acme.dev"));
    var parsed = YamlUtil.parseMap(json);
    assertEquals("tkt_abc", parsed.get("ticket"));
    assertEquals("uday", parsed.get("fde"));
    assertEquals("2026-07-07T10:15:30Z", parsed.get("expires_at"));
    assertEquals("https://sail.acme.dev/enroll?ticket=tkt_abc", parsed.get("enroll_url"));
  }

  @Test
  void enrollTicketJsonOmitsEnrollUrlWithoutAConfiguredOrigin() {
    var ticket = new EnrollmentTickets.Ticket("tkt_abc", "uday", "2026-07-07T10:15:30Z");
    var parsed = YamlUtil.parseMap(YamlUtil.dumpJson(FdeCommand.Enroll.ticketJson(ticket, null)));
    assertFalse(parsed.containsKey("enroll_url"));
  }

  @Test
  void passkeyAmbiguousMessageListsEveryCandidate() {
    var candidates = List.of(credential("alpha-one", "a"), credential("alpha-two", "b"));
    var message = FdeCommand.Passkey.ambiguousMessage("uday", "YWxwaGE", candidates);
    assertTrue(message.contains("'YWxwaGE' matches 2 passkeys of 'uday'"));
    assertTrue(message.contains("use a longer prefix"));
    assertTrue(
        message.contains(Passkeys.encodeId("alpha-one".getBytes(StandardCharsets.UTF_8))),
        "candidates list full ids so one is always copy-pasteable");
    assertTrue(message.contains(Passkeys.encodeId("alpha-two".getBytes(StandardCharsets.UTF_8))));
  }
}
