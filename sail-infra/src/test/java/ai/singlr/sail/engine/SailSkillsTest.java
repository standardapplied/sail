/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.singlr.sail.gen.SpecSkillGenerator;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SailSkillsTest {

  @Test
  void sailShipsThePortalAndTheSpecBoardInNameOrder() {
    assertEquals(
        List.of("sail-portal", "spec-board"),
        SailSkills.all().stream().map(StageSkill::name).toList());
    assertEquals(Optional.of(PortalSkill.skill()), SailSkills.named("sail-portal"));
    assertEquals(Optional.empty(), SailSkills.named("e2e"));
  }

  @Test
  void eachSkillsManifestAndFilesAreItsOwn() throws IOException {
    assertEquals(Optional.of(PortalSkill.skillMd()), SailSkills.manifest("sail-portal"));
    assertEquals(Optional.of(SpecSkillGenerator.skillMd()), SailSkills.manifest("spec-board"));
    assertEquals(Optional.empty(), SailSkills.manifest("e2e"));
    for (var skill : SailSkills.all()) {
      for (var file : skill.files()) {
        try (var bytes = SailSkills.content(skill, file)) {
          assertEquals(file.size(), bytes.readAllBytes().length, skill.name() + "/" + file.path());
        }
      }
    }
  }

  @Test
  void aSkillSailDoesNotShipHasNoContentHere() {
    var other =
        StageSkill.of(
            "e2e",
            "---\nname: e2e\ndescription: d\n---\nRun.\n",
            List.of(new StageSkill.File("SKILL.md", "x", 1, 0644)));

    assertEquals(
        "Sail ships no skill 'e2e'.",
        assertThrows(
                IllegalArgumentException.class,
                () -> SailSkills.content(other, other.files().getFirst()))
            .getMessage());
  }
}
