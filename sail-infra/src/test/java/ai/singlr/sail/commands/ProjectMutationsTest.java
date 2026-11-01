/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.ProjectDefinitionJson;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.store.ProjectStore;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectMutationsTest {

  @TempDir Path dir;
  private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
  private PrintStream out;

  @BeforeEach
  void setUp() {
    out = new PrintStream(captured, true, StandardCharsets.UTF_8);
  }

  @Test
  void anEditIsCheckedWholeBeforeAnythingItWouldFollow() {
    var clash =
        TestDefinitions.yaml(
            "name: acme\nservices:\n  db:\n    image: postgres:16\n    ports: [5432]\n");
    var refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectMutations.checked(
                    clash.withRepos(
                        List.of(new ProjectDefinition.Repo("git@github.com:a/b.git", "b", null)))));
    assertTrue(refused.getMessage().startsWith("repos[].url 'git@github.com:a/b.git'"));
    assertEquals(clash, ProjectMutations.checked(clash));
  }

  @Test
  void aDryRunPrintsTheIntentAndWritesNothing() {
    var acme = TestDefinitions.named("acme");

    mutations().write(Section.IMAGE, acme, acme, true, out, "record repo 'x'", null);

    assertEquals("[dry-run] record repo 'x'\n", captured.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aDryRunNeedsNoOperator() {
    var mutations =
        new ProjectMutations(
            new ProjectCatalog(dir.resolve("sail.db")),
            () -> {
              throw new AssertionError("a dry run asks for no operator");
            });

    assertNull(mutations.writer("acme", true));
  }

  @Test
  void aWriteIsMadeAsTheOperatorOverTheSectionItRead() {
    var mutations = mutations();
    var acme = TestDefinitions.named("acme");
    mutations.catalog().create(acme, mutations.writer("acme", false));
    var read = mutations.current("acme", OperatorCommand.PLAIN);

    mutations.write(
        Section.IMAGE,
        read,
        ProjectDefinitionJson.withSection(read, Section.IMAGE, "\"x/1\""),
        false,
        out,
        "unused",
        mutations.writer("acme", false));

    assertEquals("x/1", mutations.current("acme", OperatorCommand.PLAIN).image());
    assertEquals("", captured.toString(StandardCharsets.UTF_8));
    assertEquals(
        "The image section of project 'acme' changed since it was read. Read it again, then write"
            + " it.",
        assertThrows(
                ProjectStore.StaleSection.class,
                () ->
                    mutations.write(
                        Section.IMAGE,
                        read,
                        ProjectDefinitionJson.withSection(read, Section.IMAGE, "\"x/2\""),
                        false,
                        out,
                        "unused",
                        mutations.writer("acme", false)))
            .getMessage());
    assertEquals(
        "No project 'ghost' in the catalog. Create it with 'sail project create ghost', or sync"
            + " it from main with 'sail sync'.",
        assertThrows(
                IllegalStateException.class,
                () -> mutations.current("ghost", OperatorCommand.PLAIN))
            .getMessage());
  }

  private ProjectMutations mutations() {
    return new ProjectMutations(
        new ProjectCatalog(dir.resolve("sail.db")), () -> Actor.cliOperator("uday"));
  }

  @Test
  void aDefinitionWithoutResourcesIsRefusedNamingTheLineThatSetsThem() {
    var bare = TestDefinitions.named("acme");
    var sized = TestDefinitions.yaml("name: acme\nresources: {cpu: 2, memory: 8GB, disk: 50GB}\n");

    assertEquals(
        "Project 'acme' sets no resources, so its container cannot be made. Set them with: sail"
            + " project config set acme resources '{\"cpu\": 2, \"memory\": \"8GB\", \"disk\":"
            + " \"50GB\"}'",
        assertThrows(
                IllegalStateException.class,
                () -> ProjectMutations.requireResources(bare, OperatorCommand.PLAIN))
            .getMessage());
    assertSame(sized, ProjectMutations.requireResources(sized, OperatorCommand.PLAIN));
  }

  @Test
  void notFoundNamesBothRemedies() {
    assertEquals(
        "No project 'acme' in the catalog. Create it with 'sail project create acme', or sync it"
            + " from main with 'sail sync'.",
        ProjectMutations.notFound("acme", OperatorCommand.PLAIN).getMessage());
  }
}
