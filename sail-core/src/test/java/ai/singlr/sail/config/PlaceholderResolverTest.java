/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceholderResolverTest {

  @Test
  void resolvesNonInteractivelyFromAProvider() {
    var content = "git:\n  name: ${GIT_NAME}\n  email: ${GIT_EMAIL}\n";
    var values = Map.of("GIT_NAME", "Mady M", "GIT_EMAIL", "mady@example.com");

    var result = PlaceholderResolver.resolve(content, values::get);

    assertEquals(Map.of("git", Map.of("name", "Mady M", "email", "mady@example.com")), result);
  }

  @Test
  void providerIsConsultedOnlyForPresentPlaceholders() {
    var result =
        PlaceholderResolver.resolve(
            "name: acme\n",
            name -> {
              throw new AssertionError("provider must not be called: " + name);
            });
    assertEquals(Map.of("name", "acme"), result);
  }

  @Test
  void aValueWithAnApostropheLandsInTheValueNotInTheYamlAroundIt() {
    var redacted = PersonalFields.redact("git:\n  name: Mady O'Neil\n  email: m@example.com\n");
    assertTrue(redacted.contains("'${GIT_NAME}'"), redacted);

    var result =
        PlaceholderResolver.resolve(
            redacted, name -> "GIT_NAME".equals(name) ? "Mady O'Neil" : "m@example.com");

    assertEquals(Map.of("git", Map.of("name", "Mady O'Neil", "email", "m@example.com")), result);
  }

  @Test
  void substitutesInsideEveryStringInTheTreeAndKeepsEverythingElse() {
    var root =
        YamlUtil.parseMap(
            """
            name: acme
            git:
              name: "${GIT_NAME}"
              email: "${GIT_EMAIL}"
            ssh:
              authorized_keys:
                - "${SSH_PUBLIC_KEY}"
              port: 22
            """);

    var result =
        PlaceholderResolver.substitute(
            root, Map.of("GIT_NAME", "Carol O'Neil", "SSH_PUBLIC_KEY", "ssh-ed25519 AAAA"));

    assertEquals("acme", result.get("name"));
    assertEquals(Map.of("name", "Carol O'Neil", "email", "${GIT_EMAIL}"), result.get("git"));
    assertEquals(
        Map.of("authorized_keys", List.of("ssh-ed25519 AAAA"), "port", 22), result.get("ssh"));
    assertEquals(
        "${GIT_NAME}", ((Map<?, ?>) root.get("git")).get("name"), "the input is untouched");
  }

  @Test
  void substitutingNothingReturnsTheTreeItself() {
    var root = YamlUtil.parseMap("name: ${GIT_NAME}\n");
    assertSame(root, PlaceholderResolver.substitute(root, Map.of()));
  }

  @Test
  void aTokenIsReplacedAtEveryOccurrenceAndAnUnknownOneIsNamed() {
    var result =
        PlaceholderResolver.resolve("a: ${GIT_NAME}\nb:\n  - ${GIT_NAME}\n  - x\n", name -> "Mady");

    assertEquals(Map.of("a", "Mady", "b", List.of("Mady", "x")), result);
    var unknown =
        assertThrows(
            IllegalArgumentException.class,
            () -> PlaceholderResolver.resolve("a: ${MYSTERY}\n", name -> "x"));
    assertTrue(unknown.getMessage().contains("${MYSTERY}"), unknown.getMessage());
  }

  @Test
  void providerResolveRejectsUnknownPlaceholders() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> PlaceholderResolver.resolve("x: ${NOPE}\n", name -> "v"));
    assertTrue(ex.getMessage().contains("Unknown placeholder"));
  }

  @Test
  void providerResolveRejectsBlankValues() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> PlaceholderResolver.resolve("x: ${GIT_NAME}\n", name -> "  "));
    assertTrue(ex.getMessage().contains("GIT_NAME"));
  }

  @Test
  void tokenWrapsAName() {
    assertEquals("${GIT_NAME}", PlaceholderResolver.token(PlaceholderResolver.GIT_NAME));
  }

  @Test
  void promptForReturnsTheHumanPromptOfAKnownPlaceholder() {
    assertTrue(PlaceholderResolver.promptFor(PlaceholderResolver.GIT_NAME).contains("name"));
    assertTrue(PlaceholderResolver.promptFor(PlaceholderResolver.GIT_EMAIL).contains("email"));
  }

  @Test
  void promptForRejectsAnUnknownPlaceholder() {
    assertThrows(IllegalArgumentException.class, () -> PlaceholderResolver.promptFor("MYSTERY"));
  }
}
