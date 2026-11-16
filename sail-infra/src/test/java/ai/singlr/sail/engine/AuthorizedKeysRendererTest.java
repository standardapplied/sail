/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.FdeSshKeyStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthorizedKeysRendererTest {

  private static FdeSshKeyStore.SshKeyInfo key(String fdeId, String line) {
    return new FdeSshKeyStore.SshKeyInfo(
        "SHA256:fp", fdeId, "uday", line, "c", Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void rendersForcedCommandLinePerKeyPinnedToItsPersonById() {
    var rendered =
        AuthorizedKeysRenderer.render(
            List.of(
                key("fde_uday01", "ssh-ed25519 AAAA uday@laptop"),
                key("fde_nova02", "ssh-ed25519 BBBB nova@desktop")),
            "/usr/local/bin/sail");

    assertTrue(rendered.startsWith("# Managed by sail"), rendered);
    assertTrue(
        rendered.contains(
            "command=\"/usr/local/bin/sail _gateway --fde-id fde_uday01\",restrict"
                + " ssh-ed25519 AAAA uday@laptop"),
        rendered);
    assertTrue(
        rendered.contains("--fde-id fde_nova02\",restrict ssh-ed25519 BBBB nova@desktop"),
        rendered);
    assertFalse(rendered.contains("--fde "), "no line names a handle: " + rendered);
  }

  @Test
  void emptyRegistryRendersHeaderOnly() {
    var rendered = AuthorizedKeysRenderer.render(List.of(), "/usr/local/bin/sail");
    assertEquals(1, rendered.strip().lines().count());
    assertTrue(rendered.startsWith("# Managed by sail"));
  }

  @Test
  void refusesAnIdThatCouldBreakOutOfForcedCommandQuotes() {
    var injected = key("evil\" command=\"/bin/sh", "ssh-ed25519 AAAA x");
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> AuthorizedKeysRenderer.render(List.of(injected), "/usr/local/bin/sail"));
    assertTrue(e.getMessage().contains("unsafe FDE id"), e.getMessage());
  }

  @Test
  void refusesAnIdWithWhitespace() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthorizedKeysRenderer.render(
                List.of(key("a b", "ssh-ed25519 AAAA x")), "/usr/local/bin/sail"));
  }
}
