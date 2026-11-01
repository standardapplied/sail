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
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.OperatorCommand;
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

    ProjectMutations.write(Section.IMAGE, acme, acme, true, out, "record repo 'x'", null);

    assertEquals("[dry-run] record repo 'x'\n", captured.toString(StandardCharsets.UTF_8));
  }

  @Test
  void aDryRunNeedsNoOperator() {
    assertNull(ProjectMutations.catalogOperator("acme", true));
  }

  @Test
  void notFoundNamesBothRemedies() {
    assertEquals(
        "No project 'acme' in the catalog. Create it with 'sail project create acme', or sync it"
            + " from main with 'sail sync'.",
        ProjectMutations.notFound("acme", OperatorCommand.PLAIN).getMessage());
  }
}
