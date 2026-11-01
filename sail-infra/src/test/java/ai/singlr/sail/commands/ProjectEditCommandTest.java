/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.config.ProjectDefinition;
import ai.singlr.sail.config.ProjectDefinition.Section;
import ai.singlr.sail.config.TestDefinitions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

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
  void theEditorsSaveIsTheNewDefinitionAndItsScratchFileIsGone() throws Exception {
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

    assertEquals("name: acme\ndescription: edited\n", edited);
    assertFalse(Files.exists(opened.getFirst()));
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
}
