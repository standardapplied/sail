/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.identity.Actor;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectConfigSetCommandTest {

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
            TestDefinitions.yaml("name: acme\nimage: ubuntu/24.04\ndescription: Storefront\n"),
            Actor.cliOperator("uday"));
  }

  @Test
  void aSectionIsWrittenFromItsJsonAndTheOthersAreLeftAsTheyWere() {
    var run =
        set("", "acme", "resources", "{\"cpu\": 4, \"memory\": \"16GB\", \"disk\": \"80GB\"}");

    assertEquals(0, run.exit(), run.err());
    assertEquals(
        "  ✓ Wrote the resources section of acme. Bring its container to it with: "
            + OperatorCommand.here().sail("project apply acme")
            + "\n",
        run.out());
    assertEquals(new ProjectDefinition.Resources(4, "16GB", "80GB"), acme().resources());
    assertEquals("Storefront", acme().description());
  }

  @Test
  void aDashReadsTheValueFromStdinAndNullUnsetsTheSection() {
    assertEquals(0, set("\"debian/12\"\n", "acme", "image", "-").exit());
    assertEquals("debian/12", acme().image());

    assertEquals(0, set("", "acme", "description", "null").exit());
    assertNull(acme().description());
  }

  @Test
  void aValueTheSectionCannotHoldIsRefusedNamingTheFieldAndNothingIsWritten() {
    var run = set("", "acme", "resources", "{\"cpu\": \"four\"}");

    assertEquals(1, run.exit());
    assertEquals("  ✗ resources.cpu must be a whole number.\n", run.err());
    assertNull(acme().resources());
  }

  @Test
  void anUnknownSectionIsRefusedListingTheSections() {
    var run = set("", "acme", "ssh", "{}");

    assertEquals(1, run.exit());
    assertEquals(
        "  ✗ 'ssh' is not a section of a project definition. The sections are: description,"
            + " image, resources, runtimes, repos, services, processes, packages, agent,"
            + " pipeline.\n",
        run.err());
  }

  @Test
  void aDryRunChecksTheValueAndWritesNothing() {
    var run = set("", "acme", "image", "\"x/1\"", "--dry-run");

    assertEquals(0, run.exit(), run.err());
    assertEquals("[dry-run] write the image section of 'acme'\n", run.out());
    assertEquals("ubuntu/24.04", acme().image());
    assertEquals(1, set("", "acme", "image", "7", "--dry-run").exit(), "still checked");
  }

  @Test
  void aProjectTheCatalogDoesNotHoldIsRefusedNamingHowToMakeIt() {
    var run = set("", "ghost", "image", "\"x/1\"");

    assertEquals(1, run.exit());
    assertEquals(
        "  ✗ No project 'ghost' in the catalog. Create it with 'sail project create ghost', or"
            + " sync it from main with 'sail sync'.\n",
        run.err());
  }

  private CommandRun set(String stdin, String... args) {
    return CommandRun.of(
        new ProjectConfigSetCommand(
            () -> new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), mutations),
        args);
  }

  private ProjectDefinition acme() {
    return mutations.catalog().read("acme").orElseThrow();
  }
}
