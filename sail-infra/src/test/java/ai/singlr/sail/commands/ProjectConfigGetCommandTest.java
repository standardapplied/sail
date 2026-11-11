/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.identity.Actor;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.identity.Role;
import java.nio.file.Path;
import java.util.List;
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
            new ProjectCatalog(dir.resolve("sail.db")),
            () -> new Actor("uday", Role.ADMIN, Actor.Lane.CLI));
    mutations
        .catalog()
        .create(
            TestDefinitions.yaml("name: acme\nresources: {cpu: 2, memory: 8GB, disk: 50GB}\n"),
            new Actor("uday", Role.ADMIN, Actor.Lane.CLI));
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

  @Test
  void aProjectOutsideTheOperatorsScopeReadsAsOneTheCatalogDoesNotHold() {
    var scoped =
        new Actor("vic", Role.MEMBER, Actor.Lane.CLI).withScope(ProjectScope.of(List.of("beta")));

    assertEquals(
        new CommandRun(
            1,
            "",
            "  ✗ No project 'acme' in the catalog. Create it with 'sail project create acme', or"
                + " sync it from main with 'sail sync'.\n"),
        CommandRun.as(scoped, new ProjectConfigGetCommand(mutations), "acme", "resources"));
  }

  private CommandRun get(String... args) {
    return CommandRun.of(new ProjectConfigGetCommand(mutations), args);
  }
}
