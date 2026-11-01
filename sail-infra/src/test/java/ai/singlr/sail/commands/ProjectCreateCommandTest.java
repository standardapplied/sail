/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.gen.ServicePresets;
import ai.singlr.sail.harness.Harnesses;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;

class ProjectCreateCommandTest {

  private PrintStream originalOut;
  private final ByteArrayOutputStream asked = new ByteArrayOutputStream();

  @BeforeEach
  void captureTheConsole() {
    originalOut = System.out;
    System.setOut(new PrintStream(asked, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void restoreTheConsole() {
    ConsoleStub.restore();
    System.setOut(originalOut);
  }

  private ProjectDefinition.Agent answering(String answers) {
    ConsoleStub.answering(answers, true);
    return ProjectCreateCommand.promptAgent(
        new PrintStream(asked, true, StandardCharsets.UTF_8), Ansi.OFF);
  }

  @Test
  void thePromptsNameExactlyTheHarnessesSailKnows() {
    assertEquals("Agent type (claude-code/codex/none)", ProjectCreateCommand.agentTypePrompt());
    assertEquals("Agent CLI name (claude-code/codex)", ProjectCreateCommand.agentCliPrompt());
  }

  @Test
  void anEmptyAnswerTakesTheDefaultHarnessWhichThePromptOffers() {
    var agent = answering("\nn\n");

    assertEquals(Harnesses.DEFAULT.yamlName(), agent.type());
    assertNull(agent.install(), "no additional harness was asked for");
    assertTrue(
        asked
            .toString(StandardCharsets.UTF_8)
            .startsWith("  Agent type (claude-code/codex/none) [claude-code]: "),
        asked.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aProjectThatWantsNoAgentGetsNoAgentBlock() {
    assertNull(answering("none\n"));
    assertNull(answering("NONE\n"));
  }

  @Test
  void theHarnessesToInstallBesideTheFirstAreAskedForByName() {
    var agent = answering("codex\ny\n\nclaude-code\ny\ncodex\nn\n");

    assertEquals("codex", agent.type());
    assertEquals(
        List.of("codex", "claude-code"),
        agent.install(),
        "the project's own harness leads, and naming it again adds nothing");
    assertTrue(
        asked.toString(StandardCharsets.UTF_8).contains("  Agent CLI name (claude-code/codex): "));
  }

  private Map<String, ProjectDefinition.Process> app(
      String answers, ProjectDefinition.Runtimes runtimes, List<ProjectDefinition.Repo> repos) {
    ConsoleStub.answering(answers, true);
    return ProjectCreateCommand.promptApp(
        new PrintStream(asked, true, StandardCharsets.UTF_8), Ansi.OFF, runtimes, repos);
  }

  @Test
  void anAppIsAskedForOnlyWhenARuntimeImpliesOneAndRunsFromTheFirstRepo() {
    var node = new ProjectDefinition.Runtimes(0, "22", null);
    var java = new ProjectDefinition.Runtimes(25, null, "3.9.14");
    var repo = List.of(new ProjectDefinition.Repo("https://github.com/acme/web.git", "web", null));

    assertNull(app("", null, repo));
    assertNull(app("", new ProjectDefinition.Runtimes(0, null, "3.9.14"), repo));
    assertEquals("", asked.toString(StandardCharsets.UTF_8), "nothing was asked");
    assertEquals(
        Map.of("app", new ProjectDefinition.Process("npm run dev", "web", 3000, Map.of())),
        app("npm run dev\n\n", node, repo));
    assertTrue(
        asked
            .toString(StandardCharsets.UTF_8)
            .contains("  Command that starts it (blank to skip): "),
        asked.toString(StandardCharsets.UTF_8));
    assertEquals(
        Map.of("app", new ProjectDefinition.Process("mvn spring-boot:run", null, 9090, Map.of())),
        app("mvn spring-boot:run\n9090\n", java, null));
    assertNull(app("\n", node, repo), "a blank command skips the app");
  }

  private ProjectDefinition created(String stdin, String... args) throws Exception {
    var command =
        new ProjectCreateCommand(
            () -> new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), () -> false);
    new CommandLine(command).parseArgs(args);
    return command.definition(new PrintStream(asked, true, StandardCharsets.UTF_8), Ansi.OFF);
  }

  @Test
  void flagsMakeTheDefinitionAndEverySectionTheyLeaveTakesItsDefault() throws Exception {
    assertEquals(
        TestDefinitions.yaml(
            """
            name: acme
            resources: {cpu: 2, memory: 8GB, disk: 50GB}
            image: ubuntu/24.04
            agent: {type: claude-code, auto_branch: true, branch_prefix: agent/, auto_snapshot: true}
            """),
        created("", "acme"));
    assertEquals(
        TestDefinitions.yaml(
            """
            name: acme
            description: Storefront
            resources: {cpu: 4, memory: 16GB, disk: 100GB}
            image: debian/12
            runtimes: {jdk: 26, node: "24", maven: 3.9.14}
            repos:
              - {url: "https://github.com/acme/web.git", path: web}
            services:
              postgres:
                image: postgres:17
                ports: [5432]
                environment: {POSTGRES_DB: app, POSTGRES_USER: dev, POSTGRES_PASSWORD: dev}
                volumes: ["pgdata:/var/lib/postgresql/data"]
              redis: {image: "redis:7", ports: [6379]}
            """),
        created(
            "",
            "acme",
            "--description",
            "Storefront",
            "--cpu",
            "4",
            "--memory",
            "16",
            "--disk",
            "100GB",
            "--image",
            "debian/12",
            "--jdk",
            "26",
            "--node",
            "24",
            "--maven",
            "3.9.14",
            "--repo",
            "https://github.com/acme/web.git",
            "--service",
            "postgres:17",
            "--service",
            "redis",
            "--agent",
            "none"));
  }

  @Test
  void flagsWithNoNameAndAPresetSailDoesNotKnowAreRefused() {
    assertEquals(
        "Name the project to create, or pass its definition with --definition -.",
        assertThrows(IllegalArgumentException.class, () -> created("")).getMessage());
    assertEquals(
        "'mysql' is not a service preset. The presets are: "
            + String.join(
                ", ", ServicePresets.all().stream().map(ServicePresets.Preset::key).toList())
            + ".",
        assertThrows(
                IllegalArgumentException.class, () -> created("", "acme", "--service", "mysql"))
            .getMessage());
  }

  @Test
  void aDefinitionFromStdinIsTheWholeDefinitionAndNamesTheProject() throws Exception {
    var document = "{\"name\": \"acme\", \"description\": \"Storefront\"}";

    assertEquals(
        TestDefinitions.described("acme", "Storefront"), created(document, "--definition", "-"));
    assertEquals(
        TestDefinitions.described("acme", "Storefront"),
        created(document, "acme", "--definition", "-"));
    assertEquals(
        "The definition names project 'acme', not 'beta'. Name the one it names, or none.",
        assertThrows(
                IllegalArgumentException.class,
                () -> created(document, "beta", "--definition", "-"))
            .getMessage());
    assertEquals(
        "--definition carries the whole definition: drop the section flags, or the definition.",
        assertThrows(
                IllegalArgumentException.class,
                () -> created(document, "--definition", "-", "--cpu", "4"))
            .getMessage());
    assertEquals(
        "--definition reads the definition from stdin: pass --definition -.",
        assertThrows(
                IllegalArgumentException.class, () -> created(document, "--definition", "x.json"))
            .getMessage());
  }
}
