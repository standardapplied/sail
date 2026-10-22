/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.engine.OperatorCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectEditCommandTest {

  @Test
  void acceptsAValidDescriptorWhoseNameMatches() {
    assertDoesNotThrow(
        () -> ProjectEditCommand.validate("acme", "name: acme\ndescription: edited\n"));
  }

  @Test
  void rejectsRenamingTheProject() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectEditCommand.validate("acme", "name: renamed\n"));
    assertTrue(error.getMessage().contains("must stay 'acme'"));
  }

  @Test
  void rejectsADescriptorWithNoName() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ProjectEditCommand.validate("acme", "description: nameless\n"));
  }

  @Test
  void rejectsMalformedYaml() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> ProjectEditCommand.validate("acme", "name: acme\nrepos: [unclosed"));
    assertTrue(
        error
            .getMessage()
            .startsWith("Edited descriptor is not valid: while parsing a flow sequence"),
        error.getMessage());
  }

  @Test
  void rejectsADeletedKeyNamingWhereItsTextGoes() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectEditCommand.validate(
                    "acme", "name: acme\nagent:\n  type: codex\n  build_skill: sail-x\n"));
    assertTrue(
        error.getMessage().contains("agent.build_skill is no longer read"), error.getMessage());
  }

  @Test
  void aDeletedKeysRemedyReachesTheDoorAsDataAndIsRenderedForTheReader() {
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                ProjectEditCommand.validate(
                    "acme", "name: acme\nagent:\n  type: codex\n  build_skill: sail-x\n"));
    var sentence =
        "Edited descriptor is not valid: agent.build_skill is no longer read: sail's work prompt"
            + " says how a build runs. Put what the skill said in the repo's AGENTS.md, or keep it"
            + " as a project skill (%s project skills add) the agent invokes when relevant. Then"
            + " remove `agent.build_skill` from acme/sail.yaml.";
    assertEquals(
        List.of(sentence.formatted("sudo sail")), CliCommand.lines(error, OperatorCommand.SUDO));
    assertEquals(
        List.of(sentence.formatted("sail")), CliCommand.lines(error, OperatorCommand.UNPRIVILEGED));
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
