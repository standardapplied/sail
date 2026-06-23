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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentContextGeneratorTest {

  private static SailYaml fullConfig() {
    var resources = new SailYaml.Resources(4, "12GB", "150GB");
    var runtimes = new SailYaml.Runtimes(25, "22", null);
    var services = new LinkedHashMap<String, SailYaml.Service>();
    services.put(
        "postgres",
        new SailYaml.Service(
            "postgres:16",
            List.of(5432),
            Map.of("POSTGRES_DB", "acme", "POSTGRES_USER", "dev", "POSTGRES_PASSWORD", "dev"),
            null,
            List.of("pgdata:/var/lib/postgresql/data")));
    services.put(
        "meilisearch",
        new SailYaml.Service(
            "getmeili/meilisearch:latest",
            List.of(7700),
            Map.of("MEILI_ENV", "development", "MEILI_NO_ANALYTICS", "true"),
            null,
            List.of("msdata:/meili_data")));

    var processes = new LinkedHashMap<String, SailYaml.Process>();
    processes.put("app", new SailYaml.Process("java -jar build/app.jar", "."));
    processes.put("web", new SailYaml.Process("npm run dev", "./webapp"));

    var agentCtx =
        new SailYaml.AgentContext(
            "Backend: JDK 25, Helidon 4.3.x\nFrontend: React 19, Tailwind 4",
            "- Use virtual threads\n- Records for DTOs",
            "mvn clean package -DskipTests    # Build\nmvn test                         # Tests",
            "This is a healthcare platform. HIPAA compliance is critical.");

    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            null,
            Map.of("permissions", "full"),
            null,
            null,
            null,
            null,
            null);
    var ssh = new SailYaml.Ssh("dev", List.of("ssh-ed25519 AAAA... alice@laptop"));

    return new SailYaml(
        "acme-health",
        "Acme Health Platform \u2014 EHR integration + patient portal",
        resources,
        "ubuntu/24.04",
        List.of("postgresql-client-16"),
        runtimes,
        null,
        null,
        services,
        processes,
        agent,
        agentCtx,
        ssh);
  }

  private static SailYaml minimalConfig() {
    return new SailYaml(
        "minimal-project",
        "A minimal project",
        new SailYaml.Resources(2, "4GB", "50GB"),
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

  @Test
  void includesProjectDescription() {
    var md = AgentContextGenerator.generateClaudeMd(fullConfig());

    assertTrue(md.contains("## Project"));
    assertTrue(md.contains("Acme Health Platform"));
  }

  @Test
  void includesTechStack() {
    var md = AgentContextGenerator.generateClaudeMd(fullConfig());

    assertTrue(md.contains("## Tech Stack"));
    assertTrue(md.contains("Backend: JDK 25, Helidon 4.3.x"));
    assertTrue(md.contains("Frontend: React 19, Tailwind 4"));
  }

  @Test
  void includesConventions() {
    var md = AgentContextGenerator.generateClaudeMd(fullConfig());

    assertTrue(md.contains("## Conventions"));
    assertTrue(md.contains("Use virtual threads"));
    assertTrue(md.contains("Records for DTOs"));
  }

  @Test
  void includesBuildCommands() {
    var md = AgentContextGenerator.generateClaudeMd(fullConfig());

    assertTrue(md.contains("## Build & Run"));
    assertTrue(md.contains("mvn clean package -DskipTests"));
    assertTrue(md.contains("mvn test"));
  }

  @Test
  void includesServicesTable() {
    var md = AgentContextGenerator.generateClaudeMd(fullConfig());

    assertTrue(md.contains("## Services"));
    assertTrue(md.contains("| Service | Port | Notes |"));
    assertTrue(md.contains("Postgres 16"));
    assertTrue(md.contains("5432"));
    assertTrue(md.contains("Meilisearch"));
    assertTrue(md.contains("7700"));
  }

  @Test
  void postgresNotesIncludeUserAndDb() {
    var md = AgentContextGenerator.generateClaudeMd(fullConfig());

    assertTrue(md.contains("user: dev"));
    assertTrue(md.contains("db: acme"));
  }

  @Test
  void meilisearchNotesIncludeMode() {
    var md = AgentContextGenerator.generateClaudeMd(fullConfig());

    assertTrue(md.contains("development mode"));
  }

  @Test
  void includesProcessesTable() {
    var md = AgentContextGenerator.generateClaudeMd(fullConfig());

    assertTrue(md.contains("## Developer Commands"));
    assertTrue(md.contains("| Task | Command |"));
    assertTrue(md.contains("| app | java -jar build/app.jar |"));
    assertTrue(md.contains("| web | npm run dev (in ./webapp) |"));
  }

  @Test
  void includesEnvironmentSection() {
    var md = AgentContextGenerator.generateClaudeMd(fullConfig());

    assertTrue(md.contains("## Environment"));
    assertTrue(md.contains("Incus container managed by `sail`"));
    assertTrue(md.contains("Podman (rootless) is the container runtime"));
    assertTrue(md.contains("## Testcontainers"));
    assertTrue(md.contains("DOCKER_HOST=unix:///run/user/1000/podman/podman.sock"));
    assertTrue(md.contains("TESTCONTAINERS_RYUK_DISABLED=true"));
    assertTrue(md.contains("systemctl --user status podman.socket"));
  }

  @Test
  void includesProjectSpecific() {
    var md = AgentContextGenerator.generateClaudeMd(fullConfig());

    assertTrue(md.contains("## Project-Specific Notes"));
    assertTrue(md.contains("HIPAA compliance is critical"));
  }

  @Test
  void omitsProjectSpecificWhenNull() {
    var config =
        new SailYaml(
            "test",
            "Test project",
            new SailYaml.Resources(2, "4GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            new SailYaml.AgentContext("stack", "conv", "build", null),
            null);

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertFalse(md.contains("Project-Specific Notes"));
  }

  @Test
  void handlesMinimalConfig() {
    var md = AgentContextGenerator.generateClaudeMd(minimalConfig());

    assertTrue(md.contains("## Project"));
    assertTrue(md.contains("A minimal project"));
    assertTrue(md.contains("## Environment"));
    assertTrue(md.contains("## Conventions"));
    assertFalse(md.contains("## Tech Stack"));
    assertFalse(md.contains("## Services"));
    assertFalse(md.contains("## Developer Commands"));
    assertFalse(md.contains("## Project-Specific Notes"));
  }

  @Test
  void includesRuntimesSection() {
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
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

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertTrue(md.contains("## Runtimes"));
    assertTrue(md.contains("- JDK 25"));
    assertTrue(md.contains("- Node.js 22"));
    assertTrue(md.contains("- Maven 3.9.9"));
  }

  @Test
  void runtimesOmittedWhenAllZeroOrNull() {
    var md = AgentContextGenerator.generateClaudeMd(minimalConfig());

    assertFalse(md.contains("## Runtimes"));
  }

  @Test
  void runtimesPartialShowsOnlyConfigured() {
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
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

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertTrue(md.contains("## Runtimes"));
    assertTrue(md.contains("- JDK 25"));
    assertFalse(md.contains("Node.js"));
    assertFalse(md.contains("Maven"));
  }

  @Test
  void includesRepositoriesSection() {
    var repos =
        List.of(
            new SailYaml.Repo("https://github.com/acme/backend.git", "acme", "main"),
            new SailYaml.Repo("https://github.com/acme/lib.git", "lib", null));
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            null,
            repos,
            null,
            null,
            null,
            null,
            null);

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertTrue(md.contains("## Repositories"));
    assertTrue(md.contains("`~/workspace/acme`"));
    assertTrue(md.contains("acme/backend.git"));
    assertTrue(md.contains("(branch: main)"));
    assertTrue(md.contains("`~/workspace/lib`"));
    assertFalse(md.contains("lib` ← https://github.com/acme/lib.git (branch:"));
  }

  @Test
  void repositoriesOmittedWhenNull() {
    var md = AgentContextGenerator.generateClaudeMd(minimalConfig());

    assertFalse(md.contains("## Repositories"));
  }

  @Test
  void environmentIncludesGitIdentity() {
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            "ubuntu/24.04",
            null,
            null,
            new SailYaml.Git("Acme Eng", "eng@acme.com", "token", null),
            null,
            null,
            null,
            null,
            null,
            null);

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertTrue(md.contains("Git identity: Acme Eng <eng@acme.com>"));
  }

  @Test
  void environmentOmitsGitIdentityWhenNull() {
    var md = AgentContextGenerator.generateClaudeMd(minimalConfig());

    assertFalse(md.contains("Git identity:"));
  }

  @Test
  void handlesServicesWithNoEnvironment() {
    var services = new LinkedHashMap<String, SailYaml.Service>();
    services.put("redis", new SailYaml.Service("redis:7", List.of(6379), null, null, null));

    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
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

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertTrue(md.contains("Redis 7"));
    assertTrue(md.contains("6379"));
    assertTrue(md.contains("| - |"));
  }

  @Test
  void headerIsCorrectFormat() {
    var md = AgentContextGenerator.generateClaudeMd(fullConfig());

    assertTrue(md.startsWith("# CLAUDE.md"));
    assertTrue(md.contains("generated by sail from sail.yaml"));
    assertTrue(
        md.contains("sail:personal"), "the header points the engineer at the personal region");
  }

  @Test
  void usesNameAsFallbackWhenDescriptionNull() {
    var config =
        new SailYaml(
            "my-project",
            null,
            new SailYaml.Resources(2, "4GB", "50GB"),
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

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertTrue(md.contains("## Project\nmy-project"));
  }

  @Test
  void autonomousSectionAppendedWhenGuardrailsConfigured() {
    var guardrails = new ai.singlr.sail.config.Guardrails("4h", "stop");
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, guardrails, null, null, null, null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            agent,
            null,
            null);

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertTrue(md.contains("## Autonomous Operation"));
    assertTrue(md.contains("### Work Protocol"));
    assertTrue(md.contains("### Completion Protocol"));
    assertTrue(md.contains("### Session Handoff"));
    assertTrue(md.contains("### Error Recovery"));
  }

  @Test
  void autonomousSectionAppendedWhenSpecsDirConfigured() {
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, "specs", null, null, null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            agent,
            null,
            null);

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertTrue(md.contains("## Autonomous Operation"));
    assertTrue(md.contains("handoff.md"));
  }

  @Test
  void autonomousSectionOmittedWhenNoAutonomousConfig() {
    var md = AgentContextGenerator.generateClaudeMd(minimalConfig());

    assertFalse(md.contains("Autonomous Operation"));
  }

  @Test
  void autonomousSectionOmittedWhenAgentHasNeitherGuardrailsNorTasks() {
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            agent,
            null,
            null);

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertFalse(md.contains("Autonomous Operation"));
  }

  @Test
  void multiRepoGuidanceWhenMultipleRepos() {
    var repos =
        List.of(
            new SailYaml.Repo("https://github.com/test/backend.git", "backend", "main"),
            new SailYaml.Repo("https://github.com/test/frontend.git", "frontend", "main"));
    var guardrails = new ai.singlr.sail.config.Guardrails("4h", "stop");
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, guardrails, null, null, null, null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            repos,
            null,
            null,
            agent,
            null,
            null);

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertTrue(md.contains("### Multi-Repository Work"));
    assertTrue(md.contains("same name in each affected repo"));
  }

  @Test
  void noMultiRepoGuidanceWhenSingleRepo() {
    var repos = List.of(new SailYaml.Repo("https://github.com/test/app.git", "app", "main"));
    var guardrails = new ai.singlr.sail.config.Guardrails("4h", "stop");
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, guardrails, null, null, null, null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            repos,
            null,
            null,
            agent,
            null,
            null);

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertFalse(md.contains("Multi-Repository Work"));
  }

  @Test
  void completionProtocolIncludesSecurityAuditStep() {
    var guardrails = new ai.singlr.sail.config.Guardrails("4h", "stop");
    var securityAudit = new ai.singlr.sail.config.SecurityAudit(true, null);
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            null,
            null,
            guardrails,
            null,
            securityAudit,
            null,
            null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            agent,
            null,
            null);

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertTrue(md.contains("security audit"));
    assertTrue(md.contains("OWASP"));
    assertTrue(md.contains("hardcoded secrets"));
    assertTrue(md.contains("input validation"));
  }

  @Test
  void securityAuditStepBeforePullRequest() {
    var guardrails = new ai.singlr.sail.config.Guardrails("4h", "stop");
    var securityAudit = new ai.singlr.sail.config.SecurityAudit(true, null);
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            null,
            null,
            guardrails,
            null,
            securityAudit,
            null,
            null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            agent,
            null,
            null);

    var md = AgentContextGenerator.generateClaudeMd(config);

    var auditIdx = md.indexOf("security audit");
    var prIdx = md.indexOf("Create a pull request");
    assertTrue(auditIdx > 0, "security audit step should be present");
    assertTrue(prIdx > 0, "pull request step should be present");
    assertTrue(auditIdx < prIdx, "security audit should come before pull request");
  }

  @Test
  void taskFileStepsStartAt6WhenSecurityAuditPresent() {
    var guardrails = new ai.singlr.sail.config.Guardrails("4h", "stop");
    var securityAudit = new ai.singlr.sail.config.SecurityAudit(true, null);
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            null,
            null,
            guardrails,
            "specs",
            securityAudit,
            null,
            null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            agent,
            null,
            null);

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertTrue(md.contains("6. If no more work remains"));
  }

  @Test
  void generateFilesReturnsClaudeMdForClaudeCodeAgent() {
    var files = AgentContextGenerator.generateFiles(fullConfig());

    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("CLAUDE.md")));
  }

  @Test
  void generateFilesReturnsSecurityMd() {
    var files = AgentContextGenerator.generateFiles(fullConfig());

    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("SECURITY.md")));
  }

  @Test
  void generateFilesReturnsAgentsMdForCodexAgent() {
    var agent =
        new SailYaml.Agent("codex", true, "sail/", true, null, null, null, null, null, null, null);
    var config = configWithAgent(agent);
    var files = AgentContextGenerator.generateFiles(config);

    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("AGENTS.md")));
  }

  @Test
  void generateFilesReturnsMultipleFilesWhenMultipleInstalled() {
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            List.of("claude-code", "codex"),
            null,
            null,
            "specs",
            null,
            null,
            null);
    var config = configWithAgent(agent);
    var files = AgentContextGenerator.generateFiles(config);

    assertEquals(5, files.size());
    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("CLAUDE.md")));
    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("AGENTS.md")));
    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("SECURITY.md")));
    assertTrue(files.stream().anyMatch(f -> f.remotePath().contains("spec-board/SKILL.md")));
  }

  @Test
  void generateFilesReturnsAllWhenAllInstalled() {
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            List.of("claude-code", "codex"),
            null,
            null,
            "specs",
            null,
            null,
            null);
    var config = configWithAgent(agent);
    var files = AgentContextGenerator.generateFiles(config);

    assertEquals(5, files.size());
  }

  @Test
  void generateFilesReturnsEmptyWhenNoAgent() {
    var files = AgentContextGenerator.generateFiles(minimalConfig());

    assertTrue(files.isEmpty());
  }

  @Test
  void generateFilesUsesCorrectSshUser() {
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null);
    var ssh = new SailYaml.Ssh("alice", null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            agent,
            null,
            ssh);
    var files = AgentContextGenerator.generateFiles(config);

    assertTrue(files.stream().allMatch(f -> f.remotePath().startsWith("/home/alice/")));
  }

  @Test
  void contextFilesAreNotExecutable() {
    var files = AgentContextGenerator.generateFiles(fullConfig());

    assertTrue(files.stream().noneMatch(GeneratedFile::executable));
  }

  @Test
  void perAgentContextFilesAreMergeManaged() {
    var files = AgentContextGenerator.generateFiles(fullConfig());

    var contextFiles =
        files.stream()
            .filter(
                f -> f.remotePath().endsWith("CLAUDE.md") || f.remotePath().endsWith("AGENTS.md"))
            .toList();

    assertFalse(contextFiles.isEmpty());
    assertTrue(
        contextFiles.stream().allMatch(f -> f.mergeMarker() != null),
        "the per-agent context merges a shared body with a preserved personal region");
  }

  @Test
  void securityMdIsMergeManaged() {
    var files = AgentContextGenerator.generateFiles(fullConfig());

    var security =
        files.stream()
            .filter(f -> f.remotePath().endsWith("SECURITY.md"))
            .findFirst()
            .orElseThrow();

    assertNotNull(
        security.mergeMarker(),
        "SECURITY.md is merged like the context file: shared baseline, preserved personal notes");
  }

  @Test
  void projectSecurityFromConfigRendersIntoSecurityMd() {
    var agentCtx =
        new SailYaml.AgentContext(null, null, null, null, "All PII must be encrypted with KMS.");
    var md = AgentContextGenerator.generateSecurityMd(configWithAgentContext(agentCtx));

    assertTrue(md.contains("## Project-Specific Security Requirements"));
    assertTrue(md.contains("All PII must be encrypted with KMS."));
  }

  @Test
  void securityMdOmitsTheProjectSectionWhenUnset() {
    assertFalse(
        AgentContextGenerator.generateSecurityMd(fullConfig())
            .contains("Project-Specific Security Requirements"));
  }

  @Test
  void machineryFilesAreOverwrittenOutright() {
    var agent =
        new SailYaml.Agent(
            "claude-code", true, null, true, null, null, null, "specs", null, null, null);
    var files = AgentContextGenerator.generateFiles(configWithAgent(agent));

    var machinery = files.stream().filter(f -> f.remotePath().contains("/skills/")).toList();

    assertFalse(machinery.isEmpty(), "the spec-board skill is machinery");
    assertTrue(machinery.stream().allMatch(f -> f.mergeMarker() == null));
  }

  @Test
  void multiAgentInstallAllAreMergeManaged() {
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            List.of("claude-code", "codex"),
            null,
            null,
            null,
            null,
            null,
            null);
    var config =
        new SailYaml(
            "multi", null, null, null, null, null, null, null, null, null, agent, null, null);
    var files = AgentContextGenerator.generateFiles(config);

    var agentFiles =
        files.stream()
            .filter(
                f -> f.remotePath().endsWith("CLAUDE.md") || f.remotePath().endsWith("AGENTS.md"))
            .toList();

    assertEquals(2, agentFiles.size());
    assertTrue(agentFiles.stream().allMatch(f -> f.mergeMarker() != null));
  }

  @Test
  void allAgentContextFilesShareSameBody() {
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            List.of("claude-code", "codex"),
            null,
            null,
            null,
            null,
            null,
            null);
    var config = configWithAgent(agent);
    var files = AgentContextGenerator.generateFiles(config);

    var contextBodies =
        files.stream()
            .filter(
                f -> f.remotePath().endsWith("CLAUDE.md") || f.remotePath().endsWith("AGENTS.md"))
            .map(f -> f.content().substring(f.content().indexOf('\n')))
            .toList();

    for (var i = 1; i < contextBodies.size(); i++) {
      assertEquals(contextBodies.getFirst(), contextBodies.get(i));
    }
  }

  @Test
  void claudeMdHasCorrectHeader() {
    var files = AgentContextGenerator.generateFiles(fullConfig());
    var claude =
        files.stream().filter(f -> f.remotePath().endsWith("CLAUDE.md")).findFirst().orElseThrow();

    assertTrue(claude.content().startsWith("# CLAUDE.md"));
  }

  @Test
  void agentsMdHasCorrectHeader() {
    var agent =
        new SailYaml.Agent("codex", true, "sail/", true, null, null, null, null, null, null, null);
    var config = configWithAgent(agent);
    var files = AgentContextGenerator.generateFiles(config);
    var agents =
        files.stream().filter(f -> f.remotePath().endsWith("AGENTS.md")).findFirst().orElseThrow();

    assertTrue(agents.content().startsWith("# AGENTS.md"));
  }

  @Test
  void securityMdContainsOwaspTop10() {
    var md = AgentContextGenerator.generateSecurityMd(fullConfig());

    assertTrue(md.contains("OWASP Top 10"));
    assertTrue(md.contains("Injection"));
    assertTrue(md.contains("Broken Authentication"));
  }

  @Test
  void securityMdContainsZeroTrust() {
    var md = AgentContextGenerator.generateSecurityMd(fullConfig());

    assertTrue(md.contains("Zero Trust"));
  }

  @Test
  void securityMdContainsLeastPrivilege() {
    var md = AgentContextGenerator.generateSecurityMd(fullConfig());

    assertTrue(md.contains("Least Privilege"));
  }

  @Test
  void securityMdContainsAuditChecklist() {
    var md = AgentContextGenerator.generateSecurityMd(fullConfig());

    assertTrue(md.contains("Security Audit Checklist"));
    assertTrue(md.contains("No hardcoded secrets"));
    assertTrue(md.contains("path traversal"));
    assertTrue(md.contains("command injection"));
  }

  @Test
  void securityMdContainsApiSecurity() {
    var md = AgentContextGenerator.generateSecurityMd(fullConfig());

    assertTrue(md.contains("API Security"));
    assertTrue(md.contains("Rate-limit"));
    assertTrue(md.contains("CORS"));
  }

  @Test
  void securityMdContainsErrorHandling() {
    var md = AgentContextGenerator.generateSecurityMd(fullConfig());

    assertTrue(md.contains("Error Handling"));
    assertTrue(md.contains("Never expose internal error details"));
    assertTrue(md.contains("structured logging"));
  }

  @Test
  void securityMdContainsDependencySecurity() {
    var md = AgentContextGenerator.generateSecurityMd(fullConfig());

    assertTrue(md.contains("Dependency Security"));
    assertTrue(md.contains("Pin all dependency versions"));
    assertTrue(md.contains("Remove unused dependencies"));
  }

  @Test
  void securityMdChecklistIsComprehensive() {
    var md = AgentContextGenerator.generateSecurityMd(fullConfig());

    assertTrue(md.contains("Error responses do not leak internal details"));
    assertTrue(md.contains("Rate limiting"));
    assertTrue(md.contains("File uploads validated"));
    assertTrue(md.contains("TLS"));
  }

  @Test
  void contextIncludesUniversalPrinciples() {
    var files = AgentContextGenerator.generateFiles(fullConfig());
    var content =
        files.stream()
            .filter(f -> f.remotePath().endsWith("CLAUDE.md"))
            .findFirst()
            .orElseThrow()
            .content();

    assertTrue(content.contains("SOLID principles"));
    assertTrue(content.contains("DRY"));
    assertTrue(content.contains("Zero trust"));
    assertTrue(content.contains("self-documenting"));
    assertTrue(content.contains("simple and elegant"));
    assertTrue(content.contains("No inline comments in code, ever"));
    assertTrue(content.contains("edge cases"));
    assertTrue(content.contains("world's best coder"));
  }

  @Test
  void contextReferencesSecurity() {
    var files = AgentContextGenerator.generateFiles(fullConfig());
    var content =
        files.stream()
            .filter(f -> f.remotePath().endsWith("CLAUDE.md"))
            .findFirst()
            .orElseThrow()
            .content();

    assertTrue(content.contains("SECURITY.md"));
  }

  @Test
  void javaConventionsIncludedWhenJdkPresent() {
    var runtimes = new SailYaml.Runtimes(25, null, null);
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            runtimes,
            null,
            null,
            null,
            null,
            agent,
            null,
            null);
    var files = AgentContextGenerator.generateFiles(config);
    var content = files.getFirst().content();

    assertTrue(content.contains("Java (JDK 25)"));
    assertTrue(content.contains("Records for all value types"));
    assertTrue(content.contains("Virtual threads"));
    assertTrue(content.contains("Sealed interfaces"));
  }

  @Test
  void nodeConventionsIncludedWhenNodePresent() {
    var runtimes = new SailYaml.Runtimes(0, "22", null);
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            runtimes,
            null,
            null,
            null,
            null,
            agent,
            null,
            null);
    var files = AgentContextGenerator.generateFiles(config);
    var content = files.getFirst().content();

    assertTrue(content.contains("Node.js / TypeScript"));
    assertTrue(content.contains("Functional components"));
    assertTrue(content.contains("TypeScript preferred"));
    assertTrue(content.contains("async/await"));
  }

  @Test
  void bothLanguageConventionsWhenBothPresent() {
    var runtimes = new SailYaml.Runtimes(25, "22", null);
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            runtimes,
            null,
            null,
            null,
            null,
            agent,
            null,
            null);
    var files = AgentContextGenerator.generateFiles(config);
    var content = files.getFirst().content();

    assertTrue(content.contains("Java (JDK 25)"));
    assertTrue(content.contains("Node.js / TypeScript"));
  }

  @Test
  void noLanguageConventionsWhenNoRuntimes() {
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            agent,
            null,
            null);
    var files = AgentContextGenerator.generateFiles(config);
    var content = files.getFirst().content();

    assertFalse(content.contains("Language-Specific Standards"));
  }

  @Test
  void userConventionsAppendedAfterAutoGenerated() {
    var runtimes = new SailYaml.Runtimes(25, null, null);
    var agentCtx = new SailYaml.AgentContext(null, "- Custom project rule", null, null);
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null);
    var config =
        new SailYaml(
            "test",
            "Test",
            new SailYaml.Resources(2, "4GB", "50GB"),
            null,
            null,
            runtimes,
            null,
            null,
            null,
            null,
            agent,
            agentCtx,
            null);
    var files = AgentContextGenerator.generateFiles(config);
    var content = files.getFirst().content();

    var autoIdx = content.indexOf("Records for all value types");
    var userIdx = content.indexOf("Custom project rule");
    assertTrue(autoIdx > 0);
    assertTrue(userIdx > 0);
    assertTrue(autoIdx < userIdx, "Auto-generated should come before user conventions");
  }

  @Test
  void generateFilesDoesNotIncludeToneMd() {
    var files = AgentContextGenerator.generateFiles(fullConfig());

    assertTrue(files.stream().noneMatch(f -> f.remotePath().endsWith("TONE.md")));
  }

  @Test
  void generateFilesDoesNotIncludeContentCommand() {
    var files = AgentContextGenerator.generateFiles(fullConfig());

    assertTrue(files.stream().noneMatch(f -> f.remotePath().contains("/skills/content")));
    assertTrue(files.stream().noneMatch(f -> f.remotePath().contains("/commands/content")));
  }

  @Test
  void generateFilesCountWithAllAgents() {
    var agent =
        new SailYaml.Agent(
            "claude-code",
            true,
            "sail/",
            true,
            List.of("claude-code", "codex"),
            null,
            null,
            "specs",
            null,
            null,
            null);
    var config = configWithAgent(agent);
    var files = AgentContextGenerator.generateFiles(config);

    assertEquals(5, files.size());
  }

  @Test
  void specSectionIncludedWhenSpecsDirConfigured() {
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, "specs", null, null, null);
    var config = configWithAgent(agent);

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertTrue(md.contains("## Spec-Driven Development"));
    assertTrue(md.contains("Sail database"));
    assertTrue(md.contains("spec create"));
    assertTrue(md.contains("spec board"));
    assertTrue(md.contains("Status Lifecycle"));
    assertTrue(md.contains("pending"));
    assertTrue(md.contains("in_progress"));
    assertTrue(md.contains("depends-on"));
    assertTrue(md.contains("Interactive Mode"));
    assertFalse(md.contains("spec.yaml"), "specs are DB rows, not files");
  }

  @Test
  void specSectionOmittedWhenSpecsDirNull() {
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null);
    var config = configWithAgent(agent);

    var md = AgentContextGenerator.generateClaudeMd(config);

    assertFalse(md.contains("Spec-Driven Development"));
  }

  private static SailYaml configWithAgent(SailYaml.Agent agent) {
    return new SailYaml(
        "test",
        "Test project",
        new SailYaml.Resources(2, "4GB", "50GB"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        agent,
        null,
        null);
  }

  private static SailYaml configWithAgentContext(SailYaml.AgentContext agentContext) {
    return new SailYaml(
        "test",
        "Test project",
        new SailYaml.Resources(2, "4GB", "50GB"),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        agentContext,
        null);
  }
}
