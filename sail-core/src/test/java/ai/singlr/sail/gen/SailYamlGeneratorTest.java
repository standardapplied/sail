/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.SailYaml;
import ai.singlr.sail.config.YamlUtil;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SailYamlGeneratorTest {

  @Test
  void generatesMinimalConfig() {
    var config = minimal();

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("name: my-project"));
    assertTrue(yaml.contains("cpu: 2"));
    assertTrue(yaml.contains("memory: 8GB"));
    assertTrue(yaml.contains("disk: 50GB"));
    assertTrue(yaml.contains("image: ubuntu/24.04"));
    assertFalse(yaml.contains("runtimes:"));
    assertFalse(yaml.contains("git:"));
    assertFalse(yaml.contains("services:"));
    assertFalse(yaml.contains("agent:"));
    assertFalse(yaml.contains("repos:"));
  }

  @Test
  void includesDescriptionWhenPresent() {
    var config =
        new SailYaml(
            "my-project",
            "A cool project",
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("description: \"A cool project\""));
  }

  @Test
  void omitsDescriptionWhenNull() {
    var yaml = SailYamlGenerator.generate(minimal());

    assertFalse(yaml.contains("description:"));
  }

  @Test
  void includesRuntimes() {
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            new SailYaml.Runtimes(25, "22", "3.9.9"),
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("runtimes:"));
    assertTrue(yaml.contains("jdk: 25"));
    assertTrue(yaml.contains("node: 22"));
    assertTrue(yaml.contains("maven: 3.9.9"));
  }

  @Test
  void omitsZeroRuntimes() {
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            new SailYaml.Runtimes(0, null, null),
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertFalse(yaml.contains("runtimes:"));
  }

  @Test
  void includesJdkOnlyWhenNodeZero() {
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            new SailYaml.Runtimes(25, null, null),
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("runtimes:"));
    assertTrue(yaml.contains("jdk: 25"));
    assertFalse(yaml.contains("node:"));
    assertFalse(yaml.contains("maven:"));
  }

  @Test
  void includesGitSection() {
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            new SailYaml.Git("Alice", "alice@example.com", "token", null),
            null,
            null,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("git:"));
    assertTrue(yaml.contains("name: Alice"));
    assertTrue(yaml.contains("email: \"alice@example.com\""));
    assertTrue(yaml.contains("auth: token"));
  }

  @Test
  void includesGitSshKeyWhenSet() {
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            new SailYaml.Git("Alice", "alice@example.com", "ssh", "~/.ssh/id_ed25519"),
            null,
            null,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("auth: ssh"));
    assertTrue(yaml.contains("  ssh_key: ~/.ssh/id_ed25519"));
  }

  @Test
  void omitsGitSshKeyWhenNull() {
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            new SailYaml.Git("Alice", "alice@example.com", "token", null),
            null,
            null,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("auth: token"));
    assertFalse(yaml.contains("  ssh_key:"), "Should not emit ssh_key field when null");
  }

  @Test
  void includesReposSection() {
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            null,
            List.of(
                new SailYaml.Repo("https://github.com/acme/backend.git", "backend", "main"),
                new SailYaml.Repo("https://github.com/acme/frontend.git", "frontend", null)),
            null,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("repos:"));
    assertTrue(yaml.contains("url: \"https://github.com/acme/backend.git\""));
    assertTrue(yaml.contains("path: backend"));
    assertTrue(yaml.contains("branch: main"));
    assertTrue(yaml.contains("path: frontend"));
    var lines = yaml.lines().toList();
    var scimIdx = -1;
    for (var i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains("frontend")) {
        scimIdx = i;
        break;
      }
    }
    assertTrue(scimIdx >= 0);
  }

  @Test
  void includesServicesWithEnvAndVolumes() {
    var services = new LinkedHashMap<String, SailYaml.Service>();
    services.put(
        "postgres",
        new SailYaml.Service(
            "postgres:16",
            List.of(5432),
            Map.of("POSTGRES_DB", "app"),
            null,
            List.of("pgdata:/var/lib/postgresql/data")));

    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            null,
            null,
            services,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("services:"));
    assertTrue(yaml.contains("postgres:"));
    assertTrue(yaml.contains("image: \"postgres:16\""));
    assertTrue(yaml.contains("ports: [5432]"));
    assertTrue(yaml.contains("POSTGRES_DB: app"));
    assertTrue(yaml.contains("\"pgdata:/var/lib/postgresql/data\""));
  }

  @Test
  void includesMultipleServicePorts() {
    var services = new LinkedHashMap<String, SailYaml.Service>();
    services.put(
        "redpanda",
        new SailYaml.Service(
            "redpandadata/redpanda:latest", List.of(9092, 8081, 8082), null, null, null));

    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            null,
            null,
            services,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("ports: [9092, 8081, 8082]"));
  }

  @Test
  void includesServiceCommand() {
    var services = new LinkedHashMap<String, SailYaml.Service>();
    services.put(
        "redpanda",
        new SailYaml.Service(
            "redpandadata/redpanda:latest", List.of(9092), null, "redpanda start --smp 1", null));

    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            null,
            null,
            services,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("command: \"redpanda start --smp 1\""));
  }

  @Test
  void includesAgentSection() {
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            null,
            null,
            null,
            null,
            new SailYaml.Agent(
                "claude-code", true, "agent/", true, null, null, null, null, null, null, null),
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("agent:"));
    assertTrue(yaml.contains("type: claude-code"));
    assertTrue(yaml.contains("auto_snapshot: true"));
    assertTrue(yaml.contains("auto_branch: true"));
    assertTrue(yaml.contains("branch_prefix: agent/"));
  }

  @Test
  void includesSshSection() {
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new SailYaml.Ssh("dev", List.of("ssh-ed25519 AAAA...")));

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("ssh:"));
    assertTrue(yaml.contains("user: dev"));
    assertTrue(yaml.contains("ssh-ed25519 AAAA..."));
  }

  @Test
  void includesPackagesSection() {
    var config =
        new SailYaml(
            "test",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            List.of("htop", "jq"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);

    assertTrue(yaml.contains("packages:"));
    assertTrue(yaml.contains("  - htop"));
    assertTrue(yaml.contains("  - jq"));
  }

  @Test
  void hasCommentHeader() {
    var yaml = SailYamlGenerator.generate(minimal());

    assertTrue(yaml.startsWith("# sail.yaml"));
    assertTrue(yaml.contains("Generated by `sail project init`"));
  }

  @Test
  void hasInlineComments() {
    var yaml = SailYamlGenerator.generate(minimal());

    assertTrue(yaml.contains("# Project name"));
    assertTrue(yaml.contains("# Resource limits"));
    assertTrue(yaml.contains("# Base container image"));
  }

  @Test
  void generatedYamlIsParseable() {
    var services = new LinkedHashMap<String, SailYaml.Service>();
    services.put(
        "postgres",
        new SailYaml.Service(
            "postgres:16",
            List.of(5432),
            Map.of("POSTGRES_DB", "app", "POSTGRES_USER", "dev"),
            null,
            List.of("pgdata:/var/lib/postgresql/data")));

    var config =
        new SailYaml(
            "test-project",
            "My test project",
            new SailYaml.Resources(4, "16GB", "100GB"),
            "ubuntu/24.04",
            null,
            new SailYaml.Runtimes(25, "22", "3.9.9"),
            new SailYaml.Git("Test User", "test@example.com", "token", null),
            List.of(new SailYaml.Repo("https://github.com/test/repo.git", "repo", "main")),
            services,
            null,
            new SailYaml.Agent(
                "claude-code", true, "agent/", true, null, null, null, null, null, null, null),
            null,
            new SailYaml.Ssh("dev", List.of("ssh-ed25519 AAAA...")));

    var yaml = SailYamlGenerator.generate(config);

    var parsed = YamlUtil.parseMap(yaml);
    assertEquals("test-project", parsed.get("name"));
    assertNotNull(parsed.get("resources"));
    assertNotNull(parsed.get("runtimes"));
    assertNotNull(parsed.get("git"));
    assertNotNull(parsed.get("services"));
    assertNotNull(parsed.get("agent"));
  }

  @Test
  void generatedYamlRoundTripsViaSailYaml() {
    var services = new LinkedHashMap<String, SailYaml.Service>();
    services.put("postgres", new SailYaml.Service("postgres:16", List.of(5432), null, null, null));

    var config =
        new SailYaml(
            "round-trip",
            null,
            new SailYaml.Resources(2, "8GB", "50GB"),
            "ubuntu/24.04",
            null,
            new SailYaml.Runtimes(25, null, "3.9.9"),
            null,
            null,
            services,
            null,
            null,
            null,
            null);

    var yaml = SailYamlGenerator.generate(config);
    var parsed = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    assertEquals("round-trip", parsed.name());
    assertEquals(2, parsed.resources().cpu());
    assertEquals("8GB", parsed.resources().memory());
    assertEquals(25, parsed.runtimes().jdk());
    assertEquals("3.9.9", parsed.runtimes().maven());
    assertTrue(parsed.services().containsKey("postgres"));
  }

  @Test
  void quoteYamlPlainString() {
    assertEquals("hello", SailYamlGenerator.quoteYaml("hello"));
  }

  @Test
  void quoteYamlWithSpaces() {
    assertEquals("\"hello world\"", SailYamlGenerator.quoteYaml("hello world"));
  }

  @Test
  void quoteYamlWithColons() {
    assertEquals("\"key: value\"", SailYamlGenerator.quoteYaml("key: value"));
  }

  @Test
  void quoteYamlWithAtSign() {
    assertEquals("\"user@example.com\"", SailYamlGenerator.quoteYaml("user@example.com"));
  }

  @Test
  void quoteYamlEmptyString() {
    assertEquals("\"\"", SailYamlGenerator.quoteYaml(""));
  }

  @Test
  void quoteYamlNull() {
    assertEquals("\"\"", SailYamlGenerator.quoteYaml(null));
  }

  @Test
  void quoteYamlWithLeadingDash() {
    assertEquals("\"-flag\"", SailYamlGenerator.quoteYaml("-flag"));
  }

  @Test
  void quoteYamlWithQuotes() {
    assertEquals("\"say \\\"hello\\\"\"", SailYamlGenerator.quoteYaml("say \"hello\""));
  }

  @Test
  void quoteYamlWithNewline() {
    assertEquals("\"line1\\nline2\"", SailYamlGenerator.quoteYaml("line1\nline2"));
  }

  @Test
  void quoteYamlWithTab() {
    assertEquals("\"col1\\tcol2\"", SailYamlGenerator.quoteYaml("col1\tcol2"));
  }

  private static SailYaml minimal() {
    return new SailYaml(
        "my-project",
        null,
        new SailYaml.Resources(2, "8GB", "50GB"),
        "ubuntu/24.04",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
