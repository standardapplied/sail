/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.store.FdeSshKeyStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuthorizedKeysRendererTest {

  private static FdeSshKeyStore.SshKeyInfo key(String handle, String line) {
    return new FdeSshKeyStore.SshKeyInfo(
        "SHA256:fp", "fde_1", handle, line, "c", "2026-01-01T00:00:00Z");
  }

  @Test
  void rendersForcedCommandLinePerKey() {
    var rendered =
        AuthorizedKeysRenderer.render(
            List.of(
                key("uday", "ssh-ed25519 AAAA uday@laptop"),
                key("nova", "ssh-ed25519 BBBB nova@desktop")),
            "/usr/local/bin/sail");

    assertTrue(rendered.startsWith("# Managed by sail"), rendered);
    assertTrue(
        rendered.contains(
            "command=\"/usr/local/bin/sail _gateway --fde uday\",restrict"
                + " ssh-ed25519 AAAA uday@laptop"),
        rendered);
    assertTrue(rendered.contains("--fde nova\",restrict ssh-ed25519 BBBB nova@desktop"), rendered);
  }

  @Test
  void emptyRegistryRendersHeaderOnly() {
    var rendered = AuthorizedKeysRenderer.render(List.of(), "/usr/local/bin/sail");
    assertEquals(1, rendered.strip().lines().count());
    assertTrue(rendered.startsWith("# Managed by sail"));
  }

  @Test
  void refusesHandleThatCouldBreakOutOfForcedCommandQuotes() {
    var injected = key("evil\" command=\"/bin/sh", "ssh-ed25519 AAAA x");
    var e =
        assertThrows(
            IllegalArgumentException.class,
            () -> AuthorizedKeysRenderer.render(List.of(injected), "/usr/local/bin/sail"));
    assertTrue(e.getMessage().contains("unsafe FDE handle"), e.getMessage());
  }

  @Test
  void refusesHandleWithWhitespace() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AuthorizedKeysRenderer.render(
                List.of(key("a b", "ssh-ed25519 AAAA x")), "/usr/local/bin/sail"));
  }
}
