/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.identity.Actor;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectConfigGetCommandTest {

  @TempDir Path dir;
  private ProjectMutations mutations;

  @BeforeEach
  void aCatalogHoldingAcme() {
    mutations =
        new ProjectMutations(
            new ProjectCatalog(dir.resolve("sail.db")), () -> Actor.cliOperator("uday"));
    mutations
        .catalog()
        .create(
            TestDefinitions.yaml("name: acme\nresources: {cpu: 2, memory: 8GB, disk: 50GB}\n"),
            Actor.cliOperator("uday"));
  }

  @Test
  void aSectionPrintsAsTheJsonItHoldsAndAnUnsetOneAsNull() {
    assertEquals(
        new CommandRun(0, "{\"cpu\": 2, \"memory\": \"8GB\", \"disk\": \"50GB\"}\n", ""),
        get("acme", "resources"));
    assertEquals(new CommandRun(0, "null\n", ""), get("acme", "agent"));
  }

  @Test
  void aSectionOfAProjectTheCatalogDoesNotHoldIsRefused() {
    assertEquals(
        new CommandRun(
            1,
            "",
            "  ✗ No project 'ghost' in the catalog. Create it with 'sail project create ghost', or"
                + " sync it from main with 'sail sync'.\n"),
        get("ghost", "resources"));
    assertEquals(1, get("acme", "git").exit());
  }

  private CommandRun get(String... args) {
    return CommandRun.of(new ProjectConfigGetCommand(mutations), args);
  }
}
