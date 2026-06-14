/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SailYamlNotificationsResolverTest {

  @Test
  void defaultConstructorWorks() {
    var resolver = new SailYamlNotificationsResolver();
    assertNull(resolver.resolve("definitely-does-not-exist-anywhere"));
  }

  @Test
  void unknownProjectResolvesToNull() {
    var resolver = new SailYamlNotificationsResolver(project -> Path.of("/nope/" + project));
    assertNull(resolver.resolve("light-grid"));
  }

  @Test
  void nullPathResolvesToNull() {
    var resolver = new SailYamlNotificationsResolver(project -> null);
    assertNull(resolver.resolve("light-grid"));
  }

  @Test
  void validConfigReturnsNotifications(@TempDir Path dir) throws Exception {
    var yaml =
        """
        name: light-grid
        agent:
          type: claude-code
          specs_dir: specs
          notifications:
            url: https://ntfy.sh/light-grid
            events:
              - guardrail_triggered
              - spec_dispatched
        """;
    var sailYaml = dir.resolve("sail.yaml");
    Files.writeString(sailYaml, yaml);

    var resolver = new SailYamlNotificationsResolver(project -> sailYaml);
    var notifications = resolver.resolve("light-grid");

    assertNotNull(notifications);
    assertEquals("https://ntfy.sh/light-grid", notifications.url());
    assertTrue(notifications.events().contains("guardrail_triggered"));
  }

  @Test
  void configWithoutAgentReturnsNull(@TempDir Path dir) throws Exception {
    var yaml =
        """
        name: bare
        """;
    var sailYaml = dir.resolve("sail.yaml");
    Files.writeString(sailYaml, yaml);

    var resolver = new SailYamlNotificationsResolver(project -> sailYaml);

    assertNull(resolver.resolve("bare"));
  }

  @Test
  void agentWithoutNotificationsReturnsNull(@TempDir Path dir) throws Exception {
    var yaml =
        """
        name: bare
        agent:
          type: claude-code
          specs_dir: specs
        """;
    var sailYaml = dir.resolve("sail.yaml");
    Files.writeString(sailYaml, yaml);

    var resolver = new SailYamlNotificationsResolver(project -> sailYaml);

    assertNull(resolver.resolve("bare"));
  }

  @Test
  void malformedYamlReturnsNullAndLogs(@TempDir Path dir) throws Exception {
    var sailYaml = dir.resolve("sail.yaml");
    Files.writeString(sailYaml, "not: valid: yaml: here:");

    var resolver = new SailYamlNotificationsResolver(project -> sailYaml);

    assertNull(resolver.resolve("oops"));
  }

  @Test
  void constructorRejectsNullLookup() {
    assertThrows(NullPointerException.class, () -> new SailYamlNotificationsResolver(null));
  }
}
