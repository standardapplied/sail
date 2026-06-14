/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.gen.SailYamlGenerator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class ProjectPushCommandTest {

  @Test
  void helpTextIncludesOptions() {
    var cmd = new CommandLine(new ProjectPushCommand());
    var usage = cmd.getUsageMessage();

    assertTrue(usage.contains("--json"));
    assertTrue(usage.contains("--dry-run"));
    assertTrue(usage.contains("--file"));
    assertTrue(usage.contains("--github-token"));
    assertTrue(usage.contains("--repo"));
    assertTrue(usage.contains("--ref"));
    assertTrue(usage.contains("--message"));
    assertTrue(usage.contains("Push a project descriptor"));
  }

  @Test
  void requiresProjectName() {
    var cmd = new CommandLine(new ProjectPushCommand());
    cmd.setErr(new java.io.PrintWriter(new java.io.StringWriter()));
    var exitCode = cmd.execute();

    assertNotEquals(0, exitCode);
  }

  @Test
  void templatizeReplacesGitCredentials() {
    var config =
        new SailYaml(
            "demo",
            null,
            new SailYaml.Resources(4, "8GB", "50GB"),
            null,
            null,
            null,
            new SailYaml.Git("Alice Smith", "alice@example.com", "token", "/home/alice/.ssh/id"),
            null,
            null,
            null,
            null,
            null,
            null);

    var result = ProjectPushCommand.templatize(config);

    assertEquals("${GIT_NAME}", result.git().name());
    assertEquals("${GIT_EMAIL}", result.git().email());
    assertEquals("token", result.git().auth());
    assertNull(result.git().sshKey());
  }

  @Test
  void templatizeReplacesSshKeys() {
    var config =
        new SailYaml(
            "demo",
            null,
            new SailYaml.Resources(4, "8GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new SailYaml.Ssh("dev", List.of("ssh-ed25519 AAAA...", "ssh-rsa BBBB...")));

    var result = ProjectPushCommand.templatize(config);

    assertEquals("dev", result.ssh().user());
    assertEquals(List.of("${SSH_PUBLIC_KEY}"), result.ssh().authorizedKeys());
  }

  @Test
  void templatizePreservesNonPersonalFields() {
    var repos = List.of(new SailYaml.Repo("https://github.com/org/app.git", "app", null));
    var services = Map.of("postgres", new SailYaml.Service("postgres:16", null, null, null, null));
    var config =
        new SailYaml(
            "demo",
            "Demo project",
            new SailYaml.Resources(4, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            new SailYaml.Git("Alice", "alice@x.com", "ssh", null),
            repos,
            services,
            null,
            null,
            null,
            null);

    var result = ProjectPushCommand.templatize(config);

    assertEquals("demo", result.name());
    assertEquals("Demo project", result.description());
    assertEquals(repos, result.repos());
    assertEquals(services, result.services());
    assertEquals("ssh", result.git().auth());
  }

  @Test
  void templatizeRoundTripsThoughYamlGeneration() {
    var config =
        new SailYaml(
            "demo",
            "Demo project",
            new SailYaml.Resources(4, "8GB", "50GB"),
            null,
            null,
            null,
            new SailYaml.Git("Alice", "alice@x.com", "token", null),
            null,
            null,
            null,
            null,
            null,
            new SailYaml.Ssh("dev", List.of("ssh-ed25519 AAAA...")));

    var templatized = ProjectPushCommand.templatize(config);
    var yaml = SailYamlGenerator.generate(templatized);
    var parsed = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    assertEquals("${GIT_NAME}", parsed.git().name());
    assertEquals("${GIT_EMAIL}", parsed.git().email());
    assertEquals("token", parsed.git().auth());
    assertNull(parsed.git().sshKey());
    assertEquals(List.of("${SSH_PUBLIC_KEY}"), parsed.ssh().authorizedKeys());
  }

  @Test
  void templatizeHandlesNullGitAndSsh() {
    var config =
        new SailYaml(
            "demo",
            null,
            new SailYaml.Resources(4, "8GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    var result = ProjectPushCommand.templatize(config);

    assertNull(result.git());
    assertNull(result.ssh());
  }
}
