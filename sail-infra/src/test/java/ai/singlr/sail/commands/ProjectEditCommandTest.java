/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.common.Remedied;
import ai.singlr.sail.common.Remedy;
import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.TestDefinitions;
import ai.singlr.sail.engine.ProjectCatalog;
import ai.singlr.sail.identity.Actor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectEditCommandTest {

  private static final ProjectDefinition ACME =
      TestDefinitions.yaml("name: acme\nimage: ubuntu/24.04\n");

  @Test
  void anEditOfTheWholeDefinitionIsReadWholeAndKeepsItsName() {
    assertEquals(
        TestDefinitions.yaml("name: acme\ndescription: edited\n"),
        ProjectEditCommand.edited(
            "acme", ACME, null, "{\"name\": \"acme\", \"description\": \"edited\"}"));
    assertEquals(
        "The 'name' field must stay 'acme'. Renaming a project through edit is not supported.",
        assertThrows(
                IllegalArgumentException.class,
                () -> ProjectEditCommand.edited("acme", ACME, null, "{\"name\": \"renamed\"}"))
            .getMessage());
  }

  @Test
  void anEditOfOneSectionReplacesItAndKeepsTheRest() {
    var edited = ProjectEditCommand.edited("acme", ACME, Section.DESCRIPTION, "  \"Storefront\"\n");

    assertEquals("Storefront", edited.description());
    assertEquals("ubuntu/24.04", edited.image());
  }

  @Test
  void anEditThatIsNoDefinitionIsRefusedNamingWhatIsWrong() {
    var unknown =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectEditCommand.edited(
                    "acme", ACME, Section.AGENT, "{\"type\": \"codex\", \"build_skill\": \"x\"}"));
    assertEquals(
        "The edited definition is not valid: agent.build_skill is not a key of agent. Its keys"
            + " are: type, auto_branch, branch_prefix, auto_snapshot, install, config, guardrails,"
            + " notifications, member.",
        unknown.getMessage());
    var malformed =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectEditCommand.edited("acme", ACME, null, "{\"name\": \"acme\", "));
    assertTrue(
        malformed
            .getMessage()
            .startsWith("The edited definition is not valid: A project definition is not JSON: "),
        malformed.getMessage());
  }

  @Test
  void theEditorOpensOnTheDefinitionOrOneSectionAsJsonAPersonReads() {
    assertEquals(
        "{\n  \"name\": \"acme\",\n  \"image\": \"ubuntu/24.04\"\n}\n",
        ProjectEditCommand.seed(ACME, null));
    assertEquals("\"ubuntu/24.04\"\n", ProjectEditCommand.seed(ACME, Section.IMAGE));
    assertEquals("null\n", ProjectEditCommand.seed(ACME, Section.PIPELINE));
  }

  @Test
  void theEditorsSaveIsTheDraftAndItsScratchFileHoldsItUntilItIsSaved() throws Exception {
    var opened = new ArrayList<Path>();

    var edited =
        ProjectEditCommand.edit(
            file -> {
              opened.add(file);
              assertEquals("name: acme\n", Files.readString(file));
              Files.writeString(file, "name: acme\ndescription: edited\n");
              return 0;
            },
            "acme",
            "name: acme\n");

    assertEquals(
        new ProjectEditCommand.Draft(opened.getFirst(), "name: acme\ndescription: edited\n"),
        edited);
    assertEquals("name: acme\ndescription: edited\n", Files.readString(opened.getFirst()));
    Files.delete(opened.getFirst());
  }

  @Test
  void anEditorThatFailsChangesNothingAndLeavesNoScratchFile() {
    var opened = new ArrayList<Path>();

    var refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                ProjectEditCommand.edit(
                    file -> {
                      opened.add(file);
                      return 2;
                    },
                    "acme",
                    "name: acme\n"));

    assertEquals("Editor exited with status 2; no changes made.", refused.getMessage());
    assertFalse(Files.exists(opened.getFirst()));
  }

  @Test
  void anEditWritesEachSectionItChangesAndAnEditThatChangesNothingWritesNothing(@TempDir Path dir) {
    var mutations =
        new ProjectMutations(
            new ProjectCatalog(dir.resolve("sail.db")), () -> Actor.cliOperator("uday"));
    mutations
        .catalog()
        .create(
            TestDefinitions.yaml("name: acme\nimage: ubuntu/24.04\ndescription: Storefront\n"),
            Actor.cliOperator("uday"));
    Editor retag =
        file -> {
          Files.writeString(file, Files.readString(file).replace("ubuntu/24.04", "debian/12"));
          return 0;
        };

    var edited = CommandRun.of(new ProjectEditCommand(retag, mutations), "acme", "--json");
    var unchanged =
        CommandRun.of(new ProjectEditCommand(file -> 0, mutations), "acme", "--section", "image");

    assertEquals(
        new CommandRun(
            0, "{\"name\": \"acme\", \"status\": \"updated\", \"sections\": [\"image\"]}\n", ""),
        edited);
    assertEquals("debian/12", mutations.catalog().read("acme").orElseThrow().image());
    assertEquals("Storefront", mutations.catalog().read("acme").orElseThrow().description());
    assertEquals(new CommandRun(0, "  No changes.\n", ""), unchanged);
  }

  @Test
  void aRefusedEditKeepsWhatWasTypedAndSaysWhere(@TempDir Path dir) throws Exception {
    var mutations =
        new ProjectMutations(
            new ProjectCatalog(dir.resolve("sail.db")), () -> Actor.cliOperator("uday"));
    mutations.catalog().create(TestDefinitions.named("acme"), Actor.cliOperator("uday"));
    var opened = new ArrayList<Path>();
    Editor broken =
        file -> {
          opened.add(file);
          Files.writeString(file, "{\"name\": \"acme\", \"resources\": {\"cpu\": \"four\"}}");
          return 0;
        };

    var run = CommandRun.of(new ProjectEditCommand(broken, mutations), "acme");

    assertEquals(1, run.exit());
    assertEquals(
        "  ✗ The edited definition is not valid: resources.cpu must be a whole number. Your edit is"
            + " kept at "
            + opened.getFirst()
            + ".\n",
        run.err());
    assertEquals(
        "{\"name\": \"acme\", \"resources\": {\"cpu\": \"four\"}}",
        Files.readString(opened.getFirst()));
    Files.delete(opened.getFirst());
  }

  @Test
  void aRefusalKeepsItsOwnFixAndAddsWhereTheEditIsKept(@TempDir Path dir) {
    var stale =
        ProjectEditCommand.kept(
            new IllegalStateException("The image section changed."), dir.resolve("draft.json"));
    var withFix =
        ProjectEditCommand.kept(
            Remedied.argument("Bad.", Remedy.of("Fix it.")), dir.resolve("draft.json"));

    assertEquals(
        "The image section changed. Your edit is kept at " + dir.resolve("draft.json") + ".",
        stale.getMessage());
    assertTrue(withFix instanceof IllegalArgumentException);
    assertEquals(
        "Bad. Fix it. Your edit is kept at " + dir.resolve("draft.json") + ".",
        withFix.getMessage());
  }
}
