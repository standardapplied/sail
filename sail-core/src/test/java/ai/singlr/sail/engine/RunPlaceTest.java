/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.webauthn.Hashes;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class RunPlaceTest {

  @Test
  void aRunThatNamesASpecLivesInThatSpecsWorkspaceAndOneThatNamesNoneInTheProjectsContainer() {
    assertEquals(
        new RunPlace.SpecWorkspace("acme", "fix-login", "fix-login", "acme-fix-login"),
        RunPlace.of("acme", "fix-login"));
    assertEquals(new RunPlace.ProjectContainer("acme"), RunPlace.of("acme", null));
    assertEquals(new RunPlace.ProjectContainer("acme"), RunPlace.of("acme", " "));
    assertEquals("acme", RunPlace.of("acme", null).instance());
    assertEquals("acme-fix-login", RunPlace.of("acme", "fix-login").instance());
  }

  @Test
  void anIdOfEightyCharactersIsCutToFitWithSixHexOfItsHash() {
    var id = "a".repeat(40) + "-" + "b".repeat(39);
    var place = (RunPlace.SpecWorkspace) RunPlace.of("acme", id);

    assertEquals("a".repeat(40) + "-" + "b".repeat(10) + "-" + hash(id), place.workspace());
    assertEquals(63, place.instance().length());
    assertEquals("acme-" + place.workspace(), place.instance());
  }

  @Test
  void aCutThatEndsInADashLosesItBeforeTheHash() {
    var id = "a".repeat(50) + "-" + "b".repeat(20);

    assertEquals("a".repeat(50) + "-" + hash(id), RunPlace.workspaceName("acme", id));
  }

  @Test
  void anIdEndingInADashOrNamedMainOrNamingATemplateIsHashed() {
    assertEquals("fix-" + hash("fix-"), RunPlace.workspaceName("acme", "fix-"));
    assertEquals("main-" + hash("main"), RunPlace.workspaceName("acme", "main"));
    assertEquals("x--tmpl-" + hash("x--tmpl"), RunPlace.workspaceName("acme", "x--tmpl"));
  }

  @Test
  void anIdThatFitsExactlyIsKeptWhole() {
    var id = "c".repeat(58);

    assertEquals(id, RunPlace.workspaceName("acme", id));
    assertEquals(63, RunPlace.of("acme", id).instance().length());
  }

  @Test
  void twoIdsWhoseCutsAgreeGetTwoNames() {
    var prefix = "d".repeat(70);

    assertNotEquals(
        RunPlace.workspaceName("acme", prefix + "-one"),
        RunPlace.workspaceName("acme", prefix + "-two"));
  }

  @Test
  void aProjectNameTooLongToLeaveOneCharacterIsRefusedNamingTheRule() {
    var project = "p".repeat(55);

    var refused =
        assertThrows(IllegalArgumentException.class, () -> RunPlace.of(project, "x".repeat(70)));

    assertEquals(
        "Project '"
            + project
            + "' is 55 characters, so a spec's workspace of it cannot be named: Incus names an"
            + " instance in at most 63, and a workspace named for a long spec id needs the"
            + " project's name, a '-', one character of the id and '-' with 6 hex characters."
            + " Use a project name of at most 54 characters.",
        refused.getMessage());
    assertEquals("p".repeat(55) + "-x", RunPlace.of(project, "x").instance());
  }

  @Test
  void runsOfOneSpecShareAPlaceAndSpecLessRunsShareTheProjectsContainer() {
    assertTrue(RunPlace.of("acme", "a").equals(RunPlace.of("acme", "a")));
    assertNotEquals(RunPlace.of("acme", "a"), RunPlace.of("acme", "b"));
    assertEquals(RunPlace.of("acme", null), RunPlace.of("acme", ""));
  }

  private static String hash(String id) {
    return HexFormat.of()
        .formatHex(Hashes.sha256(id.getBytes(StandardCharsets.UTF_8)))
        .substring(0, 6);
  }
}
