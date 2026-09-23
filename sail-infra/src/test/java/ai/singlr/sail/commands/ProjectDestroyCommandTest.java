/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProjectDestroyCommandTest {

  @Test
  void defaultConfirmDoesNotMentionTheOrgCatalog() {
    var prompt = ProjectDestroyCommand.confirmPrompt("acme", false);
    assertTrue(prompt.contains("Destroy project acme"));
    assertFalse(prompt.contains("org catalog"), "a plain destroy is local-only");
  }

  @Test
  void purgeConfirmWarnsItErasesEverythingOnEveryBox() {
    var prompt = ProjectDestroyCommand.confirmPrompt("acme", true);
    assertTrue(prompt.contains("erase it everywhere"));
    assertTrue(prompt.contains("specs, rooms, runs, files and history"));
    assertTrue(prompt.contains("every box"));
    assertTrue(prompt.contains("cannot be undone"));
  }
}
