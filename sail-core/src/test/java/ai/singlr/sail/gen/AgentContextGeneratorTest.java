/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    var md = AgentContextGenerator.generateContextBody(fullConfig());

    assertTrue(md.contains("## Project"));
    assertTrue(md.contains("Acme Health Platform"));
  }

  @Test
  void includesTechStack() {
    var md = AgentContextGenerator.generateContextBody(fullConfig());

    assertTrue(md.contains("## Tech Stack"));
    assertTrue(md.contains("Backend: JDK 25, Helidon 4.3.x"));
    assertTrue(md.contains("Frontend: React 19, Tailwind 4"));
  }

  @Test
  void includesConventions() {
    var md = AgentContextGenerator.generateContextBody(fullConfig());

    assertTrue(md.contains("## Project Conventions"));
    assertTrue(md.contains("Use virtual threads"));
    assertTrue(md.contains("Records for DTOs"));
  }

  @Test
  void includesBuildCommands() {
    var md = AgentContextGenerator.generateContextBody(fullConfig());

    assertTrue(md.contains("## Build & Run"));
    assertTrue(md.contains("mvn clean package -DskipTests"));
    assertTrue(md.contains("mvn test"));
  }

  @Test
  void includesServicesTable() {
    var md = AgentContextGenerator.generateContextBody(fullConfig());

    assertTrue(md.contains("## Services"));
    assertTrue(md.contains("| Service | Port | Notes |"));
    assertTrue(md.contains("Postgres 16"));
    assertTrue(md.contains("5432"));
    assertTrue(md.contains("Meilisearch"));
    assertTrue(md.contains("7700"));
  }

  @Test
  void postgresNotesIncludeUserAndDb() {
    var md = AgentContextGenerator.generateContextBody(fullConfig());

    assertTrue(md.contains("user: dev"));
    assertTrue(md.contains("db: acme"));
  }

  @Test
  void meilisearchNotesIncludeMode() {
    var md = AgentContextGenerator.generateContextBody(fullConfig());

    assertTrue(md.contains("development mode"));
  }

  @Test
  void includesProcessesTable() {
    var md = AgentContextGenerator.generateContextBody(fullConfig());

    assertTrue(md.contains("## Developer Commands"));
    assertTrue(md.contains("| Task | Command |"));
    assertTrue(md.contains("| app | java -jar build/app.jar |"));
    assertTrue(md.contains("| web | npm run dev (in ./webapp) |"));
  }

  @Test
  void includesEnvironmentSection() {
    var md = AgentContextGenerator.generateContextBody(fullConfig());

    assertTrue(md.contains("## Environment"));
    assertTrue(md.contains("Incus container managed by `sail`"));
    assertTrue(md.contains("Podman (rootless) is the container runtime"));
  }

  @Test
  void omitsTheTestcontainersEssay() {
    var md = AgentContextGenerator.generateContextBody(fullConfig());

    assertFalse(
        md.contains("## Testcontainers"), "the Podman/Testcontainers essay is not generated");
    assertFalse(md.contains("TESTCONTAINERS_RYUK_DISABLED"));
    assertFalse(md.contains("DOCKER_HOST"));
  }

  @Test
  void includesProjectSpecific() {
    var md = AgentContextGenerator.generateContextBody(fullConfig());

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

    var md = AgentContextGenerator.generateContextBody(config);

    assertFalse(md.contains("Project-Specific Notes"));
  }

  @Test
  void handlesMinimalConfig() {
    var md = AgentContextGenerator.generateContextBody(minimalConfig());

    assertTrue(md.contains("## Project"));
    assertTrue(md.contains("A minimal project"));
    assertTrue(md.contains("## Environment"));
    assertTrue(md.contains("## Engineering Principles"));
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

    var md = AgentContextGenerator.generateContextBody(config);

    assertTrue(md.contains("## Runtimes"));
    assertTrue(md.contains("- JDK 25"));
    assertTrue(md.contains("- Node.js 22"));
    assertTrue(md.contains("- Maven 3.9.9"));
  }

  @Test
  void runtimesOmittedWhenAllZeroOrNull() {
    var md = AgentContextGenerator.generateContextBody(minimalConfig());

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

    var md = AgentContextGenerator.generateContextBody(config);

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

    var md = AgentContextGenerator.generateContextBody(config);

    assertTrue(md.contains("## Repositories"));
    assertTrue(md.contains("`~/workspace/acme`"));
    assertTrue(md.contains("acme/backend.git"));
    assertTrue(md.contains("(branch: main)"));
    assertTrue(md.contains("`~/workspace/lib`"));
    assertFalse(md.contains("lib` ← https://github.com/acme/lib.git (branch:"));
  }

  @Test
  void repositoriesOmittedWhenNull() {
    var md = AgentContextGenerator.generateContextBody(minimalConfig());

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

    var md = AgentContextGenerator.generateContextBody(config);

    assertTrue(md.contains("Git identity: Acme Eng <eng@acme.com>"));
  }

  @Test
  void environmentOmitsGitIdentityWhenNull() {
    var md = AgentContextGenerator.generateContextBody(minimalConfig());

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

    var md = AgentContextGenerator.generateContextBody(config);

    assertTrue(md.contains("Redis 7"));
    assertTrue(md.contains("6379"));
    assertTrue(md.contains("| - |"));
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

    var md = AgentContextGenerator.generateContextBody(config);

    assertTrue(md.contains("## Project\nmy-project"));
  }

  @Test
  void autonomousOperationIsNotInTheAlwaysLoadedBody() {
    var guardrails = new ai.singlr.sail.config.Guardrails("4h", null, "stop");
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, guardrails, "specs", null, null, null);
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

    var md = AgentContextGenerator.generateContextBody(config);

    assertFalse(
        md.contains("## Autonomous Operation"),
        "autonomous-run instructions belong in the dispatch prompt, not the always-loaded context");
    assertFalse(md.contains("Session Handoff"), "context handoff is the agent runtime's job");
    assertFalse(md.contains("handoff.md"), "no ~/handoff.md context-management instructions");
  }

  @Test
  void generateFilesReturnsClaudeMdForClaudeCodeAgent() {
    var files = AgentContextGenerator.generateFiles(fullConfig());

    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("CLAUDE.md")));
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

    assertEquals(6, files.size());
    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("/.claude/CLAUDE.md")));
    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("/.codex/AGENTS.md")));
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

    assertEquals(6, files.size());
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
  void claudeHomeFileLandsUnderDotClaude() {
    var files = AgentContextGenerator.generateFiles(fullConfig());

    var claude = fileEndingWith(files, "/.claude/CLAUDE.md");
    assertTrue(claude.content().contains("## Tech Stack"), "the home file carries the body");
    assertFalse(claude.executable());
  }

  @Test
  void codexHomeFileLandsUnderDotCodex() {
    var agent =
        new SailYaml.Agent("codex", true, "sail/", true, null, null, null, null, null, null, null);
    var files = AgentContextGenerator.generateFiles(configWithAgent(agent));

    assertTrue(
        files.stream().anyMatch(f -> f.remotePath().endsWith("/.codex/AGENTS.md")),
        "Codex's sail-owned context is ~/.codex/AGENTS.md");
  }

  @Test
  void sailNeverGeneratesFilesInTheEngineerWorkspace() {
    var files = AgentContextGenerator.generateFiles(fullConfig());

    assertTrue(
        files.stream().noneMatch(f -> f.remotePath().contains("/workspace/")),
        "every generated file is in sail's home namespace, never the engineer's workspace");
  }

  @Test
  void noSecurityMdIsGenerated() {
    var files = AgentContextGenerator.generateFiles(fullConfig());

    assertTrue(
        files.stream().noneMatch(f -> f.remotePath().endsWith("SECURITY.md")),
        "the security baseline now lives in the review/audit prompt, not a generated file");
  }

  @Test
  void skillsLandUnderTheHomeSkillsNamespace() {
    var agent =
        new SailYaml.Agent(
            "claude-code", true, null, true, null, null, null, "specs", null, null, null);
    var files = AgentContextGenerator.generateFiles(configWithAgent(agent));

    var skills = files.stream().filter(f -> f.remotePath().contains("/skills/")).toList();
    assertFalse(skills.isEmpty(), "the spec-board skill is generated");
    assertTrue(
        skills.stream().allMatch(f -> f.remotePath().contains("/.claude/skills/")),
        "sail's skills live under ~/.claude/skills, not the workspace");
  }

  @Test
  void multiAgentGeneratesAHomeFilePerAgent() {
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

    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("/.claude/CLAUDE.md")));
    assertTrue(files.stream().anyMatch(f -> f.remotePath().endsWith("/.codex/AGENTS.md")));
  }

  @Test
  void bothAgentsGetTheIdenticalBody() {
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
    var files = AgentContextGenerator.generateFiles(configWithAgent(agent));

    var claude = fileEndingWith(files, "/.claude/CLAUDE.md").content();
    var codex = fileEndingWith(files, "/.codex/AGENTS.md").content();
    assertEquals(claude, codex, "the sail-owned body is identical for both agents");
    assertTrue(claude.contains("## Engineering Principles"));
  }

  @Test
  void projectSecurityFromConfigRendersIntoTheContext() {
    var agentCtx =
        new SailYaml.AgentContext(null, null, null, null, "All PII must be encrypted with KMS.");
    var content =
        coreBody(AgentContextGenerator.generateFiles(configWithRuntimesAndContext(null, agentCtx)));

    assertTrue(content.contains("## Project-Specific Security Requirements"));
    assertTrue(content.contains("All PII must be encrypted with KMS."));
  }

  @Test
  void contextOmitsTheProjectSecuritySectionWhenUnset() {
    assertFalse(
        coreBody(AgentContextGenerator.generateFiles(fullConfig()))
            .contains("Project-Specific Security Requirements"));
  }

  @Test
  void contextIncludesUniversalPrinciples() {
    var content = coreBody(AgentContextGenerator.generateFiles(fullConfig()));

    assertTrue(content.contains("efficient senior engineer"));
    assertTrue(content.contains("best code is the code never written"));
    assertTrue(content.contains("SRP, DRY, KISS, YAGNI"));
    assertTrue(content.contains("Deletion over addition"));
    assertTrue(content.contains("Boring over clever"));
    assertTrue(content.contains("Fewest files possible"));
    assertTrue(content.contains("No inline comments, ever"));
    assertTrue(content.contains("Test behavior, not lines"));
    assertFalse(content.contains("SOLID principles"), "consolidated into SRP/DRY/KISS/YAGNI");
  }

  @Test
  void contextCarriesAShortSecurityStance() {
    var content = coreBody(AgentContextGenerator.generateFiles(fullConfig()));

    assertTrue(content.contains("## Security"));
    assertTrue(content.contains("Zero-trust posture"));
    assertTrue(content.contains("parameterized queries"), "it carries the always-true coding rule");
    assertFalse(
        content.contains("security audit"),
        "the stance makes no claim about an audit that may not be configured");
    assertFalse(content.contains("OWASP Top 10 Awareness"), "the full rubric lives in the audit");
  }

  @Test
  void noHardcodedLanguageStandardsAnywhere() {
    var files =
        AgentContextGenerator.generateFiles(
            configWithRuntimes(new SailYaml.Runtimes(25, "22", null)));

    assertTrue(
        files.stream().noneMatch(f -> f.content().contains("Records for all value types")),
        "sail ships no hardcoded Java standards");
    assertTrue(
        files.stream().noneMatch(f -> f.content().contains("Functional components")),
        "sail ships no hardcoded TypeScript standards");
    assertTrue(
        files.stream().noneMatch(f -> f.remotePath().contains("/skills/java/")),
        "no java skill is generated");
    assertTrue(
        files.stream().noneMatch(f -> f.remotePath().contains("/skills/typescript/")),
        "no typescript skill is generated");
    assertFalse(coreBody(files).contains("Language-Specific Standards"));
  }

  @Test
  void runtimesStillOrientTheContextWithoutPrescribingStandards() {
    var content =
        coreBody(
            AgentContextGenerator.generateFiles(
                configWithRuntimes(new SailYaml.Runtimes(25, "22", null))));

    assertTrue(content.contains("## Runtimes"), "runtimes still appear as orientation");
    assertTrue(content.contains("- JDK 25"));
    assertTrue(content.contains("- Node.js 22"));
  }

  @Test
  void configuredLanguageRulesAreMaterializedUnderTheHomeNamespace() {
    var agentCtx =
        new SailYaml.AgentContext(
            null,
            null,
            null,
            null,
            null,
            List.of(new SailYaml.AgentRule("java", List.of("**/*.java"), "- Records for DTOs.")));
    var files = AgentContextGenerator.generateFiles(configWithRuntimesAndContext(null, agentCtx));

    var rule = fileEndingWith(files, "/.claude/rules/java.md");
    assertTrue(rule.content().contains("**/*.java"));
    assertTrue(rule.content().contains("- Records for DTOs."));
    assertFalse(
        files.stream().anyMatch(f -> f.remotePath().contains("/workspace/")),
        "rules land in sail's home namespace, never the engineer's workspace");
  }

  @Test
  void noLanguageRulesWhenNoneConfigured() {
    var files = AgentContextGenerator.generateFiles(fullConfig());

    assertFalse(files.stream().anyMatch(f -> f.remotePath().contains("/rules/")));
  }

  @Test
  void userConventionsAppendedAfterUniversalPrinciples() {
    var agentCtx = new SailYaml.AgentContext(null, "- Custom project rule", null, null);
    var config = configWithRuntimesAndContext(new SailYaml.Runtimes(25, null, null), agentCtx);
    var content = coreBody(AgentContextGenerator.generateFiles(config));

    var autoIdx = content.indexOf("SRP, DRY, KISS, YAGNI");
    var userIdx = content.indexOf("Custom project rule");
    assertTrue(autoIdx > 0);
    assertTrue(userIdx > 0);
    assertTrue(autoIdx < userIdx, "Universal principles should come before user conventions");
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

    assertEquals(6, files.size(), "per agent: one home file + the spec-board SKILL.md + template");
  }

  @Test
  void specSectionIncludedWhenSpecsDirConfigured() {
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, "specs", null, null, null);
    var config = configWithAgent(agent);

    var md = AgentContextGenerator.generateContextBody(config);

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

    var md = AgentContextGenerator.generateContextBody(config);

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

  private static SailYaml configWithRuntimes(SailYaml.Runtimes runtimes) {
    return configWithRuntimesAndContext(runtimes, null);
  }

  private static SailYaml configWithRuntimesAndContext(
      SailYaml.Runtimes runtimes, SailYaml.AgentContext agentContext) {
    var agent =
        new SailYaml.Agent(
            "claude-code", true, "sail/", true, null, null, null, null, null, null, null);
    return new SailYaml(
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
        agentContext,
        null);
  }

  private static String coreBody(List<GeneratedFile> files) {
    return fileEndingWith(files, "/.claude/CLAUDE.md").content();
  }

  private static GeneratedFile fileEndingWith(List<GeneratedFile> files, String suffix) {
    return files.stream().filter(f -> f.remotePath().endsWith(suffix)).findFirst().orElseThrow();
  }
}
